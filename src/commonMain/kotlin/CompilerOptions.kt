/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

enum class ScriptTarget {
    ES3, ES5, ES2015, ES2016, ES2017, ES2018, ES2019, ES2020, ES2021, ES2022, ES2023, ES2024, ESNext;

    companion object {
        fun fromString(value: String): ScriptTarget? = when (value.lowercase()) {
            "es3" -> ES3
            "es5" -> ES5
            "es6", "es2015" -> ES2015
            "es2016" -> ES2016
            "es2017" -> ES2017
            "es2018" -> ES2018
            "es2019" -> ES2019
            "es2020" -> ES2020
            "es2021" -> ES2021
            "es2022" -> ES2022
            "es2023" -> ES2023
            "es2024" -> ES2024
            "esnext" -> ESNext
            else -> null
        }
    }
}

enum class ModuleKind {
    None, CommonJS, AMD, UMD, System, ES2015, ES2020, ES2022, ESNext, Node16, Node18, Node20, NodeNext, Preserve;

    /** True for Node16, Node18, Node20, NodeNext — all node-resolution module kinds. */
    val isNodeNext: Boolean get() = this == Node16 || this == Node18 || this == Node20 || this == NodeNext

    companion object {
        fun fromString(value: String): ModuleKind? = when (value.lowercase()) {
            "none" -> None
            "commonjs" -> CommonJS
            "amd" -> AMD
            "umd" -> UMD
            "system" -> System
            "es6", "es2015" -> ES2015
            "es2020" -> ES2020
            "es2022" -> ES2022
            "esnext" -> ESNext
            "node16" -> Node16
            "node18" -> Node18
            "node20" -> Node20
            "nodenext" -> NodeNext
            "preserve" -> Preserve
            else -> null
        }
    }
}

/**
 * Tracks the position of a compiler option in a tsconfig.json file,
 * used for emitting positioned deprecation diagnostics.
 * Stores both KEY position (for TS5101/TS5102) and VALUE position (for TS5107).
 */
data class TsconfigOptionPosition(
    val fileName: String,
    // Key position (for TS5101/TS5102 diagnostics that point to the option name)
    val keyLine: Int,        // 1-based
    val keyCharacter: Int,   // 1-based
    val keyStart: Int,       // 0-based byte offset
    val keyLength: Int,      // length of key including quotes
    // Value position (for TS5107 diagnostics that point to the option value)
    val valueLine: Int,      // 1-based
    val valueCharacter: Int, // 1-based
    val valueStart: Int,     // 0-based byte offset
    val valueLength: Int,    // length of value including quotes for strings
)

