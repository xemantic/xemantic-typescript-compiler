/*
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
public data class CompilationResult(
    val javascript: String?,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    /** `true` if any diagnostic with [DiagnosticCategory.Error] category was produced. */
    public val hasErrors: Boolean get() = diagnostics.any { it.category == DiagnosticCategory.Error }
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
public data class Diagnostic(
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
public enum class DiagnosticCategory {
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
public class TypeScriptCompiler {

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
     * @throws NotImplementedError until the compiler is implemented.
     */
    public fun compile(
        source: String,
        fileName: String = "input.ts",
    ): CompilationResult {
        TODO("TypeScript to JavaScript compilation is not yet implemented")
    }

}
