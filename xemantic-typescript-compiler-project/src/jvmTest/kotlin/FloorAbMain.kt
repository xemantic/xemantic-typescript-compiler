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
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs

/**
 * A FLOOR-ONLY draw harness for a two-BINARY A/B, and the reason it exists beside
 * [FloorDecompositionMain] is cost: that runner spends most of a process on the
 * FULL-build arms it needs for its partition checks, which an A/B on one floor row
 * does not, so a rotated four-process-per-arm batch through it costs ~40 minutes.
 *
 * The arm is the CORE CLASS DIRECTORY on the classpath, not a flag: round 867
 * measured two arms sharing one JVM writing each other's branch profiles (per-arm
 * sd 16-38%), so every arm gets its own process and the caller rotates them ABBA
 * across processes — (INC.68) watched a BLOCKED batch of 12 draws per arm invent a
 * +2.70 ms regression in a region with no causal path to its change, and both signs
 * inverted under rotation.
 *
 * ```
 * java -cp <project test>:<project main>:<CORE ARM>:<deps> \
 *   com.xemantic.typescript.compiler.project.FloorAbMainKt <projectDir> <warmups> <draws> [rows]
 * ```
 *
 * It deliberately reads NO census counter. Both arms of a two-binary A/B are
 * loaded by the SAME runner class, so a counter that exists in only one of them is
 * a `NoSuchMethodError` in the older arm — which prints a plausible partial run and
 * leaves the batch reporting one arm's medians as if they were both. Populations
 * belong in the pins, which run against one binary at a time.
 *
 * `rows` additionally takes ONE instrumented draw at the `--passTimingRows` tier
 * and prints the per-pass table, which is the deterministic half of the answer:
 * the wall says whether the floor moved, the table says which rows moved it.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [warmups] [draws] [rows]" }
    val project = args[0]
    val warmups = if (args.size > 1) args[1].toInt() else 4
    val draws = if (args.size > 2) args[2].toInt() else 8
    val rows = args.size > 3 && args[3] == "rows"
    // (INC.72) the OTHER instrument: the per-PHASE table, which is the only thing
    // that can say whether a floor saving is inside the checker's init block at all.
    val fe = args.size > 3 && args[3] == "fe"
    val nowhere = "/no/such/file/the/program/does/not/contain.ts"
    val compiler = ProjectCompiler(SystemVfs)

    fun floor() = compiler.build(project, noEmit = true, recheckOnly = setOf(nowhere))

    // Sanity, and the refusal that keeps a mis-pointed run from printing a table:
    // a floor build must check NOTHING, and the program must be non-empty.
    val probe = floor()
    require(probe.diagnostics.isEmpty()) {
        "REFUSED: the floor build reported ${probe.diagnostics.size} diagnostics"
    }
    require(probe.programFiles.size > 100) {
        "REFUSED: ${probe.programFiles.size} program files — point this at an application-shaped project"
    }
    println("project: $project files=${probe.programFiles.size}")


    repeat(warmups) { floor() }

    val ms = ArrayList<Long>(draws)
    repeat(draws) {
        val t0 = System.nanoTime()
        floor()
        ms.add((System.nanoTime() - t0) / 1_000_000)
    }
    val sorted = ms.sorted()
    println("FLOOR draws=$ms median=${sorted[sorted.size / 2]}ms min=${sorted.first()} max=${sorted.last()}")

    if (fe) {
        // Two draws, same reason as the `rows` tier: the first instrumented draw in a
        // process is the slowest and inflates every row it prints (round 846).
        repeat(2) { draw ->
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            val t0 = System.nanoTime()
            floor()
            val wall = (System.nanoTime() - t0) / 1_000_000
            FrontEnd.mode = FrontEnd.OFF
            println("FE $draw wall=$wall")
            for (i in 0 until FrontEnd.N) {
                if (FrontEnd.calls[i] == 0L) continue
                println("FEROW$draw ${FrontEnd.nanos[i]} ${FrontEnd.calls[i]} ${FrontEnd.names[i].trim()}")
            }
        }
    }

    if (rows) {
        // TWO instrumented draws, and only the SECOND is quotable: round 846
        // measured a probe's OWN cost warming up (first instrumented rebuild
        // 3,457 ms, second 1,856), sign-consistent across processes, so a
        // single-draw table inflates every row it prints by an unknown factor.
        repeat(2) { draw ->
            PassTiming.reset()
            PassTiming.detail = false
            PassTiming.spineDetail = false
            PassTiming.enabled = true
            val t0 = System.nanoTime()
            floor()
            val wall = (System.nanoTime() - t0) / 1_000_000
            PassTiming.enabled = false
            val sum = PassTiming.passNanos.values.sum()
            println("PTROWS $draw init=${PassTiming.checkerInitNanos} sum=$sum wall=$wall")
            for ((name, nanos) in PassTiming.passNanos) println("PTROW$draw $nanos $name")
        }
    }
}
