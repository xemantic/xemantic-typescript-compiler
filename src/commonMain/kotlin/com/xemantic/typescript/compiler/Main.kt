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

import kotlin.system.exitProcess

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
public fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: xemantic-typescript-compiler <input.ts> [output.js]")
        println("Error: no input file specified")
        exitProcess(1)
    }

    val inputPath = args[0]
    val outputPath = if (args.size > 1) args[1] else deriveOutputPath(inputPath)

    println("xemantic-typescript-compiler: $inputPath -> $outputPath")

    // TODO: read input file and compile with TypeScriptCompiler, then write output
    println("Error: TypeScript compilation is not yet implemented")
    exitProcess(1)
}

private fun deriveOutputPath(inputPath: String): String =
    if (inputPath.endsWith(".ts")) inputPath.dropLast(3) + ".js" else "$inputPath.js"