data class CompilerOptions(
    val target: ScriptTarget = ScriptTarget.ES3,
    val targetExplicitlySet: Boolean = false,
    val module: ModuleKind? = null,
    val strict: Boolean = false,
    /** True when `// @strict: false` was explicitly set (not just defaulting to false). */
    val strictExplicitlyFalse: Boolean = false,
    val noEmit: Boolean = false,
    val noEmitHelpers: Boolean = false,
    val declaration: Boolean = false,
    val declarationMap: Boolean = false,
    val removeComments: Boolean = false,
    val preserveConstEnums: Boolean = false,
    val sourceMap: Boolean = false,
    val noImplicitAny: Boolean = false,
    val noImplicitAnyExplicitlyFalse: Boolean = false,
    val noImplicitReturns: Boolean = false,
    val noImplicitThis: Boolean = false,
    val strictNullChecks: Boolean = false,
    /** True when `// @strictNullChecks: false` was explicitly set. */
    val strictNullChecksExplicitlyFalse: Boolean = false,
    /** True when `// @strictPropertyInitialization: false` was explicitly set. */
    val strictPropertyInitializationExplicitlyFalse: Boolean = false,
    val noUnusedLocals: Boolean = false,
    val noUnusedParameters: Boolean = false,
    val experimentalDecorators: Boolean = false,
    val emitDecoratorMetadata: Boolean = false,
    val jsx: String? = null,
    val jsxFactory: String? = null,
    val jsxFragmentFactory: String? = null,
    val reactNamespace: String? = null,
    val lib: List<String> = emptyList(),
    val outDir: String? = null,
    val rootDir: String? = null,
    val baseUrl: String? = null,
    val paths: Map<String, List<String>> = emptyMap(),
    val moduleResolution: String? = null,
    val esModuleInterop: Boolean = true,
    val esModuleInteropExplicitlyFalse: Boolean = false,
    val allowSyntheticDefaultImportsExplicitlyFalse: Boolean = false,
    val allowJs: Boolean = false,
    val checkJs: Boolean = false,
    val isolatedModules: Boolean = false,
    val skipLibCheck: Boolean = false,
    val forceConsistentCasingInFileNames: Boolean = false,
    val noEmitOnError: Boolean = false,
    val downlevelIteration: Boolean = false,
    val downlevelIterationExplicitlySet: Boolean = false,
    val importHelpers: Boolean = false,
    val allowSyntheticDefaultImports: Boolean = false,
    val useDefineForClassFields: Boolean? = null,
    val verbatimModuleSyntax: Boolean = false,
    val noCheck: Boolean = false,
    val emitDeclarationOnly: Boolean = false,
    val mapRoot: String? = null,
    val outFile: String? = null,
    val alwaysStrict: Boolean? = null,
    val newLine: String? = null,
    val fullEmitPaths: Boolean = false,
    val allowUnreachableCode: Boolean? = null,
    val allowUnusedLabels: Boolean? = null,
    val noFallthroughCasesInSwitch: Boolean = false,
    val noResolve: Boolean = false,
    val noImplicitReferences: Boolean = false,
    val moduleDetection: String? = null,
    val moduleSuffixes: List<String>? = null,
    // Deprecated options (tracked for TS5101 diagnostics)
    val charset: String? = null,
    val keyofStringsOnly: Boolean = false,
    val noImplicitUseStrict: Boolean = false,
    val noStrictGenericChecks: Boolean = false,
    val suppressExcessPropertyErrors: Boolean = false,
    val suppressImplicitAnyIndexErrors: Boolean = false,
    val out: String? = null, // distinct from outFile for diagnostic purposes
    val importsNotUsedAsValues: String? = null, // removed in TS 5.5
    val preserveValueImports: Boolean = false, // removed in TS 5.5
    val resolveJsonModule: Boolean = false,
    val inlineSourceMap: Boolean = false,
    val ignoreDeprecations: String? = null,
    /**
     * Simulated TypeScript version for version-gated diagnostics (from `// @typeScriptVersion` test directive).
     * When set, options deprecated at version X emit TS5102/TS5108 ("removed") instead of TS5101/TS5107
     * ("deprecated") if this version >= their `stopFunctioningVersion`.
     */
    val simulatedTypeScriptVersion: String? = null,
    /** Maps lowercase option names to their positions in tsconfig.json (for positioned diagnostics). */
    val tsconfigOptionPositions: Map<String, TsconfigOptionPosition> = emptyMap(),
) {

    val effectiveTarget: ScriptTarget
        get() = if (target <= ScriptTarget.ES5) ScriptTarget.ES2015 else target

    val effectiveModule: ModuleKind
        get() = module ?: when {
            effectiveTarget >= ScriptTarget.ES2015 -> ModuleKind.ES2015
            else -> ModuleKind.CommonJS
        }
}

/**
 * Returns true if the given module kind and file name indicate ES module format.
 * For Node16/NodeNext, `.cts` files are CJS; all others (`.ts`, `.mts`) default to ESM.
 * (In the real TypeScript compiler, `.ts` files check package.json "type" field,
 * but we don't have that context, so we default to ESM.)
 */
