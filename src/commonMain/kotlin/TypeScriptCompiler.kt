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

            // Force JSX mode for .js files when jsx option is set (allowJs + jsx),
            // OR when allowJs is true (TypeScript enables JSX for .js files with allowJs)
            val isPlainJsFile = file.fileName.endsWith(".js") || file.fileName.endsWith(".cjs") || file.fileName.endsWith(".mjs")
            val forceJsxForJs = isPlainJsFile && (options.jsx != null || options.allowJs)
            val parser = Parser(file.content, file.fileName, forceJsx = forceJsxForJs)
            val sourceFile = parser.parse()
            diagnostics.addAll(parser.getDiagnostics())

            val transformer = Transformer(options)
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
                // Plain .js/.mjs/.cjs/.jsx: only when outDir/outFile is set (avoids overwriting sources)
                // .jsx is JavaScript+JSX (requires allowJs); without outDir/outFile, TypeScript skips it
                if ((isPureJsFile || isJsxFile) && options.outDir == null && options.outFile == null) {
                    continue
                }
                // Skip .d.ts/.d.mts/.d.cts files (they don't produce JS output)
                if (file.fileName.endsWith(".d.ts") || file.fileName.endsWith(".d.mts") || file.fileName.endsWith(".d.cts")) {
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
                val parser = Parser(file.content, file.fileName, forceJsx = forceJsxForJsMulti)
                val sourceFile = parser.parse()
                diagnostics.addAll(parser.getDiagnostics())

                // Extract relative imports for dependency ordering
                importDeps[file.fileName] = extractRelativeImports(
                    sourceFile, file.fileName, parsed.files, options.moduleSuffixes,
                    includeReferencePathDeps = options.outFile != null,
                )

                tsFileNames.add(file.fileName)

                val transformer = Transformer(options)
                val transformed = transformer.transform(sourceFile)

                val emitter = Emitter(options)
                val javascript = emitter.emit(transformed, sourceFile)

                // Skip files that produce no meaningful output (e.g. empty .tsx/.ts files)
                if (javascript.isBlank()) continue

                // .tsx/.jsx → .jsx only when jsx=preserve; all other modes produce .js
                val isJsxPreserveMulti = options.jsx?.lowercase() == "preserve"
                val tsxExtensionMulti = if (isJsxPreserveMulti) ".jsx" else ".js"
                val jsxExtensionMulti = if (isJsxPreserveMulti) ".jsx" else ".js"
                var jsName = file.fileName
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
                jsOutputMap[file.fileName] = jsName to javascript
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
                val parts = jsOutputs.map { (_, js) ->
                    if (js.trimStart().startsWith("\"use strict\"")) {
                        anyUseStrict = true
                        js.replace(Regex("""^\s*"use strict";\n?"""), "")
                    } else js
                }
                val body = parts.joinToString("\n")
                val concatenated = if (anyUseStrict) "\"use strict\";\n$body" else body
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
