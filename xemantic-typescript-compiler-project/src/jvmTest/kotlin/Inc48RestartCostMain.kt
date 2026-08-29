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

import com.xemantic.typescript.compiler.ProjectStateSnapshot
import java.io.File

/**
 * (INC.48) WHAT A RESTART COSTS, with and without a snapshot.
 *
 * The claim under test is the queue's: a post-restart first query goes from a full
 * whole-program build to the (INC.46) gate whenever the tree has not moved. Three arms
 * on the same project in one process, ABBA-rotated:
 *
 *  - **cold-open** — `Project.open` + `diagnostics()`, no snapshot. What every host
 *    pays today on every restart.
 *  - **restored-clean** — the same, with a snapshot restored first and nothing changed
 *    on disk. Should be one build narrowed to NOTHING.
 *  - **restored-edited** — with one file's text changed since the snapshot, which is
 *    what an editor actually finds after a `git pull` or an external edit.
 *
 * It also prints the snapshot's SIZE, since a host has to store it, and asserts the
 * three arms agree ROW FOR ROW — a faster arm that answers something else is the one
 * failure mode a wall-clock harness cannot see (`kir-bench.sh`'s equivalence gate, one
 * arc over).
 *
 * Not a gate and not a pin; it asserts only its own non-vacuity.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [rotations]" }
    val dir = args[0]
    val rotations = if (args.size > 1) args[1].toInt() else 3
    // A development tree's build id ends in `.dirty` and is refused for cross-process
    // reuse by design; this harness measures the mechanism, so it installs the seam the
    // pins use. A released build needs none of this.
    ProjectStateSnapshot.allowUnstableBuildIdForTesting = true

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime()
        block()
        return (System.nanoTime() - at) / 1_000_000
    }

    fun rowsOf(project: Project): List<String> =
        project.diagnostics().map { "${it.fileName}|${it.start}|${it.code}" }.sorted()

    // ---- the snapshot a "previous process" leaves behind -----------------------
    val seed = Project.open(dir)
    seed.diagnostics()
    val state = seed.saveState() ?: error("REFUSED: no state to save")
    val reference = rowsOf(seed)
    seed.close()
    require(reference.isNotEmpty()) {
        "REFUSED: the project reports NO diagnostics, so agreement between the arms " +
            "is not evidence — point this at a real project"
    }
    println("project: $dir")
    println("snapshot: ${state.length} chars, ${reference.size} diagnostic rows")

    // (INC.48)/(INC.49) THE COLD ARM. Everything below this line is measured WARM, and
    // an IDE restart is not warm: `Project.open` in a fresh JVM pays the JIT ramp, which
    // (INC.49) measured at ~18 s of a 23 s first query. A single arm with NO warm-up,
    // one per process, is the only honest way to quote what a restart costs — so the
    // caller runs this twice, once per arm, and compares two processes.
    val coldArm = if (args.size > 2) args[2] else null
    if (coldArm != null) {
        val p = Project.open(dir)
        if (coldArm != "cold-open") {
            check(p.restoreState(state)) { "restore refused" }
        }
        val wall = ms { p.diagnostics() }
        check(rowsOf(p) == reference) { "$coldArm disagreed with the reference" }
        println("COLD $coldArm ms $wall incrementalAnswers=${p.incrementalAnswers}")
        p.close()
        return
    }

    // ---- warm-up, every arm, discarded ----------------------------------------
    repeat(2) {
        Project.open(dir).use { it.diagnostics() }
        Project.open(dir).use { p -> p.restoreState(state); p.diagnostics() }
    }

    val cold = ArrayList<Long>()
    val clean = ArrayList<Long>()
    val edited = ArrayList<Long>()
    var incrementalClean = 0
    var incrementalEdited = 0

    // One file's text, changed the way an external edit changes it: a body-only edit,
    // which is the case the gate is supposed to serve.
    val target = File(dir, "src/compiler/binder.ts")
    val original = target.readText()

    repeat(rotations) { r ->
        val order = if (r % 2 == 0) listOf(0, 1, 2) else listOf(2, 1, 0)
        for (arm in order) when (arm) {
            0 -> {
                val p = Project.open(dir)
                cold.add(ms { p.diagnostics() })
                check(rowsOf(p) == reference) { "cold-open disagreed with the reference" }
                p.close()
            }
            1 -> {
                val p = Project.open(dir)
                check(p.restoreState(state)) { "restore refused on an unchanged tree" }
                clean.add(ms { p.diagnostics() })
                check(rowsOf(p) == reference) { "restored-clean disagreed with the reference" }
                incrementalClean = p.incrementalAnswers
                p.close()
            }
            else -> {
                target.writeText(original + "\n// (INC.48) probe — a comment, so no signature moves\n")
                val p = Project.open(dir)
                check(p.restoreState(state)) { "restore refused after a body edit" }
                edited.add(ms { p.diagnostics() })
                incrementalEdited = p.incrementalAnswers
                p.close()
                target.writeText(original)
            }
        }
    }

    fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]
    println("cold-open        ms ${cold.sorted()}  median=${median(cold)}")
    println("restored-clean   ms ${clean.sorted()}  median=${median(clean)}  " +
        "incrementalAnswers=$incrementalClean")
    println("restored-edited  ms ${edited.sorted()}  median=${median(edited)}  " +
        "incrementalAnswers=$incrementalEdited")
    println("speed-up clean ${median(cold).toDouble() / median(clean)}x  " +
        "edited ${median(cold).toDouble() / median(edited)}x")
    ProjectStateSnapshot.allowUnstableBuildIdForTesting = false
}

private inline fun <T> Project.use(block: (Project) -> T): T = try {
    block(this)
} finally {
    close()
}
