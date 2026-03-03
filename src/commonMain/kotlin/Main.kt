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
 * CLI entry point for the xemantic TypeScript compiler.
 *
 * Usage:
 * ```
 * xemantic-typescript-compiler <input.ts> [output.js]
 * ```
 *
 * If `output.js` is omitted the output file name is derived from `input.ts`
 * by replacing the `.ts` extension with `.js`.
 *
 * Exit codes:
 * - `0` — compilation succeeded (possibly with warnings)
 * - `1` — compilation failed with errors, or invalid arguments
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        error("Usage: xemantic-typescript-compiler <input.ts> [output.js]\nError: no input file specified")
    }

    val inputPath = args[0]
    val outputPath = if (args.size > 1) args[1] else deriveOutputPath(inputPath)

    println("xemantic-typescript-compiler: $inputPath -> $outputPath")

    // TODO: read input file and compile with TypeScriptCompiler, then write output
    error("TypeScript compilation is not yet implemented")
}

private fun deriveOutputPath(
    inputPath: String
) = if (inputPath.endsWith(".ts")) {
    inputPath.dropLast(3) + ".js"
} else {
    "$inputPath.js"
}
