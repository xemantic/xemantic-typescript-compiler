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

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs

/**
 * (INC.1) THE GATE: for EVERY file of a real project, does a partition of ONE report
 * exactly the diagnostics the full build reports for that file?
 *
 * `recheckOnly` narrows the checker to `assignedFileNames` (the INV.6 view). Its
 * contract is sequential equivalence, and the corpus cannot test it: a corpus fixture
 * is one or two files, where a partition of one is nearly the whole program. The
 * failure mode it has to exclude is round 609's — a per-file COLLECTOR loop gated on
 * `checkedResults` instead of `binderResults` starves the partition of program-wide
 * suppression context and invents false positives — and that only shows on a program
 * with real cross-file structure.
 *
 * A file with no diagnostics agrees VACUOUSLY, so the run prints how many files
 * carried diagnostics in the full build and REFUSES a verdict when that is zero: a
 * green sweep over an all-clean program tests nothing (CLAUDE.md rounds 853/873).
 *
 * ```
 * scripts/partition-equivalence.sh [<projectDir>]
 * ```
 *
 * Prints one line per DISAGREEING file plus a summary; exit 1 on any disagreement.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [maxFiles]" }
    val limit = if (args.size > 1) args[1].toInt() else Int.MAX_VALUE
    val compiler = ProjectCompiler(SystemVfs)

    fun rowsOf(diagnostics: List<com.xemantic.typescript.compiler.Diagnostic>) =
        diagnostics.groupBy { it.fileName }
            .mapValues { (_, ds) ->
                ds.map { "${it.code}|${it.start}|${it.length}|${it.message}" }.sorted()
            }

    // A warm-up build, discarded. Without it the full arm is the first build in the
    // process — the slowest draw in this repo by a wide margin — and the ratio it is
    // divided into would compare a COLD number against warm ones, which is the error
    // CLAUDE.md names for every cross-regime comparison here.
    compiler.build(args[0], noEmit = true)
    val at = System.nanoTime()
    val full = compiler.build(args[0], noEmit = true)
    val fullMs = (System.nanoTime() - at) / 1_000_000
    val fullRows = rowsOf(full.diagnostics)
    val filesWithDiagnostics = full.programFiles.count { !fullRows[it].isNullOrEmpty() }
    println(
        "full build: ${fullMs}ms  files=${full.programFiles.size}  " +
            "diagnostics=${full.diagnostics.size}  filesCarryingThem=$filesWithDiagnostics",
    )
    require(filesWithDiagnostics > 0) {
        "REFUSED: the full build reports no per-file diagnostics, so every comparison " +
            "below would agree vacuously. Point this at a project that has some."
    }

    // THE FLOOR, free and needing no probe: a partition naming a file the program does
    // not contain checks NOTHING per-file, so what it costs is exactly the part no
    // narrowing can remove — the crawl, the parse, the bind and the program-wide
    // passes. Every partition time below is that floor plus one file's own checking,
    // which is what says whether the next lever is per-file work or program-wide work.
    val floorAt = System.nanoTime()
    compiler.build(args[0], noEmit = true, recheckOnly = setOf("/no/such/file.ts"))
    val floorMs = (System.nanoTime() - floorAt) / 1_000_000
    println("floor (partition of nothing: crawl + parse + bind + program-wide passes): ${floorMs}ms")

    var disagreed = 0
    var partitionTotal = 0L
    var slowest = 0L
    var slowestFile = ""
    val each = ArrayList<Long>()
    val targets = full.programFiles.take(limit)
    for (file in targets) {
        val t0 = System.nanoTime()
        val part = compiler.build(args[0], noEmit = true, recheckOnly = setOf(file))
        val ms = (System.nanoTime() - t0) / 1_000_000
        partitionTotal += ms
        each.add(ms)
        if (ms > slowest) { slowest = ms; slowestFile = file }
        val expected = fullRows[file] ?: emptyList()
        val actual = rowsOf(part.diagnostics)[file] ?: emptyList()
        if (expected != actual) {
            disagreed++
            println("DISAGREE ${file.substringAfterLast('/')}  full=${expected.size} partition=${actual.size}")
            for (row in (expected - actual.toSet())) println("   only-full: ${row.take(160)}")
            for (row in (actual - expected.toSet())) println("   only-part: ${row.take(160)}")
        }
    }
    each.sort()
    val median = each[each.size / 2]
    println(
        "partition sweep: ${targets.size} files  min=${each.first()}ms  median=${median}ms  " +
            "mean=${partitionTotal / targets.size}ms  slowest=${slowest}ms " +
            "(${slowestFile.substringAfterLast('/')})",
    )
    println(
        "median file's OWN checking = median - floor = ${median - floorMs}ms  " +
            "(floor is ${"%.0f".format(100.0 * floorMs / median)}% of a median narrowed query)",
    )
    println(
        "warm full build=${fullMs}ms  ratio at the median file=" +
            "${"%.2f".format(fullMs.toDouble() / median)}x  " +
            "at the slowest=${"%.2f".format(fullMs.toDouble() / slowest)}x",
    )
    println(if (disagreed == 0) "EQUIVALENT: all ${targets.size} files agree" else "DIVERGED: $disagreed file(s)")
    if (disagreed != 0) kotlin.system.exitProcess(1)
}
