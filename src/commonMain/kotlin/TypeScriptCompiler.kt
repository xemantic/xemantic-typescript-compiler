/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

/**
 * The result of compiling a TypeScript source file.
 *
 * @property fileName The logical file name of the compilation unit.
 * @property sourceEchoes The parsed source files as (fileName, cleanedContent) pairs.
 * @property jsOutputs The compiled JS outputs as (jsFileName, javascript) pairs.
 * @property isMultiFile Whether this compilation used multi-file format
 *   (explicit `@Filename:` directives).
 * @property options The compiler options used for this compilation.
 * @property diagnostics The list of diagnostic messages (errors, warnings, hints)
 *   produced during compilation.
 */
data class CompilationResult(
    val fileName: String = "input.ts",
    val sourceEchoes: List<Pair<String, String>> = emptyList(),
    val jsOutputs: List<Pair<String, String>> = emptyList(),
    val isMultiFile: Boolean = false,
    val options: CompilerOptions = CompilerOptions(),
    val diagnostics: List<Diagnostic> = emptyList(),
    /** All source files including tsconfig.json — used for error baselines which annotate all files. */
    val allSourceFiles: List<Pair<String, String>> = emptyList(),
) {
    /** `true` if any diagnostic with [DiagnosticCategory.Error] category was produced. */
    val hasErrors: Boolean get() = diagnostics.any { it.category == DiagnosticCategory.Error }
    /** The JS content of the single output, or `null` for multi-file or declaration-only results. */
    val javascript: String? get() = jsOutputs.singleOrNull()?.second
}

/**
 * A diagnostic message produced by the TypeScript compiler.
 *
 * @property message The human-readable diagnostic text.
 * @property category The severity category of this diagnostic.
 * @property code The TypeScript diagnostic code number (e.g. `2345` for TS2345).
 * @property fileName The source file where the diagnostic occurred, or `null` for
 *   project-level diagnostics.
 * @property line The 1-based line number within [fileName], or `null` if not applicable.
 * @property character The 1-based column number within the line, or `null` if not applicable.
 * @property start The 0-based byte offset of the error span in the source, or `null` for
 *   project-level diagnostics.
 * @property length The length of the error span in characters, or `null` if not applicable.
 * @property relatedInformation Additional diagnostics related to this one (e.g. "did you mean..."
 *   suggestions), rendered as `!!! related TS...:` lines in `.errors.txt` baselines.
 */
data class Diagnostic(
    val message: String,
    val category: DiagnosticCategory,
    val code: Int,
    val fileName: String? = null,
    val line: Int? = null,
    val character: Int? = null,
    val start: Int? = null,
    val length: Int? = null,
    val relatedInformation: List<Diagnostic> = emptyList(),
    val messageChain: List<String> = emptyList(),
)

/**
 * The severity category of a [Diagnostic] message, mirroring the TypeScript SDK's
 * `DiagnosticCategory` enum.
 */
enum class DiagnosticCategory {
    Warning,
    Error,
    Message,
    Suggestion,
}

/**
 * Compiles TypeScript source code to JavaScript.
 *
 * This is the core API of the xemantic TypeScript compiler. The implementation
 * is under active development; see the project README for the current status.
 *
 * ### Example
 * ```kotlin
 * val compiler = TypeScriptCompiler()
 * val result = compiler.compile("const x: number = 42;", "example.ts")
 * println(result.javascript)
 * ```
 */
class TypeScriptCompiler {

