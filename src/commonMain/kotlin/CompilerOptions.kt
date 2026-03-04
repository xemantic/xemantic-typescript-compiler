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
    val alwaysStrict: Boolean? = null,
    val newLine: String? = null,
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

data class SourceFileEntry(
    val fileName: String,
    val content: String,
)

/**
 * Result of parsing compiler options and splitting multi-file sources.
 */
data class ParsedSource(
    val options: CompilerOptions,
    val files: List<SourceFileEntry>,
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
    // Drop leading empty lines after directive removal
    val trimmedLines = sourceLines.dropWhile { it.trim().trimEnd('\r').isEmpty() }
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
                        fileEntries.add(SourceFileEntry(currentFileName, currentLines.joinToString("\n")))
                        currentLines.clear()
                    }
                    currentFileName = value
                    inGlobalDirectives = false
                } else {
                    directives[key] = value
                    if (inGlobalDirectives) {
                        globalDirectiveLines.add(line)
                    }
                }
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
        fileEntries.add(SourceFileEntry(currentFileName, currentLines.joinToString("\n")))
    }

    var options = CompilerOptions()
    for ((key, value) in directives) {
        options = applyDirective(options, key, value)
    }

    if (fileEntries.isEmpty()) {
        // Single-file test: use the original parseCompilerOptions for source cleanup
        val (_, cleanedSource) = parseCompilerOptions(source)
        return ParsedSource(options, listOf(SourceFileEntry(testFileName, cleanedSource)))
    }

    return ParsedSource(options, fileEntries)
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
        "alwaysstrict" -> options.copy(alwaysStrict = boolValue)
        "newline" -> options.copy(newLine = value.trim())
        else -> options
    }
}
