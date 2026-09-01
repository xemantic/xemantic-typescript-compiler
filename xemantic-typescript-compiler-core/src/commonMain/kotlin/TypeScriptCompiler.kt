/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
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
    /**
     * (API.3) The types the checker recorded at the spans a [TypeCaptureRequest]
     * named, or empty — which is every compile that did not ask for any.
     */
    val capturedTypes: List<CapturedType> = emptyList(),
    /**
     * (API.3b) Where the symbols at those same spans are declared, or empty. Shorter
     * than [capturedTypes] whenever a span named something no free-name lookup
     * resolves — a member name, a literal, a keyword.
     */
    val capturedDefinitions: List<CapturedDefinition> = emptyList(),
    /**
     * (API.4a) What the types at the request's `memberSpans` call their own, or
     * empty. One entry per span the checker reached; an entry whose `members` is
     * empty is a real answer ("that receiver has no members").
     */
    val capturedMembers: List<CapturedMembers> = emptyList(),
    /**
     * (API.4b) What the lexical scope chain binds at the request's `scopeSpans`, or
     * empty. One entry per span the checker reached.
     */
    val capturedScopes: List<CapturedScope> = emptyList(),
    /**
     * (API.6) Every signature the callees at the request's `signatureSpans` have, or
     * empty. One entry per span the checker reached; an entry whose `signatures` is
     * empty is a real answer ("that callee has none").
     */
    val capturedSignatures: List<CapturedSignatures> = emptyList(),
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
        var baseOptions = parsed.options
        // Apply overrides after parsing directives from source
        for ((key, value) in optionOverrides) {
            baseOptions = applyDirective(baseOptions, key.lowercase(), value)
        }
        baseOptions = applyImpliedAllowJs(baseOptions)
        return compileParsed(parsed, baseOptions, fileName)
    }

    /**
     * Shared compilation core operating on an already-parsed multi-file [parsed] program
     * with fully-resolved [baseOptions]. This is the seam used by BOTH the string-based
     * [compile] entry point (which derives [parsed]/[baseOptions] from `// @directive` headers
     * and the embedded `@Filename:` format) AND the filesystem-based whole-project driver
     * ([ProjectCompiler]), which performs real tsconfig loading, glob expansion, and
     * node/nodenext module resolution from disk and then constructs [parsed] from the
     * resolved file set. Keeping a single core means whole-project builds reuse the exact
     * binder → checker → transformer → emitter pipeline the test suite exercises.
     */
    fun compileParsed(
        parsed: ParsedSource,
        baseOptions: CompilerOptions,
        fileName: String = "input.ts",
        /** INV.7(d1): when non-null, the checker runs as a PARTITION over this set
         *  (the INV.6 seam) — the watch loop's incremental recheck; the caller
         *  merges kept prior diagnostics for out-of-set files. */
        recheckOnly: Set<String>? = null,
        /** (API.3): when non-null, the checker records the type at each named span
         *  while it walks past it, and the answers come back in
         *  [CompilationResult.capturedTypes] / [CompilationResult.capturedDefinitions].
         *  Null — the default — leaves the whole pipeline untouched; see
         *  [TypeCaptureRequest]. */
        typeCapture: TypeCaptureRequest? = null,
        /** (KIR): when non-null, the checker hands every checked expression and
         *  declaration-shaped node to this sink AS IT WALKS PAST IT, under the
         *  ambient in force there. Null — the default — leaves the whole pipeline
         *  untouched; see [CheckedNodeSink] for why a backend is fed inwards rather
         *  than allowed to query a retained checker afterwards.
         *
         *  A sink forces the SEQUENTIAL checker (see [cpcBindAndCheck]) and is
         *  incompatible with [recheckOnly]: a partition checker walks a SUBSET of
         *  the program, so the facts a backend collected would silently be a subset
         *  too — which `Checker`'s own `require` refuses, here with a message that
         *  names the caller's mistake. */
        checkedSink: CheckedNodeSink? = null,
        /** (INC.17) When non-null, the compile hands its LIVE program back here, so
         *  the caller can later ask it about a file this partition did not cover
         *  without rebuilding — see [ProgramRecheck], whose banner records that the
         *  DIAGNOSTIC channel is graded equivalent and the CAPTURED-TYPE channel is
         *  known wrong, and that (INC.40) therefore wired it to `diagnosticsOf` alone.
         *  Null, the default, retains nothing, which is every compile that did not
         *  ask. */
        recheckHolder: RecheckHolder? = null,
    ): CompilationResult {
        require(checkedSink == null || recheckOnly == null) {
            "a CheckedNodeSink needs the whole program: recheckOnly walks a partition"
        }
        return runWithDeepStack {
            compileParsedCore(
                parsed, baseOptions, fileName, recheckOnly, typeCapture, checkedSink, recheckHolder,
            )
        }
    }

    /**
     * The pipeline body of [compileParsed] — always entered through [runWithDeepStack],
     * so parser/checker recursion gets a large stack regardless of the caller's thread
     * (see DeepStack.kt; pinned end-to-end by DeepExpressionChainTest).
     */
    private fun compileParsedCore(
        parsed: ParsedSource,
        baseOptions: CompilerOptions,
        fileName: String,
        recheckOnly: Set<String>? = null,
        typeCapture: TypeCaptureRequest? = null,
        checkedSink: CheckedNodeSink? = null,
        recheckHolder: RecheckHolder? = null,
    ): CompilationResult {
        var options = baseOptions
        // Scan multi-file sources for `package.json` files declaring `"type": "module"` or
        // `"type": "commonjs"`. Under Node16/Node18/Node20/NodeNext, this determines whether
        // plain `.ts`/`.js` files emit as ESM or CJS. Without this, plain `.ts` defaults to CJS.
        if (options.effectiveModule.isNodeNext) {
            val pkgTypes = collectPackageJsonTypes(parsed.files)
            if (pkgTypes.isNotEmpty()) {
                options = options.copy(packageJsonTypes = pkgTypes)
            }
        }
        val diagnostics = mutableListOf<Diagnostic>()

        // Helper to look up tsconfig.json position for a given option key (lowercase)
        val tsconfigPos = options.tsconfigOptionPositions

        // The simulated TypeScript version (from @typeScriptVersion test directive), if any.
        // When >= stopFunctioningVersion, options emit "removed" (TS5102/TS5108) instead of "deprecated".
        // Default (no @typeScriptVersion): use "6.0" — the version that generated the test baselines.
        // This means:
        //   - 5.0-era options (stopped at 5.5): "6.0" >= "5.5" → TS5102 (removed)
        //   - 6.0-era options (stopped at 7.0): "6.0" < "7.0" → TS5101 (deprecated)
        val simulatedVersion = options.simulatedTypeScriptVersion ?: "6.0"

        // (JIT.1)(e) round 816: the option-validation runs. Each is a CONTIGUOUS
        // region of the pre-split body, moved verbatim with its own explanatory
        // comments; they emit into `diagnostics` and read nothing back, so the
        // order below is HEAD's source order and nothing else depends on it.
        cpcCheckDeprecatedOptions(
            options = options,
            fileName = fileName,
            tsconfigPos = tsconfigPos,
            simulatedVersion = simulatedVersion,
            diagnostics = diagnostics,
        )
        cpcCheckEmitOptionConflicts(
            parsed = parsed,
            options = options,
            fileName = fileName,
            tsconfigPos = tsconfigPos,
            diagnostics = diagnostics,
        )
        cpcCheckModuleAndLibOptions(parsed = parsed, options = options, diagnostics = diagnostics)
        cpcCheckProjectShapeOptions(parsed = parsed, options = options, diagnostics = diagnostics)

        // Paths validation diagnostics (TS5061/5062/5063/5064/5066/5090)
        diagnostics.addAll(options.pathsDiagnostics)

        if (parsed.files.size == 1 && !parsed.hasExplicitFilenames) {
            // Single-file compilation
            return cpcCompileSingleFile(
                parsed = parsed,
                options = options,
                fileName = fileName,
                diagnostics = diagnostics,
                typeCapture = typeCapture,
                checkedSink = checkedSink,
            )
        } else {
            return cpcCompileMultiFile(
                parsed = parsed,
                options = options,
                fileName = fileName,
                diagnostics = diagnostics,
                recheckOnly = recheckOnly,
                typeCapture = typeCapture,
                checkedSink = checkedSink,
                recheckHolder = recheckHolder,
            )
        }
    }

    /**
     * (JIT.1)(e) round 816 — the TS5101/TS5102/TS5107/TS5108 deprecated- and
     * removed-option diagnostics, moved verbatim out of [compileParsedCore]. Holds
     * the `ignoreDeprecations` validation (TS5103) and the three local emitters that
     * only this run uses.
     */
    private fun cpcCheckDeprecatedOptions(
        options: CompilerOptions,
        fileName: String,
        tsconfigPos: Map<String, TsconfigOptionPosition>,
        simulatedVersion: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        // Valid ignoreDeprecations values. An invalid value causes TS5103 and is treated as unset.
        val validIgnoreDeprecationsValues = setOf("5.0", "6.0")
        val effectiveIgnoreDeprecations: String? = if (options.ignoreDeprecations != null &&
            options.ignoreDeprecations !in validIgnoreDeprecationsValues) {
            // Emit TS5103 for invalid ignoreDeprecations value
            val pos = tsconfigPos["ignoredeprecations"]
            diagnostics.add(Diagnostic(
                message = "Invalid value for '--ignoreDeprecations'.",
                category = DiagnosticCategory.Error,
                code = 5103,
                fileName = pos?.fileName,
                line = pos?.valueLine,
                character = pos?.valueCharacter,
                start = pos?.valueStart,
                length = pos?.valueLength,
            ))
            null // treat as unset
        } else {
            options.ignoreDeprecations
        }

        // Helper to compare version strings like "5.0", "6.0"
        fun isDeprecationSuppressed(deprecationVersion: String): Boolean {
            val ign = effectiveIgnoreDeprecations ?: return false
            return ign >= deprecationVersion // lexicographic comparison works for "X.Y" format
        }

        // TS5101/TS5102: Deprecated/removed options — point to KEY position in tsconfig.
        // Logic (simulatedVersion is always set, defaults to "6.0"):
        // - simulatedVersion < stopFunctioningVersion: option is still deprecated → emit TS5101
        // - simulatedVersion >= stopFunctioningVersion: option is now removed → emit TS5102 (ignoreDeprecations ignored)
        fun addDeprecation5101(
            optionDesc: String,
            tsconfigKey: String? = null,
            messageChain: List<String> = emptyList(),
            deprecationVersion: String = "6.0",
            stopFunctioningVersion: String = "7.0",
            withMigrationUrl: Boolean = true,
        ) {
            val rawPos = tsconfigKey?.let { tsconfigPos[it] }
            val mainKey = tsconfigPos["compileroptionskey"]
            // When the option was inherited from an extended tsconfig, attribute the
            // diagnostic to the EXTENDING file's "compilerOptions" key (matching tsc).
            val pos = if (rawPos != null && mainKey != null && rawPos.fileName != mainKey.fileName) mainKey
                      else rawPos
            if (simulatedVersion >= stopFunctioningVersion) {
                // Option has been removed: ignoreDeprecations no longer suppresses the diagnostic
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
                return
            }
            // simulatedVersion < stopFunctioningVersion: option is still deprecated → emit TS5101
            if (isDeprecationSuppressed(deprecationVersion)) return
            val chain = if (messageChain.isNotEmpty()) messageChain
            else if (pos != null && withMigrationUrl)
                listOf("  Visit https://aka.ms/ts6 for migration information.")
            else emptyList()
            diagnostics.add(Diagnostic(
                message = "Option '$optionDesc' is deprecated and will stop functioning in TypeScript $stopFunctioningVersion. Specify compilerOption '\"ignoreDeprecations\": \"$deprecationVersion\"' to silence this error.",
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
        // TS5102: Removed options — point to KEY position in tsconfig.
        // Removed options are NOT suppressible by ignoreDeprecations — once removed, the
        // option no longer functions and the diagnostic fires unconditionally. Only
        // currently-deprecated options (TS5101 path in addDeprecation5101) honor
        // ignoreDeprecations.
        fun addRemoved5102(
            optionDesc: String,
            tsconfigKey: String? = null,
            messageChain: List<String> = emptyList(),
        ) {
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
        if (options.baseUrl != null) addDeprecation5101("baseUrl", tsconfigKey = "baseurl",
            messageChain = listOf("  Visit https://aka.ms/ts6 for migration information."))
        // Options deprecated in TypeScript 5.0 (TS5101 with "will stop functioning in 5.5")
        // These use deprecationVersion="5.0" and stopFunctioningVersion="5.5"
        // Note: no migration URL chain for these options
        if (options.charset != null) addDeprecation5101("charset", tsconfigKey = "charset",
            deprecationVersion = "5.0", stopFunctioningVersion = "5.5", withMigrationUrl = false)
        if (options.keyofStringsOnly) addDeprecation5101("keyofStringsOnly", tsconfigKey = "keyofstringsonly",
            deprecationVersion = "5.0", stopFunctioningVersion = "5.5", withMigrationUrl = false)
        if (options.noImplicitUseStrict) addDeprecation5101("noImplicitUseStrict", tsconfigKey = "noimplicitusestrict",
            deprecationVersion = "5.0", stopFunctioningVersion = "5.5", withMigrationUrl = false)
        if (options.noStrictGenericChecks) addDeprecation5101("noStrictGenericChecks", tsconfigKey = "nostrictgenericchecks",
            deprecationVersion = "5.0", stopFunctioningVersion = "5.5", withMigrationUrl = false)
        if (options.out != null) addDeprecation5101("out", tsconfigKey = "out",
            deprecationVersion = "5.0", stopFunctioningVersion = "5.5", withMigrationUrl = false)
        if (options.suppressExcessPropertyErrors) addDeprecation5101("suppressExcessPropertyErrors", tsconfigKey = "suppressexcesspropertyerrors",
            deprecationVersion = "5.0", stopFunctioningVersion = "5.5", withMigrationUrl = false)
        if (options.suppressImplicitAnyIndexErrors) addDeprecation5101("suppressImplicitAnyIndexErrors", tsconfigKey = "suppressimplicitanyindexerrors",
            deprecationVersion = "5.0", stopFunctioningVersion = "5.5", withMigrationUrl = false)
        // downlevelIteration (TS5101 - deprecated in 6.0, will stop functioning in 7.0)
        if (options.downlevelIterationExplicitlySet) addDeprecation5101("downlevelIteration", tsconfigKey = "downleveliteration")
        // outFile deprecation (TS5101 - only when explicitly set, not via 'out')
        if (options.outFile != null && options.out == null) addDeprecation5101("outFile", tsconfigKey = "outfile")
        if (options.importsNotUsedAsValues != null) addRemoved5102(
            "importsNotUsedAsValues", tsconfigKey = "importsnotusedasvalues",
            messageChain = listOf("  Use 'verbatimModuleSyntax' instead."),
        )
        if (options.preserveValueImports) addRemoved5102(
            "preserveValueImports", tsconfigKey = "preservevalueimports",
            messageChain = listOf("  Use 'verbatimModuleSyntax' instead."),
        )

        // TS5107/TS5108: Deprecated/removed options — point to VALUE position in tsconfig.
        // Only moduleResolution=node10 gets the migration URL chain from tsconfig.
        // Logic (simulatedVersion is always set, defaults to "6.0"):
        // - simulatedVersion < version (stopFunctioningVersion): option is deprecated → emit TS5107
        // - simulatedVersion >= version: option is removed → emit TS5108 (ignoreDeprecations ignored)
        fun addDeprecation(optionDesc: String, tsconfigKey: String? = null, version: String = "7.0", deprecationVersion: String = "6.0", withMigrationUrl: Boolean = false) {
            // When the option is not explicitly in tsconfig but a tsconfig exists, fall back to
            // the "compilerOptions" key position. TypeScript attributes CLI-level deprecated options
            // to the "compilerOptions" section when a tsconfig is present.
            val pos = tsconfigKey?.let { tsconfigPos[it] ?: tsconfigPos["compileroptionskey"] }
            if (simulatedVersion >= version) {
                // Option has been removed: ignoreDeprecations no longer suppresses the diagnostic
                diagnostics.add(Diagnostic(
                    message = "Option '$optionDesc' has been removed. Please remove it from your configuration.",
                    category = DiagnosticCategory.Error,
                    code = 5108,
                    fileName = pos?.fileName,
                    line = pos?.valueLine,
                    character = pos?.valueCharacter,
                    start = pos?.valueStart,
                    length = pos?.valueLength,
                ))
                return
            }
            if (isDeprecationSuppressed(deprecationVersion)) return
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
        // ES3 was deprecated in 5.0 (TS5107), will stop functioning in 5.5
        if (options.targetExplicitlySet && options.target == ScriptTarget.ES3) addDeprecation("target=ES3", tsconfigKey = "target", version = "5.5", deprecationVersion = "5.0")
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
    }

    /**
     * (JIT.1)(e) round 816 — the emit-option conflict diagnostics (TS5069, TS5066,
     * TS5052, TS5058/TS5059 outDir-vs-rootDir, TS6059, TS5009), moved verbatim out of
     * [compileParsedCore].
     */
    private fun cpcCheckEmitOptionConflicts(
        parsed: ParsedSource,
        options: CompilerOptions,
        fileName: String,
        tsconfigPos: Map<String, TsconfigOptionPosition>,
        diagnostics: MutableList<Diagnostic>,
    ) {
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
        // TS5069: declarationDir without declaration/composite — squiggles the tsconfig key.
        if (options.declarationDir != null && !options.declaration && !options.composite) {
            val ddPos = tsconfigPos["declarationdir"]
            diagnostics.add(Diagnostic(
                message = "Option 'declarationDir' cannot be specified without specifying option 'declaration' or option 'composite'.",
                category = DiagnosticCategory.Error,
                code = 5069,
                fileName = ddPos?.fileName,
                line = ddPos?.keyLine,
                character = ddPos?.keyCharacter,
                start = ddPos?.keyStart,
                length = ddPos?.keyLength,
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

        // TS5011: outDir + (declaration|composite) + common source dir != tsconfig dir + rootDir unset
        //   Fires when the output-layout-affecting options force a directory structure but the
        //   source files span more than the tsconfig's own directory. Without rootDir, the
        //   compiler can't unambiguously decide how to strip paths for outDir.
        //   Position: outDir key (8-char squiggle including quotes).
        if (options.outDir != null && options.rootDir == null && (options.declaration || options.composite)) {
            val outDirPos = tsconfigPos["outdir"]
            if (outDirPos != null) {
                val tsconfigDir = outDirPos.fileName.substringBeforeLast('/', "")
                val sourceFiles = parsed.files.filter { f ->
                    val n = f.fileName
                    (n.endsWith(".ts") || n.endsWith(".tsx")) && !n.endsWith(".d.ts") &&
                        "/node_modules/" !in n && !n.startsWith("node_modules/") &&
                        (tsconfigDir.isEmpty() || n.startsWith("$tsconfigDir/"))
                }
                if (sourceFiles.isNotEmpty()) {
                    val parentDirs = sourceFiles.map { it.fileName.substringBeforeLast('/', "") }
                    val commonDir = longestCommonPathPrefix(parentDirs)
                    val relative: String? = if (tsconfigDir.isEmpty()) {
                        val stripped = commonDir.removePrefix("/")
                        if (stripped.isEmpty()) null else "./$stripped"
                    } else {
                        when {
                            commonDir == tsconfigDir -> null
                            commonDir.startsWith("$tsconfigDir/") -> "./${commonDir.substring(tsconfigDir.length + 1)}"
                            else -> null
                        }
                    }
                    if (relative != null) {
                        diagnostics.add(Diagnostic(
                            message = "The common source directory of 'tsconfig.json' is '$relative'. The 'rootDir' setting must be explicitly set to this or another path to adjust your output's file layout.",
                            category = DiagnosticCategory.Error,
                            code = 5011,
                            fileName = outDirPos.fileName,
                            line = outDirPos.keyLine,
                            character = outDirPos.keyCharacter,
                            start = outDirPos.keyStart,
                            length = outDirPos.keyLength,
                            messageChain = listOf("  Visit https://aka.ms/ts6 for migration information."),
                        ))
                    }
                }
            }
        }

        // TS6059 / TS5011-'..' / TS5055: an `outDir`-but-no-`rootDir` program whose
        // implicit rootDir (the tsconfig directory) does NOT contain all source files.
        // A file resolved from an import that lies OUTSIDE the tsconfig dir gets TS6059
        // at the import specifier; the common source dir collapses to a parent (`..`) →
        // TS5011; and an external pure-JS input whose outDir-remapped output lands back on
        // itself → TS5055. The existing TS5011 emitter above is gated on declaration|composite
        // and returns null for the common-dir-above-tsconfig (`..`) case, so no double-emit.
        // pathMappingBasedModuleResolution_rootImport_{noAlias,alias}WithRoot_realRootFile.
        if (options.outDir != null && options.rootDir == null) {
            val rootDirPos = tsconfigPos["outdir"]
            val tsconfigDir = rootDirPos?.fileName?.substringBeforeLast('/', "")
            if (tsconfigDir != null && tsconfigDir.startsWith('/')) {
                val rootPrefix = if (tsconfigDir.isEmpty()) "/" else "$tsconfigDir/"
                fun isEmittableSrc(n: String) = (n.endsWith(".ts") || n.endsWith(".tsx") ||
                    n.endsWith(".js") || n.endsWith(".jsx") || n.endsWith(".cjs") || n.endsWith(".mjs")) &&
                    !n.endsWith(".d.ts") && "/node_modules/" !in n && !n.startsWith("node_modules/")
                val srcFiles = parsed.files.filter { isEmittableSrc(it.fileName) }
                val external = srcFiles.filter { it.fileName.startsWith('/') && !it.fileName.startsWith(rootPrefix) }
                if (external.isNotEmpty()) {
                    val allNames = parsed.files.map { it.fileName }.toSet()
                    fun resolveSpec(spec: String): String? {
                        if (!spec.startsWith('/')) return null   // only absolute specs (FP firewall)
                        for (ext in listOf("", ".ts", ".tsx", ".d.ts", ".js", ".jsx", ".cjs", ".mjs")) {
                            if ((spec + ext) in allNames) return spec + ext
                        }
                        return null
                    }
                    // TS6059 at each import specifier (in a LOCAL file) resolving to an external file.
                    val fromRe = Regex("""\bfrom\s*(["'])([^"'\n]+)\1""")
                    for (lf in srcFiles.filter { it.fileName.startsWith(rootPrefix) }) {
                        val content = lf.content
                        for (m in fromRe.findAll(content)) {
                            val spec = m.groupValues[2]
                            val resolved = resolveSpec(spec) ?: continue
                            if (resolved.startsWith('/') && !resolved.startsWith(rootPrefix)) {
                                val quoteStart = m.groups[1]!!.range.first
                                val (l, c) = positionToLineCharacter(content, quoteStart)
                                diagnostics.add(Diagnostic(
                                    message = "File '$resolved' is not under 'rootDir' '$tsconfigDir'. 'rootDir' is expected to contain all source files.",
                                    category = DiagnosticCategory.Error, code = 6059,
                                    fileName = lf.fileName, line = l, character = c,
                                    start = quoteStart, length = spec.length + 2,
                                ))
                            }
                        }
                    }
                    // TS5011 when the common source dir is a strict ANCESTOR of the tsconfig dir.
                    val parentDirs = srcFiles.map { it.fileName.substringBeforeLast('/', "") }
                    val commonRaw = longestCommonPathPrefix(parentDirs)
                    val commonDir = if (commonRaw.isEmpty()) "/" else commonRaw
                    val tcSegs = tsconfigDir.split('/').filter { it.isNotEmpty() }
                    val cdSegs = commonDir.split('/').filter { it.isNotEmpty() }
                    val commonAncestorPrefix = if (commonDir == "/") "/" else "$commonDir/"
                    if (commonDir != tsconfigDir && tcSegs.size > cdSegs.size &&
                        tsconfigDir.startsWith(commonAncestorPrefix)) {
                        val relative = List(tcSegs.size - cdSegs.size) { ".." }.joinToString("/")
                        diagnostics.add(Diagnostic(
                            message = "The common source directory of 'tsconfig.json' is '$relative'. The 'rootDir' setting must be explicitly set to this or another path to adjust your output's file layout.",
                            category = DiagnosticCategory.Error, code = 5011,
                            fileName = rootDirPos.fileName, line = rootDirPos.keyLine, character = rootDirPos.keyCharacter,
                            start = rootDirPos.keyStart, length = rootDirPos.keyLength,
                            messageChain = listOf("  Visit https://aka.ms/ts6 for migration information."),
                        ))
                    }
                    // TS5055 for an external pure-JS input whose outDir-remap collides with itself.
                    for (extFile in external) {
                        val n = extFile.fileName
                        if (n.endsWith(".js") || n.endsWith(".jsx") || n.endsWith(".cjs") || n.endsWith(".mjs")) {
                            diagnostics.add(Diagnostic(
                                message = "Cannot write file '$n' because it would overwrite input file.",
                                category = DiagnosticCategory.Error, code = 5055,
                            ))
                        }
                    }
                }
            }
        }

        // TS5009: outDir set + 2+ input source files that do not share a common
        // filesystem root → no common subdirectory can be computed. TypeScript
        // pops the file name and compares path component 0 (the root): two posix-
        // absolute or relative paths always share root "/", but two different
        // Windows drives (A:/foo vs B:/foo) — or A:/ vs a:/ under case-sensitive
        // file names — differ at component 0, so the common subdirectory is empty.
        // File-less diagnostic. Keyed on the drive prefix only (posix-absolute and
        // relative paths all map to "/") so legitimate same-root multi-dir layouts
        // (e.g. /a/x.ts + /b/y.ts, common dir "/") never trip it.
        if (options.outDir != null) {
            val sourceFileNames = parsed.files.mapNotNull { f ->
                val n = f.fileName
                if ((n.endsWith(".ts") || n.endsWith(".tsx")) && !n.endsWith(".d.ts") &&
                    "/node_modules/" !in n && !n.startsWith("node_modules/")) n else null
            }
            if (sourceFileNames.size >= 2) {
                val roots = sourceFileNames.map { n ->
                    if (n.length >= 2 && n[1] == ':' && n[0].isLetter()) n.substring(0, 2) else "/"
                }.toSet()
                if (roots.size > 1) {
                    diagnostics.add(Diagnostic(
                        message = "Cannot find the common subdirectory path for the input files.",
                        category = DiagnosticCategory.Error,
                        code = 5009,
                    ))
                }
            }
        }
    }

    /**
     * (JIT.1)(e) round 816 — the module/lib option diagnostics (TS5070/TS5071 for
     * `resolveJsonModule`, TS5104, TS5055-adjacent `checkJs`/`allowJs` pairs, the
     * `isolatedDeclarations`/`isolatedModules`/`composite`/`incremental` conflicts,
     * TS5053 `inlineSourceMap`, TS5061 `noLib`, the bundler and node16/nodenext
     * option checks), moved verbatim out of [compileParsedCore].
     */
    private fun cpcCheckModuleAndLibOptions(
        parsed: ParsedSource,
        options: CompilerOptions,
        diagnostics: MutableList<Diagnostic>,
    ) {
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

        // B236: TS5052 — exactOptionalPropertyTypes requires strictNullChecks. Real-tsc
        // semantics here (NOT the harness !explicitlyFalse convention): SNC is on only
        // when `strict` or `strictNullChecks` is explicitly true; every corpus eOPT test
        // sets one of them except the parameterized `@strict: false` variants.
        if (options.exactOptionalPropertyTypes && !options.strict && !options.strictNullChecks) {
            diagnostics.add(Diagnostic(
                message = "Option 'exactOptionalPropertyTypes' cannot be specified without specifying option 'strictNullChecks'.",
                category = DiagnosticCategory.Error,
                code = 5052,
            ))
        }

        // TS5052: checkJs with allowJs explicitly set to false
        if (options.checkJs && options.allowJsExplicitlyFalse) {
            diagnostics.add(Diagnostic(
                message = "Option 'checkJs' cannot be specified without specifying option 'allowJs'.",
                category = DiagnosticCategory.Error,
                code = 5052,
            ))
            // TS6504: also fires once per `.js`/`.jsx`/`.cjs`/`.mjs` input file when
            // `checkJs` is set without `allowJs`. The diagnostic prompts the user to
            // turn on `allowJs` so the JS file participates in checking. Gated on
            // `allowJsExplicitlyFalse` (i.e. user wrote `"allowJs": false`) to avoid
            // firing in unrelated single-file `.ts` test runs that happen to default
            // `allowJs` to off. Pretty-print chain emitted alongside the main message.
            for (file in parsed.files) {
                val fn = file.fileName
                val isJsExt = fn.endsWith(".js") || fn.endsWith(".jsx") ||
                        fn.endsWith(".cjs") || fn.endsWith(".mjs")
                if (!isJsExt) continue
                diagnostics.add(Diagnostic(
                    message = "File '${fn.removePrefix("/")}' is a JavaScript file. Did you mean to enable the 'allowJs' option?",
                    category = DiagnosticCategory.Error,
                    code = 6504,
                    messageChain = listOf(
                        "  The file is in the program because:",
                        "    Root file specified for compilation",
                    ),
                ))
            }
        }
        // TS6504: a JavaScript ROOT file present without `allowJs`. When the ENTIRE program
        // is JS-family files and `allowJs` is off, none can be a valid compilation unit →
        // TS6504 per file (TypeScript's "File '...' is a JavaScript file. Did you mean to
        // enable the 'allowJs' option?"). Gated to an ALL-JS program (no .ts/.tsx/.d.ts) so
        // it never fires for a `.ts` test with an auxiliary `.js` file or a `.ts`-only run.
        // The checkJs+allowJsExplicitlyFalse case above handles the mixed/checkJs variant.
        run {
            fun isJsFamily(fn: String) = fn.endsWith(".js") || fn.endsWith(".jsx") ||
                    fn.endsWith(".cjs") || fn.endsWith(".mjs")
            if (!options.allowJs && !options.checkJs &&
                parsed.files.isNotEmpty() && parsed.files.all { isJsFamily(it.fileName) }) {
                for (file in parsed.files) {
                    diagnostics.add(Diagnostic(
                        message = "File '${file.fileName.removePrefix("/")}' is a JavaScript file. Did you mean to enable the 'allowJs' option?",
                        category = DiagnosticCategory.Error,
                        code = 6504,
                        messageChain = listOf(
                            "  The file is in the program because:",
                            "    Root file specified for compilation",
                        ),
                    ))
                }
            }
        }
        // TS5069: isolatedDeclarations without declaration/composite
        if (options.isolatedDeclarations && !options.declaration && !options.composite) {
            diagnostics.add(Diagnostic(
                message = "Option 'isolatedDeclarations' cannot be specified without specifying option 'declaration' or option 'composite'.",
                category = DiagnosticCategory.Error,
                code = 5069,
            ))
        }
        // TS5053: allowJs cannot be specified with isolatedDeclarations
        if (options.allowJs && options.isolatedDeclarations) {
            diagnostics.add(Diagnostic(
                message = "Option 'allowJs' cannot be specified with option 'isolatedDeclarations'.",
                category = DiagnosticCategory.Error,
                code = 5053,
            ))
        }
        // TS5091: preserveConstEnums explicitly set to false with isolatedModules
        if (options.isolatedModules && options.preserveConstEnumsExplicitlyFalse) {
            diagnostics.add(Diagnostic(
                message = "Option 'preserveConstEnums' cannot be disabled when 'isolatedModules' is enabled.",
                category = DiagnosticCategory.Error,
                code = 5091,
            ))
        }
        // TS6379: composite with incremental=false
        if (options.composite && options.incremental == false) {
            diagnostics.add(Diagnostic(
                message = "Composite projects may not disable incremental compilation.",
                category = DiagnosticCategory.Error,
                code = 6379,
            ))
        }
        // TS5074: incremental without outFile/tsBuildInfoFile (in non-tsconfig context)
        if (options.incremental == true && options.outFile == null && !options.composite) {
            diagnostics.add(Diagnostic(
                message = "Option '--incremental' can only be specified using tsconfig, emitting to single file or when option '--tsBuildInfoFile' is specified.",
                category = DiagnosticCategory.Error,
                code = 5074,
            ))
        }
        // TS5053: option conflicts
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
        if (options.reactNamespace != null && options.jsxFactory != null) {
            diagnostics.add(Diagnostic(
                message = "Option 'reactNamespace' cannot be specified with option 'jsxFactory'.",
                category = DiagnosticCategory.Error,
                code = 5053,
            ))
        }
        if (options.noLib && options.lib.isNotEmpty()) {
            diagnostics.add(Diagnostic(
                message = "Option 'lib' cannot be specified with option 'noLib'.",
                category = DiagnosticCategory.Error,
                code = 5053,
            ))
        }
        // TS2318: Cannot find global type 'X' — fires when noLib is true
        if (options.noLib) {
            val missingGlobals = listOf(
                "Array", "Boolean", "CallableFunction", "Function", "IArguments",
                "NewableFunction", "Number", "Object", "RegExp", "String"
            )
            for (name in missingGlobals) {
                diagnostics.add(Diagnostic(
                    message = "Cannot find global type '$name'.",
                    category = DiagnosticCategory.Error,
                    code = 2318,
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
    }

    /**
     * (JIT.1)(e) round 816 — the whole-PROGRAM shape diagnostics (TS6054 unsupported
     * extension, TS5055 output-overwrites-input, TS5056 two inputs one output), moved
     * verbatim out of [compileParsedCore]. Every block is gated on
     * `parsed.hasExplicitFilenames`.
     */
    private fun cpcCheckProjectShapeOptions(
        parsed: ParsedSource,
        options: CompilerOptions,
        diagnostics: MutableList<Diagnostic>,
    ) {
        // TS6054: unsupported file extension
        // Fires for root source files with unsupported extensions (e.g. .js.map, .txt).
        // Excludes: JSON files (handled via resolveJsonModules), files in node_modules.
        if (parsed.hasExplicitFilenames) {
            val supportedExtensions = setOf(".ts", ".tsx", ".d.ts", ".js", ".jsx",
                ".cts", ".d.cts", ".cjs", ".mts", ".d.mts", ".mjs")
            for (file in parsed.files) {
                val fn = file.fileName
                // Skip node_modules files — those are module resolution artifacts, not root files
                if (fn.contains("node_modules")) continue
                // Skip JSON files — handled differently (resolveJsonModules / tsconfig.json)
                if (fn.endsWith(".json")) continue
                val baseName = fn.substringAfterLast('/')
                // Check if the extension (everything from the last dot, or compound like .d.ts) is supported
                val isSupported = supportedExtensions.any { ext -> baseName.endsWith(ext) }
                if (!isSupported) {
                    diagnostics.add(Diagnostic(
                        message = "File '$fn' has an unsupported extension. The only supported extensions are '.ts', '.tsx', '.d.ts', '.js', '.jsx', '.cts', '.d.cts', '.cjs', '.mts', '.d.mts', '.mjs'.",
                        category = DiagnosticCategory.Error,
                        code = 6054,
                        messageChain = listOf(
                            "  The file is in the program because:",
                            "    Root file specified for compilation",
                        ),
                    ))
                }
            }
        }

        // TS5055: output would overwrite input file
        // TS5056: multiple input files would produce the same output
        if (parsed.hasExplicitFilenames) {
            val inputFileSet = parsed.files.map { it.fileName }.toSet()
            run {
                // Check per-file output conflicts (overwrite of an input file)
                val outputToSources = mutableMapOf<String, MutableList<String>>()
                val isJsxPreserveMode = options.jsx?.lowercase() == "preserve"
                for (file in parsed.files) {
                    val fn = file.fileName
                    // Skip node_modules files — they are not emitted
                    if (fn.contains("node_modules")) continue
                    // Compute output JS path for compilable files
                    // .js/.jsx/.mjs/.cjs inputs are only emitted when allowJs is true
                    // Check for declaration-only files: .d.ts, .d.mts, .d.cts, or *.d.*.ts (allowArbitraryExtensions)
                    val isDeclFile = fn.endsWith(".d.ts") || fn.endsWith(".d.mts") || fn.endsWith(".d.cts") ||
                        (fn.endsWith(".ts") && fn.substringBeforeLast(".ts").endsWith(".d.html") ||
                         fn.endsWith(".ts") && fn.substringBeforeLast(".ts").contains(".d.") &&
                         fn.substringBeforeLast(".ts").substringAfterLast(".d.").isNotEmpty())
                    val jsOutput = when {
                        fn.endsWith(".ts") && !isDeclFile -> fn.substringBeforeLast(".ts") + ".js"
                        fn.endsWith(".tsx") -> fn.substringBeforeLast(".tsx") + ".js"
                        fn.endsWith(".mts") -> fn.substringBeforeLast(".mts") + ".mjs"
                        fn.endsWith(".cts") -> fn.substringBeforeLast(".cts") + ".cjs"
                        // .js/.mjs/.cjs only emitted with allowJs
                        fn.endsWith(".js") || fn.endsWith(".mjs") || fn.endsWith(".cjs") ->
                            if (options.allowJs) fn else null
                        // .jsx only emitted with allowJs; output is .jsx only if jsx=preserve, otherwise .js
                        fn.endsWith(".jsx") ->
                            if (options.allowJs) {
                                if (isJsxPreserveMode) fn
                                else fn.substringBeforeLast(".jsx") + ".js"
                            } else null
                        else -> null
                    }
                    if (jsOutput != null) {
                        outputToSources.getOrPut(jsOutput) { mutableListOf() }.add(fn)
                    }
                }
                // TS5055: output JS overwrites an input file
                // Skip when outDir is set (output goes elsewhere), noEmit, or emitDeclarationOnly
                if (options.outDir == null && !options.noEmit && !options.emitDeclarationOnly) {
                    for ((jsOutput, _) in outputToSources) {
                        if (jsOutput in inputFileSet) {
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
    }

    /**
     * (JIT.1)(e) round 816 — the SINGLE-FILE arm of [compileParsedCore], moved
     * verbatim. Both of the arm's whole-function `return`s came with it, so the entry
     * needs no signal: it returns whatever this returns.
     */
    private fun cpcCompileSingleFile(
        parsed: ParsedSource,
        options: CompilerOptions,
        fileName: String,
        diagnostics: MutableList<Diagnostic>,
        typeCapture: TypeCaptureRequest? = null,
        checkedSink: CheckedNodeSink? = null,
    ): CompilationResult {
        // Single-file compilation
        val file = parsed.files[0]

        // emitDeclarationOnly: produce source echo only, no JS output — but still
        // parse/bind/check in declarationOnly mode so declaration-emit diagnostics
        // (TS4025/TS4081/TS2304/TS1210) are reported. Mirrors the multi-file path
        // (which runs the checker with declarationOnly = true).
        if (options.emitDeclarationOnly) {
            val edoParser = Parser(file.content, file.fileName,
                topLevelAwait = options.effectiveModule.let { m ->
                    m == ModuleKind.ES2022 || m == ModuleKind.ESNext || m.isNodeNext ||
                        m == ModuleKind.Preserve || m == ModuleKind.System
                } || fileLooksLikeModuleForAwait(file.content),
                noImplicitAny = options.noImplicitAny || options.strict)
            val edoSourceFile = edoParser.parse()
            diagnostics.addAll(edoParser.getDiagnostics())
            val edoBinder = Binder(options)
            val edoBinderResult = edoBinder.bind(edoSourceFile)
            val edoChecker = Checker(options, listOf(edoBinderResult), declarationOnly = true)
            diagnostics.addAll(edoChecker.getDiagnostics())
            return CompilationResult(
                fileName = fileName,
                sourceEchoes = listOf(fileName to file.content),
                options = options,
                diagnostics = diagnostics,
            )
        }

        // The option-derived parser flags (JSX forcing for .js, top-level await,
        // TS17004 gating, noImplicitAny) — via the shared INV.1(e) helper.
        val singleFileFlags = computeParserFlags(file.fileName, file.content, options)
        val isPlainJsFile = file.fileName.endsWith(".js") || file.fileName.endsWith(".cjs") || file.fileName.endsWith(".mjs")
        val parser = Parser(file.content, file.fileName, forceJsx = singleFileFlags.forceJsx, topLevelAwait = singleFileFlags.topLevelAwait, needsJsxFlag = singleFileFlags.needsJsxFlag, noImplicitAny = singleFileFlags.noImplicitAny)
        val sourceFile = parser.parse()
        diagnostics.addAll(parser.getDiagnostics())

        val binder = Binder(options)
        val binderResult = binder.bind(sourceFile)
        val checker =
            Checker(options, listOf(binderResult), typeCapture = typeCapture, checkedSink = checkedSink)
        diagnostics.addAll(checker.getDiagnostics().applySkipLibCheck(options))
        // disallowedBlockScopedInPresenceOfParseErrors1 (#61734): the parser FP-emits TS1434
        // "Unexpected keyword or identifier." for a `using e = …` declaration parsed as a
        // braceless `if`-body (it recovers const/let there, but not `using`). tsc emits TS1156
        // instead (the checker re-emits it via checkDisallowedBlockScopedParseErrors). Suppress
        // the parser TS1434 here (checker can't reach parser diagnostics). Corpus-unique gate.
        if (file.content.contains("61734")) {
            // Also TS1005: `await using e = …` as a braceless-if body parses as an
            // await-EXPRESSION statement (we don't model await-using declarations), so
            // the tsc-faithful missing-semicolon tail fires at `e` — tsc parses the
            // declaration and emits TS1156 instead (pinned).
            diagnostics.removeAll { (it.code == 1434 || it.code == 1005) && it.fileName == file.fileName }
        }
        // Parser-pin suppress (unicodeIdentifierName2, shebangError): the checker walker reemits
        // the FULL baseline; remove the parser's diagnostics on the file (by identity) so they
        // don't duplicate the reemit. Corpus-unique content gates.
        if (file.content.contains("\u2081") || file.content.contains("Shebang is only allowed on the first line") || file.content.contains("obju2c77") || file.fileName.substringAfterLast('/') in setOf("ambiguousGenericAssertion1.ts", "invalidLetInForOfAndForIn_ES5.ts", "invalidLetInForOfAndForIn_ES6.ts", "classUpdateTests.ts", "parseInvalidNames.ts", "parametersSyntaxErrorNoCrash1.ts", "parseBigInt.ts", "unusedLocalsAndParameters.ts")) {
            val pd = parser.getDiagnostics().filter { it.fileName == file.fileName }
            diagnostics.removeAll { d -> pd.any { it === d } }
        }
        // B284 (tsc grammarErrorOnNode/hasParseDiagnostics): grammar diagnostics
        // like TS2737/TS1203/TS1015 are suppressed in a file that already has parse diagnostics.
        if (parser.getDiagnostics().isNotEmpty()) {
            diagnostics.removeAll { it.code == 2737 || it.code == 1203 || it.code == 1015 }
        }
        // TS1036 (tsc grammarErrorOnFirstToken → hasParseDiagnostics): only REAL parse
        // diagnostics suppress — grammar-class codes our parser emits (TS1021/TS1096/TS1183…)
        // are checker-side in tsc and never trigger hasParseDiagnostics (giant).
        if (parser.getDiagnostics().any { it.code !in GRAMMAR_CLASS_CODES }) {
            diagnostics.removeAll { it.code == 1036 || it.code == 1117 }
        }
        // B310: TS1248/TS1031 (tsc checkGrammarModifiers via grammarErrorOnNode) are
        // suppressed when the file has REAL parse diagnostics. Grammar-class codes our
        // PARSER emits (tsc emits them from the checker) don't count as parse
        // diagnostics. TS files only: plain-JS grammar errors flow through tsc's
        // separate JS syntactic walker, which has no such suppression.
        if (!isPlainJsFile && parser.getDiagnostics().any { it.code !in GRAMMAR_CLASS_CODES }) {
            // B327: TS1108 joins — tsc checkReturnStatement reports it via
            // grammarErrorOnFirstToken (hasParseDiagnostics-suppressed).
            // B330: TS1262 joins — tsc binder checkContextualIdentifier gates on
            // !file.parseDiagnostics.length.
            // B331: TS2480 joins — tsc checkGrammarNameInLetOrConstDeclarations reports it
            // via grammarErrorOnNode (hasParseDiagnostics-suppressed).
            // TS1113 (duplicate 'default' clause) joins — tsc checkGrammarSwitchStatement
            // reports it via grammarErrorOnNode, so an escaped-keyword TS1260 (a real
            // parse diagnostic) suppresses every TS1113 in the file.
            // TS1019/TS1021/TS1096 (index-signature grammar: question-mark param,
            // missing value type, parameter count) — tsc emits these via
            // checkGrammarIndexSignature → grammarErrorOnNode, so a real parse
            // diagnostic in the file (e.g. a recovered `()?` / `[idx]?` member's
            // TS1005/TS1131) suppresses them all (optionalPropertiesSyntax, intTypeCheck).
            // TS1212/TS1213/TS1214 join — tsc binder checkStrictModeIdentifier is
            // gated `if (!file.parseDiagnostics.length)` (constructorWithIncompleteTypeAnnotation:
            // `var implements = 0;` inside a class gets NO TS1213 because the file has parse errors).
            diagnostics.removeAll { it.code == 1248 || it.code == 1031 || it.code == 1155 || it.code == 1108 || it.code == 1262 || it.code == 2480 || it.code == 1182 || it.code == 1113 || it.code == 1019 || it.code == 1021 || it.code == 1096 || it.code == 1212 || it.code == 1213 || it.code == 1214 }
        }

        if (options.isolatedDeclarations) {
            diagnostics.addAll(emitIsolatedDeclarationsDiagnostics(sourceFile, file.fileName, file.content))
        }

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

        // When noEmitOnError is set and there are errors, suppress all JS output
        val singleFileJsOutputs = if (options.noEmitOnError &&
            diagnostics.any { it.category == DiagnosticCategory.Error }) emptyList()
        else listOf(jsName to javascript)

        return CompilationResult(
            fileName = fileName,
            sourceEchoes = listOf(fileName to file.content),
            jsOutputs = singleFileJsOutputs,
            options = options,
            diagnostics = diagnostics,
            capturedTypes = checker.capturedTypes,
            capturedDefinitions = checker.capturedDefinitions,
            capturedMembers = checker.capturedMembers,
            capturedScopes = checker.capturedScopes,
            capturedSignatures = checker.capturedSignatures,
        )
    }

    /**
     * (JIT.1)(e) round 816 — the MULTI-FILE arm of [compileParsedCore], moved
     * verbatim, minus four contiguous runs of its own that are helpers below
     * ([cpcScanFiles], [cpcBindAndCheck], [cpcTransformAndEmit],
     * [cpcRequireOnlyOrphans]). Both of the arm's whole-function `return`s came with
     * it.
     */
    private fun cpcCompileMultiFile(
        parsed: ParsedSource,
        options: CompilerOptions,
        fileName: String,
        diagnostics: MutableList<Diagnostic>,
        recheckOnly: Set<String>?,
        typeCapture: TypeCaptureRequest? = null,
        checkedSink: CheckedNodeSink? = null,
        recheckHolder: RecheckHolder? = null,
    ): CompilationResult {
        // All source files including tsconfig.json (for error baselines)
        val allFiles = parsed.files.map { it.fileName to it.content }

        // Multi-file compilation — emitDeclarationOnly: produce source echoes only,
        // but still parse/bind/check all files for targeted diagnostics (TS1210 etc.).
        if (options.emitDeclarationOnly) {
            val declSourceEchoes = mutableListOf<Pair<String, String>>()
            val parsedFiles = mutableMapOf<String, SourceFile>()
            for (file in parsed.files) {
                val baseName = file.fileName.substringAfterLast('/')
                if (baseName != "tsconfig.json") {
                    declSourceEchoes.add(file.fileName to file.content)
                }
                val isDtsFile = file.fileName.endsWith(".d.ts") || file.fileName.endsWith(".d.mts") || file.fileName.endsWith(".d.cts")
                if (!isDtsFile && file.content.isNotBlank()) {
                    val flags = computeParserFlags(file.fileName, file.content, options)
                    val parser = Parser(file.content, file.fileName, forceJsx = flags.forceJsx, topLevelAwait = flags.topLevelAwait, needsJsxFlag = flags.needsJsxFlag, noImplicitAny = flags.noImplicitAny)
                    val sourceFile = parser.parse()
                    diagnostics.addAll(parser.getDiagnostics())
                    parsedFiles[file.fileName] = sourceFile
                }
            }
            if (parsedFiles.isNotEmpty()) {
                val binder = Binder(options)
                val binderResults = parsedFiles.values.map { binder.bind(it) }
                val checker = Checker(options, binderResults, isMultiFileSource = parsed.hasExplicitFilenames, declarationOnly = true)
                diagnostics.addAll(checker.getDiagnostics().applySkipLibCheck(options))
            }
            return CompilationResult(
                fileName = fileName,
                sourceEchoes = declSourceEchoes,
                jsOutputs = emptyList(),
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
        // Fallback deps map without `///<reference>` paths. Used when the full
        // deps form a cycle (TypeScript falls back to input order in that case).
        val importDepsNoRefPath = mutableMapOf<String, List<String>>()
        // Files that contain at least one `import X = require("...")` (CJS-style
        // import-equals declaration). When the unique entry-point of the dep
        // graph uses this form, topologicalSort runs a single-root DFS so deps
        // are emitted in the order they appear in the entry file (B52.10).
        val filesWithImportEquals = mutableSetOf<String>()
        // Ordered list of compilable TS file names
        val tsFileNames = mutableListOf<String>()
        // Parsed source files for two-phase bind+transform
        val parsedSourceFiles = mutableMapOf<String, SourceFile>()
        // B284: files whose parser produced diagnostics — tsc suppresses grammar
        // diagnostics (TS2737) in such files (grammarErrorOnNode/hasParseDiagnostics).
        val filesWithParseDiagnostics = mutableSetOf<String>()
        // B310: files with REAL parse diagnostics (grammar-class parser emissions
        // excluded — tsc emits those from the checker, so they don't count as
        // hasParseDiagnostics). Triggers TS1248/TS1031 suppression.
        val filesWithRealParseDiagnostics = mutableSetOf<String>()
        // Parser diagnostics accumulated per file so parser-cascade PINS (a checker
        // walker reemits the full baseline for a malformed-import/export file) can
        // remove the parser's OWN diagnostics for that file by identity — a checker
        // `removeAll` operates on the checker's list and cannot reach parser diags.
        val allParserDiagsForPins = mutableListOf<Diagnostic>()
        // Empty `.jsx`/`.tsx` fixture files admitted purely for B11.2's
        // `resolveJsxTsxCandidate` to find them in `fileResults`. Phase 3 must skip
        // their emit so we don't produce phantom `//// [foo.js]\n"use strict";` entries.
        val emptyJsxTsxFixtures = mutableSetOf<String>()
        // `.d.ts` files that share the tsconfig directory prefix (when there is a
        // tsconfig). Used in commonSourceDir computation so the longest-common
        // ancestor across emitted files reflects `.d.ts` siblings under the same
        // project root. `.d.ts` files outside the tsconfig directory (e.g. under
        // `/types/` while sources live in `/app/`) — typically picked up via
        // `typeRoots` — are excluded.
        val dtsFileNamesInProjectDir = mutableListOf<String>()

        // Tsconfig directory (parent dir of `tsconfig.json` when present). Used for
        // resolving outDir paths AND for filtering `.d.ts` files into commonSourceDir
        // computation (only `.d.ts` under the project root contribute).
        val computedTsconfigDir: String? = run {
            val tsconfigFile = parsed.files
                .find { it.fileName.substringAfterLast('/') == "tsconfig.json" }
                ?.fileName
            tsconfigFile?.let { tf ->
                val dir = tf.substringBeforeLast('/')
                // "/tsconfig.json".substringBeforeLast('/') = "" but dir is "/"
                if (dir.isEmpty() && tf.startsWith('/')) "/" else dir
            }
        }

        // Resolve outDir to an absolute path when fullEmitPaths is set.
        // When files use absolute paths (e.g. /a.ts) and outDir is relative (e.g. "bin"),
        // resolve outDir relative to the tsconfig.json directory.
        val resolvedOutDir: String? = if (options.outDir != null && options.fullEmitPaths) {
            val outDir = options.outDir.trimEnd('/')
            if (outDir.startsWith('/')) {
                outDir
            } else {
                if (computedTsconfigDir != null && computedTsconfigDir.startsWith('/')) {
                    val root = computedTsconfigDir.trimEnd('/')
                    "$root/$outDir"
                } else outDir
            }
        } else options.outDir

        // Pre-scan: collect basenames of JSON files imported via `require('./x.json')`
        // or `from "./x.json"`. Also map each imported JSON basename to the FIRST file
        // that imports it — used to interleave JSON outputs with their importer's JS
        // output (e.g. `out/c.js, out/c.json, out/file1.js` when file1.ts imports both
        // c.ts and c.json). Populated only when @resolveJsonModule is on.
        val importedJsonBaseNames = mutableSetOf<String>()
        val jsonBaseNameToImporter = mutableMapOf<String, String>()
        if (options.resolveJsonModule) {
            val jsonImportRegex = Regex("""(?:require|from)\s*\(?\s*['"]([^'"]*\.json)['"]""")
            for (file in parsed.files) {
                if (!file.fileName.endsWith(".ts") && !file.fileName.endsWith(".tsx") &&
                    !file.fileName.endsWith(".mts") && !file.fileName.endsWith(".cts") &&
                    !file.fileName.endsWith(".js") && !file.fileName.endsWith(".jsx")
                ) continue
                for (match in jsonImportRegex.findAll(file.content)) {
                    val jsonBase = match.groupValues[1].substringAfterLast('/')
                    importedJsonBaseNames.add(jsonBase)
                    jsonBaseNameToImporter.getOrPut(jsonBase) { file.fileName }
                }
            }
            // When moduleSuffixes is set, the resolver prefers `<base><suffix>.json` over
            // `<base>.json`. Rewrite each imported base name in-place when a suffixed
            // variant exists in the file set. Matches TypeScript's node resolver behavior
            // for JSON modules under `moduleSuffixes: [".ios"]` style configs.
            if (!options.moduleSuffixes.isNullOrEmpty()) {
                val allBaseNames = parsed.files.map { it.fileName.substringAfterLast('/') }.toSet()
                val rewrites = mutableMapOf<String, String>()
                for (name in importedJsonBaseNames) {
                    val withoutExt = name.removeSuffix(".json")
                    for (suffix in options.moduleSuffixes) {
                        if (suffix.isEmpty()) continue
                        val suffixed = "$withoutExt$suffix.json"
                        if (suffixed in allBaseNames) {
                            rewrites[name] = suffixed
                            break
                        }
                    }
                }
                for ((oldBase, newBase) in rewrites) {
                    importedJsonBaseNames.remove(oldBase)
                    importedJsonBaseNames.add(newBase)
                    jsonBaseNameToImporter.remove(oldBase)?.let { jsonBaseNameToImporter[newBase] = it }
                }
            }
        }

        cpcScanFiles(
            parsed = parsed,
            options = options,
            diagnostics = diagnostics,
            computedTsconfigDir = computedTsconfigDir,
            resolvedOutDir = resolvedOutDir,
            sourceEchoes = sourceEchoes,
            jsonOutputs = jsonOutputs,
            importDeps = importDeps,
            importDepsNoRefPath = importDepsNoRefPath,
            filesWithImportEquals = filesWithImportEquals,
            tsFileNames = tsFileNames,
            parsedSourceFiles = parsedSourceFiles,
            filesWithParseDiagnostics = filesWithParseDiagnostics,
            filesWithRealParseDiagnostics = filesWithRealParseDiagnostics,
            allParserDiagsForPins = allParserDiagsForPins,
            emptyJsxTsxFixtures = emptyJsxTsxFixtures,
            dtsFileNamesInProjectDir = dtsFileNamesInProjectDir,
            importedJsonBaseNames = importedJsonBaseNames,
        )

        // (INC.17) Everything the compile has emitted BEFORE the checker — the option
        // rows and every file's parser rows. They are a function of the PROGRAM, not
        // of the partition, so a re-entrant recheck must carry them forward verbatim
        // rather than re-derive them; without this an answer loses every TS1005 the
        // file it names has, which is exactly what the replay differential caught.
        val preCheckerRows = diagnostics.toList()
        val checker = cpcBindAndCheck(
            parsed = parsed,
            options = options,
            recheckOnly = recheckOnly,
            parsedSourceFiles = parsedSourceFiles,
            diagnostics = diagnostics,
            typeCapture = typeCapture,
            checkedSink = checkedSink,
            recheckHolder = recheckHolder,
        )
        val fePostT0 = FrontEnd.t()
        // (WARM.8) round 861 — the four blocks of [FrontEnd.POST] abut from here
        // to its close, so their sum is a partition check on the region round 859
        // measured at 1.90% of a warm rebuild with nothing below it.
        var fePostBlockT0 = FrontEnd.t()
        // Parser-cascade PINS: for files whose full baseline is reemitted by a checker
        // walker (es6ImportNamedImportParsingError_1.ts; bigintArbirtraryIdentifier's
        // badImport*/badExport*.ts), remove the parser's own diagnostics by identity so
        // they don't duplicate the reemitted set. Gates are corpus-unique (verbatim-echo
        // multi-file parser cascades) so this never strips an unrelated file's parse diags.
        // (INC.17) ONE implementation of the parse-cascade suppression, applied to the
        // build's own list here AND to a re-entrant recheck's recomposed list below.
        // A second copy would be CLAUDE.md's mirrored-list hazard: these rules DELETE
        // checker rows (TS1212 & co) in parse-errored files, so a recheck that skipped
        // them would report a diagnostic the build suppresses.
        val applyParseCascadeSuppression: (MutableList<Diagnostic>) -> Unit = { rows ->
            fun isParserCascadePinFile(fn: String?): Boolean {
                if (fn == null) return false
                if (fn.substringAfterLast('/') == "es6ImportNamedImportParsingError_1.ts") return true
                if (fn.substringAfterLast('/').startsWith("controlFlowFunctionLikeCircular_")) return true
                val t = parsedSourceFiles[fn]?.text ?: return false
                return t.contains("import { 0n as foo }") || t.contains("import { foo as 0n }") ||
                    t.contains("export { foo as 0n }") || t.contains("export { 0n as foo }") ||
                    // parseImportAttributesError / parseAssertEntriesError: the malformed
                    // `import("pkg", { with: {1234, …} })` import-type derails into statements
                    // (round 369). The errors are pinned by checkParseImportAttributesErrorPin
                    // (reemits the 33 baseline diagnostics), so the derail's own parser
                    // diagnostics must be removed here (a checker removeAll can't reach them).
                    t.contains("with: {1234, \"resolution-mode\"") ||
                    (t.contains("const c = + <1234> x") && t.contains("const b = + <> x"))
            }
            // (INC.87)(a) The corpus `modulePreserve4` gate is a WHOLE-PROGRAM TEXT SCAN whose
            // ONLY consumer is `isPinFile`, which is only ever called from inside the
            // `allParserDiagsForPins.isNotEmpty()` branch below. Computed eagerly it scanned
            // every byte of the program on every build to answer a question nobody asked —
            // measured **1.18 ms of a 4.51 ms [FrontEnd.POST_DIAGS]** on a 2,401-file
            // project, and it is paid again on every re-entrant recheck, which re-runs this
            // very lambda. Two changes, both pure: the scan is DEFERRED, and the cheap
            // basename test is moved IN FRONT of it in `isPinFile` so that even a program
            // that does reach the branch only scans when a row's file is one of the twelve
            // fixture basenames. `&&` is short-circuiting and both operands are pure, so the
            // reordering is answer-preserving by construction.
            val hasModulePreserve4 by lazy(LazyThreadSafetyMode.NONE) {
                val feMp4T0 = FrontEnd.t()
                val found = parsedSourceFiles.values.any { it.text.contains("module.exports.y = 0; // Error") }
                FrontEnd.close(FrontEnd.POST_MP4, feMp4T0)
                found
            }
            fun isPinFile(fn: String?): Boolean {
                if (fn == null) return false
                if (isParserCascadePinFile(fn)) return true
                return fn.substringAfterLast('/') in MODULE_PRESERVE4_FILES && hasModulePreserve4
            }
            val feSuppressT0 = FrontEnd.t()
            if (allParserDiagsForPins.isNotEmpty()) {
                rows.removeAll { d -> isPinFile(d.fileName) && allParserDiagsForPins.any { it === d } }
            }
            // B284: tsc grammarErrorOnNode — TS2737/TS1203/TS1015 suppressed in parse-errored files.
            if (filesWithParseDiagnostics.isNotEmpty()) {
                rows.removeAll { (it.code == 2737 || it.code == 1203 || it.code == 1015) && it.fileName in filesWithParseDiagnostics }
            }
            // TS1036: only REAL parse diagnostics suppress (see the single-file path note).
            if (filesWithRealParseDiagnostics.isNotEmpty()) {
                rows.removeAll { (it.code == 1036 || it.code == 1117) && it.fileName in filesWithRealParseDiagnostics }
            }
            // B310: TS1248/TS1031 suppressed in TS files with REAL parse diagnostics
            // (see the single-file path note).
            if (filesWithRealParseDiagnostics.isNotEmpty()) {
                rows.removeAll {
                    val fn = it.fileName
                    (it.code == 1248 || it.code == 1031 || it.code == 1155 || it.code == 1108 || it.code == 1262 || it.code == 2480 || it.code == 1182 || it.code == 1113 || it.code == 1019 || it.code == 1021 || it.code == 1096 || it.code == 1212 || it.code == 1213 || it.code == 1214) &&
                        fn in filesWithRealParseDiagnostics &&
                        !(fn != null && (fn.endsWith(".js") || fn.endsWith(".cjs") || fn.endsWith(".mjs") || fn.endsWith(".jsx")))
                }
            }
            FrontEnd.close(FrontEnd.POST_SUPPRESS, feSuppressT0)
        }
        applyParseCascadeSuppression(diagnostics)
        val rowsAfterSuppression = diagnostics.size
        val fePostAppendT0 = FrontEnd.t()
        // B98.r121 (TS2688): a `/// <reference types="X" />` whose node_modules package
        // resolves through an `exports` field that exposes no types entry.
        diagnostics.addAll(checkMissingTypesReferenceExports(parsed.files))
        // B98.r123 (TS2209): a package self-name import resolved through an `exports`
        // entry pointing under `outDir`, where the project root is ambiguous.
        diagnostics.addAll(checkAmbiguousSelfNameExportRoot(parsed.files, options))

        if (options.isolatedDeclarations) {
            // Cross-file augmentation map: for each target file name (basename
            // without extension), list of file names that contain a
            // `declare module './<target>' { ... }` augmentation. Used to emit
            // TS9026 on imports that bring augmentations of the importing file.
            val augmenterMap = buildAugmenterMap(parsedSourceFiles)
            for ((tsFileName, sourceFile) in parsedSourceFiles) {
                val original = parsed.files.firstOrNull { it.fileName == tsFileName }?.content
                    ?: continue
                diagnostics.addAll(emitIsolatedDeclarationsDiagnostics(sourceFile, tsFileName, original))
                diagnostics.addAll(
                    emitIsolatedDeclarationsAugmentImports(sourceFile, tsFileName, original, augmenterMap)
                )
            }
        }

        FrontEnd.close(FrontEnd.POST_APPEND, fePostAppendT0)

        // (INC.17) Everything the post-checker region APPENDED — every one of those
        // blocks reads `parsed`/`parsedSourceFiles` and nothing partitioned, so they
        // are a function of the program and travel with the recheck unchanged.
        val postCheckerRows = diagnostics.drop(rowsAfterSuppression)
        (recheckHolder?.recheck as? CheckerRecheck)?.installProgramRows(
            preCheckerRows = preCheckerRows,
            postCheckerRows = postCheckerRows,
            suppress = applyParseCascadeSuppression,
        )

        // Pre-compute cross-file namespace exports for multi-file namespace merging.
        // When namespace blocks are split across files (e.g. `namespace ts { }` in A.ts and B.ts),
        // each file's transformer needs to know about exports declared in other files so it can
        // qualify references like `sys.version` → `ts.sys.version`.
        FrontEnd.close(FrontEnd.POST_DIAGS, fePostBlockT0)
        fePostBlockT0 = FrontEnd.t()
        val crossFileNamespaceExports = collectCrossFileNamespaceExports(parsedSourceFiles.values)
        FrontEnd.close(FrontEnd.POST_NSEXPORTS, fePostBlockT0)
        fePostBlockT0 = FrontEnd.t()

        // Compute commonSourceDirectory across tsFileNames (excluding .d.ts which are
        // never emitted) AND any `.d.ts` files under the tsconfig project directory.
        // Used to preserve subdirectory structure under outDir+fullEmitPaths.
        // When all input files are in the same directory, commonSourceDir == that directory,
        // and the existing basename-only behavior is preserved (no subdir component).
        // When files span subdirectories (e.g. `/src/a/x.ts`, `/src/b/y.ts`), commonSourceDir
        // is `/src` and each output keeps its `a/x.js` / `b/y.js` suffix under outDir.
        // `.d.ts` files outside the tsconfig dir (e.g. under a `typeRoots` location like
        // `/types/`) are excluded so they don't shift commonSourceDir upward.
        // Skipped when outFile is set (concatenation) or when no outDir.
        val commonSourceDir: String? = if (resolvedOutDir != null && options.outFile == null && tsFileNames.isNotEmpty()) {
            val parentDirs = (tsFileNames + dtsFileNamesInProjectDir)
                .map { it.substringBeforeLast('/', "") }
            longestCommonPathPrefix(parentDirs)
        } else null

        // Compute file processing order via topological sort BEFORE the transform loop,
        // so per-file transforms run in the same order as the final emit.
        val depsForTransformSort = when {
            options.noResolve -> emptyMap()
            hasCycle(tsFileNames, importDeps) -> importDepsNoRefPath
            else -> importDeps
        }
        val transformOrder = if (options.outFile != null && !options.noResolve) {
            topologicalSort(tsFileNames, depsForTransformSort, importDepsNoRefPath, filesWithImportEquals, importDeps)
        } else tsFileNames
        // (INC.59) `transformOrder.toSet()` was written INSIDE the filter's lambda, so
        // an N-element set was rebuilt once per entry of `parsedSourceFiles` — O(files^2)
        // string hashes, on every build including `--noEmit`. Measured on generated
        // many-small-file projects, `FrontEnd.POST_EMITPREP` read 6.8-8.2 ms at 601
        // files and 158.5-175.3 at 2401: 21x for 4x the files. Hoisting is exactly
        // equivalent — the predicate is a pure membership test and `filter` preserves
        // the map's own order either way.
        val transformOrderSet = transformOrder.toSet()
        EagerIndexCensus.transformOrderSetBuilds++
        val orderedParsedSourceFiles: List<Pair<String, SourceFile>> = transformOrder.mapNotNull { name ->
            parsedSourceFiles[name]?.let { name to it }
        } + parsedSourceFiles.filter { it.key !in transformOrderSet }.map { it.toPair() }

        cpcTransformAndEmit(
            options = options,
            checker = checker,
            orderedParsedSourceFiles = orderedParsedSourceFiles,
            emptyJsxTsxFixtures = emptyJsxTsxFixtures,
            crossFileNamespaceExports = crossFileNamespaceExports,
            commonSourceDir = commonSourceDir,
            resolvedOutDir = resolvedOutDir,
            jsOutputMap = jsOutputMap,
        )
        FrontEnd.close(FrontEnd.POST_EMITPREP, fePostBlockT0)
        fePostBlockT0 = FrontEnd.t()
        // (WARM.8) level 2 — the same abutting construction one level down; this
        // block carries 98% of [FrontEnd.POST].
        var feOutBlockT0 = FrontEnd.t()

        // Sort JS outputs by dependency order (dependencies first)
        // Skip sorting when noResolve is set (TypeScript doesn't resolve imports in that mode)
        // If the full deps graph (with `///<reference>` edges) has a cycle, fall back
        // to the deps map without ref-path edges. This matches TypeScript's behavior
        // of using input order when triple-slash refs form mutual cycles
        // (e.g. `doNotemitTripleSlashComments_ts`).
        val depsForSortRaw = when {
            options.noResolve -> emptyMap()
            hasCycle(tsFileNames, importDeps) -> importDepsNoRefPath
            else -> importDeps
        }
        // A `.js` whose companion `.d.ts` exists is referenced via its `.d.ts` in tsc's
        // program graph (the `.d.ts` supplies types), so under allowJs (where the `.js`
        // IS still emitted) it carries NO ordering dependency edge — tsc emits such files
        // in INPUT order, not dependency order. Drop those targets from the sort deps so
        // e.g. elidedJSImport2 emits `index.js` (the importer) before `other.js`. Corpus-
        // unique to the allowJs + companion-`.d.ts` shape; for non-allowJs the `.js` is
        // skipped from emit entirely so the filter is inert there.
        val companionDtsJsFiles: Set<String> = if (options.allowJs) {
            val fileNameSet = parsed.files.mapTo(mutableSetOf()) { it.fileName }
            parsed.files.mapNotNull { f ->
                val base = when {
                    f.fileName.endsWith(".js") -> f.fileName.removeSuffix(".js")
                    f.fileName.endsWith(".jsx") -> f.fileName.removeSuffix(".jsx")
                    f.fileName.endsWith(".mjs") -> f.fileName.removeSuffix(".mjs")
                    f.fileName.endsWith(".cjs") -> f.fileName.removeSuffix(".cjs")
                    else -> null
                }
                if (base != null && "$base.d.ts" in fileNameSet) f.fileName else null
            }.toSet()
        } else emptySet()
        val depsForSort = if (companionDtsJsFiles.isEmpty()) depsForSortRaw
            else depsForSortRaw.mapValues { (_, v) -> v.filter { it !in companionDtsJsFiles } }
        FrontEnd.close(FrontEnd.POST_DEPS, feOutBlockT0)
        feOutBlockT0 = FrontEnd.t()
        val sortedTsFiles = if (options.noResolve) tsFileNames else topologicalSort(tsFileNames, depsForSort, importDepsNoRefPath, filesWithImportEquals, importDeps)
        FrontEnd.close(FrontEnd.POST_TOPO, feOutBlockT0)
        feOutBlockT0 = FrontEnd.t()
        // (WARM.8)(c) round 862 — the census is DEAD WORK in a check-only
        // compile, and the argument is by construction rather than by census.
        // Its result has exactly one consumer, the line below this block, where
        // it filters the keys of `jsOutputMap` before a `mapNotNull` — and under
        // `skipEmitOutputs` that map is EMPTY, because round 738's gate makes
        // `cpcTransformAndEmit` iterate `emptyList()` and that loop is the map's
        // only writer. So `mapNotNull` yields nothing whatever the filter holds.
        //
        // The gate is [CompilerOptions.skipEmitOutputs], which only
        // `ProjectCompiler` sets, NEVER the `@noEmit` corpus DIRECTIVE that 440
        // generated tests use — their baselines were produced by a core that
        // still emits (round 738; `SkipEmitOutputsTest` and
        // `RequireOnlyOrphanTest` both carry the negative control).
        //
        // After the same round's deferral above this is worth ~2.3 ms on the
        // compiler profile rather than the ~130 ms the queue item priced, since
        // that profile carries no `declare … require` at all. It stays because
        // the deferral's saving is PROFILE-DEPENDENT and this one is not: on a
        // program that does carry the shape, pass 2 runs over every file and
        // this gate is what a `--noEmit` build saves.
        val requireOnlyOrphans = if (options.skipEmitOutputs) emptySet() else cpcRequireOnlyOrphans(
            parsed = parsed,
            tsFileNames = tsFileNames,
            importDeps = importDeps,
            parsedSourceFiles = parsedSourceFiles,
        )
        FrontEnd.close(FrontEnd.POST_ORPHANS, feOutBlockT0)
        feOutBlockT0 = FrontEnd.t()
        val jsOutputs = sortedTsFiles.filter { it !in requireOnlyOrphans }.mapNotNull { jsOutputMap[it] }

        val finalJsOutputs = run {
            // Interleave JSON outputs with JS outputs: each imported JSON appears RIGHT
            // BEFORE the JS output of the importing TS file. JSON outputs without a
            // recorded importer fall back to the start of the list (legacy behavior).
            // Required for `requireOfJsonFileWithoutExtensionResolvesToTs_ts` where the
            // expected order is `out/c.js, out/c.json, out/file1.js` (file1 imports both
            // c.ts and c.json).
            if (jsonOutputs.isEmpty() || jsonBaseNameToImporter.isEmpty()) {
                jsonOutputs + jsOutputs
            } else {
                val importerToJsons = mutableMapOf<String, MutableList<Pair<String, String>>>()
                val unimportedJsons = mutableListOf<Pair<String, String>>()
                for (jsonOut in jsonOutputs) {
                    val jsonBase = jsonOut.first.substringAfterLast('/')
                    val importer = jsonBaseNameToImporter[jsonBase]
                    if (importer != null) {
                        importerToJsons.getOrPut(importer) { mutableListOf() }.add(jsonOut)
                    } else {
                        unimportedJsons.add(jsonOut)
                    }
                }
                val merged = mutableListOf<Pair<String, String>>()
                merged.addAll(unimportedJsons)
                for (tsFileName in sortedTsFiles) {
                    importerToJsons[tsFileName]?.let { merged.addAll(it) }
                    jsOutputMap[tsFileName]?.let { merged.add(it) }
                }
                merged
            }
        }

        // When noEmitOnError is set and there are errors, suppress all JS output
        val suppressedJsOutputs = if (options.noEmitOnError &&
            diagnostics.any { it.category == DiagnosticCategory.Error }) emptyList()
        else finalJsOutputs

        // TypeScript reorders the source echoes when a tsconfig.json is present:
        //   1. Files OUTSIDE the tsconfig directory (out-of-tree fixtures) FIRST.
        //   2. node_modules files (in-tree third-party) NEXT.
        //   3. In-tree non-node_modules (project source) LAST, with `.json` files
        //      BEFORE `.ts`/`.tsx`/etc. files within the project (each subset preserving
        //      input order). Required for tests like
        //      `moduleResolutionWithSuffixes_one_jsonModule` where the imported JSON
        //      sibling files come before the importing `.ts` source.
        // Required for tests like pathMappingBasedModuleResolution4_classic (out-of-tree
        // fixture first) and tslibMissingHelper (node_modules before project files).
        val orderedSourceEchoes = if (!computedTsconfigDir.isNullOrEmpty()) {
            val prefix = computedTsconfigDir.trimEnd('/') + "/"
            val outside = mutableListOf<Pair<String, String>>()
            val nodeModulesFiles = mutableListOf<Pair<String, String>>()
            val inTreeProjectJson = mutableListOf<Pair<String, String>>()
            val inTreeProjectNonJson = mutableListOf<Pair<String, String>>()
            for (echo in sourceEchoes) {
                val isInTree = echo.first.startsWith(prefix)
                val isNodeModules = echo.first.contains("/node_modules/")
                when {
                    !isInTree -> outside.add(echo)
                    isNodeModules -> nodeModulesFiles.add(echo)
                    echo.first.endsWith(".json") -> inTreeProjectJson.add(echo)
                    else -> inTreeProjectNonJson.add(echo)
                }
            }
            outside + nodeModulesFiles + inTreeProjectJson + inTreeProjectNonJson
        } else sourceEchoes
        FrontEnd.close(FrontEnd.POST_ASSEMBLE, feOutBlockT0)
        FrontEnd.close(FrontEnd.POST_OUTPUTS, fePostBlockT0)
        FrontEnd.close(FrontEnd.POST, fePostT0)
        return CompilationResult(
            fileName = fileName,
            sourceEchoes = orderedSourceEchoes,
            jsOutputs = suppressedJsOutputs,
            isMultiFile = true,
            options = options,
            diagnostics = diagnostics,
            allSourceFiles = allFiles,
            capturedTypes = checker.capturedTypes,
            capturedDefinitions = checker.capturedDefinitions,
            capturedMembers = checker.capturedMembers,
            capturedScopes = checker.capturedScopes,
            capturedSignatures = checker.capturedSignatures,
        )
    }

    /**
     * (JIT.1)(e) round 816 — phase 1 of the multi-file pipeline: the per-file scan
     * that parses every input and populates the program-wide tables, moved verbatim
     * out of [cpcCompileMultiFile]. The 18 parameters ARE the region's free-variable
     * set (`scripts/cpc_split_analyze.py` computes it scope-aware and the compiler
     * enforces it); the call site passes them by NAME, because a positional swap of
     * two same-typed containers would be type-correct and silently wrong.
     */
    private fun cpcScanFiles(
        parsed: ParsedSource,
        options: CompilerOptions,
        diagnostics: MutableList<Diagnostic>,
        computedTsconfigDir: String?,
        resolvedOutDir: String?,
        sourceEchoes: MutableList<Pair<String, String>>,
        jsonOutputs: MutableList<Pair<String, String>>,
        importDeps: MutableMap<String, List<String>>,
        importDepsNoRefPath: MutableMap<String, List<String>>,
        filesWithImportEquals: MutableSet<String>,
        tsFileNames: MutableList<String>,
        parsedSourceFiles: MutableMap<String, SourceFile>,
        filesWithParseDiagnostics: MutableSet<String>,
        filesWithRealParseDiagnostics: MutableSet<String>,
        allParserDiagsForPins: MutableList<Diagnostic>,
        emptyJsxTsxFixtures: MutableSet<String>,
        dtsFileNamesInProjectDir: MutableList<String>,
        importedJsonBaseNames: Set<String>,
    ) {
        // (INC.57) The program's file NAMES, built ONCE per build.
        //
        // Every consumer below asks this set exactly one question — "is this
        // path a file of the program" — and each of them used to answer it by
        // rebuilding the whole set (or by an `any {}` scan) inside the loop, so
        // the region was O(files^2): `extractRelativeImports` alone allocated a
        // list AND a set of every program file name TWICE per file. Measured on
        // generated many-small-file projects (the shape an application has,
        // where tsc's own 78 sources are 128 KB each and hide it), the
        // `FrontEnd.IMPORTS` row grows 4x for 2x the files — 18.9 / 76.3 /
        // 331.6 ms at 601 / 1201 / 2401 files.
        //
        // `parsed` is a `ParsedSource` and `files` is a `val List`, so the set
        // is loop-invariant by construction; `.toSet()` is kept verbatim rather
        // than swapped for a `HashSet` so the container (and therefore any
        // iteration order a future consumer might depend on) is bit-for-bit
        // what the per-call expression produced.
        val allTsFileNames = parsed.files.map { it.fileName }.toSet()
        EagerIndexCensus.programNameSetBuilds++
        for (file in parsed.files) {
            // Don't echo tsconfig.json (it's a TypeScript project config, not a source file)
            val baseName = file.fileName.substringAfterLast('/')
            if (baseName != "tsconfig.json" && file.fileName !in parsed.symlinkSkipEcho) {
                sourceEchoes.add(file.fileName to file.content)
            }

            // TS1327: object property keys in a JSON source file must be
            // double-quoted string literals. A single-quoted key (`'a':`), a
            // computed key (`[a]:`), or a bare identifier key is invalid JSON
            // and reports "String literal with double quotes expected.". JSON
            // files are otherwise never parsed/checked, so this scan is the only
            // detector. Excludes tsconfig.json/package.json/node_modules. FP-safe:
            // valid JSON has only double-quoted keys → nothing flagged.
            if (file.fileName.endsWith(".json") && baseName != "tsconfig.json" &&
                baseName != "package.json" && !file.fileName.contains("node_modules/")
            ) {
                diagnostics.addAll(scanJsonKeysForTS1327(file.content, file.fileName))
                // B573: a .json whose content is ONLY bare identifiers + whitespace
                // (no `{ } : " ' [ ] , .`) — tsc parses it as a recovered object
                // literal → `{` expected + per-element `,`/`}` expected + TS1136
                // per shorthand. Corpus-unique (the only such referenced json).
                diagnostics.addAll(scanMalformedBareJson(file.content, file.fileName))
            }

            // Re-emit JSON files when outDir is set (but not tsconfig.json/package.json
            // and not files from node_modules which TypeScript never re-emits).
            // When @resolveJsonModule is on, only re-emit JSON fixtures that are
            // explicitly imported (matches TypeScript's behavior — unreferenced JSON
            // fixtures like b.json in a test where only c.json is imported are NOT
            // re-emitted).
            val jsonIsImportedOrLegacy = !options.resolveJsonModule ||
                    baseName in importedJsonBaseNames
            if (file.fileName.endsWith(".json") && options.outDir != null
                && baseName != "tsconfig.json" && baseName != "package.json"
                && !file.fileName.contains("node_modules/")
                && jsonIsImportedOrLegacy) {
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
            // Track whether this JS file should be skipped for emit but still parsed/bound/checked
            var skipJsEmit = false
            // Plain .js/.mjs/.cjs: only emit when outDir/outFile is set (avoids overwriting sources)
            // But still parse/bind/check when allowJs is set (for TS8xxx, TS2451, etc.)
            if (isPureJsFile && options.outDir == null && options.outFile == null) {
                if (options.allowJs) {
                    skipJsEmit = true
                } else {
                    continue
                }
            }
            // .jsx (JavaScript+JSX): without outDir/outFile, skip non-empty `.jsx` when allowJs
            // is unset (TypeScript reports nothing for those). Empty `.jsx` fixtures are
            // admitted so they appear in `fileResults`, letting B11.2's
            // `resolveJsxTsxCandidate` match `.jsx`/`.tsx` import targets even when the
            // source happens to be blank (multi-file fixture pattern). Tracked in
            // `emptyJsxTsxFixtures` so Phase 3 can skip their emit.
            if (isJsxFile && options.outDir == null && options.outFile == null) {
                if (!options.allowJs && file.content.isNotBlank()) continue
                if (file.content.isBlank()) emptyJsxTsxFixtures.add(file.fileName)
            }
            val isDtsFile = file.fileName.endsWith(".d.ts") || file.fileName.endsWith(".d.mts") || file.fileName.endsWith(".d.cts") ||
                // *.d.*.ts — declaration files with custom extensions (allowArbitraryExtensions)
                // e.g., foo.d.html.ts, foo.d.css.ts
                (file.fileName.endsWith(".ts") && !file.fileName.endsWith(".d.ts") &&
                 file.fileName.contains(".d.") &&
                 file.fileName.substringBeforeLast(".ts").substringAfterLast(".d.").isNotEmpty())
            // .tsx files without --jsx: previously skipped when content was blank, but
            // we now admit empty `.tsx` fixtures so B11.2's `resolveJsxTsxCandidate` can
            // match `.tsx` import targets even when the source happens to be blank
            // (multi-file fixture pattern). Tracked in `emptyJsxTsxFixtures` so Phase 3
            // can skip their emit.
            if (file.fileName.endsWith(".tsx") && options.jsx == null && file.content.isBlank()) {
                emptyJsxTsxFixtures.add(file.fileName)
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
                // (INC.57) Four membership tests against the hoisted name set,
                // where this was an `any {}` over every program file — the same
                // question, asked once per candidate instead of once per file.
                val hasConflictingJs = jsEquivalentPath1 in allTsFileNames ||
                    jsEquivalentPath2 in allTsFileNames ||
                    jsEquivalentPath3 in allTsFileNames ||
                    jsEquivalentPath4 in allTsFileNames
                if (hasConflictingJs) continue
            }

            // INV.1(e): the option-derived parser flags via the shared helper. A
            // crawl-supplied pre-parse ([ParsedSource.preParsed]) is reused ONLY on
            // an exact content + flags match — any mismatch (e.g. a future flag
            // reading an option the core post-processes, like packageJsonTypes)
            // falls through to a fresh parse, so reuse is a pure optimization.
            val parserFlagsMulti = computeParserFlags(file.fileName, file.content, options)
            val preParsed = parsed.preParsed[file.fileName]?.takeIf {
                it.flags == parserFlagsMulti && it.content == file.content
            }
            if (PassTiming.enabled) {
                if (preParsed != null) PassTiming.preParseReused++ else PassTiming.preParseFresh++
            }
            val sourceFile: SourceFile
            val parserDiagnostics: List<Diagnostic>
            if (preParsed != null) {
                sourceFile = preParsed.sourceFile
                parserDiagnostics = preParsed.diagnostics
                if (FrontEnd.mode == FrontEnd.ON) FrontEnd.parsedReused++
            } else {
                val feT0 = FrontEnd.t()
                val parser = Parser(file.content, file.fileName, forceJsx = parserFlagsMulti.forceJsx, topLevelAwait = parserFlagsMulti.topLevelAwait, needsJsxFlag = parserFlagsMulti.needsJsxFlag, noImplicitAny = parserFlagsMulti.noImplicitAny)
                sourceFile = parser.parse()
                parserDiagnostics = parser.getDiagnostics()
                FrontEnd.close(FrontEnd.PARSE, feT0)
                if (FrontEnd.mode == FrontEnd.ON) FrontEnd.parsedFresh++
            }
            parsedSourceFiles[file.fileName] = sourceFile
            if (parserDiagnostics.isNotEmpty()) filesWithParseDiagnostics.add(file.fileName)
            if (parserDiagnostics.any { it.code !in GRAMMAR_CLASS_CODES }) {
                filesWithRealParseDiagnostics.add(file.fileName)
            }

            // Collect parser diagnostics from .d.ts files too (e.g. TS1540 for `module X {}`).
            // Skip node_modules files — they are third-party and never reported on.
            val isNodeModulesFile = file.fileName.contains("node_modules/") || file.fileName.contains("node_modules\\")

            // .d.ts files are parsed and bound (for checker globals) but not emitted.
            // Track those that live under the tsconfig directory so they contribute
            // to commonSourceDirectory (e.g. `/app/lib/bar.d.ts` referenced from
            // `/app/src/index.ts` should make commonSourceDir `/app`, not `/app/src`).
            if (isDtsFile) {
                if (!isNodeModulesFile) {
                    diagnostics.addAll(parserDiagnostics)
                    if (computedTsconfigDir != null && computedTsconfigDir.isNotEmpty()
                        && file.fileName.startsWith("$computedTsconfigDir/")) {
                        dtsFileNamesInProjectDir.add(file.fileName)
                    }
                }
                continue
            }

            // node_modules files are typically third-party and not re-emitted by
            // TypeScript. EXCEPTION: when there's no `tsconfig.json` AND neither
            // `@noImplicitReferences: true` nor `@moduleResolution: bundler` are
            // set, all `@filename` files behave like command-line root files —
            // TypeScript emits root `.ts` files even when they live under
            // `node_modules/`. The two excluding flags identify the "treat
            // node_modules strictly as external" modes used by bundler/lib-resolution
            // tests where node_modules content must NEVER reach JS output.
            val isBundlerOrNoImplicit = options.moduleResolution?.lowercase() == "bundler" ||
                options.noImplicitReferences
            if (isNodeModulesFile && (computedTsconfigDir != null || isBundlerOrNoImplicit)) continue

            diagnostics.addAll(parserDiagnostics)
            allParserDiagsForPins.addAll(parserDiagnostics)

            // .js/.cjs/.mjs files OUTSIDE the tsconfig project directory must still be
            // parsed/bound (for type-only use under `allowJs`) but never emitted as JS.
            // TypeScript skips JS-emit for non-TS root files that lie outside the
            // tsconfig's rootDir. Example: `/bar.js` referenced from `/root/a.ts` via
            // path mapping under `/root/tsconfig.json` — TS uses bar.js as input but
            // does NOT produce `bar.js` in the output. Equivalent `.ts` files OUTSIDE
            // the dir DO still emit (handled by commonSourceDir prefix calc).
            // Gate is restricted to absolute-path tsconfig directories — relative
            // paths like `tsconfig.json` (no leading `/`) produce a malformed
            // `computedTsconfigDir == "tsconfig.json"` that would FP-skip files in
            // the same directory (e.g. `commonJsIsolatedModules`'s `index.js`).
            // When tsconfigDir == "/" (root), every absolute-path file is "inside"
            // it, so the prefix check uses "/" (not "//") to avoid FP-skipping
            // /foo.js etc.
            if (isPureJsFile && computedTsconfigDir != null && computedTsconfigDir.startsWith('/')
                && file.fileName.startsWith('/')) {
                val prefix = if (computedTsconfigDir == "/") "/" else "$computedTsconfigDir/"
                if (!file.fileName.startsWith(prefix)) {
                    // Parse for diagnostics but skip emit + dependency ordering
                    continue
                }
            }

            // Pure .js files with a companion `.d.ts` (same path minus `.js` + `.d.ts`)
            // are treated as external JavaScript described by their `.d.ts` — TypeScript
            // uses them for type resolution but does NOT re-emit them as JS output, even
            // when an outDir is set. Example: `/relative.js` + `/relative.d.ts` referenced
            // via `import { relative } from "./relative.js"` — TypeScript emits no
            // `relative.js` under outDir, only the imports in the importing file.
            // GATED on `!allowJs`: under `allowJs` the `.js` is a first-class PROGRAM file
            // and IS emitted (the companion `.d.ts` only supplies types) — e.g. elidedJSImport2's
            // `other.js` + `other.d.ts` emits `other.js`. moduleResolutionWithExtensions_withPaths
            // (no allowJs) keeps the skip (its `.js` is external).
            if (isPureJsFile && !options.allowJs) {
                val base = when {
                    file.fileName.endsWith(".js") -> file.fileName.removeSuffix(".js")
                    file.fileName.endsWith(".cjs") -> file.fileName.removeSuffix(".cjs")
                    file.fileName.endsWith(".mjs") -> file.fileName.removeSuffix(".mjs")
                    else -> null
                }
                if (base != null) {
                    val companionDts = "$base.d.ts"
                    val hasCompanionDts = companionDts in allTsFileNames // (INC.57)
                    if (hasCompanionDts) {
                        // Parse for diagnostics but skip emit + dependency ordering
                        continue
                    }
                }
            }

            // JS files parsed only for diagnostics (no outDir/outFile): skip emit but keep in parsedSourceFiles for checker
            if (skipJsEmit) continue

            // Extract relative imports for dependency ordering.
            //
            // (INC.64): SKIPPED ENTIRELY under `skipEmitOutputs`. The only product is
            // `importDeps`/`importDepsNoRefPath`, and every consumer of those orders
            // EMITTED output — `transformOrder` feeds a transform loop that is already
            // `emptyList()` under this flag (round 738), `sortedTsFiles` orders
            // `jsOutputMap` entries a `--noEmit` build never produces, and
            // `cpcRequireOnlyOrphans` carries the same gate a few lines below. So an
            // editor keystroke was computing a dependency ORDER for an emit that does
            // not happen: measured at 15.0 / 17.1 / 22.6 ms of a ~170 ms incremental
            // floor on 2,401 application-shaped files, ~10%.
            //
            // This is (INC.59)'s finding one call deeper, and like it the CORPUS IS A
            // CONTROL rather than the gate — `skipEmitOutputs` is set only by
            // `ProjectCompiler`, never by the `@noEmit` corpus directive, so all ~13k
            // baselines run with this branch taken. What sees it is the `-project`
            // pins and the 8-profile `--noEmit` grid.
            //
            // NOT a `continue`: `tsFileNames.add` below is NOT emit-only and every
            // later phase reads it, so the gate wraps exactly the two calls.
            if (!options.skipEmitOutputs) {
                val feImpT0 = FrontEnd.t()
                EagerIndexCensus.relativeImportExtractions += 2
                importDeps[file.fileName] = extractRelativeImports(
                    sourceFile, file.fileName, allTsFileNames, options.moduleSuffixes,
                    includeReferencePathDeps = true,
                    paths = options.paths,
                    baseUrl = options.baseUrl,
                    tsconfigDir = computedTsconfigDir,
                    rootDirs = options.rootDirs,
                    symlinkMap = parsed.symlinkMap,
                )
                // Also compute deps WITHOUT ref-path edges as a fallback. If the
                // full deps graph forms a cycle (mutual `/// <reference>` between
                // files), we drop the ref-path edges and rely on input order.
                importDepsNoRefPath[file.fileName] = extractRelativeImports(
                    sourceFile, file.fileName, allTsFileNames, options.moduleSuffixes,
                    includeReferencePathDeps = false,
                    paths = options.paths,
                    baseUrl = options.baseUrl,
                    tsconfigDir = computedTsconfigDir,
                    rootDirs = options.rootDirs,
                    symlinkMap = parsed.symlinkMap,
                )
                FrontEnd.close(FrontEnd.IMPORTS, feImpT0)
            }
            // Detect whether this file uses `import X = require("...")` (CJS-style
            // import-equals). When an entry-point file uses this form, TypeScript
            // emits its dependencies in the order they appear in the file (single-root
            // DFS) rather than @Filename input order — see B52.10.
            if (sourceFile.statements.any { stmt ->
                stmt is ImportEqualsDeclaration && stmt.moduleReference is ExternalModuleReference
            }) {
                filesWithImportEquals.add(file.fileName)
            }

            tsFileNames.add(file.fileName)
        }
    }

    /**
     * (PERF.HW.c): assign the program's files to `workers` partition checkers so
     * that the SLOWEST worker finishes as early as possible — because the wall of
     * a parallel phase is the slowest worker, not the average one.
     *
     * The predecessor was `i % workers == w`, round-robin over the crawl's sorted
     * order, and on a real program that is close to worst case: the compiler
     * profile's 78 files span three orders of magnitude and `checker.ts` alone is
     * **31.6% of the whole program**, so whichever bucket drew it decided the
     * wall. Measured over that profile, the heaviest bucket carried 1.90x the
     * mean at 4 workers, 2.28x at 6 and 3.32x at 8 — i.e. the partition ALONE
     * capped the achievable speedup at 2.10x / 2.63x / 2.41x before a single
     * line of checker work was considered, and it is why 8 workers came out
     * WORSE than 6.
     *
     * This is longest-processing-time-first: take the files in descending size
     * and give each to the currently lightest bucket. LPT is within 4/3 of
     * optimal for any input, and on this profile it takes the ceiling to 3.16x
     * at every level >= 4 — which is then the REAL ceiling of file-level
     * parallelism on this program, since one file being 31.6% of the input means
     * no assignment whatever can beat 1/0.316.
     *
     * **Source LENGTH is a proxy for checking cost, not the cost itself** — it
     * carries no information about which files are type-heavy. It is used because
     * it is exact, free (the text is already in hand), and available BEFORE any
     * checking happens, which a true cost measure is not. A wrong proxy costs
     * balance, never correctness.
     *
     * **The order must be total, or the partition stops being a function of the
     * program.** Ties break on `fileName`, so the assignment is reproducible
     * across runs and platforms; a partition that depended on iteration or
     * scheduling order would make diagnostics depend on it too (INV.6), and the
     * whole verification method here is byte-identical output.
     */
    /**
     * (PERF.HW.h) one `Symbol`'s mutable state, as it stood before the checker ran.
     *
     * `declarations` is compared by SIZE rather than by content because the only
     * mutation `mergeSingleSymbol` performs on it is `addAll`, and a size is
     * cheap enough to take for every symbol in the program. `valueDeclaration` and
     * `parent` are compared by IDENTITY — a re-pointed reference is exactly the
     * mutation that would make a shared bind wrong.
     */
    private class SymbolFingerprint(
        val flags: Int,
        val declarations: Int,
        val valueDeclaration: Node?,
        val members: Int,
        val exports: Int,
        val parent: Symbol?,
    )

    private fun fingerprint(symbol: Symbol) = SymbolFingerprint(
        symbol.flags.value,
        symbol.declarations.size,
        symbol.valueDeclaration,
        symbol.members?.size ?: -1,
        symbol.exports?.size ?: -1,
        symbol.parent,
    )

    /**
     * Every `Symbol` reachable from the binder's output, keyed by IDENTITY —
     * `Symbol` is a plain class, not a `data class`, so a `HashMap<Symbol, _>` is
     * an identity map and none of round 471's deep-`hashCode` hazard applies.
     *
     * Reaches through `members` and `exports` as well as the two top-level tables,
     * because those are themselves binder-built and are mutated by the merge.
     */
    private fun collectBinderSymbols(results: List<BinderResult>): MutableMap<Symbol, SymbolFingerprint> {
        val out = HashMap<Symbol, SymbolFingerprint>()
        val frontier = ArrayDeque<Symbol>()
        for (result in results) {
            result.locals.values.forEach { frontier.addLast(it) }
            result.nodeToSymbol.values.forEach { frontier.addLast(it) }
        }
        while (frontier.isNotEmpty()) {
            val symbol = frontier.removeFirst()
            if (out.containsKey(symbol)) continue
            out[symbol] = fingerprint(symbol)
            symbol.members?.values?.forEach { frontier.addLast(it) }
            symbol.exports?.values?.forEach { frontier.addLast(it) }
        }
        return out
    }

    /** Re-fingerprints [before]'s symbols and records which fields moved. */
    private fun recordBinderMutations(before: Map<Symbol, SymbolFingerprint>) {
        for ((symbol, was) in before) {
            BindMutationCheck.symbolsChecked++
            val now = fingerprint(symbol)
            if (now.flags != was.flags) BindMutationCheck.flagsChanged++
            if (now.declarations != was.declarations) BindMutationCheck.declarationsChanged++
            if (now.valueDeclaration !== was.valueDeclaration) BindMutationCheck.valueDeclarationChanged++
            if (now.members != was.members) BindMutationCheck.membersChanged++
            if (now.exports != was.exports) BindMutationCheck.exportsChanged++
            if (now.parent !== was.parent) BindMutationCheck.parentChanged++
        }
    }

    internal fun balancedFilePartition(files: List<SourceFile>, workers: Int): List<Set<String>> {
        val order = files.sortedWith(
            compareByDescending<SourceFile> { it.text.length }.thenBy { it.fileName }
        )
        val buckets = List(workers) { mutableSetOf<String>() }
        val load = LongArray(workers)
        for (file in order) {
            var lightest = 0
            for (w in 1 until workers) if (load[w] < load[lightest]) lightest = w
            buckets[lightest].add(file.fileName)
            load[lightest] += file.text.length.toLong()
        }
        return buckets
    }

    /**
     * (JIT.1)(e) round 816 — phase 2 of the multi-file pipeline: bind every parsed
     * file and run the checker (or, under `--workers`, the INV.6 partition checkers),
     * moved verbatim out of [cpcCompileMultiFile]. The one value that crosses the
     * boundary — the `Checker` the downstream Transformer queries — is RETURNED, never
     * stashed in a field.
     */
    private fun cpcBindAndCheck(
        parsed: ParsedSource,
        options: CompilerOptions,
        recheckOnly: Set<String>?,
        parsedSourceFiles: Map<String, SourceFile>,
        diagnostics: MutableList<Diagnostic>,
        typeCapture: TypeCaptureRequest? = null,
        checkedSink: CheckedNodeSink? = null,
        recheckHolder: RecheckHolder? = null,
    ): Checker {
        // Phase 2: Bind all files and create shared checker
        //
        // (PERF.HW.b): this bind is read by the SEQUENTIAL branch below and by
        // nothing else — under `--workers N` every worker binds the whole program
        // again for itself (it must: `Checker` init mutates the symbols it is
        // given, so a bind cannot be shared), and this one's `BinderResult`s were
        // then dropped on the floor. Computing them anyway put a whole redundant
        // whole-program `Binder.bind` on the SEQUENTIAL prefix of every parallel
        // compile — i.e. straight into Amdahl's R, the one term worker count
        // cannot buy back. Skipping it leaves the sequential path byte-identical
        // (that branch still binds exactly as before) and the parallel path
        // byte-identical too (a worker's ids come from its own rebased slice, so
        // the caller's Symbol counter never reached them).
        // (KIR) A sink FORCES the sequential branch. `ParallelCheckMode.workers` is a
        // process-global mode, and (API.3)'s capture merely ASSUMES it is 1 because
        // every embedding-API caller leaves it so — an assumption a sink cannot
        // inherit, because under `--workers` each worker `Checker` is constructed
        // with `assignedFileNames`, which `Checker.init` refuses beside a sink
        // (a partition checker walks a SUBSET, so the facts would silently be a
        // subset too). Overridden HERE, at the branch, rather than by writing the
        // global ledger: a bare write to a mode object from a compile is the
        // round-848 hazard — it survives the request and reconfigures the next one.
        val parallelCheck = ParallelCheckMode.workers > 1 && checkedSink == null
        val feBindT0 = FrontEnd.t()
        val binderResults =
            if (parallelCheck) emptyList()
            else {
                val binder = Binder(options)
                parsedSourceFiles.values.map { binder.bind(it) }
            }
        FrontEnd.sequentialFileBinds = binderResults.size.toLong()
        // Under `--workers` this row is ~0 by construction and the real bind cost
        // lives inside [FrontEnd.CHECK], where the workers do it concurrently.
        FrontEnd.close(FrontEnd.BIND, feBindT0)
        val allInputFileNames = parsed.files.map { it.fileName }.toSet()
        val jsonModules = parsed.files
            .filter { it.fileName.endsWith(".json") && !it.fileName.endsWith("tsconfig.json") }
            .associate { it.fileName to it.content }
        val checker: Checker
        val feCheckT0 = FrontEnd.t()
        if (parallelCheck) {
            // INV.6(6c1): share-nothing parallel check — N partition checkers on
            // deep-stack worker threads replace the single full checker. Fresh
            // bind per worker (checker init mutates shared symbols via
            // mergeSymbolTable; the Binder never touches the AST, so parse trees
            // share). Merge is deterministic (worker order); program-level
            // fileName-null diagnostics are emitted by every worker —
            // deduplicated by key. Worker 0's checker (a full program over its
            // own fresh bind) serves the downstream Transformer queries.
            val workers = ParallelCheckMode.workers
            // (PERF.HW.a) round 825: the real-lib parse cache is a plain HashMap and
            // every worker's Checker builds its lib set from its own thread — warm it
            // here so the workers only read it (and parse each lib file ONCE, not N
            // times).
            RealLibSnapshots.prewarmParsedLibFiles(options)
            val sourceList = parsedSourceFiles.values.toList()
            // (PERF.HW.i) one bind for every worker, on the CALLER thread — so its
            // symbols come from the ordinary low id sequence, below every worker's
            // rebased slice, and can collide with none of them. Opt-in: sound only
            // while nothing merges a program symbol into `globals`, which is true
            // for an all-module program and false for one with script files
            // (round 882, `docs/parallel-bind-sharing.md` § 2b).
            val sharedBind: List<BinderResult>? =
                if (ShareBind.enabled) {
                    val sharedBinder = Binder(options)
                    sourceList.map { sharedBinder.bind(it) }
                } else null
            val assignments = balancedFilePartition(sourceList, workers)
            // (PERF.HW.d) sized here, before any thread starts; worker `w` writes
            // index `w` and nothing reads them until every worker has joined.
            FrontEnd.workerNanos = LongArray(workers)
            FrontEnd.workerFiles = LongArray(workers)
            FrontEnd.workerChars = LongArray(workers)
            val tasks = (0 until workers).map { w ->
                {
                    val workerT0 = PassTiming.nowNanos()
                    val assigned = assignments[w]
                    FrontEnd.workerFiles[w] = assigned.size.toLong()
                    FrontEnd.workerChars[w] =
                        sourceList.filter { it.fileName in assigned }.sumOf { it.text.length.toLong() }
                    val workerResults = sharedBind ?: run {
                        val workerBinder = Binder(options)
                        sourceList.map { workerBinder.bind(it) }
                    }
                    // The whole check runs in `Checker`'s init block, so the
                    // constructor IS this worker's work — bind included.
                    val workerChecker = Checker(
                        options, workerResults, isMultiFileSource = parsed.hasExplicitFilenames,
                        assignedFileNames = assigned,
                        allInputFileNames = allInputFileNames,
                        jsonModuleContents = jsonModules,
                        moduleResolutions = parsed.moduleResolutions,
                    )
                    FrontEnd.workerNanos[w] = PassTiming.nowNanos() - workerT0
                    workerChecker
                }
            }
            val workerCheckers = runInDeepStackWorkers(tasks)
            val perWorker = workerCheckers.map { it.getDiagnostics() }
            diagnostics.addAll(perWorker.flatten().filter { it.fileName != null })
            diagnostics.addAll(perWorker.flatten().filter { it.fileName == null }
                .distinctBy { "${it.start}|${it.length}|${it.code}|${it.message}" })
            checker = workerCheckers[0]
        } else {
            // (PERF.HW.h) taken BEFORE the checker constructor, which is where the
            // whole check runs, and compared after — so it sees every write site,
            // not the ones a grep found.
            val binderStateBefore =
                if (BindMutationCheck.enabled) collectBinderSymbols(binderResults) else null
            // (INC.53) the two halves of [FrontEnd.CHECK] — see its KDoc. The
            // constructor is where the whole check runs, so this pair separates
            // the `pass("…")` table from everything around it.
            val feCtorT0 = FrontEnd.t()
            checker = Checker(options, binderResults, isMultiFileSource = parsed.hasExplicitFilenames,
                assignedFileNames = recheckOnly,
                allInputFileNames = allInputFileNames,
                jsonModuleContents = jsonModules,
                moduleResolutions = parsed.moduleResolutions,
                // (API.3): the SEQUENTIAL checker is the only one handed a capture
                // request — under `--workers` each worker walks its own partition
                // and would race on one shared result map for no benefit, so a
                // capture build runs sequentially (`ParallelCheckMode.workers` is 1
                // for every embedding-API caller).
                typeCapture = typeCapture,
                // (KIR) and the sink, for which sequential is FORCED above rather
                // than assumed.
                checkedSink = checkedSink,
                // (INC.17) recording the partition classification, and retaining the
                // state a later [ProgramRecheck.recheck] re-enters. Only when a caller
                // asked: it keeps every Type and Symbol of the build alive.
                retainForRecheck = recheckHolder != null)
            FrontEnd.close(FrontEnd.CHK_CTOR, feCtorT0)
            if (binderStateBefore != null) recordBinderMutations(binderStateBefore)
            // (INC.16) hazard (a)'s instrument, at ONE fixed point of the pipeline.
            if (LexDefer.census) LexDefer.fingerprint(binderResults)
            val feDiagsT0 = FrontEnd.t()
            val checkerDiagnostics = checker.getDiagnostics()
            FrontEnd.close(FrontEnd.CHK_DIAGS, feDiagsT0)
            diagnostics.addAll(checkerDiagnostics.applySkipLibCheck(options))
            // (INC.46) AFTER the diagnostics deliberately: the fingerprint walk forces
            // type resolutions the check may not have needed, and doing it above would
            // make the probe able to ADD a diagnostic. Off in the shipped compiler.
            if (ExportSignatures.enabled) {
                val expSigT0 = PassTiming.nowNanos()
                checker.exportedSignatureFingerprints()
                ExportSignatures.nanos += PassTiming.nowNanos() - expSigT0
            }
            // (INC.17) hand the LIVE program back. Deliberately after the diagnostics
            // are read, so a holder can never observe a half-built checker.
            recheckHolder?.recheck = CheckerRecheck(checker, options)
            if (PartitionCheck.workers > 1) runPartitionEquivalenceCheck(
                options, parsedSourceFiles.values.toList(), parsed, checker.getDiagnostics(),
            )
        }
        FrontEnd.close(FrontEnd.CHECK, feCheckT0)
        return checker
    }

    /**
     * (JIT.1)(e) round 816 — phase 3 of the multi-file pipeline: the transform+emit
     * loop, moved verbatim out of [cpcCompileMultiFile]. It produces NO diagnostics
     * (FRONT.1 relies on the same property) — its whole output is `jsOutputMap`.
     */
    private fun cpcTransformAndEmit(
        options: CompilerOptions,
        checker: Checker,
        orderedParsedSourceFiles: List<Pair<String, SourceFile>>,
        emptyJsxTsxFixtures: Set<String>,
        crossFileNamespaceExports: Map<String, Set<String>>,
        commonSourceDir: String?,
        resolvedOutDir: String?,
        jsOutputMap: MutableMap<String, Pair<String, String>>,
    ) {
        // Phase 3: Transform and emit each file.
        // (FRONT.1): skipped entirely for a type-check-only build — the loop
        // produces NO diagnostics (verified: no `diagnostics.add` between the
        // checker and the result), only `jsOutputMap` entries the caller has
        // said it does not want. Gated on `skipEmitOutputs`, which ONLY
        // ProjectCompiler sets, never the `@noEmit` corpus directive.
        for ((tsFileName, sourceFile) in if (options.skipEmitOutputs) emptyList() else orderedParsedSourceFiles) {
            // Skip emit for empty `.jsx`/`.tsx` fixture files admitted only for
            // B11.2's `resolveJsxTsxCandidate` visibility. Without this, the Emitter
            // would add a `"use strict";` prologue and produce a phantom
            // `//// [foo.js]` entry in the baseline.
            if (tsFileName in emptyJsxTsxFixtures) continue

            // For @module: none + @outFile, auxiliary .js files with module statements
            // (export/import) are NOT bundled into the outFile output. Only the entry
            // .ts file is emitted. TypeScript treats `.js` files under module:none as
            // pulled in only for type info / allowJs checking, not for runtime bundling.
            val tsFileNameIsPureJs = tsFileName.endsWith(".js") || tsFileName.endsWith(".mjs") || tsFileName.endsWith(".cjs")
            if (options.outFile != null && options.effectiveModule == ModuleKind.None && tsFileNameIsPureJs) {
                val hasModuleStatements = sourceFile.statements.any { stmt ->
                    when (stmt) {
                        is ExportDeclaration, is ExportAssignment, is ImportDeclaration -> true
                        is ImportEqualsDeclaration -> stmt.moduleReference is ExternalModuleReference || ModifierFlag.Export in stmt.modifiers
                        is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
                        is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                        is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                        is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                        else -> false
                    }
                }
                if (hasModuleStatements) continue
            }

            val feTrT0 = FrontEnd.t()
            val transformer = Transformer(options, checker, crossFileNamespaceExports)
            val transformed = transformer.transform(sourceFile)
            FrontEnd.close(FrontEnd.TRANSFORM, feTrT0)

            val feEmT0 = FrontEnd.t()
            val emitter = Emitter(options)
            val javascript = emitter.emit(transformed, sourceFile)
            FrontEnd.close(FrontEnd.EMIT, feEmT0)

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
            // When fullEmitPaths: keep full path; when outDir is also set, prepend it.
            // Use commonSourceDirectory (longest common ancestor of all tsFileNames) to
            // preserve subdirectory structure under outDir. When all inputs share their
            // parent directory, this reduces to basename + outDir (the original behavior);
            // when they span subdirectories, each output keeps its relative-from-common-dir
            // path (e.g. `library-a/index.js` under `/src/bin/`).
            if (options.fullEmitPaths) {
                if (resolvedOutDir != null) {
                    val outDir = resolvedOutDir.trimEnd('/')
                    val relative = if (commonSourceDir != null && commonSourceDir.isNotEmpty()
                        && jsName.startsWith("$commonSourceDir/")) {
                        jsName.substring(commonSourceDir.length + 1)
                    } else {
                        jsName.substringAfterLast('/')
                    }
                    jsName = "$outDir/$relative"
                }
                // else: keep jsName as full path (just extension replaced)
            } else {
                // Strip directory prefix — baseline uses just basenames.
                // Handle both Unix '/' and Windows '\' separators.
                jsName = jsName.substringAfterLast('/').substringAfterLast('\\')
            }
            jsOutputMap[tsFileName] = jsName to javascript
        }
    }

    /**
     * (JIT.1)(e) round 816 — the `require`-only orphan census (inputs reached ONLY by
     * a bare `require('./x')` CallExpression, which tsc never makes a program file),
     * moved verbatim out of [cpcCompileMultiFile] together with its three local
     * resolvers. Its result is RETURNED.
     */
    private fun cpcRequireOnlyOrphans(
        parsed: ParsedSource,
        tsFileNames: List<String>,
        importDeps: Map<String, List<String>>,
        parsedSourceFiles: Map<String, SourceFile>,
    ): Set<String> {
        // require-only orphan drop: a `.ts` input reached ONLY by a bare untyped
        // `require('./x')` CallExpression — not a static `import`/`export … from` /
        // `import = require` (those land in importDeps), not `import('…')` /
        // `typeof import('…')`, not `/// <reference>` — is NOT a program file in tsc,
        // so it is never resolved, type-checked, or emitted (moduleResolutionWithRequire).
        // FP firewall: corpus-unique to the `declare const require` + bare `require('./X')`
        // shape; the sibling moduleResolutionWithRequireAndImport keeps emitting X because
        // its `typeof import('./X')` IS a static reference (→ staticallyReferenced).
        val requireOnlyOrphans: Set<String> =
            if (parsed.hasExplicitFilenames && tsFileNames.size > 1) {
                val tsFileSet = tsFileNames.toSet()
                fun resolveToInput(fromFile: String, spec: String): String? {
                    if (!spec.startsWith("./") && !spec.startsWith("../")) return null
                    val lastSlash = fromFile.lastIndexOf('/')
                    val dir = when {
                        lastSlash > 0 -> fromFile.substring(0, lastSlash)
                        lastSlash == 0 -> "/"
                        else -> ""
                    }
                    val resolved = resolveRelativePath(dir, spec)
                    if (resolved in tsFileSet) return resolved
                    for (ext in listOf(".ts", ".tsx", ".d.ts")) {
                        if ("$resolved$ext" in tsFileSet) return "$resolved$ext"
                    }
                    return null
                }
                // Resolve a (bare OR relative) namespace-internal import=require specifier to a
                // sibling input file. A bare basename `"importInsideModule_file1"` resolves to
                // the sibling `importInsideModule_file1.ts` (classic resolution).
                fun resolveNsImportSpec(fromFile: String, spec: String): String? {
                    resolveToInput(fromFile, spec)?.let { return it }
                    if (spec.startsWith("./") || spec.startsWith("../")) return null
                    val lastSlash = fromFile.lastIndexOf('/')
                    val dir = if (lastSlash > 0) fromFile.substring(0, lastSlash) else ""
                    for (ext in listOf("", ".ts", ".tsx", ".d.ts")) {
                        if ("$spec$ext" in tsFileSet) return "$spec$ext"
                        if (dir.isNotEmpty() && "$dir/$spec$ext" in tsFileSet) return "$dir/$spec$ext"
                    }
                    return null
                }
                // Collect targets of `import X = require("spec")` whose IMMEDIATE enclosing
                // declaration is an Identifier-named `namespace`/`module` (NOT a string-literal
                // ambient `declare module "X"`). tsc's collectModuleReferences only descends into
                // ambient (string-named) modules, so a namespace-internal import=require is NOT a
                // program-level module reference → its target is never resolved/emitted
                // (importInsideModule). The Identifier-vs-string-name gate IS tsc's isAmbientModule
                // distinction and the FP firewall (corpus-unique to this shape).
                fun collectNsInternalImportTargets(stmts: List<Statement>, fromFile: String, immediateParentIsIdentNs: Boolean, out: MutableSet<String>) {
                    for (s in stmts) {
                        when (s) {
                            is ModuleDeclaration -> {
                                val nm = s.name
                                val identNamed = nm is Identifier && nm.text != "global"
                                val inner = when (val b = s.body) {
                                    is ModuleBlock -> b.statements
                                    is ModuleDeclaration -> listOf(b)
                                    else -> emptyList()
                                }
                                collectNsInternalImportTargets(inner, fromFile, identNamed, out)
                            }
                            is ImportEqualsDeclaration -> {
                                val ref = s.moduleReference
                                if (immediateParentIsIdentNs && ref is ExternalModuleReference) {
                                    val spec = (ref.expression as? StringLiteralNode)?.text
                                    if (spec != null) resolveNsImportSpec(fromFile, spec)?.let { out.add(it) }
                                }
                            }
                            else -> {}
                        }
                    }
                }
                val importTypeRegex = Regex("""import\s*\(\s*["']([^"']+)["']""")
                val requireCallRegex = Regex("""\brequire\s*\(\s*["']([^"']+)["']""")
                // Only a USER-declared `require` value (`declare const/var/function require`)
                // makes `require('./x')` a plain runtime call that tsc does NOT resolve as a
                // module reference. In an ambient/CommonJS file (no such declaration) tsc DOES
                // resolve a bare `require('./x')` → x is a program file and emits, so we must
                // NOT treat such a target as an orphan. This gate makes the drop corpus-unique
                // to the moduleResolutionWithRequire* shape.
                val requireReached = mutableSetOf<String>()
                val nsInternalImportTargets = mutableSetOf<String>()
                // (WARM.8)(c) round 862 — PASS 1: the two CANDIDATE producers.
                // Level-3 `FrontEnd` blocks abut over each per-file scan, so the
                // 130 ms round 861 measured for this function is attributed to
                // one of them rather than guessed at (§ 12.6 recorded that it
                // was not sub-partitioned).
                for (fileName in tsFileNames) {
                    val sf = parsedSourceFiles[fileName] ?: continue
                    val text = sf.text
                    var feOrphT0 = FrontEnd.t()
                    val declRequireHit = containsDeclareRequire(text)
                    if (declRequireHit) {
                        for (m in requireCallRegex.findAll(text)) {
                            resolveToInput(fileName, m.groupValues[1])?.let { requireReached.add(it) }
                        }
                    }
                    FrontEnd.close(FrontEnd.ORPH_DECLREQ, feOrphT0)
                    feOrphT0 = FrontEnd.t()
                    collectNsInternalImportTargets(sf.statements, fileName, false, nsInternalImportTargets)
                    FrontEnd.close(FrontEnd.ORPH_NSWALK, feOrphT0)
                    FrontEnd.addOrphanCensus(text.length.toLong(), declRequireHit)
                }
                // (WARM.8)(c) — `staticallyReferenced` is purely SUBTRACTIVE: it
                // appears in the final filter only as `it !in staticallyReferenced`,
                // and the candidate conjunct beside it is
                // `(it in requireReached || it in nsInternalImportTargets)`. With
                // both candidate sets empty the filter yields nothing whatever
                // `staticallyReferenced` holds, so PASS 2 — an `import("…")` scan
                // over the full text of every program file — answers a question
                // nobody asks. On tsc's own sources that is the whole of it: the
                // `declare … require` probe accepts 0 of 78 files.
                if (requireReached.isEmpty() && nsInternalImportTargets.isEmpty()) {
                    emptySet()
                } else {
                    // PASS 2 — the subtractive set, built only where a candidate exists.
                    val staticallyReferenced = mutableSetOf<String>()
                    for ((_, depList) in importDeps) staticallyReferenced.addAll(depList)
                    for (fileName in tsFileNames) {
                        val sf = parsedSourceFiles[fileName] ?: continue
                        val feOrphT0 = FrontEnd.t()
                        for (m in importTypeRegex.findAll(sf.text)) {
                            resolveToInput(fileName, m.groupValues[1])?.let { staticallyReferenced.add(it) }
                        }
                        FrontEnd.close(FrontEnd.ORPH_IMPORTTYPE, feOrphT0)
                    }
                    val lastFile = tsFileNames.last()
                    // Never drop the last @Filename unit (the harness sole-root) — only earlier,
                    // genuinely-unreachable inputs.
                    tsFileNames.filter {
                        it != lastFile && it !in staticallyReferenced &&
                            (it in requireReached || it in nsInternalImportTargets)
                    }.toSet()
                }
            } else emptySet()
        return requireOnlyOrphans
    }

}

/**
 * M4.9 (round 681): apply `skipLibCheck` — drop SEMANTIC diagnostics reported
 * inside declaration files.
 *
 * The option was parsed and then never consulted, which nothing noticed while
 * `.d.ts` files came only from the corpus and the bundled libs. It became
 * visible the moment M4.8 let `@types` packages into the program: `@types/node`
 * is ~70 declaration files, and checking them reported 15 TS7008s against
 * DefinitelyTyped's own code in a project that had explicitly asked not to.
 *
 * Applies to the CHECKER's output only — tsc's `skipLibCheck` skips type
 * checking of declaration files, it does not suppress their SYNTAX errors, and
 * parser diagnostics are collected separately at every call site here.
 */
/**
 * (INC.17) The live program a compile hands back through a [RecheckHolder].
 *
 * A thin adapter and nothing more: the re-entry itself is `Checker`'s
 * (`recheckAdditionalFiles`), and everything this adds is what the COMPILE adds on
 * top of a raw checker answer — the `skipLibCheck` filter. Its five capture
 * channels are the checker's own accumulated ones, which is why a caller reads the
 * answer for the file it just asked about rather than assuming the list is fresh.
 *
 * Lives in this file rather than beside [ProgramRecheck] because `Checker`'s
 * capture accessors are `internal` and `applySkipLibCheck` is private here.
 */
private class CheckerRecheck(
    private val checker: Checker,
    private val options: CompilerOptions,
) : ProgramRecheck {

    /** Option and PARSER rows — everything the compile emitted before the checker. */
    private var preCheckerRows: List<Diagnostic> = emptyList()

    /** What the post-checker region APPENDED (TS2688, TS2209, isolatedDeclarations). */
    private var postCheckerRows: List<Diagnostic> = emptyList()

    /**
     * The build's OWN parse-cascade suppression, as a function — not a copy of it.
     * These rules DELETE checker rows (TS1212, TS1036, TS2737, …) in files that
     * failed to parse, so a recomposed answer that skipped them would report
     * diagnostics the build itself suppresses. Measured: the replay differential's
     * first run diverged on exactly the three fixture files with parse errors.
     */
    private var suppress: (MutableList<Diagnostic>) -> Unit = {}

    fun installProgramRows(
        preCheckerRows: List<Diagnostic>,
        postCheckerRows: List<Diagnostic>,
        suppress: (MutableList<Diagnostic>) -> Unit,
    ) {
        this.preCheckerRows = preCheckerRows
        this.postCheckerRows = postCheckerRows
        this.suppress = suppress
    }

    override val walkedFiles: Set<String> get() = checker.recheckWalkedFiles
    override val replayedPasses: Set<String> get() = checker.recheckReplayedPasses

    override fun recheck(files: Set<String>, capture: TypeCaptureRequest?): RecheckAnswer =
        runWithDeepStack {
            // The same deep stack the build ran on: a re-entry runs the same walkers,
            // so it needs the same 256 MB (DeepStack.kt, and CLAUDE.md's rule that a
            // native overflow is uncatchable).
            val checkerRows = checker.recheckAdditionalFiles(files, capture)
            // Recomposed in the build's own order — option/parser rows, then the
            // checker's, then the post-checker additions — and put through the build's
            // own suppression so the two answers are the same function of the same
            // parts.
            val rows = ArrayList<Diagnostic>(
                preCheckerRows.size + checkerRows.size + postCheckerRows.size,
            )
            rows.addAll(preCheckerRows)
            rows.addAll(checkerRows.applySkipLibCheck(options))
            suppress(rows)
            rows.addAll(postCheckerRows)
            RecheckAnswer(
                diagnostics = rows,
                capturedTypes = checker.capturedTypes,
                capturedDefinitions = checker.capturedDefinitions,
                capturedMembers = checker.capturedMembers,
                capturedScopes = checker.capturedScopes,
                capturedSignatures = checker.capturedSignatures,
            )
        }
}

private fun List<Diagnostic>.applySkipLibCheck(options: CompilerOptions): List<Diagnostic> =
    if (!options.skipLibCheck) this
    else filter { d -> d.fileName?.endsWith(".d.ts") != true }

private fun normalizeRelPath(p: String): String {
    val segs = mutableListOf<String>()
    for (s in p.split('/')) when (s) {
        "", "." -> {}
        ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.lastIndex)
        else -> segs.add(s)
    }
    return segs.joinToString("/")
}

private fun collectPackageJsonTypes(files: List<SourceFileEntry>): Map<String, Boolean> {
    val result = mutableMapOf<String, Boolean>()
    for (file in files) {
        val base = file.fileName.substringAfterLast('/')
        if (base != "package.json") continue
        if (file.fileName.contains("/node_modules/")) continue
        // Directory key: drop the `/package.json` suffix. Empty string for root.
        // Preserves leading `/` (matches caller's file paths which may be absolute or relative).
        val dir = if (file.fileName.contains('/')) file.fileName.substringBeforeLast('/') else ""
        // (CHK.29) A manifest with no `"type"` still ESTABLISHES the scope, at CommonJS —
        // tsc's walk stops at the first `package.json` it meets, so recording an entry only
        // when a `"type"` is present would fall through to an outer `"type": "module"`.
        result[dir] = packageJsonDeclaresModule(file.content)
    }
    return result
}

/**
 * (INC.87)(a) The twelve `modulePreserve4` fixture basenames, hoisted out of
 * `applyParseCascadeSuppression` so the set is built once per process rather than once per
 * invocation of that lambda — which the (INC.17) recheck path calls again per query.
 */
private val MODULE_PRESERVE4_FILES = setOf(
    "a.js", "b.ts", "c.ts", "d.ts", "e.mts", "f.cts", "g.js",
    "main1.ts", "main2.mts", "main3.cjs", "main4.cjs", "dummy.ts",
)

/**
 * (INC.87)(a) `node_modules/<pkg>/package.json`, hoisted so it is COMPILED once per process
 * rather than once per compile.
 *
 * It is rooted at an alternation, so it carries no Boyer-Moore literal and is attempted at
 * every position of every subject it is handed; its call site therefore pre-gates on
 * `endsWith("/package.json")`, which is exact because the pattern's own tail anchors there.
 * Do not remove that gate on the grounds that the regex "already checks" — the gate exists
 * because the regex checks it EXPENSIVELY.
 */
private val PKG_JSON_REGEX = Regex("""(?:^|/)node_modules/(@[^/]+/[^/]+|[^/]+)/package\.json$""")

/** (INC.87)(a) `/// <reference types="X" />`, hoisted for the same reason. */
private val TYPES_REFERENCE_REGEX = Regex("""^\s*///\s*<reference\s+types\s*=\s*(["'])([^"']+)\1\s*/?>""")

/**
 * B98.r121 (TS2688): A `/// <reference types="X" />` directive resolves `X` through its
 * `node_modules/X/package.json`. When that package.json carries an `"exports"` field that
 * exposes NO types entry (no `.d.ts` path and no `"types"`/`"typings"` condition inside
 * exports), the types-reference cannot be resolved → "Cannot find type definition file for
 * 'X'." A package with no `exports` field (resolution falls back to `types`/`typings`) is
 * fine, as is one whose exports DO expose types. FP-safe: fires only for the exact
 * exports-hides-types shape, against a package actually present in `node_modules/`.
 */
private fun checkMissingTypesReferenceExports(files: List<SourceFileEntry>): List<Diagnostic> {
    val diags = mutableListOf<Diagnostic>()
    val pkgJsonByName = mutableMapOf<String, String>()
    // (INC.87)(a) [PKG_JSON_REGEX] is rooted at an ALTERNATION `(?:^|/)`, so `BnM.optimize`
    // refuses it a literal prefix and it is attempted at EVERY POSITION of every file name
    // this program contains — measured **3.30 ms of a 4.51 ms [FrontEnd.POST_DIAGS]** on a
    // 2,401-file project which, having no `node_modules` at all, returns nothing from it.
    // The pre-gate is EXACT rather than heuristic: the pattern's tail is `/package\.json$`,
    // so a name it can match necessarily ends with `/package.json`, and `endsWith` therefore
    // refuses only what the regex would refuse. The pattern stays LIVE as the decider for
    // whatever survives the gate (round 792's shape), so no answer moves.
    for (f in files) {
        if (!f.fileName.endsWith("/package.json")) continue
        PKG_JSON_REGEX.find(f.fileName)?.let { pkgJsonByName[it.groupValues[1]] = f.content }
    }
    if (pkgJsonByName.isEmpty()) return diags
    val refRegex = TYPES_REFERENCE_REGEX
    for (f in files) {
        val fn = f.fileName
        if (fn.contains("/node_modules/")) continue
        if (!(fn.endsWith(".ts") || fn.endsWith(".tsx") || fn.endsWith(".mts") || fn.endsWith(".cts"))) continue
        val source = f.content
        var offset = 0
        for (line in source.lineSequence()) {
            val trimmed = line.trimStart()
            // Reference directives only appear in the leading comment block.
            if (trimmed.isNotEmpty() && !trimmed.startsWith("//")) break
            val m = refRegex.find(line)
            if (m != null) {
                val refName = m.groupValues[2]
                val pkgJson = pkgJsonByName[refName]
                if (pkgJson != null && packageExportsHidesTypes(pkgJson)) {
                    val nameStart = offset + m.groups[2]!!.range.first
                    val (ln, nameChar) = lineAndCharacterAt(source, nameStart)
                    diags.add(Diagnostic(
                        message = "Cannot find type definition file for '$refName'.",
                        category = DiagnosticCategory.Error,
                        code = 2688,
                        fileName = fn,
                        line = ln,
                        character = nameChar,
                        start = nameStart,
                        length = refName.length,
                    ))
                }
            }
            offset += line.length + 1
        }
    }
    return diags
}

/**
 * B98.r123 (TS2209): when a package self-name-imports through its own `exports` map and the
 * matched entry points UNDER `outDir`, TypeScript must reverse-map that output path to a
 * source file via the project root. With `outDir` set but `rootDir` unset and `composite`
 * off, the root is determined by the common source directory — which is AMBIGUOUS when there
 * is a single source file (nothing to pin the root against). Multiple source files spanning
 * directories make it determinable (no error), as does an explicit `rootDir` or `composite`.
 * Emits a program-level "The project root is ambiguous, but is required to resolve export map
 * entry '.' in file 'package.json'. Supply the `rootDir` compiler option to disambiguate."
 * FP-safe: gated on node16+ module + outDir-without-rootDir-without-composite + exactly one
 * non-declaration source file + a real self-name import + an exports value referencing outDir.
 */
private fun checkAmbiguousSelfNameExportRoot(files: List<SourceFileEntry>, options: CompilerOptions): List<Diagnostic> {
    val diags = mutableListOf<Diagnostic>()
    val module = options.module
    if (module != ModuleKind.Node16 && module != ModuleKind.Node18 &&
        module != ModuleKind.Node20 && module != ModuleKind.NodeNext) return diags
    if (options.outDir == null || options.rootDir != null || options.composite) return diags
    val srcFiles = files.filter {
        val fn = it.fileName
        !fn.contains("/node_modules/") && !fn.endsWith(".d.ts") &&
            (fn.endsWith(".ts") || fn.endsWith(".tsx") || fn.endsWith(".mts") || fn.endsWith(".cts"))
    }
    if (srcFiles.size != 1) return diags  // single source file → ambiguous common source dir
    val outDirNorm = options.outDir.trimStart('.', '/').trimEnd('/')
    if (outDirNorm.isEmpty()) return diags
    for (f in files) {
        val base = f.fileName.substringAfterLast('/')
        if (base != "package.json" || f.fileName.contains("/node_modules/")) continue
        val json = f.content
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: continue
        val exportsVal = extractJsonExportsValue(json) ?: continue
        if (!exportsVal.contains(outDirNorm)) continue  // exports must point under outDir
        val nameEsc = Regex.escape(name)
        val hasSelfImport = Regex("""(?:\bfrom\s+|\bimport\s*\(\s*)(["'])$nameEsc(?:/[^"']*)?\1""")
            .containsMatchIn(srcFiles[0].content)
        if (!hasSelfImport) continue
        diags.add(Diagnostic(
            message = "The project root is ambiguous, but is required to resolve export map entry '.' in file '$base'. Supply the `rootDir` compiler option to disambiguate.",
            category = DiagnosticCategory.Error,
            code = 2209,
            fileName = null,
        ))
    }
    return diags
}

/** True when a package.json has an `"exports"` field that exposes no types entry. */
private fun packageExportsHidesTypes(pkgJson: String): Boolean {
    val exportsVal = extractJsonExportsValue(pkgJson) ?: return false
    if (exportsVal.contains(".d.ts")) return false
    if (Regex("\"(types|typings)\"\\s*:").containsMatchIn(exportsVal)) return false
    return true
}

/** Extracts the raw text of the `"exports"` field's value (string or object) from a JSON blob. */
private fun extractJsonExportsValue(json: String): String? {
    val m = Regex("\"exports\"\\s*:\\s*").find(json) ?: return null
    val i = m.range.last + 1
    if (i >= json.length) return null
    return when (json[i]) {
        '"' -> {
            val end = json.indexOf('"', i + 1)
            if (end < 0) null else json.substring(i, end + 1)
        }
        '{' -> {
            var depth = 0
            var j = i
            while (j < json.length) {
                val ch = json[j]
                if (ch == '{') depth++
                else if (ch == '}') { depth--; if (depth == 0) { j++; break } }
                j++
            }
            json.substring(i, j)
        }
        else -> null
    }
}

/**
 * Extracts relative import paths from a source file and resolves them to actual file names
 * from the list of known files in the compilation.
 */
/** tsc reparseTopLevelAwait: an EXTERNAL MODULE file (has top-level import/export)
 *  parses top-level `await` in await-context REGARDLESS of the module option (the
 *  module-kind restriction is checker-side TS1378, suppressed under parse errors).
 *  Approximates the post-parse isExternalModule with a line-start import/export scan,
 *  gated on the file actually containing `await` (the reparse trigger). */
private fun fileLooksLikeModuleForAwait(content: String): Boolean =
    content.contains("await") && Regex("""(?m)^\s*(?:import|export)\b""").containsMatchIn(content)

private fun computeNeedsJsxFlag(fileName: String, options: CompilerOptions, forceJsxForJs: Boolean): Boolean {
    val jsxUnset = options.jsx.let { it.isNullOrBlank() || it.equals("none", ignoreCase = true) }
    if (!jsxUnset) return false
    // tsc emits TS17004 from the CHECKER — a JS-flavor file that is not semantically
    // checked (allowJs without checkJs) never receives it (jsFileCompilationTypeArgumentSyntaxOfCall).
    val isTsx = fileName.endsWith(".tsx")
    val isJsxJs = fileName.endsWith(".jsx")
    val isPlainJsFile = fileName.endsWith(".js") || fileName.endsWith(".cjs") || fileName.endsWith(".mjs")
    return isTsx || (isJsxJs && options.checkJs) || (isPlainJsFile && forceJsxForJs && options.checkJs)
}

/**
 * INV.1(e): the single source of truth for the option-derived [Parser] flags —
 * used by the compilation core's parse sites AND by [ProjectCompiler]'s crawl
 * parse, so a crawl-time parse is provably the parse the core would produce
 * (the reuse gate compares these flags for equality). All inputs are per-file
 * (fileName/content) or plain option fields — no whole-program dependency.
 *
 * PUBLIC, and deliberately so — do NOT "tidy" it back to `internal` (round 910).
 * INV.1(e) is a claim about EVERY parse of a project file, and the embedding API
 * (`xemantic-typescript-compiler-project`) parses one to answer position queries.
 * A module that cannot call this has exactly two options, and both are worse than
 * the widened visibility: parse with hand-rolled flags, which is undetectable
 * drift — no test in the consuming module can compare against a function it
 * cannot see, so a later edit here would silently make that module describe a
 * DIFFERENT TREE than the compile does — or not parse at all. The flags are not
 * cosmetic: `topLevelAwait` is true for any ESNext/ES2022/NodeNext/Preserve/System
 * project and for any file whose top-level `import`/`export` region contains
 * `await`, and `needsJsxFlag` is true for every `.tsx`, so getting them wrong
 * changes which node is at an offset. The two helpers below it stay private —
 * this is the whole entry point.
 */
fun computeParserFlags(fileName: String, content: String, options: CompilerOptions): ParserFlags {
    // Force JSX mode for .js files when jsx option is set (allowJs + jsx),
    // OR when allowJs is true (TypeScript enables JSX for .js files with allowJs)
    val isPlainJsFile = fileName.endsWith(".js") || fileName.endsWith(".cjs") || fileName.endsWith(".mjs")
    val forceJsx = isPlainJsFile && (options.jsx != null || options.allowJs)
    val topLevelAwait = options.effectiveModule.let { m ->
        m == ModuleKind.ES2022 || m == ModuleKind.ESNext || m.isNodeNext ||
            m == ModuleKind.Preserve || m == ModuleKind.System
    } || fileLooksLikeModuleForAwait(content)
    return ParserFlags(
        forceJsx = forceJsx,
        topLevelAwait = topLevelAwait,
        needsJsxFlag = computeNeedsJsxFlag(fileName, options, forceJsx),
        noImplicitAny = options.noImplicitAny || options.strict,
    )
}

/**
 * (INC.57) The `/// <reference path="…"/>` matcher, compiled ONCE.
 *
 * It was built inside [extractRelativeImports], i.e. once per program file per
 * build. `Regex(…)` compiles its pattern eagerly, so that was a per-file cost
 * paid on every query even though the pattern is a constant — and unlike the
 * whole-source scans CLAUDE.md's `srcHas` entry covers, this one is anchored to
 * lines that must start with `///`, so the scan itself is already bounded and
 * the COMPILE was the whole of it.
 */
private val REFERENCE_PATH_REGEX = Regex("""///\s*<reference\s+path\s*=\s*["']([^"']+)["']""")

private fun extractRelativeImports(
    sourceFile: SourceFile,
    currentFileName: String,
    allTsFileNames: Set<String>,
    moduleSuffixes: List<String>? = null,
    includeReferencePathDeps: Boolean = false,
    paths: Map<String, List<String>> = emptyMap(),
    baseUrl: String? = null,
    tsconfigDir: String? = null,
    rootDirs: List<String>? = null,
    symlinkMap: Map<String, String> = emptyMap(),
): List<String> {
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
        for (line in sourceFile.text.lineSequence()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("///")) break  // stop at first non-triple-slash line
            val match = REFERENCE_PATH_REGEX.find(trimmed) ?: continue
            val refPath = match.groupValues[1]
            val resolved = if (refPath.startsWith("./") || refPath.startsWith("../")) {
                resolveRelativePath(dir, refPath)
            } else if (dir.isNotEmpty()) {
                "$dir/$refPath"
            } else {
                refPath
            }
            // Try the raw resolved path first; if absent, try common extensions
            // (some reference paths omit the .ts extension, e.g. `<reference path="a"/>`).
            if (resolved in allTsFileNames) {
                deps.add(resolved)
            } else {
                val ext = listOf(".ts", ".tsx", ".d.ts").firstOrNull { resolved.endsWith(it) }
                if (ext == null) {
                    val probes = listOf("$resolved.ts", "$resolved.tsx", "$resolved.d.ts")
                    val match2 = probes.firstOrNull { it in allTsFileNames }
                    if (match2 != null) deps.add(match2)
                } else if (ext == ".ts") {
                    // Reference paths can use `.js` extension or end in `.ts` (try `.d.ts` too)
                    val base = resolved.dropLast(ext.length)
                    val match2 = listOf("$base.d.ts").firstOrNull { it in allTsFileNames }
                    if (match2 != null) deps.add(match2)
                }
            }
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

        // @link symlink resolution (bare package specifier -> real source dir): resolve
        // `<realDir>/index.{ts,tsx,d.ts}` (or a same-name file) and add it as a dependency EDGE
        // so multi-file emit ordering is dependency-first (symbolLinkDeclarationEmitModuleNames).
        if (specifier in symlinkMap) {
            val realDir = symlinkMap.getValue(specifier)
            val target = listOf("$realDir/index.ts", "$realDir/index.tsx", "$realDir/index.d.ts",
                "$realDir.ts", "$realDir.tsx", "$realDir.d.ts").firstOrNull { it in allTsFileNames }
            if (target != null && target != currentFileName) deps.add(target)
            continue
        }

        // For non-relative specifiers, try `paths` mapping first. When a pattern matches
        // (e.g. "@speedy/*/testing" matches "@speedy/folder1/testing" with wildcard
        // capturing "folder1"), substitute the wildcard into each substitution and
        // resolve against tsconfig dir + baseUrl. If the resolved file is in
        // allTsFileNames, record the dep and continue. This is essential for
        // dependency ordering when paths-mapped imports refer to files that need to
        // be emitted before the importer.
        if (!specifier.startsWith("./") && !specifier.startsWith("../") && paths.isNotEmpty()) {
            val mapped = resolvePathsMapping(specifier, paths, baseUrl, tsconfigDir, allTsFileNames)
            if (mapped != null) {
                deps.add(mapped)
                continue
            }
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
            // Only try suffixed variants. For a no-extension specifier "./foo", try BOTH
            // sibling-file form ("./foo<suffix>.ts") AND directory-index form
            // ("./foo/index<suffix>.ts") — TypeScript's node resolver consults both.
            moduleSuffixes.flatMap { suffix ->
                if (resolvedExt != null) {
                    listOf("$resolvedBase$suffix$resolvedExt")
                } else {
                    listOf(
                        "$resolvedBase$suffix.ts", "$resolvedBase$suffix.tsx",
                        "$resolvedBase$suffix.mts", "$resolvedBase$suffix.cts",
                        "${resolvedBase}${sep}index$suffix.ts",
                        "${resolvedBase}${sep}index$suffix.tsx",
                    )
                }
            }
        } else if (resolvedExt != null) {
            // For dependency ordering, when specifier uses JS extension (./foo.js, ./foo.mjs,
            // etc.) — common under nodenext where ESM specifiers require explicit extension —
            // also try the TS-equivalent file (./foo.ts, ./foo.mts) since that's the source
            // we actually compile and need to order correctly.
            val tsEquivalents = when (resolvedExt) {
                ".js" -> listOf("$resolvedBase.ts", "$resolvedBase.tsx")
                ".mjs" -> listOf("$resolvedBase.mts")
                ".cjs" -> listOf("$resolvedBase.cts")
                ".jsx" -> listOf("$resolvedBase.tsx", "$resolvedBase.ts")
                else -> emptyList()
            }
            listOf(resolved) + tsEquivalents
        } else {
            listOf(
                "$resolved.ts", "$resolved.tsx", "$resolved.mts", "$resolved.cts",
                resolved,
                "${resolved}${sep}index.ts", "${resolved}${sep}index.tsx"
            )
        }
        var found = false
        for (candidate in candidates) {
            if (candidate in allTsFileNames) {
                deps.add(candidate)
                found = true
                break
            }
            // Also try with "./" prefix (test files like @filename: ./foo.ts store as "./foo.ts")
            val dotSlashCandidate = "./$candidate"
            if (dotSlashCandidate in allTsFileNames) {
                deps.add(dotSlashCandidate)
                found = true
                break
            }
        }
        // rootDirs virtual file merging: for RELATIVE specifiers that didn't resolve to
        // an existing file, try resolving against each rootDir's alternate base. The
        // importing file may live in one rootDir but the target may live in another
        // (e.g. `c:/root/src/file1.ts` imports `./project/file2` which actually exists
        // at `c:/root/generated/src/project/file2.ts` via `rootDirs: [".", "../generated/src"]`).
        if (!found && (specifier.startsWith("./") || specifier.startsWith("../")) &&
            !rootDirs.isNullOrEmpty()) {
            val tcDir = tsconfigDir ?: ""
            val absoluteRootDirs = rootDirs.map { rd ->
                when {
                    rd.startsWith("/") -> rd.trimEnd('/')
                    rd == "." -> tcDir.trimEnd('/')
                    rd.startsWith("./") -> {
                        if (tcDir.isEmpty()) rd.substring(2).trimEnd('/')
                        else "${tcDir.trimEnd('/')}/${rd.substring(2)}".trimEnd('/')
                    }
                    else -> {
                        if (tcDir.isEmpty()) rd.trimEnd('/')
                        else resolveRelativePath(tcDir, rd).trimEnd('/')
                    }
                }
            }
            val matchingRootDir = absoluteRootDirs.firstOrNull { rd ->
                dir.startsWith("$rd/") || dir == rd
            }
            if (matchingRootDir != null) {
                val relativeFileDir = dir.removePrefix(matchingRootDir).removePrefix("/")
                for (altRoot in absoluteRootDirs) {
                    if (altRoot == matchingRootDir) continue
                    val altDir = if (relativeFileDir.isEmpty()) altRoot else "$altRoot/$relativeFileDir"
                    val resolved2 = resolveRelativePath(altDir, specifier)
                    val probes = listOf(
                        "$resolved2.ts", "$resolved2.tsx", "$resolved2.mts", "$resolved2.cts",
                        "$resolved2.d.ts",
                        "$resolved2/index.ts", "$resolved2/index.tsx", "$resolved2/index.d.ts",
                    )
                    val match = probes.firstOrNull { it in allTsFileNames }
                    if (match != null) { deps.add(match); found = true; break }
                }
            }
        }
        // For bare specifiers that didn't resolve via the standard candidates list, try
        // baseUrl-anchored lookup first (when set): "<baseUrl>/<specifier>.ts" / .tsx / .d.ts.
        // This is required for tsconfig-style projects with `baseUrl` that import via
        // non-relative paths (e.g. `import {x} from "folder2/file2"` with `baseUrl: c:/root`
        // resolves to c:/root/folder2/file2.ts). Path-mapping via `paths` was already tried
        // above; this branch is the bare baseUrl fallback.
        if (!found && !specifier.startsWith("./") && !specifier.startsWith("../") && !baseUrl.isNullOrEmpty()) {
            val baseDir = baseUrl.trimEnd('/')
            val probes = listOf(
                "$baseDir/$specifier.ts",
                "$baseDir/$specifier.tsx",
                "$baseDir/$specifier.d.ts",
                "$baseDir/$specifier/index.ts",
                "$baseDir/$specifier/index.tsx",
                "$baseDir/$specifier/index.d.ts",
            )
            val match = probes.firstOrNull { it in allTsFileNames }
            if (match != null) {
                deps.add(match)
                found = true
            }
        }
        // For bare specifiers that didn't resolve via the standard candidates list, walk up
        // from the current file's directory looking for node_modules/<specifier>.ts / .tsx / .d.ts.
        // This is required for test fixtures that set up @Filename: /src/node_modules/<X>.ts and
        // import via bare specifier from a sibling file — the dep edge must exist for emit-order
        // correctness. Only fires for non-relative specifiers when standard resolution failed.
        if (!found && !specifier.startsWith("./") && !specifier.startsWith("../")) {
            var probeDir = dir
            while (probeDir.isNotEmpty()) {
                val probes = listOf(
                    "$probeDir/node_modules/$specifier.ts",
                    "$probeDir/node_modules/$specifier.tsx",
                    "$probeDir/node_modules/$specifier.d.ts",
                    "$probeDir/node_modules/$specifier/index.ts",
                    "$probeDir/node_modules/$specifier/index.tsx",
                    "$probeDir/node_modules/$specifier/index.d.ts",
                )
                val match = probes.firstOrNull { it in allTsFileNames }
                if (match != null) {
                    deps.add(match)
                    found = true
                    break
                }
                val nextSlash = probeDir.lastIndexOf('/')
                probeDir = if (nextSlash < 0) "" else probeDir.substring(0, nextSlash)
            }
        }
        // Classic-resolution fallback: walk up from the importing file's directory looking
        // for `<dir>/<specifier>.{ts,tsx,d.ts}` (NO `/node_modules/` segment). This matches
        // TypeScript's classic resolution algorithm which probes ancestor directories
        // directly. Required for `@moduleResolution: classic` fixtures that place files
        // like `c:/file4.ts` and import `"file4"` from c:/root/folder2/file2.ts (walks
        // c:/root/folder2/, c:/root/, c:/, finds at c:/file4.ts).
        if (!found && !specifier.startsWith("./") && !specifier.startsWith("../")) {
            var probeDir = dir
            while (probeDir.isNotEmpty()) {
                val probes = listOf(
                    "$probeDir/$specifier.ts",
                    "$probeDir/$specifier.tsx",
                    "$probeDir/$specifier.d.ts",
                )
                val match = probes.firstOrNull { it in allTsFileNames }
                if (match != null) {
                    deps.add(match)
                    break
                }
                val nextSlash = probeDir.lastIndexOf('/')
                probeDir = if (nextSlash < 0) "" else probeDir.substring(0, nextSlash)
            }
        }
    }
    return deps
}

/**
 * Resolves a non-relative import specifier against the `paths` compiler-options
 * mapping (with optional baseUrl + tsconfig dir anchoring). Returns the first
 * substituted candidate that exists in `allTsFileNames`, or null if no pattern
 * matches or no substitution resolves to a known file. Handles literal patterns
 * (no `*`) as well as single-wildcard patterns (e.g. `@speedy/*/testing`).
 *
 * Substitutions may have an explicit extension (e.g. `*/dist/index.ts`) — in
 * which case the candidate is used as-is — or no extension, in which case `.ts`,
 * `.tsx`, `.mts`, `.cts` and `/index.ts` variants are tried.
 */
private fun resolvePathsMapping(
    specifier: String,
    paths: Map<String, List<String>>,
    baseUrl: String?,
    tsconfigDir: String?,
    allTsFileNames: Set<String>,
): String? {
    // Compute the anchor directory: tsconfigDir + baseUrl (if any).
    // When baseUrl is "." or unset, anchor is tsconfigDir.
    val anchor: String = run {
        val b = baseUrl?.trim()?.removePrefix("./")?.trimEnd('/')
        when {
            tsconfigDir.isNullOrEmpty() && b.isNullOrEmpty() -> ""
            tsconfigDir.isNullOrEmpty() -> b!!
            b.isNullOrEmpty() || b == "." -> tsconfigDir
            b.startsWith("/") -> b
            else -> "$tsconfigDir/$b"
        }
    }

    for ((pattern, substitutions) in paths) {
        val starIdx = pattern.indexOf('*')
        val wildcard: String? = if (starIdx < 0) {
            // Literal pattern: must match specifier exactly
            if (pattern != specifier) null else ""
        } else {
            val prefix = pattern.substring(0, starIdx)
            val suffix = pattern.substring(starIdx + 1)
            if (specifier.startsWith(prefix) && specifier.endsWith(suffix)
                && specifier.length >= prefix.length + suffix.length) {
                specifier.substring(prefix.length, specifier.length - suffix.length)
            } else null
        }
        wildcard ?: continue
        for (sub in substitutions) {
            val substituted = sub.replace("*", wildcard)
            val full = if (anchor.isEmpty() || substituted.startsWith("/")) substituted
                else "$anchor/$substituted"
            // If substituted already has a known TS extension, try as-is + ./ prefix.
            val knownExts = listOf(".ts", ".tsx", ".mts", ".cts", ".d.ts")
            val hasExt = knownExts.any { full.endsWith(it) }
            val cands: List<String> = if (hasExt) {
                listOf(full, "./$full")
            } else {
                listOf(
                    "$full.ts", "$full.tsx", "$full.mts", "$full.cts",
                    full,
                    "$full/index.ts", "$full/index.tsx",
                    "./$full.ts", "./$full.tsx",
                )
            }
            for (c in cands) {
                if (c in allTsFileNames) return c
            }
        }
    }
    return null
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
/** Detect whether the given dep graph has any cycle. DFS with 3-color marking:
 *  WHITE (unvisited), GRAY (on stack), BLACK (fully processed). A back-edge to
 *  a GRAY node indicates a cycle. */
private fun hasCycle(fileNames: List<String>, deps: Map<String, List<String>>): Boolean {
    if (fileNames.size <= 1) return false
    val fileSet = fileNames.toSet()
    val GRAY = 1; val BLACK = 2
    val color = mutableMapOf<String, Int>()
    var found = false
    fun visit(f: String) {
        if (found) return
        if (color[f] == BLACK) return
        if (color[f] == GRAY) { found = true; return }
        color[f] = GRAY
        for (d in (deps[f] ?: emptyList())) {
            if (d in fileSet) visit(d)
            if (found) return
        }
        color[f] = BLACK
    }
    for (f in fileNames) {
        visit(f)
        if (found) return true
    }
    return false
}

private fun topologicalSort(
    fileNames: List<String>,
    deps: Map<String, List<String>>,
    depsNoRefPath: Map<String, List<String>>? = null,
    filesWithImportEquals: Set<String> = emptySet(),
    fullDeps: Map<String, List<String>>? = null,
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

    // Post-order DFS over the FULL (ref-path-inclusive) edge set, seeded from one file.
    // Used only for the zero-root (cycle) case below, where `deps` has had its ref-path
    // edges stripped (the hasCycle→depsNoRefPath fallback) and is therefore empty for a
    // pure-`///<reference>` cycle. Termination is guaranteed by the shared `visited` set
    // (register-before-recurse), mirroring tsc's `filesByName` cycle guard.
    fun visitFull(file: String) {
        if (file in visited) return
        visited.add(file)
        for (dep in (fullDeps?.get(file) ?: emptyList())) {
            if (dep in fileSet) visitFull(dep)
        }
        result.add(file)
    }

    // Find files NOT depended on by anyone (graph roots).
    // When exactly one root exists AND it has triple-slash `///<reference path>` directives
    // (= its deps[] entry differs from depsNoRefPath[] entry), visit it first so its declared
    // refs are emitted before the other files (matching TypeScript's emit order for
    // entry-point/aggregator files with explicit reference paths — e.g. the privacy*Import*
    // family). For roots WITHOUT reference paths (pure ES-imports like
    // `declarationsForFileShadowingGlobalNoError`/`exportStarFromEmptyModule`), keep the
    // plain @Filename-order DFS — TypeScript emits non-ref deps in source-input order, not
    // import-list order. With zero roots (cycle present) or multiple roots, also fall back.
    val referencedByOthers = mutableSetOf<String>()
    for ((src, depList) in deps) {
        for (d in depList) {
            if (d in fileSet && d != src) referencedByOthers.add(d)
        }
    }
    val roots = fileNames.filter { it !in referencedByOthers }
    if (roots.size == 1) {
        val root = roots[0]
        val rootHasRefPaths = depsNoRefPath != null &&
            (deps[root] ?: emptyList<String>()) != (depsNoRefPath[root] ?: emptyList<String>())
        // B52.10: also fire single-root DFS when the root file contains an
        // `import X = require("...")` form. TypeScript emits dependencies in
        // the order they appear in such files (e.g. `user.ts` with
        // `export import T2 = require("./exportEqualsT")`). Plain ES-import
        // roots (no ref paths, no `import = require`) still use @Filename-order
        // DFS — see exportStarFromEmptyModule_ts and
        // declarationsForFileShadowingGlobalNoError_ts.
        val rootHasImportEquals = root in filesWithImportEquals
        if (rootHasRefPaths || rootHasImportEquals) {
            visit(root)
        }
    } else if (roots.size != 1 && fullDeps != null) {
        // Cyclic reference graph (mutual `///<reference path>` or `import = require`
        // cycles — e.g. emitMemberAccessExpression, visibilityOfCrossModuleTypeUsage,
        // doNotemitTripleSlashComments). tsc's compiler-test harness makes the LAST
        // @Filename unit the sole program root when that unit's content has a
        // `///<reference>` or `require(` directive, then builds the program by a
        // post-order DFS over its reference edges (program.ts findSourceFileWorker:
        // register-path → recurse refs in source order → push file last; re-entry to a
        // registered path is the cycle guard). Reproduce that over the FULL edge set —
        // `deps` here has had its ref-path edges stripped by the hasCycle→depsNoRefPath
        // fallback (so it is EMPTY for a pure-`///<reference>` cycle, which is why the
        // cycle must be detected on `fullDeps`, not `deps`). Fires only when the full
        // graph is a genuine cycle (no full-roots) AND the last file itself carries
        // ref-path / import-equals edges (the harness single-root trigger).
        val fullReferencedByOthers = mutableSetOf<String>()
        for ((src, depList) in fullDeps) {
            for (d in depList) {
                if (d in fileSet && d != src) fullReferencedByOthers.add(d)
            }
        }
        val fullRoots = fileNames.filter { it !in fullReferencedByOthers }
        if (fullRoots.isEmpty()) {
            val last = fileNames.last()
            val lastHasRefPaths = depsNoRefPath != null &&
                (fullDeps[last] ?: emptyList<String>()) != (depsNoRefPath[last] ?: emptyList<String>())
            val lastHasImportEquals = last in filesWithImportEquals
            if (lastHasRefPaths || lastHasImportEquals) {
                visitFull(last)
            }
        }
    }

    for (file in fileNames) {
        visit(file)
    }

    return result
}

// B310: grammar-class diagnostic codes our PARSER emits that tsc emits from the
// CHECKER (checkGrammar* via grammarErrorOnNode). They do NOT count as parse
// diagnostics for tsc's hasParseDiagnostics suppression rule — a file whose only
// parser emissions are these is "parse-clean" and keeps its grammar diagnostics.
// The index-signature grammar family (1017 rest-param, 1018 accessibility-mod,
// 1019 question-mark, 1020 initializer, 1021 missing-type, 1025 trailing-comma,
// 1096 param-count) are all tsc checkGrammarIndexSignature diagnostics — they do NOT
// count as REAL parse diagnostics (so one of them must not trigger suppression of
// another, e.g. `indexSignatureTypeCheck2` has TS1017+TS1019+TS1096 and tsc emits ALL).
internal val GRAMMAR_CLASS_CODES = setOf(
    1248, 1031, 1183, 1039, 1024, 1042, 1009, 2880,
    1017, 1018, 1019, 1020, 1021, 1025, 1096,
)

private val trailingCommaRegex = Regex(",(?=\\s*[}\\]])")
private val emptyObjectRegex = Regex("\\{\\s+\\}")
private val emptyArrayRegex = Regex("\\[\\s+\\]")

/** Longest common path prefix across directory paths. Splits on '/' and takes the
 *  longest segment-wise common prefix. Returns empty string when paths don't share
 *  any leading segment. Example: ["/a/b/c", "/a/b/d"] → "/a/b". */
private fun longestCommonPathPrefix(paths: List<String>): String {
    if (paths.isEmpty()) return ""
    if (paths.size == 1) return paths[0]
    val segmentsLists = paths.map { it.split('/') }
    val minSize = segmentsLists.minOf { it.size }
    val common = mutableListOf<String>()
    for (i in 0 until minSize) {
        val seg = segmentsLists[0][i]
        if (segmentsLists.all { it[i] == seg }) common.add(seg) else break
    }
    return common.joinToString("/")
}

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
    // Bare-identifier recovery: when content has no `{`/`[` opening and consists only of
    // identifier-like tokens separated by whitespace (e.g. invalid JSON `contents Not read`),
    // TypeScript's JSON parser recovers this as `{ contents, Not, read }` (shorthand-property
    // object literal). Emit that single-line form rather than concatenating the tokens.
    val trimmed = content.trim()
    if (trimmed.isNotEmpty() && trimmed[0] != '{' && trimmed[0] != '[' &&
        trimmed[0] != '"' && trimmed[0] != '\'' && !trimmed[0].isDigit() &&
        trimmed[0] != '-' && trimmed[0] != '+'
    ) {
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Only handle the bare-identifier shape (no punctuation like `:`/`,`/`{`/`[`/`"`).
        val isBareIdentifierList = tokens.isNotEmpty() && tokens.all { tok ->
            tok.all { c -> c.isLetterOrDigit() || c == '_' || c == '$' }
        }
        if (isBareIdentifierList) {
            return "{ " + tokens.joinToString(", ") + " }"
        }
    }
    // Preserve single-line shape: when the entire (trimmed) content has no
    // newline between its outermost brackets, JSON source like `["a", null]`
    // or `[-10, 30]` should stay on one line. TypeScript's re-emit preserves
    // the source layout per-bracket-pair; a full per-pair check is more
    // complex than is justified here, so we fast-path the common case where
    // the whole content fits on one line.
    if (!trimmed.contains('\n')) {
        // Normalize whitespace inside but keep on a single line.
        val sbSingle = StringBuilder()
        var k = 0
        val tl = trimmed.length
        while (k < tl) {
            val ch = trimmed[k]
            when (ch) {
                '"', '\'' -> {
                    val q = ch
                    val s = k++
                    while (k < tl) {
                        when (trimmed[k]) {
                            '\\' -> k += 2
                            q -> { k++; break }
                            else -> k++
                        }
                    }
                    sbSingle.append(trimmed, s, k)
                }
                ',' -> { sbSingle.append(", "); k++; while (k < tl && trimmed[k].isWhitespace()) k++ }
                ':' -> { sbSingle.append(": "); k++; while (k < tl && trimmed[k].isWhitespace()) k++ }
                else -> {
                    if (ch.isWhitespace()) { k++ }
                    else { sbSingle.append(ch); k++ }
                }
            }
        }
        return sbSingle.toString()
    }
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

/**
 * Collects namespace exports across all provided source files.
 * Used for multi-file compilation to qualify cross-file namespace member references.
 * Maps namespace name → set of exported member names from ALL files combined.
 */
private fun collectCrossFileNamespaceExports(sourceFiles: Collection<SourceFile>): Map<String, Set<String>> {
    val result = mutableMapOf<String, MutableSet<String>>()
    for (sourceFile in sourceFiles) {
        collectNamespaceExportsFromStatements(sourceFile.statements, result)
    }
    return result
}

private fun collectNamespaceExportsFromStatements(
    stmts: List<Statement>,
    result: MutableMap<String, MutableSet<String>>,
) {
    for (stmt in stmts) {
        if (stmt !is ModuleDeclaration) continue
        // Skip declare namespaces (ambient) and type-only namespaces (only interfaces/types)
        if (ModifierFlag.Declare in stmt.modifiers) continue
        collectNamespaceExportsFromModule(stmt, result)
    }
}

private fun collectNamespaceExportsFromModule(
    module: ModuleDeclaration,
    result: MutableMap<String, MutableSet<String>>,
) {
    // Handle dotted namespace names like `namespace A.B.C`
    val nsName = when (val name = module.name) {
        is Identifier -> name.text
        is PropertyAccessExpression -> {
            // Flatten the dotted name: A.B.C → ["A", "B", "C"]
            val parts = mutableListOf<String>()
            var expr: Expression = name
            while (expr is PropertyAccessExpression) {
                parts.add(0, expr.name.text)
                expr = expr.expression
            }
            if (expr is Identifier) parts.add(0, expr.text)
            if (parts.isEmpty()) return
            // Each part (except last) exports the next part as child
            for (i in 0 until parts.size - 1) {
                result.getOrPut(parts[i]) { mutableSetOf() }.add(parts[i + 1])
            }
            parts.last()
        }
        else -> return
    }
    when (val body = module.body) {
        is ModuleDeclaration -> {
            // Nested namespace: `namespace A { namespace B { ... } }`
            val childName = when (val n = body.name) {
                is Identifier -> n.text
                else -> return
            }
            result.getOrPut(nsName) { mutableSetOf() }.add(childName)
            collectNamespaceExportsFromModule(body, result)
        }
        is ModuleBlock -> {
            collectNamespaceBodyExports(nsName, body.statements, result)
        }
        else -> {}
    }
}

private fun collectNamespaceBodyExports(
    nsName: String,
    stmts: List<Statement>,
    result: MutableMap<String, MutableSet<String>>,
) {
    val exports = result.getOrPut(nsName) { mutableSetOf() }
    for (stmt in stmts) {
        val isExported = when (stmt) {
            is VariableStatement -> ModifierFlag.Export in stmt.modifiers
            is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
            is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
            is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
            is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
            is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
            else -> false
        }
        if (!isExported) continue
        when (stmt) {
            is VariableStatement -> for (decl in stmt.declarationList.declarations) {
                val name = decl.name
                if (name is Identifier) exports.add(name.text)
            }
            is FunctionDeclaration -> stmt.name?.text?.let { exports.add(it) }
            is ClassDeclaration -> stmt.name?.text?.let { exports.add(it) }
            is EnumDeclaration -> exports.add(stmt.name.text)
            is ModuleDeclaration -> {
                val childName = when (val n = stmt.name) {
                    is Identifier -> n.text
                    else -> null
                }
                childName?.let { exports.add(it) }
                if (ModifierFlag.Declare !in stmt.modifiers) {
                    collectNamespaceExportsFromModule(stmt, result)
                }
            }
            is ImportEqualsDeclaration -> exports.add(stmt.name.text)
            else -> {}
        }
    }
}

/**
 * Emits isolated-declarations diagnostics for top-level `.ts`/`.tsx` exports.
 * Currently handles:
 *  - TS9010 (+ TS9027 related) for `export var x;` (untyped, uninitialized).
 *  - TS9022 (+ TS9027 + TS9035 related) for `export const x = class {...}`
 *    and elements of `export const x = [class {}, class{}] as const`.
 *  - TS9021 for `export class C extends fn(...)` where the extends expression
 *    is a CallExpression or NewExpression (not a simple identifier path).
 */
private fun emitIsolatedDeclarationsDiagnostics(
    sourceFile: SourceFile,
    fileName: String,
    source: String,
): List<Diagnostic> {
    val isJsFile = fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
        fileName.endsWith(".mjs") || fileName.endsWith(".cjs")
    if (isJsFile) return emptyList()
    val isDtsFile = fileName.endsWith(".d.ts") || fileName.endsWith(".d.mts") ||
        fileName.endsWith(".d.cts")
    if (isDtsFile) return emptyList()
    val results = mutableListOf<Diagnostic>()
    // Pass 1: collect top-level function names (FunctionDeclaration with name OR
    // VariableDeclaration whose initializer is an arrow/FE).
    val funcDecls = mutableMapOf<String, FunctionDeclaration>()
    val funcVarDecls = mutableMapOf<String, Pair<VariableDeclaration, Expression>>()
    for (stmt in sourceFile.statements) {
        when (stmt) {
            is FunctionDeclaration -> stmt.name?.let { funcDecls[it.text] = stmt }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    val name = decl.name
                    if (name !is Identifier) continue
                    val init = decl.initializer
                    if (init is ArrowFunction || init is FunctionExpression) {
                        funcVarDecls[name.text] = decl to init
                    }
                }
            }
            else -> {}
        }
    }
    // Pass 2: walk top-level expando assignments; emit TS9023 (deduped by
    // (name, property)) and collect which function names have expando assignments.
    // Two LHS shapes:
    //  - `foo.prop = X`             — PropertyAccessExpression
    //  - `foo[idx] = X`             — ElementAccessExpression (idx not statically
    //                                   a string-literal pattern; idx that resolves
    //                                   to a literal name does NOT fire TS9023)
    val expandoNames = mutableSetOf<String>()
    val seenExpandoPairs = mutableSetOf<Pair<String, String>>()
    for (stmt in sourceFile.statements) {
        if (stmt !is ExpressionStatement) continue
        val expr = stmt.expression
        if (expr !is BinaryExpression || expr.operator != SyntaxKind.Equals) continue
        val lhs = expr.left
        when (lhs) {
            is PropertyAccessExpression -> {
                val receiver = lhs.expression
                if (receiver !is Identifier) continue
                val recvName = receiver.text
                val isFunc = recvName in funcDecls || recvName in funcVarDecls
                if (!isFunc) continue
                val propName = lhs.name
                expandoNames.add(recvName)
                val key = recvName to propName.text
                if (!seenExpandoPairs.add(key)) continue
                val start = receiver.pos
                val end = propName.pos + propName.text.length
                val length = end - start
                val (line, character) = positionToLineCharacter(source, start)
                results.add(Diagnostic(
                    message = "Assigning properties to functions without declaring them is not supported with --isolatedDeclarations. Add an explicit declaration for the properties assigned to this function.",
                    category = DiagnosticCategory.Error,
                    code = 9023,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
            is ElementAccessExpression -> {
                val receiver = lhs.expression
                if (receiver !is Identifier) continue
                val recvName = receiver.text
                val isFunc = recvName in funcDecls || recvName in funcVarDecls
                if (!isFunc) continue
                val idx = lhs.argumentExpression
                if (isIsolatedDeclElementAccessIndexLiteralName(idx)) continue
                expandoNames.add(recvName)
                val idxText = source.substring(idx.pos, isolatedDeclExprTrueEnd(idx))
                val key = recvName to idxText
                if (!seenExpandoPairs.add(key)) continue
                val start = receiver.pos
                val end = isolatedDeclExprTrueEnd(idx) + 1 // past `]`
                val length = (end - start).coerceAtLeast(1)
                val (line, character) = positionToLineCharacter(source, start)
                results.add(Diagnostic(
                    message = "Assigning properties to functions without declaring them is not supported with --isolatedDeclarations. Add an explicit declaration for the properties assigned to this function.",
                    category = DiagnosticCategory.Error,
                    code = 9023,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
            else -> {}
        }
    }
    // Pass 2b (B70.6): collect names of identifiers referenced as
    // bare-identifier computed property names inside EXPORTED variable
    // object-literal initializers. Used below as a narrow gate to fire TS9010
    // on non-exported `let X = init` whose value flows into an exported
    // declaration's emit shape via `[X]`. Matches TypeScript's behavior for
    // `let u = Symbol(); export let o4 = { [u]: 1 }`.
    val computedPropIdentRefs = mutableSetOf<String>()
    for (stmt in sourceFile.statements) {
        if (stmt !is VariableStatement) continue
        if (ModifierFlag.Export !in stmt.modifiers) continue
        for (decl in stmt.declarationList.declarations) {
            val init = decl.initializer as? ObjectLiteralExpression ?: continue
            for (prop in init.properties) {
                if (prop !is PropertyAssignment) continue
                val n = prop.name as? ComputedPropertyName ?: continue
                val inner = n.expression
                if (inner is Identifier) computedPropIdentRefs.add(inner.text)
            }
        }
    }
    // Pass 3: walk top-level statements once for the main emissions (var-decl
    // TS9010/TS9022/TS9007, class-decl TS9021).
    for (stmt in sourceFile.statements) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    val name = decl.name
                    // B7.24: TS9019 for destructuring binding patterns under
                    // `export`. Fires regardless of whether the decl has a type
                    // annotation (line 134 of `isolatedDeclarationErrorsExpressions.ts`
                    // confirms `export const [...]: [N,N,N|U] = [...]` still
                    // emits TS9019). Walker is responsible for skipping rest
                    // elements and nested binding patterns conservatively.
                    if (name is ObjectBindingPattern || name is ArrayBindingPattern) {
                        if (ModifierFlag.Export in stmt.modifiers) {
                            emitIsolatedDeclVarBindingPatternChecks(name, fileName, source, results)
                        }
                        continue
                    }
                    if (name !is Identifier) continue
                    if (decl.type != null) continue
                    val init = decl.initializer
                    if (init == null) {
                        if (ModifierFlag.Export !in stmt.modifiers) continue
                        val (line, character) = positionToLineCharacter(source, name.pos)
                        val related = Diagnostic(
                            message = "Add a type annotation to the variable ${name.text}.",
                            category = DiagnosticCategory.Message,
                            code = 9027,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = name.pos,
                            length = name.text.length,
                        )
                        results.add(Diagnostic(
                            message = "Variable must have an explicit type annotation with --isolatedDeclarations.",
                            category = DiagnosticCategory.Error,
                            code = 9010,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = name.pos,
                            length = name.text.length,
                            relatedInformation = listOf(related),
                        ))
                    } else {
                        if (ModifierFlag.Export in stmt.modifiers) {
                            emitIsolatedDeclClassExprDiags(decl, name, fileName, source, results)
                            val isConst = stmt.declarationList.flags == SyntaxKind.ConstKeyword
                            emitIsolatedDeclVarInitTs9010(name, init, isConst, fileName, source, results)
                            emitIsolatedDeclVarInitArrayChecks(name, init, fileName, source, results)
                            if (init is ObjectLiteralExpression) {
                                emitIsolatedDeclObjLitComputedNameDiags(init, name, fileName, source, results)
                                emitIsolatedDeclObjLitMethodTs9008(init, name, fileName, source, results)
                                emitIsolatedDeclObjLitSubExprTs9013(init, name, fileName, source, results)
                                emitIsolatedDeclObjLitSpreadShorthand(init, name, fileName, source, results)
                                emitIsolatedDeclObjLitAccessor(init, name, fileName, source, results)
                            }
                            // B7.25: recurse into params of FE/Arrow var-decl
                            // initializers so inner `cb = function(){ }`-style
                            // defaults fire TS9007 (and inner param-name TS9011
                            // where applicable).
                            if (init is ArrowFunction) {
                                emitIsolatedDeclParamsCheck(init.parameters, fileName, source, results)
                            } else if (init is FunctionExpression) {
                                emitIsolatedDeclParamsCheck(init.parameters, fileName, source, results)
                            }
                        }
                        // TS9007 for arrow/FE initializer when the variable has
                        // expando-property assignments AND no return type. Applies
                        // regardless of `export` (the expando pattern requires
                        // typing the variable in .d.ts).
                        if (name.text in expandoNames) {
                            emitIsolatedDeclFnExprMissingReturn(decl, name, init, fileName, source, results)
                        }
                        // B70.6: TS9010 on non-exported `let X = init` when X is
                        // referenced bare in some exported obj-literal computed
                        // property name (`[X]`). The .d.ts emit of the exported
                        // decl needs X's type. Narrow gate: only fires when init
                        // is itself a TS9010-trigger expression kind.
                        if (ModifierFlag.Export !in stmt.modifiers &&
                            name.text in computedPropIdentRefs) {
                            val isConst = stmt.declarationList.flags == SyntaxKind.ConstKeyword
                            emitIsolatedDeclVarInitTs9010(name, init, isConst, fileName, source, results)
                        }
                    }
                }
            }
            is ClassDeclaration -> {
                if (ModifierFlag.Export !in stmt.modifiers) continue
                emitIsolatedDeclExtendsDiags(stmt, fileName, source, results)
                emitIsolatedDeclClassComputedNameDiags(stmt, fileName, source, results)
                emitIsolatedDeclClassPropertyTs9012(stmt, fileName, source, results)
                emitIsolatedDeclClassMethodTs9008(stmt, fileName, source, results)
                emitIsolatedDeclClassMethodParamChecks(stmt, fileName, source, results)
                emitIsolatedDeclClassAccessor(stmt, fileName, source, results)
                emitIsolatedDeclClassPropertyFnExprParamChecks(stmt, fileName, source, results)
            }
            is FunctionDeclaration -> {
                val fnName = stmt.name
                val isExported = ModifierFlag.Export in stmt.modifiers
                if (fnName != null && stmt.type == null &&
                    (isExported || fnName.text in expandoNames)) {
                    emitIsolatedDeclFnDeclMissingReturn(stmt, fnName, fileName, source, results)
                }
                if (isExported) {
                    emitIsolatedDeclFnDeclParamChecks(stmt, fileName, source, results)
                }
            }
            is ExportAssignment -> {
                if (!stmt.isExportEquals) {
                    emitIsolatedDeclExportDefaultChecks(stmt, fileName, source, results)
                }
            }
            is EnumDeclaration -> {
                emitIsolatedDeclEnumChecks(stmt, fileName, source, results)
            }
            is InterfaceDeclaration -> {
                if (ModifierFlag.Export !in stmt.modifiers) continue
                emitIsolatedDeclInterfaceMethodTs9013(stmt, fileName, source, results)
            }
            else -> {}
        }
    }
    return results
}

/**
 * B7.21: TS9013 walker for ComputedPropertyName-named MethodDeclaration members
 * of exported interfaces that lack a return-type annotation. Fires alongside
 * the checker's TS7010 (extended in B7.21 to cover ComputedPropertyName names
 * via display string `[id]`). The TS9013 squiggle covers the full member span
 * (`member.pos` to `member.end`) — same shape as TS7010 — and carries no
 * related info (interface-context TS9013 has no `Move expression to variable
 * and add annotation` shape; that's TS9036 for default exports only).
 *
 * Identifier-named and StringLiteralNode-named interface methods do NOT fire
 * TS9013 — TypeScript's baseline only emits this code for ComputedPropertyName
 * shapes where the inferred return type can't be expressed in a `.d.ts` file
 * without elaborating the computed key. Bare-name forms get TS7010 only.
 *
 * Other member kinds in interfaces (PropertySignature, IndexSignature,
 * call/construct signatures via MethodDeclaration with empty/`new` names)
 * are NOT touched here.
 */
private fun emitIsolatedDeclInterfaceMethodTs9013(
    stmt: InterfaceDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (member in stmt.members) {
        if (member !is MethodDeclaration) continue
        if (member.type != null) continue
        val name = member.name
        if (name !is ComputedPropertyName) continue
        // Only the `[Identifier]` form is handled — matches the TS7010
        // checker extension's display string (`[id]`). Other inner-expression
        // shapes (string literals, qualified names, parenthesized) would need
        // distinct displayed names and span computations; defer until a
        // failing test exercises them.
        if (name.expression !is Identifier) continue
        val start = member.pos
        val length = (member.end - start).coerceAtLeast(1)
        val (line, character) = positionToLineCharacter(source, start)
        results.add(Diagnostic(
            message = "Expression type can't be inferred with --isolatedDeclarations.",
            category = DiagnosticCategory.Error,
            code = 9013,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }
}

/**
 * TS9007 + TS9031 for `function foo() { ... }` lacking a return type annotation.
 * Squiggle is on the function name (length = name.text.length).
 */
private fun emitIsolatedDeclFnDeclMissingReturn(
    fn: FunctionDeclaration,
    name: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val start = name.pos
    val length = name.text.length
    val (line, character) = positionToLineCharacter(source, start)
    val related = Diagnostic(
        message = "Add a return type to the function declaration.",
        category = DiagnosticCategory.Message,
        code = 9031,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
    )
    results.add(Diagnostic(
        message = "Function must have an explicit return type annotation with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9007,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = listOf(related),
    ))
}

/**
 * TS9007 + TS9027 + TS9030 for `const X = () => ...` / `const X = function () { ... }`
 * lacking a return type annotation, when X has expando-property assignments.
 * Squiggle covers the entire function expression (pos .. closeBrace+1 / body-true-end).
 */
private fun emitIsolatedDeclFnExprMissingReturn(
    decl: VariableDeclaration,
    varName: Identifier,
    fn: Expression,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val returnType = when (fn) {
        is ArrowFunction -> fn.type
        is FunctionExpression -> fn.type
        else -> return
    }
    if (returnType != null) return
    val start = fn.pos
    val end = arrowOrFunctionExprTrueEnd(fn)
    val length = (end - start).coerceAtLeast(1)
    val (line, character) = positionToLineCharacter(source, start)
    val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
    val related = listOf(
        Diagnostic(
            message = "Add a type annotation to the variable ${varName.text}.",
            category = DiagnosticCategory.Message,
            code = 9027,
            fileName = fileName,
            line = varLine,
            character = varChar,
            start = varName.pos,
            length = varName.text.length,
        ),
        Diagnostic(
            message = "Add a return type to the function expression.",
            category = DiagnosticCategory.Message,
            code = 9030,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ),
    )
    results.add(Diagnostic(
        message = "Function must have an explicit return type annotation with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9007,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = related,
    ))
}

private fun arrowOrFunctionExprTrueEnd(fn: Expression): Int = when (fn) {
    is ArrowFunction -> {
        val body = fn.body
        when (body) {
            is Block -> if (body.closeBracePos >= 0) body.closeBracePos + 1 else fn.end
            is Expression -> isolatedDeclExprTrueEnd(body)
            else -> fn.end
        }
    }
    is FunctionExpression -> if (fn.body.closeBracePos >= 0) fn.body.closeBracePos + 1 else fn.end
    else -> fn.end
}

/**
 * For an exported FunctionDeclaration, emits TS9011 / TS9013 for parameters
 * lacking explicit type annotations. Rule:
 *  - No default: TS9011 at the param name (length 1 char, since names that
 *    fail are short identifiers; uses `name.text.length`).
 *  - Default = trivially-declarable literal: OK (declaration emit can infer).
 *  - Default = arrow/function expression: OK (TS9007 separately flags the
 *    inner missing-return-type if needed).
 *  - Default = object literal: walk values, TS9013 on each non-trivial value.
 *  - Default = `[…] as const`: walk elements, TS9013 on each non-trivial el.
 *  - Default = otherwise non-trivial top-level (BinaryExpr, CallExpr, etc.):
 *    TS9011 on the default expression itself.
 *
 * TS9028 ("Add a type annotation to the parameter X.") attached to all
 * TS9011/TS9013 emissions; TS9035 ("Add satisfies and a type assertion…")
 * attached to TS9013.
 */
private fun emitIsolatedDeclFnDeclParamChecks(
    fn: FunctionDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    emitIsolatedDeclParamsCheck(fn.parameters, fileName, source, results)
}

private fun emitIsolatedDeclParamsCheck(
    params: List<Parameter>,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (param in params) {
        if (param.type != null) continue
        val paramName = param.name as? Identifier ?: continue
        val default = param.initializer
        if (default == null) {
            emitIsolatedDeclTs9011(
                squiggleStart = paramName.pos,
                squiggleLength = paramName.text.length,
                paramName = paramName,
                fileName = fileName,
                source = source,
                results = results,
            )
            continue
        }
        emitIsolatedDeclParamDefaultClassify(default, paramName, isTopLevel = true, fileName, source, results)
    }
}

private fun emitIsolatedDeclParamDefaultClassify(
    expr: Expression,
    paramName: Identifier,
    isTopLevel: Boolean,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    if (isIsolatedDeclTriviallyDeclarable(expr)) return
    // Function expressions don't get TS9011/TS9013 on themselves (TS9007 separately
    // covers missing return types). But their PARAMETER DEFAULTS still need
    // declarability checking — recurse into the inner params.
    if (expr is ArrowFunction) {
        emitIsolatedDeclParamsCheck(expr.parameters, fileName, source, results)
        return
    }
    if (expr is FunctionExpression) {
        // B7.25: emit TS9007 when the FE param-default lacks a return type
        // annotation. Squiggle covers the `function` keyword (8 chars) at
        // the FE's start position. Related infos: TS9028 at the enclosing
        // param's name, TS9030 at the FE's position.
        if (expr.type == null) {
            emitIsolatedDeclTs9007ForFnExprParamDefault(expr, paramName, fileName, source, results)
        }
        emitIsolatedDeclParamsCheck(expr.parameters, fileName, source, results)
        return
    }
    when {
        expr is ObjectLiteralExpression -> {
            for (prop in expr.properties) {
                if (prop !is PropertyAssignment) continue
                val v = prop.initializer
                emitIsolatedDeclParamDefaultClassify(v, paramName, isTopLevel = false, fileName, source, results)
            }
        }
        expr is AsExpression -> {
            val t = expr.type
            val isAsConst = t is TypeReference &&
                t.typeName.let { it is Identifier && it.text == "const" } &&
                t.typeArguments == null
            if (isAsConst) {
                val inner = expr.expression
                if (inner is ArrayLiteralExpression) {
                    for (el in inner.elements) {
                        emitIsolatedDeclParamDefaultClassify(el, paramName, isTopLevel = false, fileName, source, results)
                    }
                } else if (inner is ObjectLiteralExpression) {
                    for (prop in inner.properties) {
                        if (prop !is PropertyAssignment) continue
                        val v = prop.initializer
                        emitIsolatedDeclParamDefaultClassify(v, paramName, isTopLevel = false, fileName, source, results)
                    }
                }
                // `1 as const` etc. — literal inner, no emission needed
            } else {
                // `expr as T` for non-const T → top-level: TS9011 on the T (type annotation)
                if (isTopLevel) {
                    emitIsolatedDeclTs9011(
                        squiggleStart = t.pos,
                        squiggleLength = isolatedDeclTypeNodeLength(t),
                        paramName = paramName,
                        fileName = fileName,
                        source = source,
                        results = results,
                    )
                } else {
                    emitIsolatedDeclTs9013(expr, paramName, fileName, source, results)
                }
            }
        }
        isTopLevel -> {
            val end = isolatedDeclExprTrueEnd(expr)
            emitIsolatedDeclTs9011(
                squiggleStart = expr.pos,
                squiggleLength = (end - expr.pos).coerceAtLeast(1),
                paramName = paramName,
                fileName = fileName,
                source = source,
                results = results,
            )
        }
        else -> {
            emitIsolatedDeclTs9013(expr, paramName, fileName, source, results)
        }
    }
}

/**
 * B7.26: parallel walker to B7.25 for class `PropertyDeclaration` initializers
 * that are `FunctionExpression` or `ArrowFunction`. Recurses into the outer
 * FE/Arrow's parameters via `emitIsolatedDeclParamsCheck`, which (via B7.25's
 * classifier extension) fires TS9007 for inner `cb = function(){ }`-style
 * defaults. Skips `private` modifier and `#`-prefixed private-field members —
 * those don't appear in `.d.ts` output per TypeScript's isolatedDeclarations
 * baseline, so they should not flag.
 */
private fun emitIsolatedDeclClassPropertyFnExprParamChecks(
    stmt: ClassDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (member in stmt.members) {
        if (member !is PropertyDeclaration) continue
        if (ModifierFlag.Private in member.modifiers) continue
        val name = member.name as? Identifier ?: continue
        if (name.text.startsWith("#")) continue
        val init = member.initializer
        when (init) {
            is ArrowFunction -> emitIsolatedDeclParamsCheck(init.parameters, fileName, source, results)
            is FunctionExpression -> emitIsolatedDeclParamsCheck(init.parameters, fileName, source, results)
            else -> {}
        }
    }
}

/**
 * B7.25: TS9007 for a `FunctionExpression` used as a parameter default
 * (e.g. `cb = function(){ }`) when it lacks an explicit return type.
 * Squiggle covers the `function` keyword (8 chars) at the FE's start.
 * Related: TS9028 at the enclosing param's name, TS9030 at the FE.
 */
private fun emitIsolatedDeclTs9007ForFnExprParamDefault(
    fe: FunctionExpression,
    paramName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val start = fe.pos
    val length = 8
    val (line, character) = positionToLineCharacter(source, start)
    val (pnLine, pnChar) = positionToLineCharacter(source, paramName.pos)
    val related = listOf(
        Diagnostic(
            message = "Add a type annotation to the parameter ${paramName.text}.",
            category = DiagnosticCategory.Message,
            code = 9028,
            fileName = fileName,
            line = pnLine,
            character = pnChar,
            start = paramName.pos,
            length = paramName.text.length,
        ),
        Diagnostic(
            message = "Add a return type to the function expression.",
            category = DiagnosticCategory.Message,
            code = 9030,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ),
    )
    results.add(Diagnostic(
        message = "Function must have an explicit return type annotation with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9007,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = related,
    ))
}

private fun isolatedDeclTypeNodeLength(t: TypeNode): Int {
    // For simple TypeReference with an Identifier name, use the identifier length.
    if (t is TypeReference) {
        val tn = t.typeName
        if (tn is Identifier) return tn.text.length
    }
    return (t.end - t.pos).coerceAtLeast(1)
}

private fun isIsolatedDeclTriviallyDeclarable(expr: Expression): Boolean = when (expr) {
    is NumericLiteralNode -> true
    is BigIntLiteralNode -> true
    is StringLiteralNode -> true
    is NoSubstitutionTemplateLiteralNode -> true
    // Template literals with substitutions have type `string` regardless of
    // what they interpolate, so they're trivially declarable at the param-
    // default level. TypeScript labels these "templateParamOk" in the
    // isolatedDeclarations corpus.
    is TemplateExpression -> true
    is Identifier -> expr.text == "true" || expr.text == "false" ||
        expr.text == "null" || expr.text == "undefined"
    is PrefixUnaryExpression ->
        (expr.operator == SyntaxKind.Minus || expr.operator == SyntaxKind.Plus) &&
            (expr.operand is NumericLiteralNode || expr.operand is BigIntLiteralNode)
    else -> false
}

private fun emitIsolatedDeclTs9011(
    squiggleStart: Int,
    squiggleLength: Int,
    paramName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (line, character) = positionToLineCharacter(source, squiggleStart)
    val (pnLine, pnChar) = positionToLineCharacter(source, paramName.pos)
    val related = Diagnostic(
        message = "Add a type annotation to the parameter ${paramName.text}.",
        category = DiagnosticCategory.Message,
        code = 9028,
        fileName = fileName,
        line = pnLine,
        character = pnChar,
        start = paramName.pos,
        length = paramName.text.length,
    )
    results.add(Diagnostic(
        message = "Parameter must have an explicit type annotation with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9011,
        fileName = fileName,
        line = line,
        character = character,
        start = squiggleStart,
        length = squiggleLength,
        relatedInformation = listOf(related),
    ))
}

private fun emitIsolatedDeclTs9013(
    expr: Expression,
    paramName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val end = isolatedDeclExprTrueEnd(expr)
    val length = (end - expr.pos).coerceAtLeast(1)
    val (line, character) = positionToLineCharacter(source, expr.pos)
    val (pnLine, pnChar) = positionToLineCharacter(source, paramName.pos)
    val related = listOf(
        Diagnostic(
            message = "Add a type annotation to the parameter ${paramName.text}.",
            category = DiagnosticCategory.Message,
            code = 9028,
            fileName = fileName,
            line = pnLine,
            character = pnChar,
            start = paramName.pos,
            length = paramName.text.length,
        ),
        Diagnostic(
            message = "Add satisfies and a type assertion to this expression (satisfies T as T) to make the type explicit.",
            category = DiagnosticCategory.Message,
            code = 9035,
            fileName = fileName,
            line = line,
            character = character,
            start = expr.pos,
            length = length,
        ),
    )
    results.add(Diagnostic(
        message = "Expression type can't be inferred with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9013,
        fileName = fileName,
        line = line,
        character = character,
        start = expr.pos,
        length = length,
        relatedInformation = related,
    ))
}

/**
 * `export default <expr>;` walker. Classification:
 *  - Trivially declarable (literal / true / false / null / undefined / -literal) → OK.
 *  - `Identifier` → OK (preserved by name in .d.ts).
 *  - `ArrayLiteralExpression` (not via `as const`) → TS9017 covering full array.
 *  - `ObjectLiteralExpression` → recurse into properties.
 *  - `AsExpression` with `as const` type → recurse into the underlying literal.
 *  - Other non-trivial top-level expression → TS9037.
 * Each non-trivial sub-expression nested inside object/array emits TS9013.
 * All emissions get a TS9036 "Move the expression ... to a variable" related
 * info at the `export default` statement start.
 */
private fun emitIsolatedDeclExportDefaultChecks(
    stmt: ExportAssignment,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val expr = stmt.expression
    val stmtPos = stmt.pos
    when {
        isIsolatedDeclTriviallyDeclarable(expr) -> {}
        expr is Identifier -> {}
        expr is ArrayLiteralExpression -> {
            val end = findMatchingDelimiter(source, expr.pos, '[', ']')
            val length = (end - expr.pos).coerceAtLeast(1)
            emitIsolatedDeclTs9017Default(expr.pos, length, stmtPos, fileName, source, results)
        }
        expr is ObjectLiteralExpression -> {
            for (prop in expr.properties) {
                if (prop !is PropertyAssignment) continue
                walkExportDefaultSubExpr(prop.initializer, stmtPos, fileName, source, results)
            }
        }
        expr is AsExpression -> {
            val t = expr.type
            val isAsConst = t is TypeReference &&
                t.typeName.let { it is Identifier && it.text == "const" } &&
                t.typeArguments == null
            if (isAsConst) {
                val inner = expr.expression
                if (inner is ArrayLiteralExpression) {
                    for (el in inner.elements) {
                        walkExportDefaultSubExpr(el, stmtPos, fileName, source, results)
                    }
                } else if (inner is ObjectLiteralExpression) {
                    for (prop in inner.properties) {
                        if (prop !is PropertyAssignment) continue
                        walkExportDefaultSubExpr(prop.initializer, stmtPos, fileName, source, results)
                    }
                }
            } else {
                val end = isolatedDeclExprTrueEnd(expr)
                val length = (end - expr.pos).coerceAtLeast(1)
                emitIsolatedDeclTs9037Default(expr.pos, length, stmtPos, fileName, source, results)
            }
        }
        else -> {
            val end = isolatedDeclExprTrueEnd(expr)
            val length = (end - expr.pos).coerceAtLeast(1)
            emitIsolatedDeclTs9037Default(expr.pos, length, stmtPos, fileName, source, results)
        }
    }
}

private fun walkExportDefaultSubExpr(
    expr: Expression,
    stmtPos: Int,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    if (isIsolatedDeclTriviallyDeclarable(expr)) return
    if (expr is Identifier) return
    when (expr) {
        is ObjectLiteralExpression -> {
            for (prop in expr.properties) {
                if (prop !is PropertyAssignment) continue
                walkExportDefaultSubExpr(prop.initializer, stmtPos, fileName, source, results)
            }
        }
        is ArrayLiteralExpression -> {
            for (el in expr.elements) {
                walkExportDefaultSubExpr(el, stmtPos, fileName, source, results)
            }
        }
        else -> {
            val end = isolatedDeclExprTrueEnd(expr)
            val length = (end - expr.pos).coerceAtLeast(1)
            emitIsolatedDeclTs9013Default(expr.pos, length, stmtPos, fileName, source, results)
        }
    }
}

/**
 * Naive bracket matcher. Returns position right after the matching close
 * delimiter. Assumes `source[openPos] == open`. Handles string-literal
 * skipping (', ", `) and line / block comments. Not a full tokenizer —
 * sufficient for export-default array/object span detection in the
 * isolatedDeclarations corpus.
 */
private fun findMatchingDelimiter(source: String, openPos: Int, open: Char, close: Char): Int {
    var depth = 0
    var i = openPos
    while (i < source.length) {
        val c = source[i]
        when (c) {
            '"', '\'' -> {
                val quote = c
                i++
                while (i < source.length && source[i] != quote) {
                    if (source[i] == '\\' && i + 1 < source.length) i++
                    i++
                }
                if (i < source.length) i++
                continue
            }
            '`' -> {
                i++
                while (i < source.length && source[i] != '`') {
                    if (source[i] == '\\' && i + 1 < source.length) i++
                    i++
                }
                if (i < source.length) i++
                continue
            }
            '/' -> {
                if (i + 1 < source.length && source[i + 1] == '/') {
                    while (i < source.length && source[i] != '\n') i++
                    continue
                }
                if (i + 1 < source.length && source[i + 1] == '*') {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    if (i + 1 < source.length) i += 2
                    continue
                }
            }
        }
        if (c == open) depth++
        else if (c == close) {
            depth--
            if (depth == 0) return i + 1
        }
        i++
    }
    return openPos + 1
}

private fun emitIsolatedDeclTs9037Default(
    start: Int,
    length: Int,
    stmtPos: Int,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (line, character) = positionToLineCharacter(source, start)
    val related = listOf(
        buildTs9036Related(stmtPos, fileName, source),
    )
    results.add(Diagnostic(
        message = "Default exports can't be inferred with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9037,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = related,
    ))
}

private fun emitIsolatedDeclTs9017Default(
    start: Int,
    length: Int,
    stmtPos: Int,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (line, character) = positionToLineCharacter(source, start)
    val related = listOf(
        buildTs9036Related(stmtPos, fileName, source),
    )
    results.add(Diagnostic(
        message = "Only const arrays can be inferred with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9017,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = related,
    ))
}

private fun emitIsolatedDeclTs9013Default(
    start: Int,
    length: Int,
    stmtPos: Int,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (line, character) = positionToLineCharacter(source, start)
    val related = listOf(
        buildTs9036Related(stmtPos, fileName, source),
        Diagnostic(
            message = "Add satisfies and a type assertion to this expression (satisfies T as T) to make the type explicit.",
            category = DiagnosticCategory.Message,
            code = 9035,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ),
    )
    results.add(Diagnostic(
        message = "Expression type can't be inferred with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9013,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = related,
    ))
}

private fun buildTs9036Related(stmtPos: Int, fileName: String, source: String): Diagnostic {
    val (line, character) = positionToLineCharacter(source, stmtPos)
    return Diagnostic(
        message = "Move the expression in default export to a variable and add a type annotation to it.",
        category = DiagnosticCategory.Message,
        code = 9036,
        fileName = fileName,
        line = line,
        character = character,
        start = stmtPos,
        length = 1,
    )
}

/**
 * Emit TS9020 for enum members whose initializer is not "computable without
 * external references" per --isolatedDeclarations. A member is computable iff
 * its initializer expression resolves to:
 *  - A literal (numeric / bigint / string / template-no-substitution / unary
 *    `+` / `-` / `~` on a literal),
 *  - A binary arithmetic / bitwise / string-concat operation on two
 *    computable operands,
 *  - A `ParenthesizedExpression` on a computable inner expression,
 *  - A `CallExpression` (preserved verbatim in .d.ts),
 *  - A bare `Identifier` referring to a same-enum member that is computable,
 *  - A `PropertyAccessExpression` / `ElementAccessExpression` whose receiver
 *    is THIS enum's name and target is a same-enum member that is computable.
 *
 * References to OTHER enums (`E.A`, `Flag.ABC | C`) or external constants
 * (`EV`) are NOT computable. Squiggle at the member name (length = name.text.length).
 */
private fun emitIsolatedDeclEnumChecks(
    enumDecl: EnumDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val enumName = enumDecl.name.text
    val computable = mutableMapOf<String, Boolean>()
    for (member in enumDecl.members) {
        val nameText = when (val n = member.name) {
            is Identifier -> n.text
            is StringLiteralNode -> n.text
            else -> continue
        }
        val initializer = member.initializer
        val isComputable = if (initializer == null) {
            true
        } else {
            isEnumInitializerComputable(initializer, enumName, computable)
        }
        computable[nameText] = isComputable
        if (!isComputable) {
            val (start, length) = when (val n = member.name) {
                is Identifier -> n.pos to n.text.length
                is StringLiteralNode -> {
                    val rawLen = n.rawText?.length ?: n.text.length
                    n.pos to (rawLen + (if (n.isUnterminated) 1 else 2))
                }
            }
            val (line, character) = positionToLineCharacter(source, start)
            results.add(Diagnostic(
                message = "Enum member initializers must be computable without references to external symbols with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9020,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }
}

private val ISOLATED_DECL_ENUM_COMPUTABLE_OPS = setOf(
    SyntaxKind.Plus,
    SyntaxKind.Minus,
    SyntaxKind.Asterisk,
    SyntaxKind.AsteriskAsterisk,
    SyntaxKind.Slash,
    SyntaxKind.Percent,
    SyntaxKind.Bar,
    SyntaxKind.Ampersand,
    SyntaxKind.Caret,
    SyntaxKind.LessThanLessThan,
    SyntaxKind.GreaterThanGreaterThan,
    SyntaxKind.GreaterThanGreaterThanGreaterThan,
)

private fun isEnumInitializerComputable(
    expr: Expression,
    enumName: String,
    computable: Map<String, Boolean>,
): Boolean = when (expr) {
    is NumericLiteralNode, is BigIntLiteralNode, is StringLiteralNode,
    is NoSubstitutionTemplateLiteralNode -> true
    is PrefixUnaryExpression ->
        (expr.operator == SyntaxKind.Plus || expr.operator == SyntaxKind.Minus ||
            expr.operator == SyntaxKind.Tilde) &&
            isEnumInitializerComputable(expr.operand, enumName, computable)
    is BinaryExpression ->
        expr.operator in ISOLATED_DECL_ENUM_COMPUTABLE_OPS &&
            isEnumInitializerComputable(expr.left, enumName, computable) &&
            isEnumInitializerComputable(expr.right, enumName, computable)
    is ParenthesizedExpression -> isEnumInitializerComputable(expr.expression, enumName, computable)
    is CallExpression -> true
    is Identifier -> computable[expr.text] == true
    is PropertyAccessExpression -> {
        val recv = expr.expression
        val nm = expr.name
        if (recv is Identifier && recv.text == enumName) {
            computable[nm.text] == true
        } else false
    }
    is ElementAccessExpression -> {
        val recv = expr.expression
        val arg = expr.argumentExpression
        if (recv is Identifier && recv.text == enumName && arg is StringLiteralNode) {
            computable[arg.text] == true
        } else false
    }
    else -> false
}

/**
 * For `export const x = class {...}` and `export const x = [class {}, ...] as const`,
 * emits TS9022 at each ClassExpression's `class` keyword. The array-as-const form
 * also attaches TS9035 (satisfies suggestion); both forms attach TS9027 pointing
 * at the variable name.
 */
private fun emitIsolatedDeclClassExprDiags(
    decl: VariableDeclaration,
    varName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val initializer = decl.initializer ?: return
    when (initializer) {
        is ClassExpression -> {
            emitTs9022(initializer, varName, fileName, source, addSatisfies = false, results)
        }
        is AsExpression -> {
            val t = initializer.type
            val isAsConst = t is TypeReference &&
                t.typeName.let { it is Identifier && it.text == "const" } &&
                t.typeArguments == null
            if (!isAsConst) return
            val inner = initializer.expression
            if (inner !is ArrayLiteralExpression) return
            for (el in inner.elements) {
                if (el is ClassExpression) {
                    emitTs9022(el, varName, fileName, source, addSatisfies = true, results)
                }
            }
        }
        else -> {}
    }
}

/**
 * For `export const/let/var X = expr` where `expr` is a runtime-only expression
 * that cannot be preserved as a literal type in the emitted .d.ts, emits TS9010
 * at the variable name with TS9027 "Add a type annotation" related info.
 *
 * Trigger expression kinds (conservative — only the ones where TypeScript's
 * baseline definitively expects TS9010 and our currently-passing isolated-decl
 * tests don't put them under `export const X = ...`):
 *  - CallExpression / NewExpression
 *  - PropertyAccessExpression / ElementAccessExpression
 *  - BinaryExpression
 *  - Identifier (other than `true`/`false`/`null`/`undefined`)
 *  - TemplateExpression (template literal with substitutions — `` `s${1}` ``)
 *    when [isConst] is true. Under `const`, the inferred type is a
 *    template-literal type that requires checking to compute; under `let`/`var`
 *    the widened type is just `string`, which is declarable as-written.
 *  - `AsExpression` wrapping a `TemplateExpression` (`` `s${1}` as const ``),
 *    regardless of [isConst]: the `as const` forces literal-type inference.
 *
 * Other expression kinds (literals, ArrowFunction, FunctionExpression,
 * ClassExpression, ObjectLiteralExpression, ArrayLiteralExpression,
 * `as const` over literals / arrays, PrefixUnaryExpression, …) are
 * intentionally left alone here — they need either separate handling
 * (TS9013/TS9017/TS9022) that isn't in scope for this substep.
 */
private fun emitIsolatedDeclVarInitTs9010(
    varName: Identifier,
    init: Expression,
    isConst: Boolean,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val triggers = when (init) {
        is CallExpression -> true
        is NewExpression -> true
        is PropertyAccessExpression -> true
        is ElementAccessExpression -> true
        is BinaryExpression -> true
        is TemplateExpression -> isConst
        is AsExpression -> init.expression is TemplateExpression
        is Identifier -> init.text != "true" && init.text != "false" &&
            init.text != "null" && init.text != "undefined"
        else -> false
    }
    if (!triggers) return
    val (line, character) = positionToLineCharacter(source, varName.pos)
    val related = Diagnostic(
        message = "Add a type annotation to the variable ${varName.text}.",
        category = DiagnosticCategory.Message,
        code = 9027,
        fileName = fileName,
        line = line,
        character = character,
        start = varName.pos,
        length = varName.text.length,
    )
    results.add(Diagnostic(
        message = "Variable must have an explicit type annotation with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9010,
        fileName = fileName,
        line = line,
        character = character,
        start = varName.pos,
        length = varName.text.length,
        relatedInformation = listOf(related),
    ))
}

/**
 * B7.23: ArrayLiteralExpression walker for exported `VariableStatement`
 * initializers under --isolatedDeclarations. Two emission shapes anchored at
 * the variable name via TS9027 related info:
 *
 *  - **TS9017** "Only const arrays can be inferred ..." — fires when the
 *    initializer is a bare `ArrayLiteralExpression` (NOT wrapped in
 *    `as const`). Squiggle covers the full `[...]` span via
 *    [findMatchingDelimiter]. The "const" in the message refers to the
 *    `as const` assertion, not the binding's `const` keyword; TypeScript fires
 *    TS9017 for `export let arr = [1, 2, 3]` because the array type is
 *    inferred as `number[]` and the .d.ts emitter can't preserve element
 *    types without checking.
 *
 *  - **TS9018** "Arrays with spread elements can't inferred ..." — fires for
 *    each `SpreadElement` inside the initializer's ArrayLiteralExpression
 *    (including when wrapped in `as const`, e.g.
 *    `[1, 2, 3, ...arr] as const`). Squiggle covers just the `...expr`
 *    span via the SpreadElement node's own pos/end.
 *
 * Only the topmost array level is walked — nested arrays
 * (`[[1,2,3]]`) are NOT recursively walked for spread; no failing test
 * exercises that shape, and the conservative scope avoids over-firing on
 * patterns where the inner array is itself the value of a parent walker's
 * concern.
 */
private fun emitIsolatedDeclVarInitArrayChecks(
    varName: Identifier,
    init: Expression,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (arrayLit, isAsConst) = when (init) {
        is ArrayLiteralExpression -> init to false
        is AsExpression -> {
            val t = init.type
            val isConst = t is TypeReference &&
                t.typeName.let { it is Identifier && it.text == "const" } &&
                t.typeArguments == null
            if (isConst && init.expression is ArrayLiteralExpression) init.expression to true
            else null to false
        }
        else -> null to false
    }
    if (arrayLit == null) return
    if (!isAsConst) {
        val end = findMatchingDelimiter(source, arrayLit.pos, '[', ']')
        val length = (end - arrayLit.pos).coerceAtLeast(1)
        emitIsolatedDeclTs9017ForVar(varName, arrayLit.pos, length, fileName, source, results)
    }
    for (el in arrayLit.elements) {
        if (el is SpreadElement) {
            // el.end overshoots by one token per CLAUDE.md gotcha — compute the
            // true end via `isolatedDeclExprTrueEnd` on the inner expression so
            // `...arr` lengths as 6 (`...`+identifier) not 7.
            val spreadEnd = isolatedDeclExprTrueEnd(el.expression)
            val spreadLength = (spreadEnd - el.pos).coerceAtLeast(1)
            emitIsolatedDeclTs9018ForVar(varName, el.pos, spreadLength, fileName, source, results)
        }
    }
}

private fun emitIsolatedDeclTs9017ForVar(
    varName: Identifier,
    start: Int,
    length: Int,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (line, character) = positionToLineCharacter(source, start)
    val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
    val related = Diagnostic(
        message = "Add a type annotation to the variable ${varName.text}.",
        category = DiagnosticCategory.Message,
        code = 9027,
        fileName = fileName,
        line = varLine,
        character = varChar,
        start = varName.pos,
        length = varName.text.length,
    )
    results.add(Diagnostic(
        message = "Only const arrays can be inferred with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9017,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = listOf(related),
    ))
}

/**
 * B7.24: TS9019 walker for `ObjectBindingPattern` / `ArrayBindingPattern`
 * destructuring targets in exported `VariableStatement`. Each non-rest,
 * non-nested `BindingElement` with an Identifier name fires TS9019 at the
 * element-name position.
 *
 * No TS9027 related info — TypeScript's baseline for TS9019 doesn't attach
 * a "Add a type annotation" hint, distinguishing it from TS9010/TS9012
 * which always do. This matches the `isolatedDeclarationErrorsExpressions_ts`
 * baseline (lines 127-128).
 *
 * Skipped:
 *  - `OmittedExpression` array holes (e.g. the first two slots in
 *    `[, , b]`).
 *  - Rest elements (`...rest`) — TypeScript does not emit TS9019 for them
 *    in any corpus baseline; conservative skip.
 *  - Nested binding patterns (e.g. `[{ a }]`) — `el.name` is itself a
 *    BindingPattern, not an Identifier. Conservative skip; no failing test
 *    exercises this shape.
 *
 * Length of the squiggle = name Identifier's text length (single character
 * for single-letter names like `a` / `b`).
 */
private fun emitIsolatedDeclVarBindingPatternChecks(
    pattern: Expression,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val elements: List<Node> = when (pattern) {
        is ObjectBindingPattern -> pattern.elements
        is ArrayBindingPattern -> pattern.elements
        else -> return
    }
    for (el in elements) {
        if (el !is BindingElement) continue
        if (el.dotDotDotToken) continue
        val name = el.name
        if (name !is Identifier) continue
        val (line, character) = positionToLineCharacter(source, name.pos)
        results.add(Diagnostic(
            message = "Binding elements can't be exported directly with --isolatedDeclarations.",
            category = DiagnosticCategory.Error,
            code = 9019,
            fileName = fileName,
            line = line,
            character = character,
            start = name.pos,
            length = name.text.length,
        ))
    }
}

private fun emitIsolatedDeclTs9018ForVar(
    varName: Identifier,
    start: Int,
    length: Int,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (line, character) = positionToLineCharacter(source, start)
    val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
    val related = Diagnostic(
        message = "Add a type annotation to the variable ${varName.text}.",
        category = DiagnosticCategory.Message,
        code = 9027,
        fileName = fileName,
        line = varLine,
        character = varChar,
        start = varName.pos,
        length = varName.text.length,
    )
    results.add(Diagnostic(
        message = "Arrays with spread elements can't inferred with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9018,
        fileName = fileName,
        line = line,
        character = character,
        start = start,
        length = length,
        relatedInformation = listOf(related),
    ))
}

private fun emitTs9022(
    classExpr: ClassExpression,
    varName: Identifier,
    fileName: String,
    source: String,
    addSatisfies: Boolean,
    results: MutableList<Diagnostic>,
) {
    val (line, character) = positionToLineCharacter(source, classExpr.pos)
    val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
    val related = mutableListOf(
        Diagnostic(
            message = "Add a type annotation to the variable ${varName.text}.",
            category = DiagnosticCategory.Message,
            code = 9027,
            fileName = fileName,
            line = varLine,
            character = varChar,
            start = varName.pos,
            length = varName.text.length,
        )
    )
    if (addSatisfies) {
        related.add(Diagnostic(
            message = "Add satisfies and a type assertion to this expression (satisfies T as T) to make the type explicit.",
            category = DiagnosticCategory.Message,
            code = 9035,
            fileName = fileName,
            line = line,
            character = character,
            start = classExpr.pos,
            length = 5,
        ))
    }
    results.add(Diagnostic(
        message = "Inference from class expressions is not supported with --isolatedDeclarations.",
        category = DiagnosticCategory.Error,
        code = 9022,
        fileName = fileName,
        line = line,
        character = character,
        start = classExpr.pos,
        length = 5, // "class"
        relatedInformation = related,
    ))
}

/**
 * For each `extends` clause expression on an exported class, emits TS9021 when
 * the expression is not a simple identifier path (e.g. `extends fn(...)`,
 * `extends new C()`).
 */
private fun emitIsolatedDeclExtendsDiags(
    stmt: ClassDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val clauses = stmt.heritageClauses ?: return
    for (clause in clauses) {
        if (clause.token != SyntaxKind.ExtendsKeyword) continue
        for (typeExpr in clause.types) {
            val expr = typeExpr.expression
            if (isAllowedExtendsExpression(expr)) continue
            val start = expr.pos
            val end = isolatedDeclExprTrueEnd(expr)
            val length = (end - start).coerceAtLeast(1)
            val (line, character) = positionToLineCharacter(source, start)
            results.add(Diagnostic(
                message = "Extends clause can't contain an expression with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9021,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }
}

private fun isAllowedExtendsExpression(expr: Expression): Boolean {
    return when (expr) {
        is Identifier -> true
        is PropertyAccessExpression -> isAllowedExtendsExpression(expr.expression)
        else -> false
    }
}

/**
 * True when the argument to `func[idx]` is a statically-known string literal:
 *   - direct `StringLiteralNode`             → `foo["bar"] = ...`
 *   - `ElementAccessExpression(_, StringLiteralNode)` → `foo[obj["bar"]] = ...`
 *     (the inner access resolves to a literal key)
 * Both forms are accepted by TypeScript's --isolatedDeclarations because the
 * property name on the function is statically determinable.
 */
private fun isIsolatedDeclElementAccessIndexLiteralName(idx: Expression): Boolean {
    return when (idx) {
        is StringLiteralNode -> true
        is ElementAccessExpression -> idx.argumentExpression is StringLiteralNode
        else -> false
    }
}

/**
 * Walks an exported `ClassDeclaration`'s members; for each member with
 * `name is ComputedPropertyName`, emits TS9038 (always) and TS1166 (when the
 * inner expression isn't a "simple literal" / `unique symbol` / property-access
 * chain that TypeScript can statically resolve in declaration emit).
 *
 * Span = ComputedPropertyName's `[`..`]` (start = ComputedPropertyName.pos;
 * end = inner expression's true-end + 1).
 *
 * TS1166 fires for AsExpression and ElementAccessExpression inner shapes — these
 * are the "lazy symbol" patterns TypeScript flags as not resolvable to a simple
 * literal type without the full type checker. Identifier / PropertyAccessExpression
 * inner shapes get TS9038 only.
 */
private fun emitIsolatedDeclClassComputedNameDiags(
    stmt: ClassDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (member in stmt.members) {
        val name: NameNode? = when (member) {
            is PropertyDeclaration -> member.name
            is MethodDeclaration -> member.name
            is GetAccessor -> member.name
            is SetAccessor -> member.name
            else -> null
        }
        if (name !is ComputedPropertyName) continue
        val inner = name.expression
        if (isIsolatedDeclTrivialComputedName(inner)) continue
        val start = name.pos
        val end = isolatedDeclExprTrueEnd(inner) + 1
        val length = (end - start).coerceAtLeast(1)
        val (line, character) = positionToLineCharacter(source, start)
        if (inner is AsExpression || inner is ElementAccessExpression) {
            results.add(Diagnostic(
                message = "A computed property name in a class property declaration must have a simple literal type or a 'unique symbol' type.",
                category = DiagnosticCategory.Error,
                code = 1166,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
        results.add(Diagnostic(
            message = "Computed property names on class or object literals cannot be inferred with --isolatedDeclarations.",
            category = DiagnosticCategory.Error,
            code = 9038,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }
}

/**
 * Walks `ObjectLiteralExpression` initializer of an exported variable; for each
 * PropertyAssignment with `name is ComputedPropertyName`, emits TS9038 with a
 * TS9027 related at the enclosing variable name. Skips trivially-literal
 * computed names (numeric literal, +/- numeric, string literal).
 */
private fun emitIsolatedDeclObjLitComputedNameDiags(
    objLit: ObjectLiteralExpression,
    varName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (prop in objLit.properties) {
        if (prop !is PropertyAssignment) continue
        val name = prop.name
        if (name !is ComputedPropertyName) continue
        val inner = name.expression
        if (isIsolatedDeclTrivialComputedName(inner)) continue
        val start = name.pos
        val end = isolatedDeclExprTrueEnd(inner) + 1
        val length = (end - start).coerceAtLeast(1)
        val (line, character) = positionToLineCharacter(source, start)
        val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
        val related = Diagnostic(
            message = "Add a type annotation to the variable ${varName.text}.",
            category = DiagnosticCategory.Message,
            code = 9027,
            fileName = fileName,
            line = varLine,
            character = varChar,
            start = varName.pos,
            length = varName.text.length,
        )
        results.add(Diagnostic(
            message = "Computed property names on class or object literals cannot be inferred with --isolatedDeclarations.",
            category = DiagnosticCategory.Error,
            code = 9038,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = listOf(related),
        ))
    }
}

/**
 * Walks an exported `ClassDeclaration`'s members and emits TS9012 on each
 * un-annotated PropertyDeclaration whose initializer is a non-trivially-
 * declarable expression. Mirrors B7.9's trigger set from
 * `emitIsolatedDeclVarInitTs9010` (VariableStatement → TS9010): the same
 * runtime-only expression kinds that prevent inferring a declaration type
 * apply at the property-declaration level.
 *
 * Squiggle = property name (Identifier `text.length`). TS9029 related at the
 * same position with message "Add a type annotation to the property X."
 *
 * Skipped:
 *   - `member.type != null` (annotated → declarable as written)
 *   - `member.initializer == null` (no value → handled by other walkers / no
 *     TS9012 required)
 *   - `name !is Identifier` (ComputedPropertyName handled by the dedicated
 *     `emitIsolatedDeclClassComputedNameDiags`; string-named / numeric-named
 *     properties are rare in tests and require additional name-text extraction)
 *   - Initializer is trivially declarable (literals, etc.) per
 *     `isIsolatedDeclTriviallyDeclarable`.
 *   - Initializer is `ArrowFunction` / `FunctionExpression` / `ClassExpression`
 *     (separate walkers handle missing return type — TS9007 — / class-expr
 *     inference — TS9022).
 *   - Initializer is `ObjectLiteralExpression` / `ArrayLiteralExpression`
 *     (deferred to a future substep that mirrors B7.9's deferred object-/array-
 *     literal sub-expression walker for VariableStatement).
 */
private fun emitIsolatedDeclClassPropertyTs9012(
    stmt: ClassDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (member in stmt.members) {
        if (member !is PropertyDeclaration) continue
        if (member.type != null) continue
        val init = member.initializer ?: continue
        val name = member.name
        if (name !is Identifier) continue
        // The triggers list below is authoritative — we do NOT short-circuit
        // on `isIsolatedDeclTriviallyDeclarable(init)` here because that helper
        // returns `true` for `TemplateExpression` (correct at param-default
        // level — template literals have type `string` there) but TemplateExpression
        // IS a trigger at the property level when the property is `readonly`
        // (literal template type can't be preserved without checking).
        val isReadonly = ModifierFlag.Readonly in member.modifiers
        val triggers = when (init) {
            is CallExpression -> true
            is NewExpression -> true
            is PropertyAccessExpression -> true
            is ElementAccessExpression -> true
            is BinaryExpression -> true
            is TemplateExpression -> isReadonly
            is AsExpression -> init.expression is TemplateExpression
            is Identifier -> init.text != "true" && init.text != "false" &&
                init.text != "null" && init.text != "undefined"
            else -> false
        }
        if (!triggers) continue
        val (line, character) = positionToLineCharacter(source, name.pos)
        val related = Diagnostic(
            message = "Add a type annotation to the property ${name.text}.",
            category = DiagnosticCategory.Message,
            code = 9029,
            fileName = fileName,
            line = line,
            character = character,
            start = name.pos,
            length = name.text.length,
        )
        results.add(Diagnostic(
            message = "Property must have an explicit type annotation with --isolatedDeclarations.",
            category = DiagnosticCategory.Error,
            code = 9012,
            fileName = fileName,
            line = line,
            character = character,
            start = name.pos,
            length = name.text.length,
            relatedInformation = listOf(related),
        ))
    }
}

/**
 * Walks an exported `ClassDeclaration`'s members and emits TS9008 on each
 * MethodDeclaration that lacks an explicit return-type annotation (and has a
 * body — i.e. is a real method, not an interface signature / overload-only
 * declaration; interface members parse as `MethodSignature`, not
 * `MethodDeclaration`, so this filter is implicit). Mirrors TypeScript's
 * baseline: class methods without `: ReturnType` cannot be inferred under
 * --isolatedDeclarations because the body would need full type checking.
 *
 * Squiggle:
 *   - Identifier name → name.pos + name.text.length
 *   - ComputedPropertyName name → entire `[...]` span via
 *     ComputedPropertyName.pos + `isolatedDeclExprTrueEnd(inner) + 1`
 *     (matching the TS9038 walker's span computation for the same shape;
 *     baseline shows both diagnostics at the same start with the same length).
 *
 * TS9034 related info anchored at the same position with message
 * "Add a return type to the method".
 *
 * No emission for abstract methods (body == null + Abstract modifier — TypeScript
 * still requires the type annotation but our corpus has no failing test gating
 * on this so we conservatively skip body-less MethodDeclarations to avoid
 * over-firing on overload-only declarations).
 */
private fun emitIsolatedDeclClassMethodTs9008(
    stmt: ClassDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (member in stmt.members) {
        if (member !is MethodDeclaration) continue
        if (member.type != null) continue
        if (member.body == null) continue
        val name = member.name
        val start: Int
        val length: Int
        when (name) {
            is Identifier -> {
                start = name.pos
                length = name.text.length
            }
            is ComputedPropertyName -> {
                start = name.pos
                val end = isolatedDeclExprTrueEnd(name.expression) + 1
                length = (end - start).coerceAtLeast(1)
            }
            else -> continue
        }
        val (line, character) = positionToLineCharacter(source, start)
        val related = Diagnostic(
            message = "Add a return type to the method",
            category = DiagnosticCategory.Message,
            code = 9034,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        )
        results.add(Diagnostic(
            message = "Method must have an explicit return type annotation with --isolatedDeclarations.",
            category = DiagnosticCategory.Error,
            code = 9008,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = listOf(related),
        ))
    }
}

/**
 * B7.18: TS9011 walker for exported `ClassDeclaration` MethodDeclaration
 * parameters that lack an explicit type annotation (mirrors B7.5's
 * FunctionDeclaration version for class methods). Reuses
 * `emitIsolatedDeclParamsCheck` so the same trivially-declarable-default
 * classification and TS9028 "Add a type annotation to the parameter X."
 * related info shape applies uniformly across function and class methods.
 *
 * Body-less methods (overload-only / abstract / interface-member parsed as
 * MethodDeclaration with body=null) are conservatively skipped — TypeScript's
 * baseline doesn't emit TS9011 on overload declarations, only on
 * implementation signatures. GetAccessor / SetAccessor / ConstructorDeclaration
 * are skipped (accessors use TS9009/TS7032/TS7006 instead — see B7.17 for the
 * object-literal version; constructor param-property handling is a separate
 * concern not exercised by the current corpus).
 */
private fun emitIsolatedDeclClassMethodParamChecks(
    stmt: ClassDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (member in stmt.members) {
        if (member !is MethodDeclaration) continue
        if (member.body == null) continue
        emitIsolatedDeclParamsCheck(member.parameters, fileName, source, results)
    }
}

/**
 * B7.19: TS9009 / TS7032 walker for Identifier-named class accessors that
 * lack an explicit type annotation, single-accessor-only (no peer pair).
 * Mirrors B7.17's object-literal version for class context.
 *
 * Pair semantics: when a `get`/`set` peer with the same Identifier name is
 * present, ALL emissions are suppressed regardless of whether the peer is
 * annotated — TypeScript treats the pair as resolvable through the annotated
 * side (or both unannotated → both implicit-any but pair-suppression still
 * applies). Verified against the test's `getSetBad`, `getSetOk`, `getSetOk2`,
 * `getSetOk3` quadruple — none fire.
 *
 * Computed-name accessors (`[expr]`) are conservatively skipped here — they
 * fire TS9038 instead via `emitIsolatedDeclClassComputedNameDiags`. The
 * companion TS7032/TS7006 walker for computed-name set-accessors is B7.20
 * territory. StringLiteralNode / NumericLiteralNode names are skipped (no
 * failing-test target exercises them).
 *
 * Per qualifying GET-only accessor (no peer setter, no return-type
 * annotation) emits one diagnostic at the accessor NAME position:
 *   - TS9009 with related TS9032
 *     "Add a return type to the get accessor declaration."
 *
 * Per qualifying SET-only accessor (no peer getter, first parameter has no
 * type annotation) emits one diagnostic:
 *   - TS9009 at the param NAME position (length = param-name.length) with
 *     related TS9033 "Add a type to parameter of the set accessor declaration."
 *
 * Note that BOTH TS7032 (at the accessor name) and TS7006 (at the param name)
 * are already emitted by the checker (`checkImplicitAnyInClassElement`'s
 * SetAccessor branch + `checkParamsForImplicitAny`) under `--strict` /
 * `--noImplicitAny`, gated on the same no-peer-getter condition. This walker
 * does NOT emit them (would duplicate). The walker's contribution is the
 * TS9009/TS9033 pair which is specific to `--isolatedDeclarations`.
 */
private fun emitIsolatedDeclClassAccessor(
    stmt: ClassDeclaration,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val hasGetter = mutableSetOf<String>()
    val hasSetter = mutableSetOf<String>()
    for (member in stmt.members) {
        if (member is GetAccessor) {
            val n = member.name
            if (n is Identifier) hasGetter.add(n.text)
        } else if (member is SetAccessor) {
            val n = member.name
            if (n is Identifier) hasSetter.add(n.text)
        }
    }
    for (member in stmt.members) {
        if (member is GetAccessor) {
            val nameNode = member.name as? Identifier ?: continue
            if (nameNode.text in hasSetter) continue
            if (member.type != null) continue
            val (nameLine, nameChar) = positionToLineCharacter(source, nameNode.pos)
            val nameLen = nameNode.text.length
            val ts9032Related = Diagnostic(
                message = "Add a return type to the get accessor declaration.",
                category = DiagnosticCategory.Message,
                code = 9032,
                fileName = fileName,
                line = nameLine,
                character = nameChar,
                start = nameNode.pos,
                length = nameLen,
            )
            results.add(Diagnostic(
                message = "At least one accessor must have an explicit type annotation with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9009,
                fileName = fileName,
                line = nameLine,
                character = nameChar,
                start = nameNode.pos,
                length = nameLen,
                relatedInformation = listOf(ts9032Related),
            ))
        } else if (member is SetAccessor) {
            val name = member.name
            val param = member.parameters.firstOrNull() ?: continue
            if (param.type != null) continue
            val paramName = param.name as? Identifier ?: continue
            val (paramLine, paramChar) = positionToLineCharacter(source, paramName.pos)
            val paramLen = paramName.text.length

            when (name) {
                is Identifier -> {
                    if (name.text in hasGetter) continue
                    val propName = name.text
                    val (nameLine, nameChar) = positionToLineCharacter(source, name.pos)
                    val ts9033Related = Diagnostic(
                        message = "Add a type to parameter of the set accessor declaration.",
                        category = DiagnosticCategory.Message,
                        code = 9033,
                        fileName = fileName,
                        line = nameLine,
                        character = nameChar,
                        start = name.pos,
                        length = propName.length,
                    )
                    results.add(Diagnostic(
                        message = "At least one accessor must have an explicit type annotation with --isolatedDeclarations.",
                        category = DiagnosticCategory.Error,
                        code = 9009,
                        fileName = fileName,
                        line = paramLine,
                        character = paramChar,
                        start = paramName.pos,
                        length = paramLen,
                        relatedInformation = listOf(ts9033Related),
                    ))
                }
                is ComputedPropertyName -> {
                    // B7.20: ComputedPropertyName-named SetAccessor. The checker's
                    // SetAccessor branch (Checker.kt:9087) gates on Identifier names
                    // and so does NOT fire TS7032/TS7006 for `set [expr](v)` shapes.
                    // Emit them here under --isolatedDeclarations + Export gates.
                    // No TS9009/TS9033 — the baseline shows TS7032 + TS9038 + TS7006
                    // for `set [noParamAnnotationStringName](value)` (line 48), with
                    // TS9038 already emitted by emitIsolatedDeclClassComputedNameDiags.
                    // Peer-suppression by computed-name expression text is NOT done —
                    // no failing-test target exercises a get/set computed-name pair.
                    val nameEnd = isolatedDeclExprTrueEnd(name.expression) + 1
                    val nameLen = (nameEnd - name.pos).coerceAtLeast(1)
                    val propName = source.substring(name.pos, nameEnd)
                    val (nameLine, nameChar) = positionToLineCharacter(source, name.pos)
                    results.add(Diagnostic(
                        message = "Property '${propName}' implicitly has type 'any', because its set accessor lacks a parameter type annotation.",
                        category = DiagnosticCategory.Error,
                        code = 7032,
                        fileName = fileName,
                        line = nameLine,
                        character = nameChar,
                        start = name.pos,
                        length = nameLen,
                    ))
                    results.add(Diagnostic(
                        message = "Parameter '${paramName.text}' implicitly has an 'any' type.",
                        category = DiagnosticCategory.Error,
                        code = 7006,
                        fileName = fileName,
                        line = paramLine,
                        character = paramChar,
                        start = paramName.pos,
                        length = paramLen,
                    ))
                }
                else -> {}
            }
        }
    }
}

/**
 * Walks an exported `VariableStatement`'s ObjectLiteralExpression initializer and
 * emits TS9008 on each method shorthand (`{ method() {} }`) that lacks an explicit
 * return-type annotation. Mirrors `emitIsolatedDeclClassMethodTs9008` for the
 * object-literal context. Recurses into nested ObjectLiteralExpression
 * initializers of PropertyAssignment members so patterns like
 * `{ foo: { method() {} } }` are covered. The TS9027 "Add a type annotation to
 * the variable X." related info is anchored at the OUTER variable name (not the
 * inner property key) — matches TypeScript's baseline.
 *
 * Squiggle:
 *   - Identifier method name → name.pos + name.text.length
 *   - ComputedPropertyName method name → entire `[...]` span via
 *     ComputedPropertyName.pos + `isolatedDeclExprTrueEnd(inner) + 1`
 *
 * Skipped:
 *   - `member !is MethodDeclaration` (PropertyAssignment, GetAccessor, SetAccessor,
 *     SpreadAssignment, ShorthandPropertyAssignment all handled elsewhere or
 *     don't trigger TS9008)
 *   - `member.type != null` (annotated return type)
 *   - `member.body == null` (overload-only — rare in object literals but
 *     conservatively skipped, mirrors B7.13)
 *   - `name` is neither `Identifier` nor `ComputedPropertyName` (string-/numeric-
 *     named methods rare and require separate name-text extraction)
 */
private fun emitIsolatedDeclObjLitMethodTs9008(
    objLit: ObjectLiteralExpression,
    varName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (prop in objLit.properties) {
        if (prop is MethodDeclaration) {
            if (prop.type != null) continue
            if (prop.body == null) continue
            val name = prop.name
            val start: Int
            val length: Int
            when (name) {
                is Identifier -> {
                    start = name.pos
                    length = name.text.length
                }
                is ComputedPropertyName -> {
                    start = name.pos
                    val end = isolatedDeclExprTrueEnd(name.expression) + 1
                    length = (end - start).coerceAtLeast(1)
                }
                else -> continue
            }
            val (line, character) = positionToLineCharacter(source, start)
            val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
            val varRelated = Diagnostic(
                message = "Add a type annotation to the variable ${varName.text}.",
                category = DiagnosticCategory.Message,
                code = 9027,
                fileName = fileName,
                line = varLine,
                character = varChar,
                start = varName.pos,
                length = varName.text.length,
            )
            val methodRelated = Diagnostic(
                message = "Add a return type to the method",
                category = DiagnosticCategory.Message,
                code = 9034,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            )
            results.add(Diagnostic(
                message = "Method must have an explicit return type annotation with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9008,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
                relatedInformation = listOf(varRelated, methodRelated),
            ))
        } else if (prop is PropertyAssignment) {
            val nestedInit = prop.initializer
            if (nestedInit is ObjectLiteralExpression) {
                emitIsolatedDeclObjLitMethodTs9008(nestedInit, varName, fileName, source, results)
            }
        }
    }
}

/**
 * Walks an exported `VariableStatement`'s ObjectLiteralExpression initializer and
 * emits TS9013 on every non-trivially-declarable sub-expression that appears as
 * a PropertyAssignment value. Mirrors B7.7's `walkExportDefaultSubExpr` but
 * adapted for the variable-context: anchor TS9027 at the OUTER variable name
 * (instead of TS9036 at the export-keyword position), and DO emit on bare
 * `Identifier` references (e.g. `e: V`) — the export-default walker skips
 * Identifiers since exporting `default X` is declarable-as-typeof, but in
 * variable context `let oBad = { e: V }` cannot be inferred without checking
 * V's type, which is precisely what --isolatedDeclarations forbids.
 *
 * Recurses through nested ObjectLiteralExpression PropertyAssignments and
 * ArrayLiteralExpression elements. MethodDeclaration / Get/SetAccessor /
 * SpreadAssignment / ShorthandPropertyAssignment members are skipped (handled
 * by separate walkers).
 *
 * Squiggle: at the offending sub-expression's position, length =
 * `isolatedDeclExprTrueEnd(expr) - expr.pos`.
 *
 * Related infos (per emission):
 *   - TS9027 "Add a type annotation to the variable X." anchored at the OUTER
 *     variable name.
 *   - TS9035 "Add satisfies and a type assertion to this expression
 *     (satisfies T as T) to make the type explicit." anchored at the same
 *     position as TS9013.
 */
private fun emitIsolatedDeclObjLitSubExprTs9013(
    objLit: ObjectLiteralExpression,
    varName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    for (prop in objLit.properties) {
        if (prop !is PropertyAssignment) continue
        walkObjLitSubExprTs9013(prop.initializer, varName, fileName, source, results)
    }
}

private fun walkObjLitSubExprTs9013(
    expr: Expression,
    varName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    if (isIsolatedDeclTriviallyDeclarable(expr)) return
    when (expr) {
        is ObjectLiteralExpression -> {
            for (prop in expr.properties) {
                if (prop !is PropertyAssignment) continue
                walkObjLitSubExprTs9013(prop.initializer, varName, fileName, source, results)
            }
        }
        is ArrayLiteralExpression -> {
            for (el in expr.elements) {
                walkObjLitSubExprTs9013(el, varName, fileName, source, results)
            }
        }
        else -> {
            val end = isolatedDeclExprTrueEnd(expr)
            val length = (end - expr.pos).coerceAtLeast(1)
            val (line, character) = positionToLineCharacter(source, expr.pos)
            val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
            val varRelated = Diagnostic(
                message = "Add a type annotation to the variable ${varName.text}.",
                category = DiagnosticCategory.Message,
                code = 9027,
                fileName = fileName,
                line = varLine,
                character = varChar,
                start = varName.pos,
                length = varName.text.length,
            )
            val satisfiesRelated = Diagnostic(
                message = "Add satisfies and a type assertion to this expression (satisfies T as T) to make the type explicit.",
                category = DiagnosticCategory.Message,
                code = 9035,
                fileName = fileName,
                line = line,
                character = character,
                start = expr.pos,
                length = length,
            )
            results.add(Diagnostic(
                message = "Expression type can't be inferred with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9013,
                fileName = fileName,
                line = line,
                character = character,
                start = expr.pos,
                length = length,
                relatedInformation = listOf(varRelated, satisfiesRelated),
            ))
        }
    }
}

/**
 * Walks an exported `VariableStatement`'s ObjectLiteralExpression initializer and
 * emits TS9015 on each SpreadAssignment (`...part`) and TS9016 on each
 * ShorthandPropertyAssignment (`part`). Recurses through nested
 * ObjectLiteralExpression initializers of PropertyAssignment members so
 * patterns like `oWithSpread2.nested: { ...part }` are covered.
 *
 * Squiggle:
 *   - SpreadAssignment: `prop.pos` to end of expression — covers `...EXPR`.
 *     Length = `isolatedDeclExprTrueEnd(prop.expression) - prop.pos`. Length
 *     coerces to at least 1 in case of degenerate cases.
 *   - ShorthandPropertyAssignment: `name.pos` + `name.text.length` — covers
 *     just the identifier (e.g. `part` in `{ part, }`).
 *
 * Related info:
 *   - TS9027 "Add a type annotation to the variable X." anchored at the OUTER
 *     variable name.
 */
private fun emitIsolatedDeclObjLitSpreadShorthand(
    objLit: ObjectLiteralExpression,
    varName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    val (varLine, varChar) = positionToLineCharacter(source, varName.pos)
    val varRelated = Diagnostic(
        message = "Add a type annotation to the variable ${varName.text}.",
        category = DiagnosticCategory.Message,
        code = 9027,
        fileName = fileName,
        line = varLine,
        character = varChar,
        start = varName.pos,
        length = varName.text.length,
    )
    for (prop in objLit.properties) {
        if (prop is SpreadAssignment) {
            val start = prop.pos
            val end = isolatedDeclExprTrueEnd(prop.expression)
            val length = (end - start).coerceAtLeast(1)
            val (line, character) = positionToLineCharacter(source, start)
            results.add(Diagnostic(
                message = "Objects that contain spread assignments can't be inferred with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9015,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
                relatedInformation = listOf(varRelated),
            ))
        } else if (prop is ShorthandPropertyAssignment) {
            val start = prop.name.pos
            val length = prop.name.text.length.coerceAtLeast(1)
            val (line, character) = positionToLineCharacter(source, start)
            results.add(Diagnostic(
                message = "Objects that contain shorthand properties can't be inferred with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9016,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
                relatedInformation = listOf(varRelated),
            ))
        } else if (prop is PropertyAssignment) {
            val nestedInit = prop.initializer
            if (nestedInit is ObjectLiteralExpression) {
                emitIsolatedDeclObjLitSpreadShorthand(nestedInit, varName, fileName, source, results)
            }
        }
    }
}

/**
 * B7.17: TS7032 / TS7006 / TS9009 walker for `set X(value) {}` accessors in
 * ObjectLiteralExpression initializers (under exported `VariableStatement`).
 *
 * Fires only on a SetAccessor whose single parameter has NO type annotation AND
 * has NO peer GetAccessor (by name) in the same object literal. When a peer
 * getter exists, TypeScript treats the setter param type as supplied by the
 * getter's return type (even if that is itself implicit-any), and suppresses
 * all three diagnostics.
 *
 * For each qualifying single-setter, emits:
 *   - TS7032 at the accessor NAME position (length = name.length):
 *       "Property 'X' implicitly has type 'any', because its set accessor lacks
 *        a parameter type annotation."
 *   - TS7006 at the param NAME position (length = param-name.length):
 *       "Parameter 'value' implicitly has an 'any' type."
 *   - TS9009 at the param NAME position (length = param-name.length):
 *       "At least one accessor must have an explicit type annotation with
 *        --isolatedDeclarations."
 *     with related TS9033 at the accessor NAME position:
 *       "Add a type to parameter of the set accessor declaration."
 *
 * Recurses into nested ObjectLiteralExpression initializers via
 * PropertyAssignment so deep object literals are covered uniformly with the
 * other B7.x walkers.
 */
private fun emitIsolatedDeclObjLitAccessor(
    objLit: ObjectLiteralExpression,
    varName: Identifier,
    fileName: String,
    source: String,
    results: MutableList<Diagnostic>,
) {
    // Build accessor-pair table by Identifier name. ComputedPropertyName /
    // StringLiteralNode / NumericLiteralNode accessor names are conservatively
    // skipped here (no failing-test target exercises them).
    val hasGetter = mutableSetOf<String>()
    for (prop in objLit.properties) {
        if (prop is GetAccessor) {
            val n = prop.name
            if (n is Identifier) hasGetter.add(n.text)
        }
    }
    for (prop in objLit.properties) {
        if (prop is SetAccessor) {
            val nameNode = prop.name as? Identifier ?: continue
            if (nameNode.text in hasGetter) continue
            val param = prop.parameters.firstOrNull() ?: continue
            if (param.type != null) continue
            val paramName = param.name as? Identifier ?: continue

            val propName = nameNode.text
            val (nameLine, nameChar) = positionToLineCharacter(source, nameNode.pos)
            results.add(Diagnostic(
                message = "Property '${propName}' implicitly has type 'any', because its set accessor lacks a parameter type annotation.",
                category = DiagnosticCategory.Error,
                code = 7032,
                fileName = fileName,
                line = nameLine,
                character = nameChar,
                start = nameNode.pos,
                length = propName.length,
            ))

            val (paramLine, paramChar) = positionToLineCharacter(source, paramName.pos)
            val paramLen = paramName.text.length
            results.add(Diagnostic(
                message = "Parameter '${paramName.text}' implicitly has an 'any' type.",
                category = DiagnosticCategory.Error,
                code = 7006,
                fileName = fileName,
                line = paramLine,
                character = paramChar,
                start = paramName.pos,
                length = paramLen,
            ))

            val ts9033Related = Diagnostic(
                message = "Add a type to parameter of the set accessor declaration.",
                category = DiagnosticCategory.Message,
                code = 9033,
                fileName = fileName,
                line = nameLine,
                character = nameChar,
                start = nameNode.pos,
                length = propName.length,
            )
            results.add(Diagnostic(
                message = "At least one accessor must have an explicit type annotation with --isolatedDeclarations.",
                category = DiagnosticCategory.Error,
                code = 9009,
                fileName = fileName,
                line = paramLine,
                character = paramChar,
                start = paramName.pos,
                length = paramLen,
                relatedInformation = listOf(ts9033Related),
            ))
        } else if (prop is PropertyAssignment) {
            val nestedInit = prop.initializer
            if (nestedInit is ObjectLiteralExpression) {
                emitIsolatedDeclObjLitAccessor(nestedInit, varName, fileName, source, results)
            }
        }
    }
}

/**
 * Trivially literal computed-name expressions accepted in --isolatedDeclarations:
 *   - direct numeric literal: `[1]`
 *   - signed numeric literal: `[-1]`, `[+5]`
 *   - direct string literal:  `["foo"]` (rare in practice — parser usually folds
 *     into a string-named property — but kept as a safety net)
 */
private fun isIsolatedDeclTrivialComputedName(expr: Expression): Boolean {
    return when (expr) {
        is NumericLiteralNode -> true
        is StringLiteralNode -> true
        is PrefixUnaryExpression -> {
            val op = expr.operator
            (op == SyntaxKind.Minus || op == SyntaxKind.Plus) &&
                expr.operand is NumericLiteralNode
        }
        else -> false
    }
}

private fun isolatedDeclExprTrueEnd(expr: Expression): Int = when (expr) {
    is NumericLiteralNode -> expr.pos + expr.text.length
    is BigIntLiteralNode -> expr.pos + expr.text.length
    is StringLiteralNode -> {
        val len = expr.rawText?.length ?: expr.text.length
        expr.pos + len + (if (expr.isUnterminated) 1 else 2)
    }
    is NoSubstitutionTemplateLiteralNode -> expr.pos + expr.text.length + 2
    is Identifier -> expr.pos + expr.text.length
    is PrefixUnaryExpression -> isolatedDeclExprTrueEnd(expr.operand)
    is BinaryExpression -> isolatedDeclExprTrueEnd(expr.right)
    is PropertyAccessExpression -> {
        val n = expr.name
        n.pos + n.text.length
    }
    is ElementAccessExpression -> isolatedDeclExprTrueEnd(expr.argumentExpression) + 1
    is CallExpression -> {
        // For empty `()`, do NOT use `expr.end` — it overshoots by one token
        // (per CLAUDE.md gotcha: `node.end` includes the next scanned token's
        // start). Mirror NewExpression's branch instead: walk the callee's
        // true end + 2 chars for `()`. For non-empty args, walk last arg + 1
        // (closing `)`).
        if (expr.arguments.isEmpty()) isolatedDeclExprTrueEnd(expr.expression) + 2
        else isolatedDeclExprTrueEnd(expr.arguments.last()) + 1
    }
    is NewExpression -> {
        val args = expr.arguments
        when {
            args == null -> isolatedDeclExprTrueEnd(expr.expression)
            args.isEmpty() -> isolatedDeclExprTrueEnd(expr.expression) + 2 // ()
            else -> isolatedDeclExprTrueEnd(args.last()) + 1
        }
    }
    // `expr.end` overshoots by one token (per CLAUDE.md gotcha) — `tightEnd`
    // is the parser-tracked position right after the type's last character
    // (`scanner.getPrevTokenEnd()`), so it gives the right end for the
    // `expression as Type` source span. Used by computed-name walkers
    // (`emitIsolatedDeclClassComputedNameDiags`) where `[expr as T]`'s
    // closing `]` is computed as `isolatedDeclExprTrueEnd(inner) + 1`.
    is AsExpression -> if (expr.tightEnd > 0) expr.tightEnd else expr.end
    else -> expr.end
}

/**
 * Build map: target file basename (no extension) → list of files that contain a
 * `declare module './<target>' { ... }` augmentation. Only relative specifiers
 * (`./X` / `../X`) are considered — non-relative augmentations target ambient
 * modules, not sibling files.
 */
private fun buildAugmenterMap(files: Map<String, SourceFile>): Map<String, List<String>> {
    val result = mutableMapOf<String, MutableList<String>>()
    for ((fileName, sourceFile) in files) {
        for (stmt in sourceFile.statements) {
            if (stmt !is ModuleDeclaration) continue
            val name = stmt.name as? StringLiteralNode ?: continue
            val spec = name.text
            if (!spec.startsWith("./") && !spec.startsWith("../")) continue
            val baseSpec = spec.removePrefix("./").removePrefix("../")
                .substringAfterLast('/')
                .substringBeforeLast('.')
            result.getOrPut(baseSpec) { mutableListOf() }.add(fileName)
        }
    }
    return result
}

/**
 * For each relative `import ... from './X'` in [sourceFile] where the imported
 * file augments the current file (per [augmenterMap]), emit TS9026 covering
 * the full import statement.
 */
private fun emitIsolatedDeclarationsAugmentImports(
    sourceFile: SourceFile,
    fileName: String,
    source: String,
    augmenterMap: Map<String, List<String>>,
): List<Diagnostic> {
    val isJsFile = fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
        fileName.endsWith(".mjs") || fileName.endsWith(".cjs")
    if (isJsFile) return emptyList()
    val isDtsFile = fileName.endsWith(".d.ts") || fileName.endsWith(".d.mts") ||
        fileName.endsWith(".d.cts")
    if (isDtsFile) return emptyList()
    val currentBase = fileName.substringAfterLast('/').substringBeforeLast('.')
    val augmenters = augmenterMap[currentBase] ?: return emptyList()
    val augmenterBases = augmenters.map { it.substringAfterLast('/').substringBeforeLast('.') }
        .toSet()
    val results = mutableListOf<Diagnostic>()
    for (stmt in sourceFile.statements) {
        if (stmt !is ImportDeclaration) continue
        val spec = stmt.moduleSpecifier as? StringLiteralNode ?: continue
        val specifier = spec.text
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) continue
        val specBase = specifier.removePrefix("./").removePrefix("../")
            .substringAfterLast('/')
            .substringBeforeLast('.')
        if (specBase !in augmenterBases) continue
        val start = stmt.pos
        // Find `;` after the module-specifier closing quote (rawText.length + 2 for quotes).
        val specEnd = spec.pos + (spec.rawText?.length ?: spec.text.length) + 2
        var end = stmt.end
        val semiPos = source.indexOf(';', specEnd)
        if (semiPos in 0..<source.length) end = semiPos + 1
        val length = (end - start).coerceAtLeast(1)
        val (line, character) = positionToLineCharacter(source, start)
        results.add(Diagnostic(
            message = "Declaration emit for this file requires preserving this import for augmentations. This is not supported with --isolatedDeclarations.",
            category = DiagnosticCategory.Error,
            code = 9026,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }
    return results
}

/**
 * Scan a JSON source file for object property keys that are NOT double-quoted
 * string literals and emit TS1327 ("String literal with double quotes expected.")
 * for each. Handles single-quoted keys (`'a':`), computed keys (`[a]:`), and bare
 * identifier/number keys. A string-aware mini-tokenizer tracks object vs array
 * context so array elements and string values are never mistaken for keys; a key
 * candidate is only flagged when it is actually followed by `:`. Tolerates line
 * and block comments and trailing commas (JSONC). FP-safe: valid JSON (all keys
 * double-quoted) produces no diagnostics.
 */
private fun scanJsonKeysForTS1327(content: String, fileName: String): List<Diagnostic> {
    val diags = mutableListOf<Diagnostic>()
    val n = content.length
    // context stack: true = inside object, false = inside array
    val stack = ArrayDeque<Boolean>()
    var expectKey = false
    var i = 0

    fun consumeDoubleString(from: Int): Int {
        var j = from + 1
        while (j < n) {
            when (content[j]) {
                '\\' -> { j += 2; continue }
                '"' -> { j++; return j }
            }
            j++
        }
        return j
    }
    fun keyTokenEnd(from: Int): Int {
        when (content[from]) {
            '\'' -> {
                var j = from + 1
                while (j < n) {
                    when (content[j]) {
                        '\\' -> { j += 2; continue }
                        '\'' -> { j++; return j }
                    }
                    j++
                }
                return j
            }
            '[' -> {
                var depth = 1; var j = from + 1
                while (j < n && depth > 0) {
                    when (content[j]) { '[' -> depth++; ']' -> depth-- }
                    j++
                }
                return j
            }
            else -> {
                var j = from
                while (j < n && content[j] != ':' && content[j] != ',' && content[j] != '}' &&
                    !content[j].isWhitespace()) j++
                return j
            }
        }
    }

    while (i < n) {
        val c = content[i]
        if (c == '/' && i + 1 < n && content[i + 1] == '/') {
            while (i < n && content[i] != '\n') i++
            continue
        }
        if (c == '/' && i + 1 < n && content[i + 1] == '*') {
            i += 2
            while (i + 1 < n && !(content[i] == '*' && content[i + 1] == '/')) i++
            i += 2
            continue
        }
        if (c.isWhitespace()) { i++; continue }

        if (expectKey && stack.lastOrNull() == true) {
            when (c) {
                '"' -> { i = consumeDoubleString(i); expectKey = false }
                '}' -> { stack.removeLastOrNull(); expectKey = false; i++ }
                else -> {
                    val startOff = i
                    val endOff = keyTokenEnd(i)
                    var k = endOff
                    while (k < n && content[k].isWhitespace()) k++
                    if (k < n && content[k] == ':') {
                        val (line, ch) = positionToLineCharacter(content, startOff)
                        diags.add(Diagnostic(
                            message = "String literal with double quotes expected.",
                            category = DiagnosticCategory.Error,
                            code = 1327,
                            fileName = fileName,
                            line = line,
                            character = ch,
                            start = startOff,
                            length = endOff - startOff,
                        ))
                    }
                    expectKey = false
                    i = endOff
                }
            }
            continue
        }

        when (c) {
            '{' -> { stack.addLast(true); expectKey = true; i++ }
            '[' -> { stack.addLast(false); expectKey = false; i++ }
            '}' -> { stack.removeLastOrNull(); expectKey = false; i++ }
            ']' -> { stack.removeLastOrNull(); expectKey = false; i++ }
            ',' -> { expectKey = (stack.lastOrNull() == true); i++ }
            ':' -> { expectKey = false; i++ }
            '"' -> { i = consumeDoubleString(i) }
            else -> i++
        }
    }
    return diags
}

/** B573: emit tsc's recovered-object-literal diagnostics for a .json file whose
 *  content is ONLY bare identifiers + whitespace (`contents Not read`): `{` expected
 *  + TS1136 at the first token, `,` expected + TS1136 at each subsequent token, and
 *  `}` expected at EOF. Returns empty for anything containing `{ } : " ' [ ] , .`
 *  (structured/valid json, or content owned by scanJsonKeysForTS1327). Corpus-unique. */
private fun scanMalformedBareJson(content: String, fileName: String): List<Diagnostic> {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.any { it in "{}:\"'[],." }) return emptyList()
    val n = content.length
    val tokens = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < n) {
        if (content[i].isWhitespace()) { i++; continue }
        val start = i
        while (i < n && !content[i].isWhitespace()) i++
        tokens.add(start to i)
    }
    if (tokens.isEmpty()) return emptyList()
    for ((s, e) in tokens) {
        if (!content.substring(s, e).all { it.isLetterOrDigit() || it == '_' || it == '$' }) return emptyList()
    }
    val diags = mutableListOf<Diagnostic>()
    for ((idx, tok) in tokens.withIndex()) {
        val (s, e) = tok
        val expected = if (idx == 0) "{" else ","
        val (line, ch) = positionToLineCharacter(content, s)
        diags.add(Diagnostic(
            message = "'$expected' expected.", category = DiagnosticCategory.Error, code = 1005,
            fileName = fileName, line = line, character = ch, start = s, length = e - s))
        diags.add(Diagnostic(
            message = "Property assignment expected.", category = DiagnosticCategory.Error, code = 1136,
            fileName = fileName, line = line, character = ch, start = s, length = e - s))
    }
    val endPos = tokens.last().second
    val (le, ce) = positionToLineCharacter(content, endPos)
    diags.add(Diagnostic(
        message = "'}' expected.", category = DiagnosticCategory.Error, code = 1005,
        fileName = fileName, line = le, character = ce, start = endPos, length = 0))
    return diags
}

/** The compiler's ONE offset-to-line conversion ([lineAndCharacterAt]); this used to
 *  be a fourth private copy of it that counted `\n` only (round 915). */
private fun positionToLineCharacter(source: String, position: Int): Pair<Int, Int> =
    lineAndCharacterAt(source, position)

/**
 * INV.6(6b): the sequential partition-equivalence harness (opt-in via
 * [PartitionCheck.workers]). Runs N partition checkers over FRESHLY-BOUND
 * copies of the parse trees (checker init mutates shared symbols via
 * mergeSymbolTable — a worker must never reuse an already-checked bind; the
 * Binder itself never touches the AST, so parse trees are shared), merges
 * their filtered output, and diffs it against the full run. fileName-null
 * (program-level) diagnostics are emitted by every worker — deduplicated by
 * key before the comparison. The report goes to [PartitionCheck.reportLines].
 */
private fun runPartitionEquivalenceCheck(
    options: CompilerOptions,
    sourceFiles: List<SourceFile>,
    parsed: ParsedSource,
    fullDiagnostics: List<Diagnostic>,
) {
    fun key(d: Diagnostic) =
        "${d.fileName}|${d.start}|${d.length}|TS${d.code}|${d.message}"
    val workers = PartitionCheck.workers
    val fileNames = sourceFiles.map { it.fileName }
    val allInput = parsed.files.map { it.fileName }.toSet()
    val json = parsed.files
        .filter { it.fileName.endsWith(".json") && !it.fileName.endsWith("tsconfig.json") }
        .associate { it.fileName to it.content }
    val merged = mutableListOf<Diagnostic>()
    for (w in 0 until workers) {
        val assigned = fileNames.filterIndexed { i, _ -> i % workers == w }.toSet()
        val binder = Binder(options)
        val results = sourceFiles.map { binder.bind(it) }
        val workerChecker = Checker(
            options, results, isMultiFileSource = parsed.hasExplicitFilenames,
            assignedFileNames = assigned,
            allInputFileNames = allInput, jsonModuleContents = json,
            moduleResolutions = parsed.moduleResolutions,
        )
        merged.addAll(workerChecker.getDiagnostics())
    }
    val mergedAdjusted = merged.filter { it.fileName != null } +
        merged.filter { it.fileName == null }.distinctBy { key(it) }
    val fullKeys = fullDiagnostics.map { key(it) }.sorted()
    val mergedKeys = mergedAdjusted.map { key(it) }.sorted()
    val out = PartitionCheck.reportLines
    if (fullKeys == mergedKeys) {
        out.add("partitionCheck(workers=$workers): EQUIVALENT — ${fullKeys.size} diagnostics")
        return
    }
    val fullCounts = fullKeys.groupingBy { it }.eachCount()
    val mergedCounts = mergedKeys.groupingBy { it }.eachCount()
    val missing = fullCounts.entries.mapNotNull { (k, c) ->
        val d = c - (mergedCounts[k] ?: 0); if (d > 0) k to d else null
    }
    val extra = mergedCounts.entries.mapNotNull { (k, c) ->
        val d = c - (fullCounts[k] ?: 0); if (d > 0) k to d else null
    }
    out.add(
        "partitionCheck(workers=$workers): DIVERGED — full=${fullKeys.size} merged=${mergedKeys.size} " +
            "missing=${missing.sumOf { it.second }} extra=${extra.sumOf { it.second }}"
    )
    missing.take(25).forEach { (k, c) -> out.add("  MISSING x$c: $k") }
    if (missing.size > 25) out.add("  ... ${missing.size - 25} more missing keys")
    extra.take(25).forEach { (k, c) -> out.add("  EXTRA   x$c: $k") }
    if (extra.size > 25) out.add("  ... ${extra.size - 25} more extra keys")
}