    /**
     * Compiles the given TypeScript [source] string to JavaScript.
     *
     * TypeScript compiler options embedded in the source file as single-line comments
     * (e.g. `// @target: ES2015`, `// @module: commonjs`) are respected when present.
     *
     * @param source The TypeScript source code to compile.
     * @param fileName The logical file name for this source, used in diagnostics.
     *   Defaults to `"input.ts"`.
     * @param optionOverrides Compiler option overrides applied after parsing directives
     *   from the source. Keys are lowercase option names (e.g. `"target"`, `"module"`),
     *   values are the option value strings (e.g. `"es5"`, `"commonjs"`). Used by
     *   parameterized tests to vary a single option while keeping all other directives.
     * @return A [CompilationResult] containing the JavaScript output and any diagnostics.
     */
    fun compile(
        source: String,
        fileName: String = "input.ts",
        optionOverrides: Map<String, String> = emptyMap(),
    ): CompilationResult {
        // Parse multi-file source and compiler options
        val parsed = parseMultiFileSource(source, fileName)
        var options = parsed.options
        // Apply overrides after parsing directives from source
        for ((key, value) in optionOverrides) {
            options = applyDirective(options, key.lowercase(), value)
        }
        val diagnostics = mutableListOf<Diagnostic>()

        // Helper to compare version strings like "5.0", "6.0"
        fun isDeprecationSuppressed(deprecationVersion: String): Boolean {
            val ign = options.ignoreDeprecations ?: return false
            return ign >= deprecationVersion // lexicographic comparison works for "X.Y" format
        }

        // Helper to look up tsconfig.json position for a given option key (lowercase)
        val tsconfigPos = options.tsconfigOptionPositions

        // TS5101: Deprecated options — point to KEY position in tsconfig
        fun addDeprecation5101(
            optionDesc: String,
            tsconfigKey: String? = null,
            messageChain: List<String> = emptyList(),
            deprecationVersion: String = "6.0",
        ) {
            if (isDeprecationSuppressed(deprecationVersion)) return
            val pos = tsconfigKey?.let { tsconfigPos[it] }
            val chain = if (pos != null)
                listOf("  Visit https://aka.ms/ts6 for migration information.")
            else messageChain
            diagnostics.add(Diagnostic(
                message = "Option '$optionDesc' is deprecated and will stop functioning in TypeScript 7.0. Specify compilerOption '\"ignoreDeprecations\": \"$deprecationVersion\"' to silence this error.",
                category = DiagnosticCategory.Error,
                code = 5101,
                fileName = pos?.fileName,
                line = pos?.keyLine,
                character = pos?.keyCharacter,
                start = pos?.keyStart,
                length = pos?.keyLength,
                messageChain = chain,
            ))
        }
        // TS5102: Removed options — point to KEY position in tsconfig
        // ignoreDeprecations suppresses TS5102 for options that were deprecated at or before the specified version
        fun addRemoved5102(
            optionDesc: String,
            tsconfigKey: String? = null,
            messageChain: List<String> = emptyList(),
            deprecationVersion: String = "5.0",
        ) {
            if (isDeprecationSuppressed(deprecationVersion)) return
            val pos = tsconfigKey?.let { tsconfigPos[it] }
            diagnostics.add(Diagnostic(
                message = "Option '$optionDesc' has been removed. Please remove it from your configuration.",
                category = DiagnosticCategory.Error,
                code = 5102,
                fileName = pos?.fileName,
                line = pos?.keyLine,
                character = pos?.keyCharacter,
                start = pos?.keyStart,
                length = pos?.keyLength,
                messageChain = messageChain,
            ))
        }
        // baseUrl deprecation (TS5101 with migration URL)
        if (options.baseUrl != null) addDeprecation5101(
            "baseUrl", tsconfigKey = "baseurl",
            messageChain = listOf("  Visit https://aka.ms/ts6 for migration information.")
        )
        // Removed options (TS5102) — all deprecated in version 5.0
        if (options.charset != null) addRemoved5102("charset", tsconfigKey = "charset")
        // downlevelIteration (TS5101 - still deprecated, not removed; fires even when set to false)
        if (options.downlevelIterationExplicitlySet) addDeprecation5101("downlevelIteration", tsconfigKey = "downleveliteration")
        if (options.keyofStringsOnly) addRemoved5102("keyofStringsOnly", tsconfigKey = "keyofstringsonly")
        if (options.noImplicitUseStrict) addRemoved5102("noImplicitUseStrict", tsconfigKey = "noimplicitusestrict")
        if (options.noStrictGenericChecks) addRemoved5102("noStrictGenericChecks", tsconfigKey = "nostrictgenericchecks")
        if (options.out != null) addRemoved5102("out", tsconfigKey = "out")
        // outFile deprecation (TS5101 - only when explicitly set, not via 'out')
        if (options.outFile != null && options.out == null) addDeprecation5101("outFile", tsconfigKey = "outfile")
        if (options.suppressExcessPropertyErrors) addRemoved5102("suppressExcessPropertyErrors", tsconfigKey = "suppressexcesspropertyerrors")
        if (options.suppressImplicitAnyIndexErrors) addRemoved5102("suppressImplicitAnyIndexErrors", tsconfigKey = "suppressimplicitanyindexerrors")
        if (options.importsNotUsedAsValues != null) addRemoved5102(
            "importsNotUsedAsValues", tsconfigKey = "importsnotusedasvalues",
            messageChain = listOf("  Use 'verbatimModuleSyntax' instead."),
        )
        if (options.preserveValueImports) addRemoved5102(
            "preserveValueImports", tsconfigKey = "preservevalueimports",
            messageChain = listOf("  Use 'verbatimModuleSyntax' instead."),
        )

        // TS5107: Deprecated options — point to VALUE position in tsconfig
        // Only moduleResolution=node10 gets the migration URL chain from tsconfig;
        // other TS5107 options don't include it.
        fun addDeprecation(optionDesc: String, tsconfigKey: String? = null, version: String = "7.0", deprecationVersion: String = "6.0", withMigrationUrl: Boolean = false) {
            if (isDeprecationSuppressed(deprecationVersion)) return
            val pos = tsconfigKey?.let { tsconfigPos[it] }
            val chain = if (pos != null && withMigrationUrl) listOf("  Visit https://aka.ms/ts6 for migration information.") else emptyList()
            diagnostics.add(Diagnostic(
                message = "Option '$optionDesc' is deprecated and will stop functioning in TypeScript $version. Specify compilerOption '\"ignoreDeprecations\": \"$deprecationVersion\"' to silence this error.",
                category = DiagnosticCategory.Error,
                code = 5107,
                fileName = pos?.fileName,
                line = pos?.valueLine,
                character = pos?.valueCharacter,
                start = pos?.valueStart,
                length = pos?.valueLength,
                messageChain = chain,
            ))
        }
        // Target deprecations — only when target is explicitly set
        // ES3 is fully removed (TS5108), not just deprecated
        if (options.targetExplicitlySet && options.target == ScriptTarget.ES3) {
            if (!isDeprecationSuppressed("5.0")) {
                val pos = tsconfigPos["target"]
                diagnostics.add(Diagnostic(
                    message = "Option 'target=ES3' has been removed. Please remove it from your configuration.",
                    category = DiagnosticCategory.Error,
                    code = 5108,
                    fileName = pos?.fileName,
                    line = pos?.valueLine,
                    character = pos?.valueCharacter,
                    start = pos?.valueStart,
                    length = pos?.valueLength,
                ))
            }
        }
        if (options.targetExplicitlySet && options.target == ScriptTarget.ES5) addDeprecation("target=ES5", tsconfigKey = "target")
        // Module deprecations
        if (options.module == ModuleKind.AMD) addDeprecation("module=AMD", tsconfigKey = "module")
        if (options.module == ModuleKind.UMD) addDeprecation("module=UMD", tsconfigKey = "module")
        if (options.module == ModuleKind.System) addDeprecation("module=System", tsconfigKey = "module")
        if (options.module == ModuleKind.None) addDeprecation("module=None", tsconfigKey = "module")
        // Module resolution deprecations — node10 gets migration URL chain
        when (options.moduleResolution?.lowercase()) {
            "classic" -> addDeprecation("moduleResolution=classic", tsconfigKey = "moduleresolution")
            "node", "node10" -> addDeprecation("moduleResolution=node10", tsconfigKey = "moduleresolution", withMigrationUrl = true)
        }
        // Boolean option deprecations (explicitly set to false)
        if (options.allowSyntheticDefaultImportsExplicitlyFalse)
            addDeprecation("allowSyntheticDefaultImports=false", tsconfigKey = "allowsyntheticdefaultimports")
        if (options.esModuleInteropExplicitlyFalse)
            addDeprecation("esModuleInterop=false", tsconfigKey = "esmoduleinterop")

        // TS6082: outFile with explicitly-set non-AMD/System module
        // Only fires when module is explicitly specified (not defaulted) and not emitDeclarationOnly
        if (options.outFile != null && options.module != null && !options.emitDeclarationOnly) {
            if (options.module != ModuleKind.AMD && options.module != ModuleKind.System && options.module != ModuleKind.None) {
                diagnostics.add(Diagnostic(
                    message = "Only 'amd' and 'system' modules are supported alongside --outFile.",
                    category = DiagnosticCategory.Error,
                    code = 6082,
                ))
            }
        }

        // TS5069: emitDeclarationOnly without declaration/composite
        if (options.emitDeclarationOnly && !options.declaration) {
            diagnostics.add(Diagnostic(
                message = "Option 'emitDeclarationOnly' cannot be specified without specifying option 'declaration' or option 'composite'.",
                category = DiagnosticCategory.Error,
                code = 5069,
            ))
        }
        // TS5069: declarationMap without declaration/composite
        if (options.declarationMap && !options.declaration) {
            diagnostics.add(Diagnostic(
                message = "Option 'declarationMap' cannot be specified without specifying option 'declaration' or option 'composite'.",
                category = DiagnosticCategory.Error,
                code = 5069,
            ))
        }
        // TS5069: mapRoot without sourceMap or declarationMap
        if (options.mapRoot != null && !options.sourceMap) {
            diagnostics.add(Diagnostic(
                message = "Option 'mapRoot' cannot be specified without specifying option 'sourceMap' or option 'declarationMap'.",
                category = DiagnosticCategory.Error,
                code = 5069,
            ))
        }

        // TS5070: resolveJsonModule with classic moduleResolution
        // Classic is the default for module=none/amd/umd/system
        var emitted5070 = false
        val effectiveModuleRes = options.moduleResolution?.lowercase() ?: run {
            val mod = options.effectiveModule
            if (mod == ModuleKind.None || mod == ModuleKind.AMD || mod == ModuleKind.UMD || mod == ModuleKind.System) "classic"
            else null
        }
        if (options.resolveJsonModule && effectiveModuleRes == "classic") {
            diagnostics.add(Diagnostic(
                message = "Option '--resolveJsonModule' cannot be specified when 'moduleResolution' is set to 'classic'.",
                category = DiagnosticCategory.Error,
                code = 5070,
            ))
            emitted5070 = true
        }

        // TS5071: resolveJsonModule with module=none/system/umd
        // Also fires when moduleResolution=bundler (which implies resolveJsonModule)
        // TS5071 is mutually exclusive with TS5070 — don't emit both
        if (!emitted5070) {
            val effectiveResolveJson = options.resolveJsonModule || options.moduleResolution?.lowercase() == "bundler"
            if (effectiveResolveJson) {
                val effModule = options.effectiveModule
                if (effModule == ModuleKind.None || effModule == ModuleKind.System || effModule == ModuleKind.UMD) {
                    diagnostics.add(Diagnostic(
                        message = "Option '--resolveJsonModule' cannot be specified when 'module' is set to 'none', 'system', or 'umd'.",
                        category = DiagnosticCategory.Error,
                        code = 5071,
                    ))
                }
            }
        }

        // TS5053: inlineSourceMap conflicts
        if (options.inlineSourceMap) {
            if (options.mapRoot != null) {
                diagnostics.add(Diagnostic(
                    message = "Option 'mapRoot' cannot be specified with option 'inlineSourceMap'.",
                    category = DiagnosticCategory.Error,
                    code = 5053,
                ))
            }
            if (options.sourceMap) {
                diagnostics.add(Diagnostic(
                    message = "Option 'sourceMap' cannot be specified with option 'inlineSourceMap'.",
                    category = DiagnosticCategory.Error,
                    code = 5053,
                ))
            }
        }

        // TS5095: moduleResolution=bundler with incompatible module
        if (options.moduleResolution?.lowercase() == "bundler") {
            val effModule = options.effectiveModule
            if (effModule == ModuleKind.None || effModule == ModuleKind.AMD ||
                effModule == ModuleKind.UMD || effModule == ModuleKind.System) {
                diagnostics.add(Diagnostic(
                    message = "Option 'bundler' can only be used when 'module' is set to 'preserve', 'commonjs', or 'es2015' or later.",
                    category = DiagnosticCategory.Error,
                    code = 5095,
                ))
            }
        }

        // TS5110: module must match moduleResolution for nodenext/node16/node18/node20
        val modRes = options.moduleResolution?.lowercase()
        if (modRes in setOf("nodenext", "node16", "node18", "node20")) {
            val expectedModule = when (modRes) {
                "nodenext" -> ModuleKind.NodeNext
                "node18" -> ModuleKind.Node18
                "node20" -> ModuleKind.Node20
                else -> ModuleKind.Node16
            }
            val displayModRes = when (modRes) {
                "nodenext" -> "NodeNext"
                "node18" -> "Node18"
                "node20" -> "Node20"
                else -> "Node16"
            }
            if (options.module != expectedModule) {
                diagnostics.add(Diagnostic(
                    message = "Option 'module' must be set to '$displayModRes' when option 'moduleResolution' is set to '$displayModRes'.",
                    category = DiagnosticCategory.Error,
                    code = 5110,
                ))
            }
        }

        // TS5055: output would overwrite input file
        // TS5056: multiple input files would produce the same output
        if (parsed.hasExplicitFilenames) {
            val inputFileSet = parsed.files.map { it.fileName }.toSet()
            if (options.outFile != null) {
                val outJs = options.outFile
                val outDts = options.outFile.substringBeforeLast('.') + ".d.ts"
                if (outJs in inputFileSet) {
                    diagnostics.add(Diagnostic(
                        message = "Cannot write file '$outJs' because it would overwrite input file.",
                        category = DiagnosticCategory.Error,
                        code = 5055,
                        messageChain = listOf("  Adding a tsconfig.json file will help organize projects that contain both TypeScript and JavaScript files. Learn more at https://aka.ms/tsconfig."),
                    ))
                }
                if (options.declaration && outDts in inputFileSet) {
                    diagnostics.add(Diagnostic(
                        message = "Cannot write file '$outDts' because it would overwrite input file.",
                        category = DiagnosticCategory.Error,
                        code = 5055,
                        messageChain = listOf("  Adding a tsconfig.json file will help organize projects that contain both TypeScript and JavaScript files. Learn more at https://aka.ms/tsconfig."),
                    ))
                }
            } else {
                // No outFile — check per-file output conflicts
                val outputToSources = mutableMapOf<String, MutableList<String>>()
                for (file in parsed.files) {
                    val fn = file.fileName
                    // Compute output JS path for compilable files
                    val jsOutput = when {
                        fn.endsWith(".ts") && !fn.endsWith(".d.ts") -> fn.substringBeforeLast(".ts") + ".js"
                        fn.endsWith(".tsx") -> fn.substringBeforeLast(".tsx") + ".js"
                        fn.endsWith(".mts") -> fn.substringBeforeLast(".mts") + ".mjs"
                        fn.endsWith(".cts") -> fn.substringBeforeLast(".cts") + ".cjs"
                        fn.endsWith(".js") || fn.endsWith(".jsx") || fn.endsWith(".mjs") || fn.endsWith(".cjs") -> fn
                        else -> null
                    }
                    if (jsOutput != null) {
                        outputToSources.getOrPut(jsOutput) { mutableListOf() }.add(fn)
                    }
                }
                // TS5055: output JS overwrites an input file
                for ((jsOutput, sources) in outputToSources) {
                    if (jsOutput in inputFileSet) {
                        // Only flag if the input file is different from the source
                        // (e.g., a.ts produces a.js, and a.js is also an input)
                        val isOwnOutput = sources.size == 1 && sources[0] == jsOutput
                        if (!isOwnOutput) {
                            diagnostics.add(Diagnostic(
                                message = "Cannot write file '$jsOutput' because it would overwrite input file.",
                                category = DiagnosticCategory.Error,
                                code = 5055,
                                messageChain = listOf("  Adding a tsconfig.json file will help organize projects that contain both TypeScript and JavaScript files. Learn more at https://aka.ms/tsconfig."),
                            ))
                        }
                    }
                }
                // TS5056: multiple inputs produce the same output
                for ((jsOutput, sources) in outputToSources) {
                    if (sources.size > 1) {
                        diagnostics.add(Diagnostic(
                            message = "Cannot write file '$jsOutput' because it would be overwritten by multiple input files.",
                            category = DiagnosticCategory.Error,
                            code = 5056,
                        ))
                    }
                }
                // TS5055: declaration output overwrites input .d.ts file
                if (options.declaration) {
                    for (file in parsed.files) {
                        val fn = file.fileName
                        val dtsOutput = when {
                            fn.endsWith(".ts") && !fn.endsWith(".d.ts") -> fn.substringBeforeLast(".ts") + ".d.ts"
                            fn.endsWith(".tsx") -> fn.substringBeforeLast(".tsx") + ".d.ts"
                            fn.endsWith(".mts") -> fn.substringBeforeLast(".mts") + ".d.mts"
                            fn.endsWith(".cts") -> fn.substringBeforeLast(".cts") + ".d.cts"
                            else -> null
                        }
                        if (dtsOutput != null && dtsOutput in inputFileSet) {
                            diagnostics.add(Diagnostic(
                                message = "Cannot write file '$dtsOutput' because it would overwrite input file.",
                                category = DiagnosticCategory.Error,
                                code = 5055,
                                messageChain = listOf("  Adding a tsconfig.json file will help organize projects that contain both TypeScript and JavaScript files. Learn more at https://aka.ms/tsconfig."),
                            ))
                        }
                    }
                }
            }
        }

        if (parsed.files.size == 1 && !parsed.hasExplicitFilenames) {
            // Single-file compilation
            val file = parsed.files[0]

            // emitDeclarationOnly: produce source echo only, no JS output
            if (options.emitDeclarationOnly) {
                return CompilationResult(
                    fileName = fileName,
                    sourceEchoes = listOf(fileName to file.content),
                    options = options,
                    diagnostics = diagnostics,
                )
            }

            // Force JSX mode for .js files when jsx option is set (allowJs + jsx),
            // OR when allowJs is true (TypeScript enables JSX for .js files with allowJs)
            val isPlainJsFile = file.fileName.endsWith(".js") || file.fileName.endsWith(".cjs") || file.fileName.endsWith(".mjs")
            val forceJsxForJs = isPlainJsFile && (options.jsx != null || options.allowJs)
            val topLevelAwait = options.effectiveModule.let { m ->
                m == ModuleKind.ES2022 || m == ModuleKind.ESNext || m.isNodeNext ||
                    m == ModuleKind.Preserve || m == ModuleKind.System
            }
            val parser = Parser(file.content, file.fileName, forceJsx = forceJsxForJs, topLevelAwait = topLevelAwait)
            val sourceFile = parser.parse()
            diagnostics.addAll(parser.getDiagnostics())

            val binder = Binder(options)
            val binderResult = binder.bind(sourceFile)
            val checker = Checker(options, listOf(binderResult))
            diagnostics.addAll(checker.getDiagnostics())

            val transformer = Transformer(options, checker)
            val transformed = transformer.transform(sourceFile)

            val emitter = Emitter(options)
            val javascript = emitter.emit(transformed, sourceFile)

            val isJsxPreserve = options.jsx?.lowercase() == "preserve"
            val tsxExtension = if (isJsxPreserve) ".jsx" else ".js"
            val jsxExtension = if (isJsxPreserve) ".jsx" else ".js"
            val jsName = options.outFile?.substringAfterLast('/')
                ?: file.fileName.substringAfterLast('/')
                    .replace(".tsx", tsxExtension)
                    .replace(".jsx", jsxExtension)
                    .replace(".mts", ".mjs")
                    .replace(".cts", ".cjs")
                    .replace(".ts", ".js")

            return CompilationResult(
                fileName = fileName,
                sourceEchoes = listOf(fileName to file.content),
                jsOutputs = listOf(jsName to javascript),
                options = options,
                diagnostics = diagnostics,
            )
        } else {
            // All source files including tsconfig.json (for error baselines)
            val allFiles = parsed.files.map { it.fileName to it.content }

            // Multi-file compilation — emitDeclarationOnly: produce source echoes only
            if (options.emitDeclarationOnly) {
                val declSourceEchoes = mutableListOf<Pair<String, String>>()
                for (file in parsed.files) {
                    val baseName = file.fileName.substringAfterLast('/')
                    if (baseName != "tsconfig.json") {
                        declSourceEchoes.add(file.fileName to file.content)
                    }
                }
                return CompilationResult(
                    fileName = fileName,
                    sourceEchoes = declSourceEchoes,
                    isMultiFile = true,
                    options = options,
                    diagnostics = diagnostics,
                    allSourceFiles = allFiles,
                )
            }

            val sourceEchoes = mutableListOf<Pair<String, String>>() // fileName -> content
            // Map from tsFileName -> (jsName, javascript)
            val jsOutputMap = mutableMapOf<String, Pair<String, String>>()
            // JSON files to re-emit (with outDir prefix)
            val jsonOutputs = mutableListOf<Pair<String, String>>()
            // Map from tsFileName -> list of tsFileNames it imports (for dependency sort)
            val importDeps = mutableMapOf<String, List<String>>()
            // Ordered list of compilable TS file names
            val tsFileNames = mutableListOf<String>()
            // Parsed source files for two-phase bind+transform
            val parsedSourceFiles = mutableMapOf<String, SourceFile>()

            // Resolve outDir to an absolute path when fullEmitPaths is set.
            // When files use absolute paths (e.g. /a.ts) and outDir is relative (e.g. "bin"),
            // resolve outDir relative to the tsconfig.json directory.
            val resolvedOutDir: String? = if (options.outDir != null && options.fullEmitPaths) {
                val outDir = options.outDir.trimEnd('/')
                if (outDir.startsWith('/')) {
                    outDir
                } else {
                    val tsconfigFile = parsed.files
                        .find { it.fileName.substringAfterLast('/') == "tsconfig.json" }
                        ?.fileName
                    val tsconfigDir = tsconfigFile?.let { tf ->
                        val dir = tf.substringBeforeLast('/')
                        // "/tsconfig.json".substringBeforeLast('/') = "" but dir is "/"
                        if (dir.isEmpty() && tf.startsWith('/')) "/" else dir
                    }
                    if (tsconfigDir != null && tsconfigDir.startsWith('/')) {
                        val root = tsconfigDir.trimEnd('/')
                        "$root/$outDir"
                    } else outDir
                }
            } else options.outDir

            for (file in parsed.files) {
                // Don't echo tsconfig.json (it's a TypeScript project config, not a source file)
                val baseName = file.fileName.substringAfterLast('/')
                if (baseName != "tsconfig.json") {
                    sourceEchoes.add(file.fileName to file.content)
                }

                // Re-emit JSON files when outDir is set (but not tsconfig.json/package.json
                // and not files from node_modules which TypeScript never re-emits)
                if (file.fileName.endsWith(".json") && options.outDir != null
                    && baseName != "tsconfig.json" && baseName != "package.json"
                    && !file.fileName.contains("/node_modules/")) {
                    val jsonContent = reformatJson(stripJsonTrailingCommas(file.content)).trimEnd()
                    if (options.fullEmitPaths) {
                        val outDir = resolvedOutDir!!.trimEnd('/')
                        val jsonBaseName = file.fileName.substringAfterLast('/')
                        jsonOutputs.add("$outDir/$jsonBaseName" to jsonContent)
                    } else {
                        val jsonBaseName = file.fileName.substringAfterLast('/')
                        jsonOutputs.add(jsonBaseName to jsonContent)
                    }
                    continue
                }

                // Skip non-TS files; include .js/.mjs/.cjs only when outDir is set
                // (without outDir, TypeScript skips re-emitting JS files to avoid overwriting sources)
                // .jsx files are always compiled (they are TS-like files requiring JSX stripping)
                val isPureJsFile = file.fileName.endsWith(".js") ||
                        file.fileName.endsWith(".mjs") || file.fileName.endsWith(".cjs")
                val isJsxFile = file.fileName.endsWith(".jsx")
                val isJsFile = isPureJsFile || isJsxFile
                val isTsFile = file.fileName.endsWith(".ts") || file.fileName.endsWith(".tsx") ||
                        file.fileName.endsWith(".mts") || file.fileName.endsWith(".cts")
                if (!isTsFile && !isJsFile) {
                    continue
                }
                // Plain .js/.mjs/.cjs: only when outDir/outFile is set (avoids overwriting sources)
                if (isPureJsFile && options.outDir == null && options.outFile == null) {
                    continue
                }
                // .jsx (JavaScript+JSX): without outDir/outFile, skip if no allowJs OR source is empty
                // (empty .jsx files have no TypeScript content to transform)
                if (isJsxFile && options.outDir == null && options.outFile == null &&
                    (!options.allowJs || file.content.isBlank())) {
                    continue
                }
                val isDtsFile = file.fileName.endsWith(".d.ts") || file.fileName.endsWith(".d.mts") || file.fileName.endsWith(".d.cts")
                // .tsx files without --jsx: skip only if the file content is blank
                // (TypeScript reports an error for JSX syntax without --jsx, but still emits non-JSX tsx content)
                if (file.fileName.endsWith(".tsx") && options.jsx == null && file.content.isBlank()) {
                    continue
                }
                // allowJs: skip a .ts/.tsx file if a .js/.jsx file with the same full path (minus extension) exists.
                // TypeScript "blocks" TS emit when a JS file of the same name is present (avoids conflict).
                if (options.allowJs && isTsFile) {
                    val tsPathWithoutExt = file.fileName
                        .replace(".tsx", "")
                        .replace(".mts", "")
                        .replace(".cts", "")
                        .replace(".ts", "")
                    val jsEquivalentPath1 = "$tsPathWithoutExt.js"
                    val jsEquivalentPath2 = "$tsPathWithoutExt.jsx"
                    val jsEquivalentPath3 = "$tsPathWithoutExt.mjs"
                    val jsEquivalentPath4 = "$tsPathWithoutExt.cjs"
                    val hasConflictingJs = parsed.files.any { other ->
                        other.fileName == jsEquivalentPath1 || other.fileName == jsEquivalentPath2 ||
                        other.fileName == jsEquivalentPath3 || other.fileName == jsEquivalentPath4
                    }
                    if (hasConflictingJs) continue
                }

                // Force JSX mode for .js files when jsx option is set (allowJs + jsx),
                // OR when allowJs is true (TypeScript enables JSX for .js files with allowJs)
                val isPlainJsFileMulti = file.fileName.endsWith(".js") || file.fileName.endsWith(".cjs") || file.fileName.endsWith(".mjs")
                val forceJsxForJsMulti = isPlainJsFileMulti && (options.jsx != null || options.allowJs)
                val topLevelAwaitMulti = options.effectiveModule.let { m ->
                    m == ModuleKind.ES2022 || m == ModuleKind.ESNext || m.isNodeNext ||
                        m == ModuleKind.Preserve || m == ModuleKind.System
                }
                val parser = Parser(file.content, file.fileName, forceJsx = forceJsxForJsMulti, topLevelAwait = topLevelAwaitMulti)
                val sourceFile = parser.parse()
                parsedSourceFiles[file.fileName] = sourceFile

                // .d.ts files are parsed and bound (for checker globals) but not emitted
                if (isDtsFile) continue

                diagnostics.addAll(parser.getDiagnostics())

                // Extract relative imports for dependency ordering
                importDeps[file.fileName] = extractRelativeImports(
                    sourceFile, file.fileName, parsed.files, options.moduleSuffixes,
                    includeReferencePathDeps = options.outFile != null,
                )

                tsFileNames.add(file.fileName)
            }

            // Phase 2: Bind all files and create shared checker
            val binder = Binder(options)
            val binderResults = parsedSourceFiles.values.map { binder.bind(it) }
            val checker = Checker(options, binderResults, isMultiFileSource = parsed.hasExplicitFilenames)
            diagnostics.addAll(checker.getDiagnostics())

            // Phase 3: Transform and emit each file
            for ((tsFileName, sourceFile) in parsedSourceFiles) {
                val transformer = Transformer(options, checker)
                val transformed = transformer.transform(sourceFile)

                val emitter = Emitter(options)
                val javascript = emitter.emit(transformed, sourceFile)

                // Skip files that produce no meaningful output (e.g. empty .tsx/.ts files)
                // But keep blank files if the original had module statements (imports/exports)
                // since they should still appear in the baseline with empty output sections.
                if (javascript.isBlank()) {
                    val hadModuleStmts = sourceFile.statements.any {
                        it is ImportDeclaration || it is ExportDeclaration || it is ExportAssignment ||
                            (it is ImportEqualsDeclaration && it.moduleReference is ExternalModuleReference)
                    }
                    if (!hadModuleStmts) continue
                }

                // .tsx/.jsx → .jsx only when jsx=preserve; all other modes produce .js
                val isJsxPreserveMulti = options.jsx?.lowercase() == "preserve"
                val tsxExtensionMulti = if (isJsxPreserveMulti) ".jsx" else ".js"
                val jsxExtensionMulti = if (isJsxPreserveMulti) ".jsx" else ".js"
                var jsName = tsFileName
                    .replace(".tsx", tsxExtensionMulti)
                    .replace(".jsx", jsxExtensionMulti)
                    .replace(".mts", ".mjs")
                    .replace(".cts", ".cjs")
                    .replace(".ts", ".js")
                // When fullEmitPaths: keep full path; when outDir is also set, prepend it
                if (options.fullEmitPaths) {
                    if (resolvedOutDir != null) {
                        val outDir = resolvedOutDir.trimEnd('/')
                        val base = jsName.substringAfterLast('/')
                        jsName = "$outDir/$base"
                    }
                    // else: keep jsName as full path (just extension replaced)
                } else {
                    // Strip directory prefix — baseline uses just basenames.
                    // Handle both Unix '/' and Windows '\' separators.
                    jsName = jsName.substringAfterLast('/').substringAfterLast('\\')
                }
                jsOutputMap[tsFileName] = jsName to javascript
            }

            // Sort JS outputs by dependency order (dependencies first)
            // Skip sorting when noResolve is set (TypeScript doesn't resolve imports in that mode)
            val sortedTsFiles = if (options.noResolve) tsFileNames else topologicalSort(tsFileNames, importDeps)
            val jsOutputs = sortedTsFiles.mapNotNull { jsOutputMap[it] }

            // When outFile is set, concatenate all JS outputs into a single file.
            // Exception: isolatedModules is incompatible with outFile — TypeScript ignores outFile
            // and produces separate output files for each input file.
            val finalJsOutputs = if (options.outFile != null && !options.isolatedModules && jsOutputs.isNotEmpty()) {
                val outFileName = options.outFile.substringAfterLast('/')
                // Concatenate, hoisting a single "use strict"; to the very top.
                // In outFile bundles, TypeScript places "use strict" at the global scope
                // before all file content (including AMD define() wrappers).
                // Strip it from each file's output, then prepend once if any file had it.
                var anyUseStrict = false
                var anyShebang: String? = null
                val parts = jsOutputs.mapIndexed { idx, (_, js) ->
                    var result = js
                    // Strip shebang from all files; keep first file's shebang for output
                    if (result.startsWith("#!")) {
                        val lineEnd = result.indexOf('\n')
                        if (lineEnd >= 0) {
                            if (anyShebang == null) anyShebang = result.substring(0, lineEnd + 1)
                            result = result.substring(lineEnd + 1)
                        }
                    }
                    if (result.trimStart().startsWith("\"use strict\"")) {
                        anyUseStrict = true
                        result = result.replace(Regex("""^\s*"use strict";\n?"""), "")
                    }
                    result
                }
                val body = parts.joinToString("\n")
                val prefix = buildString {
                    if (anyShebang != null) append(anyShebang)
                    if (anyUseStrict) append("\"use strict\";\n")
                }
                val concatenated = prefix + body
                listOf(outFileName to concatenated)
            } else {
                jsonOutputs + jsOutputs
            }

            return CompilationResult(
                fileName = fileName,
                sourceEchoes = sourceEchoes,
                jsOutputs = finalJsOutputs,
                isMultiFile = true,
                options = options,
                diagnostics = diagnostics,
                allSourceFiles = allFiles,
            )
        }
    }

}