fun isESModuleFormat(module: ModuleKind, fileName: String): Boolean {
    // module: preserve passes through all file formats as-is (ESM syntax)
    if (module == ModuleKind.Preserve) return true
    // .cjs/.cts files are always CJS regardless of module setting
    if (fileName.endsWith(".cjs") || fileName.endsWith(".cts")) return false
    // .mjs/.mts files are always ESM regardless of module setting
    if (fileName.endsWith(".mjs") || fileName.endsWith(".mts")) return true
    return when (module) {
        ModuleKind.ES2015, ModuleKind.ES2020, ModuleKind.ES2022, ModuleKind.ESNext -> true
        ModuleKind.Node16, ModuleKind.Node18, ModuleKind.Node20, ModuleKind.NodeNext -> {
            // In node resolution modes, only .mts/.mjs files are ESM by default.
            // Plain .ts files are CJS (we don't have package.json "type" context).
            // .mts/.mjs already handled above, so only those reach here as true.
            false
        }
        else -> false
    }
}

data class SourceFileEntry(
    val fileName: String,
    val content: String,
)

/**
 * Result of parsing compiler options and splitting multi-file sources.
 *
 * @property hasExplicitFilenames true when one or more `// @Filename:` directives were
 *   present in the source, even if only a single file was declared. When true, the
 *   multi-file baseline format must be used (filenames come from the directives, not
 *   from the overall test-file name).
 */
data class ParsedSource(
    val options: CompilerOptions,
    val files: List<SourceFileEntry>,
    val hasExplicitFilenames: Boolean = false,
)

/**
 * Parses `// @key: value` directives from the source header, returning the
 * [CompilerOptions] and the cleaned source (with directives and BOM stripped).
 */
fun parseCompilerOptions(source: String): Pair<CompilerOptions, String> {
    // Normalize line endings to LF to ensure consistent positions across platforms.
    // This matches parseMultiFileSource behavior and prevents \r\n mismatch in
    // diagnostic spans vs LF-normalized sourceLines in the error baseline formatter.
    val cleaned = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    val lines = cleaned.split('\n')
    val directiveLines = mutableListOf<Int>()
    val directives = mutableMapOf<String, String>()

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim().trimEnd('\r')
        if (trimmed.startsWith("// @") || trimmed.startsWith("//@")) {
            val content = if (trimmed.startsWith("// @")) {
                trimmed.removePrefix("// @")
            } else {
                trimmed.removePrefix("//@")
            }
            val colonIndex = content.indexOf(':')
            if (colonIndex >= 0) {
                val key = content.substring(0, colonIndex).trim().lowercase()
                val value = content.substring(colonIndex + 1).trim()
                directives[key] = value
                directiveLines.add(index)
            }
        }
    }

    val sourceLines = lines.filterIndexed { index, _ -> index !in directiveLines }
    // Drop leading truly-empty lines after directive removal, but preserve lines
    // that contain whitespace characters (they appear in baseline source echoes).
    val trimmedLines = sourceLines.dropWhile { it.trimEnd('\r').isEmpty() }
    val strippedSource = trimmedLines.joinToString("\n")

    var options = CompilerOptions()
    for ((key, value) in directives) {
        options = applyDirective(options, key, value)
    }

    return options to strippedSource
}

/**
 * Parses compiler options AND `// @Filename:` directives to split multi-file sources.
 * Returns [ParsedSource] with options and a list of source files.
 * If no `// @Filename:` directives are found, returns a single file with the test name.
 */
