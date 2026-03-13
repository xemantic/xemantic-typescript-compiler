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
    ES3, ES5, ES2015, ES2016, ES2017, ES2018, ES2019, ES2020, ES2021, ES2022, ES2023, ESNext;

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
            "esnext" -> ESNext
            else -> null
        }
    }
}

enum class ModuleKind {
    None, CommonJS, AMD, UMD, System, ES2015, ES2020, ES2022, ESNext, Node16, NodeNext;

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
            "nodenext" -> NodeNext
            else -> null
        }
    }
}

data class CompilerOptions(
    val target: ScriptTarget = ScriptTarget.ES3,
    val module: ModuleKind? = null,
    val strict: Boolean = false,
    val noEmit: Boolean = false,
    val noEmitHelpers: Boolean = false,
    val declaration: Boolean = false,
    val removeComments: Boolean = false,
    val preserveConstEnums: Boolean = false,
    val sourceMap: Boolean = false,
    val noImplicitAny: Boolean = false,
    val noImplicitReturns: Boolean = false,
    val noImplicitThis: Boolean = false,
    val strictNullChecks: Boolean = false,
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
    val esModuleInterop: Boolean = false,
    val allowJs: Boolean = false,
    val checkJs: Boolean = false,
    val isolatedModules: Boolean = false,
    val skipLibCheck: Boolean = false,
    val forceConsistentCasingInFileNames: Boolean = false,
    val noEmitOnError: Boolean = false,
    val downlevelIteration: Boolean = false,
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
    val noResolve: Boolean = false,
    val moduleDetection: String? = null,
    val moduleSuffixes: List<String>? = null,
) {
    val effectiveAlwaysStrict: Boolean
        get() = alwaysStrict ?: strict

    val effectiveTarget: ScriptTarget
        get() = target

    val effectiveModule: ModuleKind
        get() = module ?: when {
            target >= ScriptTarget.ES2015 -> ModuleKind.ES2015
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
    // .cjs/.cts files are always CJS regardless of module setting
    if (fileName.endsWith(".cjs") || fileName.endsWith(".cts")) return false
    // .mjs/.mts files are always ESM regardless of module setting
    if (fileName.endsWith(".mjs") || fileName.endsWith(".mts")) return true
    return when (module) {
        ModuleKind.ES2015, ModuleKind.ES2020, ModuleKind.ES2022, ModuleKind.ESNext -> true
        ModuleKind.Node16, ModuleKind.NodeNext -> {
            !fileName.endsWith(".cts")
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
    val cleaned = source.removePrefix("\uFEFF")
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
    val cleaned = source.removePrefix("\uFEFF")
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
    for ((key, value) in directives) {
        options = applyDirective(options, key, value)
    }

    // Apply options from tsconfig.json if present in the file entries
    val tsconfigEntry = fileEntries.find { it.fileName.substringAfterLast('/') == "tsconfig.json" }
    if (tsconfigEntry != null) {
        options = applyTsconfigOptions(options, tsconfigEntry.content)
    }

    if (fileEntries.isEmpty()) {
        // Single-file test: use the original parseCompilerOptions for source cleanup
        val (_, cleanedSource) = parseCompilerOptions(source)
        return ParsedSource(options, listOf(SourceFileEntry(testFileName, cleanedSource)))
    }

    return ParsedSource(options, fileEntries, hasExplicitFilenames = true)
}

private fun applyDirective(options: CompilerOptions, key: String, value: String): CompilerOptions {
    val boolValue = value.lowercase() == "true"
    return when (key) {
        "target" -> {
            val target = ScriptTarget.fromString(value.split(",")[0].trim())
            if (target != null) options.copy(target = target) else options
        }

        "module" -> {
            val module = ModuleKind.fromString(value.trim())
            if (module != null) options.copy(module = module) else options
        }

        "strict" -> options.copy(strict = boolValue)
        "noemit" -> options.copy(noEmit = boolValue)
        "noemithelpers" -> options.copy(noEmitHelpers = boolValue)
        "declaration" -> options.copy(declaration = boolValue)
        "removecomments" -> options.copy(removeComments = boolValue)
        "preserveconstenums" -> options.copy(preserveConstEnums = boolValue)
        "sourcemap" -> options.copy(sourceMap = boolValue)
        "noimplicitany" -> options.copy(noImplicitAny = boolValue)
        "noimplicitreturns" -> options.copy(noImplicitReturns = boolValue)
        "noimplicitthis" -> options.copy(noImplicitThis = boolValue)
        "strictnullchecks" -> options.copy(strictNullChecks = boolValue)
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
        "esmoduleinterop" -> options.copy(esModuleInterop = boolValue)
        "allowjs" -> options.copy(allowJs = boolValue)
        "checkjs" -> options.copy(checkJs = boolValue)
        "isolatedmodules" -> options.copy(isolatedModules = boolValue)
        "skiplibcheck" -> options.copy(skipLibCheck = boolValue)
        "forceconsistentcasinginfilenames" -> options.copy(forceConsistentCasingInFileNames = boolValue)
        "noemitonerror" -> options.copy(noEmitOnError = boolValue)
        "downleveliteration" -> options.copy(downlevelIteration = boolValue)
        "importhelpers" -> options.copy(importHelpers = boolValue)
        "allowsyntheticdefaultimports" -> options.copy(allowSyntheticDefaultImports = boolValue)
        "usedefineforclassfields" -> options.copy(useDefineForClassFields = boolValue)
        "verbatimmodulesyntax" -> options.copy(verbatimModuleSyntax = boolValue)
        "nocheck" -> options.copy(noCheck = boolValue)
        "emitdeclarationonly" -> options.copy(emitDeclarationOnly = boolValue)
        "maproot" -> options.copy(mapRoot = value.trim())
        "outfile" -> options.copy(outFile = value.trim())
        "out" -> options.copy(outFile = value.trim())
        "alwaysstrict" -> options.copy(alwaysStrict = boolValue)
        "newline" -> options.copy(newLine = value.trim())
        "fullemitpaths" -> options.copy(fullEmitPaths = boolValue)
        "noresolve" -> options.copy(noResolve = boolValue)
        "moduledetection" -> options.copy(moduleDetection = value.trim())
        else -> options
    }
}

/**
 * Parses a tsconfig.json content and applies its `compilerOptions` to the given options.
 * Uses simple string matching rather than a full JSON parser.
 */
private fun applyTsconfigOptions(options: CompilerOptions, json: String): CompilerOptions {
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
    val compilerOptionsBlock = json.substring(braceStart + 1, pos - 1)

    // Parse key-value pairs from the block
    val kvPattern = Regex(""""(\w+)"\s*:\s*("([^"]*)"|(true|false)|(\d+))""")
    val kvPairs = mutableListOf<Pair<String, String>>()
    for (match in kvPattern.findAll(compilerOptionsBlock)) {
        val key = match.groupValues[1].lowercase()
        val value = match.groupValues[3].ifEmpty {
            match.groupValues[4].ifEmpty {
                match.groupValues[5]
            }
        }
        kvPairs.add(key to value)
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
    // Options like outDir, rootDir, paths, etc. affect module resolution in complex ways
    // that we don't fully support — skip them to avoid regressions.
    val allowedTsconfigOptions = setOf(
        "target", "module", "strict", "noemit", "noemithelpers",
        "declaration", "removecomments", "preserveconstenums", "sourcemap",
        "experimentaldecorators", "emitdecoratormetadata", "jsx", "jsxfactory", "jsxfragmentfactory", "reactnamespace",
        "esmoduleinterop", "isolatedmodules", "downleveliteration",
        "importhelpers", "allowsyntheticdefaultimports", "usedefineforclassfields",
        "verbatimmodulesyntax", "emitdeclarationonly", "outfile",
        "alwaysstrict", "newline", "noresolve", "moduledetection",
        "outdir", "allowjs",
    )

    var result = options
    for ((key, value) in kvPairs) {
        if (key !in allowedTsconfigOptions) continue
        result = applyDirective(result, key, value)
    }
    // Apply array options
    for ((key, values) in arrayPairs) {
        result = when (key) {
            "modulesuffixes" -> result.copy(moduleSuffixes = values)
            else -> result
        }
    }
    return result
}
