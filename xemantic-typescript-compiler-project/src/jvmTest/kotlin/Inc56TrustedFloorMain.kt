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
import java.io.File

/**
 * (INC.56) THE PRICE OF THE HOST'S FILESYSTEM PROMISE — one arm per JVM.
 *
 * The arm is a real per-keystroke query: edit one file through [Project.updateFile],
 * then ask [Project.diagnosticsOf] about that file alone. That build crawls, parses
 * and binds the whole program and checks one file, so it is the incremental FLOOR
 * plus one small file — the quantity the whole (INC.\*) arc is about.
 *
 * ## Why ONE arm per process
 *
 * Round 867: two arms that are the same code driven by different data are not
 * independent — the one that runs first writes the branch profile the other is
 * compiled against, and per-arm sd fell from 16-38% to 2-5% when each arm got its own
 * JVM. `arm` therefore selects `plain` or `trust`, and `scripts/inc56-trusted-floor.sh`
 * rotates the two ACROSS processes.
 *
 * ## What to read, and in what order
 *
 * Per (INC.72), the wall of a composite quantity carries terms that are not the
 * change: the crawl's own `read+decode` sum swung ±100 ms between two runs of ONE
 * binary in that round. So the receipts here are, in order:
 *
 *  1. `RETAINED` — the deterministic count of reads answered from memory. Zero on the
 *     `plain` arm and one per non-`.json` program file per build on `trust`.
 *  2. `FEROW` — the `--frontEnd` phase rows, above all `import-graph crawl (WALL)`.
 *  3. `WALL` — the query medians, as the sanity check.
 *
 * ```
 * java -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.Inc56TrustedFloorMainKt \
 *   <projectDir> plain|trust [rotations [instrumentedDraws]]
 * ```
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <projectDir> plain|trust [rotations [draws]]" }
    val dir = args[0]
    val arm = args[1]
    require(arm == "plain" || arm == "trust") { "arm must be plain or trust, was $arm" }
    val rotations = if (args.size > 2) args[2].toInt() else 8

    val project = Project.open(dir)
    project.trustFilesystem = arm == "trust"

    // The whole-program build the user already waited for: it is what establishes the
    // program and, on the `trust` arm, what fills the retention. Every arm below is a
    // query AFTER it, which is the state an editor lives in.
    val all = project.diagnostics()
    val target = project.files.first { it.endsWith(".ts") && !it.endsWith(".d.ts") }
    val text = File(target).readText()
    println("arm=$arm files=${project.files.size} diagnostics=${all.size} target=$target")

    fun query(rotation: Int): Long {
        project.updateFile(target, text + "\n// xtsc keystroke $rotation\n")
        val at = System.nanoTime()
        project.diagnosticsOf(setOf(target))
        return (System.nanoTime() - at) / 1_000_000
    }

    // Warm up and discard: the first builds in a process are the slowest draws in this
    // repo by a wide margin (round 869), and the ramp belongs to no arm.
    repeat(6) { query(-it - 1) }

    val walls = ArrayList<Long>()
    repeat(rotations) { walls.add(query(it)) }
    walls.sort()
    println("WALL $arm median=${walls[walls.size / 2]}ms $walls")
    println("RETAINED $arm ${project.retainedReadCount}")

    // Instrumented draws. The FIRST is discarded — a probe's own cost warms up too
    // (round 846: first instrumented rebuild 3,457 ms, second 1,856) — and the rest are
    // reported as a MEDIAN per row, because one draw of the crawl's wall cannot resolve
    // this: (INC.72) measured that quantity carrying a ±20 ms concurrent term, and this
    // change is inside that very term.
    val draws = 1 + (if (args.size > 3) args[3].toInt() else 6)
    val crawlWall = ArrayList<Long>()
    val readRow = ArrayList<Long>()
    val resolveRow = ArrayList<Long>()
    val lastRows = LongArray(FrontEnd.N)
    val lastCalls = LongArray(FrontEnd.N)
    repeat(draws) { draw ->
        FrontEnd.reset()
        FrontEnd.mode = FrontEnd.ON
        query(1000 + draw)
        FrontEnd.mode = FrontEnd.OFF
        if (draw == 0) return@repeat
        crawlWall.add(FrontEnd.nanos[FrontEnd.CRAWL])
        readRow.add(FrontEnd.nanos[FrontEnd.READ])
        resolveRow.add(FrontEnd.nanos[FrontEnd.CRAWL_RESOLVE])
        for (i in 0 until FrontEnd.N) {
            lastRows[i] = FrontEnd.nanos[i]
            lastCalls[i] = FrontEnd.calls[i]
        }
    }

    fun medianUs(xs: MutableList<Long>): String {
        xs.sort()
        return "${xs[xs.size / 2] / 1000}us  ${xs.map { it / 1000 }}"
    }
    println("FECRAWL $arm median=${medianUs(crawlWall)}")
    println("FEREAD $arm median=${medianUs(readRow)}")
    println("FERESOLVE $arm median=${medianUs(resolveRow)}")
    for (i in 0 until FrontEnd.N) {
        if (lastCalls[i] == 0L) continue
        println("FEROW $arm ${lastRows[i]} ${lastCalls[i]} ${FrontEnd.names[i].trim()}")
    }
    println("RESIDENT $arm ${project.residentReadCount}")
    project.close()
}