fun parseMultiFileSource(source: String, testFileName: String): ParsedSource {
    val cleaned = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    val lines = cleaned.split('\n')
    val directives = mutableMapOf<String, String>()
    val fileEntries = mutableListOf<SourceFileEntry>()
    var currentFileName: String? = null
    val currentLines = mutableListOf<String>()
    val globalDirectiveLines = mutableListOf<String>()
    var inGlobalDirectives = true

    for (line in lines) {
        val trimmed = line.trim().trimEnd('\r')
        if (trimmed.startsWith("// @") || trimmed.startsWith("//@")) {
            val content = if (trimmed.startsWith("// @")) {
                trimmed.removePrefix("// @")
            } else {
                trimmed.removePrefix("//@")
            }
            val colonIndex = content.indexOf(':')
            if (colonIndex >= 0) {
                val key = content.substring(0, colonIndex).trim().lowercase()
                val value = content.substring(colonIndex + 1).trim()
                if (key == "filename") {
                    // Start a new file
                    if (currentFileName != null) {
                        // Strip leading blank lines (artifacts of whitespace after the @filename directive)
                        val fileContent = currentLines.joinToString("\n").trimStart('\n', '\r')
                        // Skip empty file entries when the same filename immediately follows
                        // (duplicate @filename directives, e.g. in augmentExportEquals2.ts)
                        if (fileContent.isNotEmpty() || value != currentFileName) {
                            fileEntries.add(SourceFileEntry(currentFileName, fileContent))
                        }
                    }
                    // Clear any preamble lines collected before the first @Filename marker
                    currentLines.clear()
                    currentFileName = value
                    inGlobalDirectives = false
                } else {
                    directives[key] = value
                    if (inGlobalDirectives) {
                        globalDirectiveLines.add(line)
                    }
                }
            } else if (!inGlobalDirectives) {
                // No colon — not a key:value directive (e.g. // @ts-ignore, // @ts-expect-error)
                // Treat as regular source content
                currentLines.add(line)
            }
        } else {
            if (inGlobalDirectives && currentFileName == null) {
                // Non-directive line before any @Filename — part of first file only if non-empty
                if (trimmed.isNotEmpty()) {
                    inGlobalDirectives = false
                    currentLines.add(line)
                }
                // Skip blank lines that appear between global directives (before first @Filename)
            } else {
                currentLines.add(line)
            }
        }
    }

    // Flush the last file
    if (currentFileName != null) {
        val fileContent = currentLines.joinToString("\n").trimStart('\n', '\r')
        fileEntries.add(SourceFileEntry(currentFileName, fileContent))
    }

    var options = CompilerOptions()

    // Apply options from tsconfig.json FIRST (if present in the file entries).
    // Test directives (// @target: etc.) are applied AFTER and take precedence.
    val tsconfigEntry = fileEntries.find { it.fileName.substringAfterLast('/') == "tsconfig.json" }
    if (tsconfigEntry != null) {
        options = applyTsconfigOptions(options, tsconfigEntry.content, tsconfigEntry.fileName)
    }

    for ((key, value) in directives) {
        options = applyDirective(options, key, value)
    }

    if (fileEntries.isEmpty()) {
        // Single-file test: use the original parseCompilerOptions for source cleanup
        val (_, cleanedSource) = parseCompilerOptions(source)
        return ParsedSource(options, listOf(SourceFileEntry(testFileName, cleanedSource)))
    }

    return ParsedSource(options, fileEntries, hasExplicitFilenames = true)
}

