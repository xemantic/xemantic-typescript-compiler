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

import kotlin.time.measureTimedValue

/**
 * CLI entry point for the xemantic TypeScript compiler — whole-project build.
 *
 * Usage:
 * ```
 * xemantic-typescript-compiler [--project|-p <dir-or-tsconfig>] [--noEmit] [path]
 * ```
 *
 * `path` (or `--project`) points at a directory containing `tsconfig.json`, or
 * directly at a tsconfig file; it defaults to the current directory. The compiler
 * loads the config, resolves the file graph (relative + `node_modules`), type-checks
 * the program, and (unless `--noEmit`) writes JS/declaration outputs to `outDir`.
 */
fun main(args: Array<String>) {
    var project = "."
    var noEmit = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--noEmit", "--noemit" -> noEmit = true
            "--project", "-p" -> { i++; if (i < args.size) project = args[i] }
            "--help", "-h" -> { printUsage(); return }
            else -> if (!a.startsWith("-")) project = a
        }
        i++
    }

    println("xemantic-typescript-compiler — whole-project build")
    println("project: $project${if (noEmit) "  (noEmit)" else ""}")

    val (result, duration) = measureTimedValue {
        ProjectCompiler(SystemVfs).build(project, noEmit)
    }

    println("config:  ${result.configPath}")
    println("files:   ${result.rootFiles.size} root, ${result.programFiles.size} in program")
    if (result.unresolved.isNotEmpty()) {
        println("unresolved imports: ${result.unresolved.size} (e.g. ${
            result.unresolved.take(5).joinToString(", ") { "'${it.second}'" }
        })")
    }
    if (!noEmit) println("emitted: ${result.written.size} output file(s)")

    printDiagnostics(result.diagnostics)
    println("time:    ${duration.inWholeMilliseconds} ms")
    println(if (result.errorCount == 0) "OK — 0 errors" else "FAILED — ${result.errorCount} error(s)")
}

private fun printDiagnostics(diagnostics: List<Diagnostic>) {
    val errors = diagnostics.filter { it.category == DiagnosticCategory.Error }
    val warnings = diagnostics.filter { it.category == DiagnosticCategory.Warning }
    println("diagnostics: ${errors.size} error(s), ${warnings.size} warning(s)")
    if (diagnostics.isEmpty()) return

    // Count by code for a compact overview.
    val byCode = diagnostics.groupingBy { it.code }.eachCount().entries.sortedByDescending { it.value }
    println("  by code: " + byCode.joinToString(", ") { "TS${it.key}×${it.value}" })

    // Show the first errors in detail.
    val shown = errors.take(30)
    for (d in shown) {
        val loc = if (d.fileName != null && d.line != null) "${d.fileName}:${d.line}:${d.character ?: 1}" else (d.fileName ?: "")
        println("  $loc - error TS${d.code}: ${d.message}")
    }
    if (errors.size > shown.size) println("  ... and ${errors.size - shown.size} more error(s)")
}

private fun printUsage() {
    println(
        """
        Usage: xemantic-typescript-compiler [--project|-p <dir-or-tsconfig>] [--noEmit] [path]

          path / --project   directory containing tsconfig.json, or a tsconfig path (default: .)
          --noEmit           type-check only; do not write outputs
          --help, -h         show this help
        """.trimIndent()
    )
}
