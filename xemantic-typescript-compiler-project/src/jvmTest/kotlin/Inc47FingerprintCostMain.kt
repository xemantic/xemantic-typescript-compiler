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

import com.xemantic.typescript.compiler.ExportSignatures
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs

/**
 * (INC.47) The WHOLE-PROGRAM fingerprint census, and the two controls that decide
 * whether the mechanism can converge at all — the (INC.46) step-(1) instrument,
 * re-taken through [ProjectCompiler] rather than through [Project].
 *
 * ## Why this exists beside `Inc46FingerprintCostMain`
 *
 * That runner drove its arms with `project.diagnostics()`, which was a whole-program
 * build when it was written. (INC.46) step (3) then made `diagnostics()` INCREMENTAL,
 * so the same runner now fingerprints ONE file per rotation and reports
 * `census … of 1 files`, `whole-program escapes: []` and `PARTITION-AGREEMENT 1/24` —
 * a decayed instrument that reads like a clean bill of health. It is left in place as
 * the record of what step (1) measured; this one asks the compiler directly, so it
 * cannot decay the same way.
 *
 * ## What it prints, and what each line is for
 *
 *  - **COST** — the whole-program fingerprint time and the per-file tail, against the
 *    rebuild it rides on. (INC.46)'s threshold was stated per FILE (`types.ts`'s 874
 *    exports), which is the row (INC.47) exists to move: before it, that file is a
 *    node-budget STOP and therefore an ESCAPE, so every edit touching it falls back
 *    to a whole-program build.
 *  - **STABILITY** — two builds of identical text must produce identical
 *    fingerprints. This is the id-freedom claim: a hash carrying a `Type.id` passes
 *    every structural unit test and then invalidates everything, always.
 *  - **PARTITION AGREEMENT** — a NARROWED build's fingerprint for a file must equal
 *    the whole-program build's, because the baseline comes from a whole-program build
 *    and an edit's answer from a narrowed one. A systematic disagreement means every
 *    first edit falls back forever, which is the feasibility question rather than an
 *    optimization detail.
 *
 * Not a gate and not a pin; it asserts only its own non-vacuity.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [rotations]" }
    val dir = args[0]
    val rotations = if (args.size > 1) args[1].toInt() else 3
    val compiler = ProjectCompiler(SystemVfs)

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime()
        block()
        return (System.nanoTime() - at) / 1_000_000
    }

    // ---- warm-up, both arms, discarded (round 869: the first instrumented rebuild
    //      in a process is the slowest draw, by up to 15%) -----------------------
    repeat(3) { compiler.build(dir, noEmit = true) }
    repeat(3) { compiler.build(dir, noEmit = true, exportSignatures = true) }
    val probe = compiler.build(dir, noEmit = true, exportSignatures = true)
    require(probe.diagnostics.isNotEmpty()) {
        "REFUSED: the build reports NO diagnostics — point this at a real project"
    }
    require(probe.exportSignatures.size > 1) {
        "REFUSED: ${probe.exportSignatures.size} file(s) fingerprinted, so this is not " +
            "a whole-program census"
    }
    println("warmup done — files=${probe.programFiles.size} diagnostics=${probe.diagnostics.size}")

    // ---- ABBA-rotated arms ---------------------------------------------------
    val off = ArrayList<Long>()
    val on = ArrayList<Long>()
    val fpMs = ArrayList<Long>()
    repeat(rotations) { r ->
        val order = if (r % 2 == 0) listOf(false, true) else listOf(true, false)
        for (armOn in order) {
            ExportSignatures.reset()
            val wall = ms { compiler.build(dir, noEmit = true, exportSignatures = armOn) }
            if (armOn) { on.add(wall); fpMs.add(ExportSignatures.nanos / 1_000_000) } else off.add(wall)
        }
    }

    fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]
    println("rebuild.off  ms  ${off.sorted()}  median=${median(off)}")
    println("rebuild.on   ms  ${on.sorted()}  median=${median(on)}")
    println("fingerprint  ms  ${fpMs.sorted()}  median=${median(fpMs)}")

    // ---- the census ----------------------------------------------------------
    ExportSignatures.reset()
    val first = compiler.build(dir, noEmit = true, exportSignatures = true)
    println(
        "census exports=${ExportSignatures.exports} typeNodes=${ExportSignatures.typeNodes} " +
            "nanos=${ExportSignatures.nanos / 1_000_000}ms budgetStops=${ExportSignatures.budgetStops} " +
            "whole=${ExportSignatures.whole.size} of ${first.exportSignatures.size} files",
    )
    for ((f, n) in ExportSignatures.fileNanos.entries.sortedByDescending { it.value }.take(8)) {
        println(
            "  cost ${n / 1_000_000}.${(n / 1_000) % 1000}ms " +
                "exports=${ExportSignatures.fileExports[f]} ${f.substringAfterLast('/')}",
        )
    }
    println(
        "whole-program escapes: " +
            ExportSignatures.whole.map { it.substringAfterLast('/') }.sorted(),
    )

    // ---- CONTROL 1: identical text, identical fingerprints -------------------
    ExportSignatures.reset()
    val second = compiler.build(dir, noEmit = true, exportSignatures = true)
    var same = 0
    val movedFiles = ArrayList<String>()
    for ((f, h) in first.exportSignatures) {
        if (second.exportSignatures[f] == h) same++ else movedFiles.add(f.substringAfterLast('/'))
    }
    println("STABILITY identical-text rebuild: $same/${first.exportSignatures.size} equal")
    if (movedFiles.isNotEmpty()) println("  MOVED: ${movedFiles.sorted()}")

    // ---- CONTROL 2: a narrowed build agrees with the whole-program one --------
    var agree = 0
    val disagree = ArrayList<String>()
    val narrowMs = ArrayList<Long>()
    val narrowFpMs = ArrayList<Long>()
    val sample = first.programFiles.filter { it.endsWith(".ts") && !it.endsWith(".d.ts") }.take(24)
    for (f in sample) {
        ExportSignatures.reset()
        val narrowed = ProjectCompiler(SystemVfs)
        narrowMs.add(
            ms { narrowed.build(dir, noEmit = true, recheckOnly = setOf(f), exportSignatures = true) },
        )
        narrowFpMs.add(ExportSignatures.nanos / 1_000_000)
        if (ExportSignatures.fingerprints[f] == second.exportSignatures[f]) agree++
        else disagree.add(f.substringAfterLast('/'))
    }
    println("PARTITION-AGREEMENT narrowed-vs-whole: $agree/${sample.size} equal")
    if (disagree.isNotEmpty()) println("  DISAGREE: ${disagree.sorted()}")
    println("narrowed build ms ${narrowMs.sorted()}")
    println("narrowed fingerprint ms ${narrowFpMs.sorted()}")
}