/**
 * Extracts relative import paths from a source file and resolves them to actual file names
 * from the list of known files in the compilation.
 */
private fun extractRelativeImports(
    sourceFile: SourceFile,
    currentFileName: String,
    allFiles: List<SourceFileEntry>,
    moduleSuffixes: List<String>? = null,
    includeReferencePathDeps: Boolean = false,
): List<String> {
    val allTsFileNames = allFiles.map { it.fileName }.toSet()
    val deps = mutableListOf<String>()
    val lastSlash = currentFileName.lastIndexOf('/')
    val dir = when {
        lastSlash > 0 -> currentFileName.substring(0, lastSlash)
        lastSlash == 0 -> "/" // absolute root path like /index.ts
        else -> ""
    }

    // Extract /// <reference path="..."/> directives from the raw source text.
    // These create ordering dependencies (referenced file must be emitted first in outFile bundles).
    // Only used when outFile is set — for separate-file output TypeScript uses original order.
    if (includeReferencePathDeps) {
        val referencePathRegex = Regex("""///\s*<reference\s+path\s*=\s*["']([^"']+)["']""")
        for (line in sourceFile.text.lineSequence()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("///")) break  // stop at first non-triple-slash line
            val match = referencePathRegex.find(trimmed) ?: continue
            val refPath = match.groupValues[1]
            val resolved = if (refPath.startsWith("./") || refPath.startsWith("../")) {
                resolveRelativePath(dir, refPath)
            } else if (dir.isNotEmpty()) {
                "$dir/$refPath"
            } else {
                refPath
            }
            if (resolved in allTsFileNames) deps.add(resolved)
        }
    }

    for (stmt in sourceFile.statements) {
        val specifier = when (stmt) {
            is ImportDeclaration -> (stmt.moduleSpecifier as? StringLiteralNode)?.text
            is ExportDeclaration -> (stmt.moduleSpecifier as? StringLiteralNode)?.text
            is ImportEqualsDeclaration -> {
                val ref = stmt.moduleReference
                if (ref is ExternalModuleReference) (ref.expression as? StringLiteralNode)?.text else null
            }
            else -> null
        } ?: continue

        // Resolve the specifier against known files.
        // For relative specifiers (./x, ../x) resolve relative to the current file's directory.
        // For bare specifiers (e.g. "file1") also try matching against known filenames directly
        // (TypeScript resolves these in single-folder compilations).
        val resolved = if (specifier.startsWith("./") || specifier.startsWith("../")) {
            resolveRelativePath(dir, specifier)
        } else {
            specifier
        }

        // Build candidate list respecting moduleSuffixes.
        // When moduleSuffixes is set, ONLY try suffixed variants (no fallback to un-suffixed).
        // An empty-string suffix "" means "also try without suffix" (TypeScript convention).
        // If specifier has an extension (e.g. "./foo.js"), insert suffix before it.
        // If no extension (e.g. "./foo"), append suffix then try .ts/.tsx/etc.
        val sep = if (resolved.isEmpty() || resolved.endsWith("/")) "" else "/"
        val knownExtensions = listOf(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs")
        val resolvedExt = knownExtensions.firstOrNull { resolved.endsWith(it) }
        val resolvedBase = if (resolvedExt != null) resolved.dropLast(resolvedExt.length) else resolved
        val candidates: List<String> = if (!moduleSuffixes.isNullOrEmpty()) {
            // Only try suffixed variants
            moduleSuffixes.flatMap { suffix ->
                if (resolvedExt != null) {
                    listOf("$resolvedBase$suffix$resolvedExt")
                } else {
                    listOf("$resolvedBase$suffix.ts", "$resolvedBase$suffix.tsx",
                        "$resolvedBase$suffix.mts", "$resolvedBase$suffix.cts")
                }
            }
        } else if (resolvedExt != null) {
            listOf(resolved)
        } else {
            listOf(
                "$resolved.ts", "$resolved.tsx", "$resolved.mts", "$resolved.cts",
                resolved,
                "${resolved}${sep}index.ts", "${resolved}${sep}index.tsx"
            )
        }
        for (candidate in candidates) {
            if (candidate in allTsFileNames) {
                deps.add(candidate)
                break
            }
            // Also try with "./" prefix (test files like @filename: ./foo.ts store as "./foo.ts")
            val dotSlashCandidate = "./$candidate"
            if (dotSlashCandidate in allTsFileNames) {
                deps.add(dotSlashCandidate)
                break
            }
        }
    }
    return deps
}

