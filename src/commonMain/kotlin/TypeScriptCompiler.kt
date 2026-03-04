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
 * @property javascript The compiled JavaScript output, or `null` if compilation
 *   produced only errors and generated no output.
 * @property diagnostics The list of diagnostic messages (errors, warnings, hints)
 *   produced during compilation.
 */
data class CompilationResult(
    val javascript: String?,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    /** `true` if any diagnostic with [DiagnosticCategory.Error] category was produced. */
    val hasErrors: Boolean get() = diagnostics.any { it.category == DiagnosticCategory.Error }
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

        if (parsed.files.size == 1) {
            // Single-file compilation
            val file = parsed.files[0]
            val parser = Parser(file.content, file.fileName)
            val sourceFile = parser.parse()
            diagnostics.addAll(parser.getDiagnostics())

            val transformer = Transformer(options)
            val transformed = transformer.transform(sourceFile)

            val emitter = Emitter(options)
            val javascript = emitter.emit(transformed, sourceFile)

            val baseline = formatBaseline(fileName, file.content, javascript)

            return CompilationResult(
                javascript = baseline,
                diagnostics = diagnostics,
            )
        } else {
            // Multi-file compilation
            val sourceEchoes = mutableListOf<Pair<String, String>>() // fileName -> content
            val jsOutputs = mutableListOf<Pair<String, String>>() // jsName -> javascript

            for (file in parsed.files) {
                // Don't echo tsconfig.json (it's a TypeScript project config, not a source file)
                val baseName = file.fileName.substringAfterLast('/')
                if (baseName != "tsconfig.json") {
                    sourceEchoes.add(file.fileName to file.content)
                }

                // Skip non-TS files (e.g., .json, .d.ts, .js)
                if (!file.fileName.endsWith(".ts") && !file.fileName.endsWith(".tsx")) {
                    continue
                }
                // Skip .d.ts files (they don't produce JS output)
                if (file.fileName.endsWith(".d.ts")) {
                    continue
                }

                val parser = Parser(file.content, file.fileName)
                val sourceFile = parser.parse()
                diagnostics.addAll(parser.getDiagnostics())

                val transformer = Transformer(options)
                val transformed = transformer.transform(sourceFile)

                val emitter = Emitter(options)
                val javascript = emitter.emit(transformed, sourceFile)

                val jsName = file.fileName
                    .replace(".tsx", ".jsx")
                    .replace(".ts", ".js")
                jsOutputs.add(jsName to javascript)
            }

            val baseline = formatMultiFileBaseline(fileName, sourceEchoes, jsOutputs)

            return CompilationResult(
                javascript = baseline,
                diagnostics = diagnostics,
            )
        }
    }

}
