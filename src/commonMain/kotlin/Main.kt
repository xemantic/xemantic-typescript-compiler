/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
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
    var listAll = false
    var passTiming = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--noEmit", "--noemit" -> noEmit = true
            "--listAll", "--listall" -> listAll = true
            "--passTiming", "--passtiming" -> passTiming = true
            "--partitionCheck", "--partitioncheck" -> {
                i++; if (i < args.size) PartitionCheck.workers = args[i].toIntOrNull() ?: 0
            }
            "--workers" -> {
                i++; if (i < args.size) ParallelCheckMode.workers = args[i].toIntOrNull() ?: 0
            }
            "--project", "-p" -> { i++; if (i < args.size) project = args[i] }
            "--help", "-h" -> { printUsage(); return }
            else -> if (!a.startsWith("-")) project = a
        }
        i++
    }

    println("xemantic-typescript-compiler — whole-project build")
    println("project: $project${if (noEmit) "  (noEmit)" else ""}")

    if (passTiming) {
        PassTiming.reset()
        PassTiming.enabled = true
    }
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

    printDiagnostics(result.diagnostics, listAll)
    PartitionCheck.reportLines.forEach { println(it) }
    println("time:    ${duration.inWholeMilliseconds} ms")
    if (passTiming) PassTiming.dump(::println)
    println(if (result.errorCount == 0) "OK — 0 errors" else "FAILED — ${result.errorCount} error(s)")
}

private fun printDiagnostics(diagnostics: List<Diagnostic>, listAll: Boolean = false) {
    val errors = diagnostics.filter { it.category == DiagnosticCategory.Error }
    val warnings = diagnostics.filter { it.category == DiagnosticCategory.Warning }
    println("diagnostics: ${errors.size} error(s), ${warnings.size} warning(s)")
    if (diagnostics.isEmpty()) return

    // Count by code for a compact overview.
    val byCode = diagnostics.groupingBy { it.code }.eachCount().entries.sortedByDescending { it.value }
    println("  by code: " + byCode.joinToString(", ") { "TS${it.key}×${it.value}" })

    // Show the first errors in detail (--listAll: every error, for run-to-run FP diffing).
    val shown = if (listAll) errors else errors.take(30)
    for (d in shown) {
        val loc = if (d.fileName != null && d.line != null) "${d.fileName}:${d.line}:${d.character ?: 1}" else (d.fileName ?: "")
        println("  $loc - error TS${d.code}: ${d.message}")
        // --listAll: print elaboration chains too (indented; never matches the
        // `error TS` grep the A/B diffs key on) — TS2769/TS2322 triage needs them.
        if (listAll) d.messageChain.forEach { line -> println("      |$line") }
    }
    if (errors.size > shown.size) println("  ... and ${errors.size - shown.size} more error(s)")
}

private fun printUsage() {
    println(
        """
        Usage: xemantic-typescript-compiler [--project|-p <dir-or-tsconfig>] [--noEmit] [path]

          path / --project   directory containing tsconfig.json, or a tsconfig path (default: .)
          --noEmit           type-check only; do not write outputs
          --listAll          print every error (default: first 30) — for run-to-run FP diffing
          --passTiming       print the INV.0 per-pass wall-time table + recompute counters
          --partitionCheck N run N sequential partition checkers and diff vs the full run (INV.6(6b))
          --workers N        parallel share-nothing partition check on N threads (INV.6(6c); line order may differ)
                             + the INV.3(a) globals-lookup conflation classification
          --help, -h         show this help
        """.trimIndent()
    )
}