internal fun applyDirective(options: CompilerOptions, key: String, value: String): CompilerOptions {
    val boolValue = value.lowercase() == "true"
    return when (key) {
        "target" -> {
            val target = ScriptTarget.fromString(value.split(",")[0].trim())
            if (target != null) options.copy(target = target, targetExplicitlySet = true) else options
        }

        "module" -> {
            val module = ModuleKind.fromString(value.trim())
            if (module != null) options.copy(module = module) else options
        }

        "strict" -> options.copy(strict = boolValue, strictExplicitlyFalse = !boolValue)
        "noemit" -> options.copy(noEmit = boolValue)
        "noemithelpers" -> options.copy(noEmitHelpers = boolValue)
        "declaration" -> options.copy(declaration = boolValue)
        "declarationmap" -> options.copy(declarationMap = boolValue)
        "removecomments" -> options.copy(removeComments = boolValue)
        "preserveconstenums" -> options.copy(preserveConstEnums = boolValue)
        "sourcemap" -> options.copy(sourceMap = boolValue)
        "noimplicitany" -> options.copy(noImplicitAny = boolValue, noImplicitAnyExplicitlyFalse = !boolValue)
        "noimplicitreturns" -> options.copy(noImplicitReturns = boolValue)
        "noimplicitthis" -> options.copy(noImplicitThis = boolValue)
        "strictnullchecks" -> options.copy(strictNullChecks = boolValue, strictNullChecksExplicitlyFalse = !boolValue)
        "strictpropertyinitialization" -> options.copy(strictPropertyInitializationExplicitlyFalse = !boolValue)
        "nounusedlocals" -> options.copy(noUnusedLocals = boolValue)
        "nounusedparameters" -> options.copy(noUnusedParameters = boolValue)
        "experimentaldecorators" -> options.copy(experimentalDecorators = boolValue)
        "emitdecoratormetadata" -> options.copy(emitDecoratorMetadata = boolValue)
        "jsx" -> options.copy(jsx = value.trim())
        "jsxfactory" -> options.copy(jsxFactory = value.trim())
        "jsxfragmentfactory" -> options.copy(jsxFragmentFactory = value.trim())
        "reactnamespace" -> options.copy(reactNamespace = value.trim())
        "lib" -> options.copy(lib = value.split(",").map { it.trim() })
        "outdir" -> options.copy(outDir = value.trim())
        "rootdir" -> options.copy(rootDir = value.trim())
        "baseurl" -> options.copy(baseUrl = value.trim())
        "moduleresolution" -> options.copy(moduleResolution = value.trim())
        "esmoduleinterop" -> options.copy(
            esModuleInterop = boolValue,
            esModuleInteropExplicitlyFalse = !boolValue,
        )
        "allowjs" -> options.copy(allowJs = boolValue)
        "checkjs" -> options.copy(checkJs = boolValue)
        "isolatedmodules" -> options.copy(isolatedModules = boolValue)
        "skiplibcheck" -> options.copy(skipLibCheck = boolValue)
        "forceconsistentcasinginfilenames" -> options.copy(forceConsistentCasingInFileNames = boolValue)
        "noemitonerror" -> options.copy(noEmitOnError = boolValue)
        "downleveliteration" -> options.copy(downlevelIteration = boolValue, downlevelIterationExplicitlySet = true)
        "importhelpers" -> options.copy(importHelpers = boolValue)
        "allowsyntheticdefaultimports" -> options.copy(
            allowSyntheticDefaultImports = boolValue,
            allowSyntheticDefaultImportsExplicitlyFalse = !boolValue,
        )
        "usedefineforclassfields" -> options.copy(useDefineForClassFields = boolValue)
        "verbatimmodulesyntax" -> options.copy(verbatimModuleSyntax = boolValue)
        "nocheck" -> options.copy(noCheck = boolValue)
        "emitdeclarationonly" -> options.copy(emitDeclarationOnly = boolValue)
        "maproot" -> options.copy(mapRoot = value.trim())
        "outfile" -> options.copy(outFile = value.trim())
        "out" -> options.copy(out = value.trim()) // 'out' is removed (TS5102), don't set outFile
        "alwaysstrict" -> options.copy(alwaysStrict = boolValue)
        "newline" -> options.copy(newLine = value.trim())
        "fullemitpaths" -> options.copy(fullEmitPaths = boolValue)
        "allowunreachablecode" -> options.copy(allowUnreachableCode = boolValue)
        "allowunusedlabels" -> options.copy(allowUnusedLabels = boolValue)
        "nofallthroughcasesinswitch" -> options.copy(noFallthroughCasesInSwitch = boolValue)
        "noresolve" -> options.copy(noResolve = boolValue)
        "noimplicitreferences" -> options.copy(noImplicitReferences = boolValue)
        "moduledetection" -> options.copy(moduleDetection = value.trim())
        "charset" -> options.copy(charset = value.trim())
        "keyofstringsonly" -> options.copy(keyofStringsOnly = boolValue)
        "noimplicitusestrict" -> options.copy(noImplicitUseStrict = boolValue)
        "nostrictgenericchecks" -> options.copy(noStrictGenericChecks = boolValue)
        "suppressexcesspropertyerrors" -> options.copy(suppressExcessPropertyErrors = boolValue)
        "suppressimplicitanyindexerrors" -> options.copy(suppressImplicitAnyIndexErrors = boolValue)
        "importsnotusedasvalues" -> options.copy(importsNotUsedAsValues = value.trim())
        "preservevalueimports" -> options.copy(preserveValueImports = boolValue)
        "resolvejsonmodule" -> options.copy(resolveJsonModule = boolValue)
        "inlinesourcemap" -> options.copy(inlineSourceMap = boolValue)
        "ignoredeprecations" -> options.copy(ignoreDeprecations = value.trim())
        "typescriptversion" -> options.copy(simulatedTypeScriptVersion = value.trim())
        else -> options
    }
}

