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
 */
data class Diagnostic(
    val message: String,
    val category: DiagnosticCategory,
    val code: Int,
    val fileName: String? = null,
    val line: Int? = null,
    val character: Int? = null,
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
     * @return A [CompilationResult] containing the JavaScript output and any diagnostics.
     */
    fun compile(
        source: String,
        fileName: String = "input.ts",
    ): CompilationResult {
        // Parse multi-file source and compiler options
        val parsed = parseMultiFileSource(source, fileName)
        val options = parsed.options
        val diagnostics = mutableListOf<Diagnostic>()

        // TS5107: AMD and System module formats are deprecated
        if (options.module == ModuleKind.AMD) {
            diagnostics.add(Diagnostic(
                message = "Option 'module=AMD' is deprecated and will stop functioning in TypeScript 7.0. Specify compilerOption '\"ignoreDeprecations\": \"6.0\"' to silence this error.",
                category = DiagnosticCategory.Error,
                code = 5107,
            ))
        }
        if (options.module == ModuleKind.System) {
            diagnostics.add(Diagnostic(
                message = "Option 'module=System' is deprecated and will stop functioning in TypeScript 7.0. Specify compilerOption '\"ignoreDeprecations\": \"6.0\"' to silence this error.",
                category = DiagnosticCategory.Error,
                code = 5107,
            ))
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

            val parser = Parser(file.content, file.fileName)
            val sourceFile = parser.parse()
            diagnostics.addAll(parser.getDiagnostics())

            val transformer = Transformer(options)
            val transformed = transformer.transform(sourceFile)

            val emitter = Emitter(options)
            val javascript = emitter.emit(transformed, sourceFile)

            val tsxExtension = if (options.jsx?.lowercase() == "preserve") ".jsx" else ".js"
            val jsName = options.outFile?.substringAfterLast('/')
                ?: file.fileName.substringAfterLast('/')
                    .replace(".tsx", tsxExtension)
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
                    val jsonContent = stripJsonTrailingCommas(file.content).trimEnd()
                    if (options.fullEmitPaths) {
                        val outDir = options.outDir.trimEnd('/')
                        val jsonBaseName = file.fileName.substringAfterLast('/')
                        jsonOutputs.add("$outDir/$jsonBaseName" to jsonContent)
                    } else {
                        val jsonBaseName = file.fileName.substringAfterLast('/')
                        jsonOutputs.add(jsonBaseName to jsonContent)
                    }
                    continue
                }

                // Skip non-TS files; include .js/.jsx/.mjs/.cjs only when outDir is set
                // (without outDir, TypeScript skips re-emitting JS files to avoid overwriting sources)
                val isJsFile = file.fileName.endsWith(".js") || file.fileName.endsWith(".jsx") ||
                        file.fileName.endsWith(".mjs") || file.fileName.endsWith(".cjs")
                val isTsFile = file.fileName.endsWith(".ts") || file.fileName.endsWith(".tsx") ||
                        file.fileName.endsWith(".mts") || file.fileName.endsWith(".cts")
                if (!isTsFile && !isJsFile) {
                    continue
                }
                if (isJsFile && options.outDir == null && options.outFile == null) {
                    continue
                }
                // Skip .d.ts/.d.mts/.d.cts files (they don't produce JS output)
                if (file.fileName.endsWith(".d.ts") || file.fileName.endsWith(".d.mts") || file.fileName.endsWith(".d.cts")) {
                    continue
                }

                tsFileNames.add(file.fileName)

                val parser = Parser(file.content, file.fileName)
                val sourceFile = parser.parse()
                diagnostics.addAll(parser.getDiagnostics())

                // Extract relative imports for dependency ordering
                importDeps[file.fileName] = extractRelativeImports(sourceFile, file.fileName, parsed.files)

                val transformer = Transformer(options)
                val transformed = transformer.transform(sourceFile)

                val emitter = Emitter(options)
                val javascript = emitter.emit(transformed, sourceFile)

                // .tsx → .jsx only when jsx=preserve; all other modes (react, react-jsx, etc.) produce .js
                val tsxExtension = if (options.jsx?.lowercase() == "preserve") ".jsx" else ".js"
                var jsName = file.fileName
                    .replace(".tsx", tsxExtension)
                    .replace(".mts", ".mjs")
                    .replace(".cts", ".cjs")
                    .replace(".ts", ".js")
                // When fullEmitPaths + outDir, prepend outDir to basename
                if (options.fullEmitPaths && options.outDir != null) {
                    val outDir = options.outDir.trimEnd('/')
                    val base = jsName.substringAfterLast('/')
                    jsName = "$outDir/$base"
                } else {
                    // Strip directory prefix — baseline uses just basenames
                    jsName = jsName.substringAfterLast('/')
                }
                jsOutputMap[file.fileName] = jsName to javascript
            }

            // Sort JS outputs by dependency order (dependencies first)
            // Skip sorting when noResolve is set (TypeScript doesn't resolve imports in that mode)
            val sortedTsFiles = if (options.noResolve) tsFileNames else topologicalSort(tsFileNames, importDeps)
            val jsOutputs = sortedTsFiles.mapNotNull { jsOutputMap[it] }

            // When outFile is set, concatenate all JS outputs into a single file
            val finalJsOutputs = if (options.outFile != null && jsOutputs.isNotEmpty()) {
                val outFileName = options.outFile.substringAfterLast('/')
                // Concatenate, but only keep the first "use strict"; directive
                var seenUseStrict = false
                val parts = jsOutputs.map { (_, js) ->
                    if (!seenUseStrict) {
                        seenUseStrict = js.trimStart().startsWith("\"use strict\"")
                        js
                    } else {
                        // Remove leading "use strict"; from subsequent files
                        js.replace(Regex("""^\s*"use strict";\n?"""), "")
                    }
                }
                val concatenated = parts.joinToString("\n")
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
): List<String> {
    val allTsFileNames = allFiles.map { it.fileName }.toSet()
    val deps = mutableListOf<String>()
    val lastSlash = currentFileName.lastIndexOf('/')
    val dir = when {
        lastSlash > 0 -> currentFileName.substring(0, lastSlash)
        lastSlash == 0 -> "/" // absolute root path like /index.ts
        else -> ""
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

        // Try with .ts and .tsx extensions, and also /index.ts for directory imports (e.g. "./")
        val sep = if (resolved.isEmpty() || resolved.endsWith("/")) "" else "/"
        for (candidate in listOf(
            "$resolved.ts", "$resolved.tsx", resolved,
            "${resolved}${sep}index.ts", "${resolved}${sep}index.tsx"
        )) {
            if (candidate in allTsFileNames) {
                deps.add(candidate)
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