/**
 * Resolves a relative import path against a base directory.
 * Preserves whether the path is absolute (starts with /) or relative.
 */
private fun resolveRelativePath(dir: String, specifier: String): String {
    val base = when {
        dir.isEmpty() -> specifier
        dir == "/" -> "/$specifier"
        else -> "$dir/$specifier"
    }
    val isAbsolute = base.startsWith("/")
    val parts = base.split('/')
    val resolved = mutableListOf<String>()
    for (part in parts) {
        when (part) {
            ".", "" -> {} // skip
            ".." -> if (resolved.isNotEmpty()) resolved.removeLast()
            else -> resolved.add(part)
        }
    }
    return if (isAbsolute) "/" + resolved.joinToString("/") else resolved.joinToString("/")
}

/**
 * Topologically sorts a list of TS file names based on their import dependencies.
 * Uses DFS (post-order) to match TypeScript's file ordering: for each file in original
 * order, recursively emit its unvisited dependencies first, then the file itself.
 * This keeps dependents close to their dependencies in the original source order.
 */
private fun topologicalSort(
    fileNames: List<String>,
    deps: Map<String, List<String>>,
): List<String> {
    if (fileNames.size <= 1) return fileNames

    val fileSet = fileNames.toSet()
    val visited = mutableSetOf<String>()
    val result = mutableListOf<String>()

    fun visit(file: String) {
        if (file in visited) return
        visited.add(file)
        // Visit dependencies first (post-order DFS)
        for (dep in (deps[file] ?: emptyList())) {
            if (dep in fileSet) visit(dep)
        }
        result.add(file)
    }

    for (file in fileNames) {
        visit(file)
    }

    return result
}