/**
 * Parses a tsconfig.json content and applies its `compilerOptions` to the given options.
 * Uses simple string matching rather than a full JSON parser.
 * Tracks option value positions for emitting positioned deprecation diagnostics.
 */
private fun applyTsconfigOptions(options: CompilerOptions, json: String, tsconfigFileName: String = "tsconfig.json"): CompilerOptions {
    // Extract the compilerOptions block
    val compilerOptionsStart = json.indexOf("\"compilerOptions\"")
    if (compilerOptionsStart < 0) return options

    val braceStart = json.indexOf('{', compilerOptionsStart + "\"compilerOptions\"".length)
    if (braceStart < 0) return options

    // Find matching closing brace
    var depth = 1
    var pos = braceStart + 1
    while (pos < json.length && depth > 0) {
        when (json[pos]) {
            '{' -> depth++
            '}' -> depth--
        }
        pos++
    }
    val blockStart = braceStart + 1
    val compilerOptionsBlock = json.substring(blockStart, pos - 1)

    // Parse key-value pairs from the block, tracking positions relative to the full JSON
    // keyStart/keyLength point to the option KEY (e.g., "baseUrl"), valueStart/valueLength to the VALUE
    data class KvMatch(
        val key: String, val value: String,
        val keyStart: Int, val keyLength: Int,
        val valueStart: Int, val valueLength: Int,
    )

    val kvPattern = Regex(""""(\w+)"\s*:\s*("([^"]*)"|(true|false)|(\d+))""")
    val kvMatches = mutableListOf<KvMatch>()
    for (match in kvPattern.findAll(compilerOptionsBlock)) {
        val key = match.groupValues[1].lowercase()
        val value = match.groupValues[3].ifEmpty {
            match.groupValues[4].ifEmpty {
                match.groupValues[5]
            }
        }
        // Key position: full match starts at the opening quote of the key
        val keyStartInJson = blockStart + match.range.first
        val keyLength = match.groupValues[1].length + 2 // +2 for quotes
        // Value position: group 2 is the full value (including quotes for strings)
        val valueGroup = match.groups[2]!!
        val valueStartInJson = blockStart + valueGroup.range.first
        val valueLength = valueGroup.range.last - valueGroup.range.first + 1
        kvMatches.add(KvMatch(key, value, keyStartInJson, keyLength, valueStartInJson, valueLength))
    }

    // Parse array-valued options (e.g. moduleSuffixes: [".ios", ""])
    val arrayPattern = Regex(""""(\w+)"\s*:\s*\[([^\]]*)]""")
    val arrayPairs = mutableListOf<Pair<String, List<String>>>()
    for (match in arrayPattern.findAll(compilerOptionsBlock)) {
        val key = match.groupValues[1].lowercase()
        val items = Regex(""""([^"]*)"""").findAll(match.groupValues[2])
            .map { it.groupValues[1] }.toList()
        arrayPairs.add(key to items)
    }

    // Only apply a safe subset of tsconfig options that our transpiler handles correctly.
    val allowedTsconfigOptions = setOf(
        "target", "module", "strict", "noemit", "noemithelpers",
        "declaration", "declarationmap", "removecomments", "preserveconstenums", "sourcemap",
        "experimentaldecorators", "emitdecoratormetadata", "jsx", "jsxfactory", "jsxfragmentfactory", "reactnamespace",
        "esmoduleinterop", "isolatedmodules", "downleveliteration",
        "importhelpers", "allowsyntheticdefaultimports", "usedefineforclassfields",
        "verbatimmodulesyntax", "emitdeclarationonly", "outfile",
        "alwaysstrict", "newline", "noresolve", "moduledetection",
        "outdir", "allowjs", "ignoredeprecations", "moduleresolution",
        // Deprecated/removed options needed for diagnostics
        "charset", "keyofstringsonly", "noimplicitusestrict", "nostrictgenericchecks",
        "suppressexcesspropertyerrors", "suppressimplicitanyindexerrors",
        "out", "importsnotusedasvalues", "preservevalueimports",
        "noimplicitany", "noimplicitreturns", "strictnullchecks",
        "nounusedlocals", "nounusedparameters", "baseurl",
        "resolvejsonmodule", "inlinesourcemap", "sourcemap", "maproot",
    )

    // Compute line/column positions for option keys and values in the tsconfig JSON
    val optionPositions = mutableMapOf<String, TsconfigOptionPosition>()
    // Add a synthetic "compileroptionskey" entry pointing to the "compilerOptions" key itself.
    // This is used as a fallback position for TS5107 deprecation diagnostics when the deprecated
    // option is set via CLI/test directive (not in tsconfig), but a tsconfig is present.
    // TypeScript attributes such CLI-level deprecated options to the "compilerOptions" key position.
    val compilerOptionsKeyStart = json.indexOf("\"compilerOptions\"")
    if (compilerOptionsKeyStart >= 0) {
        val keyLength = "\"compilerOptions\"".length
        val keyLineCol = computeLineAndColumn(json, compilerOptionsKeyStart)
        optionPositions["compileroptionskey"] = TsconfigOptionPosition(
            fileName = tsconfigFileName,
            keyLine = keyLineCol.first,
            keyCharacter = keyLineCol.second,
            keyStart = compilerOptionsKeyStart,
            keyLength = keyLength,
            valueLine = keyLineCol.first,
            valueCharacter = keyLineCol.second,
            valueStart = compilerOptionsKeyStart,
            valueLength = keyLength,
        )
    }
    for (kv in kvMatches) {
        if (kv.key in allowedTsconfigOptions) {
            val keyLineCol = computeLineAndColumn(json, kv.keyStart)
            val valueLineCol = computeLineAndColumn(json, kv.valueStart)
            optionPositions[kv.key] = TsconfigOptionPosition(
                fileName = tsconfigFileName,
                keyLine = keyLineCol.first,
                keyCharacter = keyLineCol.second,
                keyStart = kv.keyStart,
                keyLength = kv.keyLength,
                valueLine = valueLineCol.first,
                valueCharacter = valueLineCol.second,
                valueStart = kv.valueStart,
                valueLength = kv.valueLength,
            )
        }
    }

    var result = options
    for (kv in kvMatches) {
        if (kv.key !in allowedTsconfigOptions) continue
        result = applyDirective(result, kv.key, kv.value)
    }
    // Apply array options
    for ((key, values) in arrayPairs) {
        result = when (key) {
            "modulesuffixes" -> result.copy(moduleSuffixes = values)
            else -> result
        }
    }
    // Merge tsconfig option positions into the result
    if (optionPositions.isNotEmpty()) {
        result = result.copy(tsconfigOptionPositions = result.tsconfigOptionPositions + optionPositions)
    }
    return result
}

/** Compute 1-based line and 1-based column for a 0-based offset in text. */
private fun computeLineAndColumn(text: String, offset: Int): Pair<Int, Int> {
    var line = 1
    var col = 1
    for (i in 0 until offset.coerceAtMost(text.length)) {
        if (text[i] == '\n') {
            line++
            col = 1
        } else if (text[i] != '\r') {
            col++
        }
    }
    return line to col
}
