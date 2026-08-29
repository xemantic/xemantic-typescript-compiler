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

import com.xemantic.typescript.compiler.FrontEnd
import com.xemantic.typescript.compiler.LexDefer
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * (INC.16) ONE ARM of the `bindLexicalScopes` deferral, selected by the
 * `XTSC_LEX_DEFER` environment variable (`0` restores the pre-(INC.16) eager build;
 * anything else is the shipped deferral). One arm per PROCESS by round 867's law — the arms
 * are the same code driven by a flag, so a shared branch profile makes them
 * dependent.
 *
 * It prints four things:
 *
 *  * the LAZY/EAGER build counts for a FULL build and for a FLOOR one — the
 *    deferral's whole question is how many of the program's files are asked for;
 *  * the `forcedBy` census (which `PassTiming.currentPass` was first to force each
 *    file), i.e. the READER the queue entry says blocks the deferral;
 *  * per-file FINGERPRINTS of the INV.2(c) tables, written to `$XTSC_LEX_FP` — the
 *    two arms' files are diffed by the driver script, and that diff is hazard (a);
 *  * floor / full / narrowed-query medians plus the `FrontEnd` bind rows.
 */
private const val NOWHERE = "/no/such/file/the/program/does/not/contain.ts"

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [warmups]" }
    val project = args[0]
    val warmups = if (args.size > 1) args[1].toInt() else 3
    LexDefer.deferred = System.getenv("XTSC_LEX_DEFER") != "0"
    LexDefer.verifySkip = System.getenv("XTSC_LEX_VERIFY") == "1"
    println("ARM lexDeferred=${LexDefer.deferred} verifySkip=${LexDefer.verifySkip}")

    val compiler = ProjectCompiler(SystemVfs)
    fun full() = compiler.build(project, noEmit = true)
    fun floor() = compiler.build(project, noEmit = true, recheckOnly = setOf(NOWHERE))
    fun query(f: String) = compiler.build(project, noEmit = true, recheckOnly = setOf(f))

    fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    // ---- sanity + the build-count census, on a cold pair.
    LexDefer.resetCounters(); LexDefer.census = true
    val probeFull = full()
    println("COUNTS full  lazy=${LexDefer.lazyBuilds} eager=${LexDefer.eagerBuilds} " +
        "skipped=${LexDefer.skippedFiles} violations=${LexDefer.skipViolations} " +
        "localsSkipped=${LexDefer.localsSkippedFiles} " +
        "localsViolations=${LexDefer.localsSkipViolations} " +
        "localsVisited=${LexDefer.localsSymbolsVisited}")
    for ((pass, n) in LexDefer.forcedBy) println("FORCEDBY full  $n $pass")
    LexDefer.resetCounters()
    val probeFloor = floor()
    println("COUNTS floor lazy=${LexDefer.lazyBuilds} eager=${LexDefer.eagerBuilds} " +
        "skipped=${LexDefer.skippedFiles} violations=${LexDefer.skipViolations} " +
        "localsSkipped=${LexDefer.localsSkippedFiles} " +
        "localsViolations=${LexDefer.localsSkipViolations} " +
        "localsVisited=${LexDefer.localsSymbolsVisited}")
    for ((pass, n) in LexDefer.forcedBy) println("FORCEDBY floor $n $pass")
    LexDefer.census = false

    require(probeFull.diagnostics.isNotEmpty()) { "REFUSED: full build reports no diagnostics" }
    require(probeFloor.diagnostics.isEmpty()) { "REFUSED: floor build is not a floor" }
    val files = probeFull.programFiles.sorted()
    println("program: files=${files.size} fullDiagnostics=${probeFull.diagnostics.size}")

    // ---- the fingerprint arm: a FULL build with the census on, so every file's
    // tables exist in BOTH arms and the comparison is not a comparison of absence.
    LexDefer.fingerprints.clear()
    LexDefer.census = true
    full()
    LexDefer.census = false
    System.getenv("XTSC_LEX_FP")?.let { path ->
        File(path).printWriter().use { w ->
            for ((f, h) in LexDefer.fingerprints.toSortedMap()) w.println("$f $h")
        }
        println("FP wrote ${LexDefer.fingerprints.size} rows to $path")
    }

    // ---- warm up and discard.
    repeat(warmups) { full(); floor() }

    // ---- timings, palindrome-drawn.
    fun median(xs: MutableList<Long>): Long { xs.sort(); return xs[xs.size / 2] }
    val fl = ArrayList<Long>(); val fu = ArrayList<Long>()
    repeat(2) { fu.add(timed { full() }); fl.add(timed { floor() }) }
    repeat(2) { fl.add(timed { floor() }); fu.add(timed { full() }) }
    println("WALL floor median=${median(fl)} $fl")
    println("WALL full  median=${median(fu)} $fu")

    // ---- the narrowed-query ladder: every program file, once.
    val q = ArrayList<Long>()
    for (f in files) q.add(timed { query(f) })
    val sorted = q.sorted()
    println("QUERY n=${q.size} median=${sorted[sorted.size / 2]} min=${sorted.first()} max=${sorted.last()}")
    println("QUERY sum=${q.sum()}")

    // ---- FrontEnd rows on a floor build and on a full one.
    fun feArm(tag: String, block: () -> Unit) {
        FrontEnd.reset(); FrontEnd.mode = FrontEnd.ON
        val ms = timed(block)
        FrontEnd.mode = FrontEnd.OFF
        println("FE.$tag wall=$ms")
        for (s in 0 until FrontEnd.N) {
            if (FrontEnd.calls[s] == 0L) continue
            println("FE $tag ${FrontEnd.nanos[s] / 1000} us calls=${FrontEnd.calls[s]} ${FrontEnd.names[s].trim()}")
        }
    }
    feArm("floor") { floor() }
    feArm("full") { full() }

    // ---- the pass table on a floor build (rows tier).
    PassTiming.reset(); PassTiming.detail = false; PassTiming.spineDetail = false
    PassTiming.enabled = true
    val ptMs = timed { floor() }
    PassTiming.enabled = false
    println("PT.floor wall=$ptMs init=${PassTiming.checkerInitNanos / 1000}us " +
        "sum=${PassTiming.passNanos.values.sum() / 1000}us")
    for ((name, nanos) in PassTiming.passNanos.entries.sortedByDescending { it.value }.take(15)) {
        println("PT floor ${nanos / 1000} us $name")
    }
}