private val trailingCommaRegex = Regex(",(?=\\s*[}\\]])")
private val emptyObjectRegex = Regex("\\{\\s+\\}")
private val emptyArrayRegex = Regex("\\[\\s+\\]")

private fun stripJsonTrailingCommas(content: String): String =
    content.replace(trailingCommaRegex, "")
        .replace(emptyObjectRegex, "{}")
        .replace(emptyArrayRegex, "[]")

/** Re-emit JSON with 4-space indentation, matching TypeScript's emitter output.
 *  Returns the original content unchanged if it contains non-standard constructs
 *  like computed property keys (e.g. `[a]: 10`). */
private fun reformatJson(content: String): String {
    // Detect computed property keys: `]:` pattern means a closing bracket before a colon
    if (content.contains(Regex("\\]\\s*:"))) return content
    val sb = StringBuilder()
    var indent = 0
    var i = 0
    val len = content.length

    fun addIndent() { repeat(indent * 4) { sb.append(' ') } }

    while (i < len) {
        when (val ch = content[i]) {
            '{', '[' -> {
                sb.append(ch)
                indent++
                // peek past whitespace for closing bracket (empty container)
                var j = i + 1
                while (j < len && content[j].isWhitespace()) j++
                if (j < len && (content[j] == '}' || content[j] == ']')) {
                    sb.append(content[j])
                    indent--
                    i = j + 1
                } else {
                    sb.append('\n'); addIndent()
                    i++
                }
            }
            '}', ']' -> {
                indent--
                sb.append('\n'); addIndent(); sb.append(ch)
                i++
            }
            ':' -> { sb.append(": "); i++ }
            ',' -> {
                sb.append(',')
                i++
                while (i < len && content[i].isWhitespace()) i++
                sb.append('\n'); addIndent()
            }
            '"', '\'' -> {
                val end = ch
                val start = i++
                while (i < len) {
                    when (content[i]) {
                        '\\' -> i += 2
                        end -> { i++; break }
                        else -> i++
                    }
                }
                sb.append(content, start, i)
            }
            else -> {
                if (ch.isWhitespace()) { i++ }
                else {
                    // number, boolean, null — copy until delimiter
                    val start = i
                    while (i < len && content[i] != ',' && content[i] != '}' &&
                           content[i] != ']' && !content[i].isWhitespace()) i++
                    sb.append(content, start, i)
                }
            }
        }
    }
    return sb.toString()
}
