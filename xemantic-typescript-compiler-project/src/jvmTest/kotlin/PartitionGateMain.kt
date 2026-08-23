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
 * (INC.18) THE SENSITIVITY ARM of the partition gate.
 *
 * `PartitionEquivalenceMain` is the REALISM arm: it sweeps tsc's own 78 sources,
 * which is the shape a real editor session has. What it cannot do is DISCRIMINATE.
 * Measured while censusing (INC.17): on that project the full build's 46
 * diagnostics are netted by exactly **one** pass — `checkSpine` — and only **5 of
 * 78 files carry any row at all, so 73 of the per-file comparisons are empty
 * against empty. A defect that silently starved 204 of the 205 partition-dependent
 * passes would be invisible there, and all eight dashboard profiles are that same
 * codebase.
 *
 * This arm runs the SAME comparison ([PartitionGate.sweep]) over a fixture built
 * for the opposite property: many files, each carrying rows a DIFFERENT dedicated
 * walker owns. It REFUSES rather than reports when its own sensitivity has fallen
 * below the floors it is given — a gate that cannot fail is worth nothing, and a
 * gate that stopped being able to fail must say so instead of printing green
 * (CLAUDE.md rounds 853/873/895).
 *
 * ```
 * scripts/partition-gate.sh          # both arms
 * java … PartitionGateMainKt <projectDir> <minPasses> <minFilesCarrying>
 * ```
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [minPasses] [minFilesCarrying]" }
    val project = args[0]
    val minPasses = if (args.size > 1) args[1].toInt() else 0
    val minFilesCarrying = if (args.size > 2) args[2].toInt() else 1
    val compiler = ProjectCompiler(SystemVfs)

    // Discarded: the first build in a process is the slowest draw in this repo, and
    // the receipt below is taken from a `PassTiming`-enabled one.
    compiler.build(project, noEmit = true)

    val full = compiler.build(project, noEmit = true)
    val fullRows = PartitionGate.rowsOf(full.diagnostics)
    val filesCarrying = full.programFiles.count { !fullRows[it].isNullOrEmpty() }

    val sensitivity = PartitionGate.sensitivity(compiler, project)
    println("project: $project")
    println(
        "program: files=${full.programFiles.size}  diagnostics=${full.diagnostics.size}  " +
            "filesCarryingThem=$filesCarrying",
    )
    // THE RECEIPT. A COUNT, not a ms.
    println(
        "SENSITIVITY passes=${sensitivity.nettingPasses.size} " +
            "netTotal=${sensitivity.netTotal} diagnostics=${sensitivity.diagnostics}",
    )
    println("SENSITIVITY passList ${sensitivity.nettingPasses}")

    // CONTROLS — printed, then enforced. The signed per-pass deltas are taken from
    // the same build whose diagnostics they are supposed to account for, so a hook
    // that had stopped firing reads zero, which is indistinguishable from a project
    // on which nothing emits.
    require(sensitivity.netTotal != 0) {
        "REFUSED: `PassTiming.diagNetByPass` accounted for NOTHING on a build that " +
            "reported ${sensitivity.diagnostics} diagnostics — the attribution hook is " +
            "dead, so the receipt below would be a zero from a blind instrument."
    }
    require(filesCarrying >= minFilesCarrying) {
        "REFUSED: only $filesCarrying file(s) carry diagnostics (floor $minFilesCarrying). " +
            "A per-file comparison over files that carry nothing agrees VACUOUSLY."
    }
    require(sensitivity.nettingPasses.size >= minPasses) {
        "REFUSED: only ${sensitivity.nettingPasses.size} distinct pass(es) net a " +
            "diagnostic here (floor $minPasses). This arm exists to DISCRIMINATE; " +
            "below its floor it is the vacuous gate it was built to replace."
    }

    val disagreed = PartitionGate.sweep(compiler, project, full.programFiles, fullRows)
    println(
        if (disagreed == 0) "EQUIVALENT: all ${full.programFiles.size} files agree"
        else "DIVERGED: $disagreed file(s)",
    )
    if (disagreed != 0) kotlin.system.exitProcess(1)
}
