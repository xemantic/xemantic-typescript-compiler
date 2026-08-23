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

import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler

/**
 * (INC.18) THE PARTITION GATE'S SHARED PARTS — one comparison, two arms.
 *
 * `recheckOnly` narrows the checker to `assignedFileNames` (the INV.6 view), and
 * its contract is sequential equivalence: a partition of one must report, for the
 * file it names, exactly what the full build reports for that file. The gate is a
 * DIFFERENTIAL — full versus narrowed — so it can only see a defect that makes the
 * two arms DISAGREE, and its resolution is bounded by how much of the checker the
 * comparison actually gets to compare.
 *
 * [sensitivity] is the number that bounds it, and it is a COUNT, not a ms: **how
 * many DISTINCT passes net a diagnostic on the project**, straight off
 * [PassTiming.diagNetByPass] (the SIGNED delta, so a retracting pass is not clamped
 * away as [PassTiming.diagsByPass] would clamp it — there are 73 `removeAll`,
 * 5 `removeAt` and 2 `clear` sites in `Checker.kt`).
 *
 * On tsc's own 78 sources — and therefore on all eight dashboard profiles, which
 * are the same codebase — that count is **1**: every diagnostic the build reports
 * is netted by `checkSpine`, and the other ~415 pass rows move the count by zero.
 * A gate run there is comparing an essentially empty population, which is why a
 * SENSITIVITY arm exists beside the realism arm.
 */
internal object PartitionGate {

    /** A file's diagnostics as sorted, position-and-message-keyed rows. */
    fun rowsOf(diagnostics: List<Diagnostic>): Map<String, List<String>> =
        diagnostics.groupBy { it.fileName ?: "" }
            .mapValues { (_, ds) ->
                ds.map { "${it.code}|${it.start}|${it.length}|${it.message}" }.sorted()
            }

    class Sensitivity(
        val nettingPasses: List<String>,
        val diagnostics: Int,
        val netTotal: Int,
    )

    /**
     * ONE `PassTiming`-enabled build, read for the receipt.
     *
     * The positive control is printed and is arithmetic: the signed per-pass deltas
     * must SUM to the build's own diagnostics count. A hook that had stopped firing
     * would read zero, which is indistinguishable from a project on which nothing
     * emits — round 853's defect, and the reason this prints rather than asserts
     * quietly.
     */
    fun sensitivity(compiler: ProjectCompiler, project: String): Sensitivity {
        PassTiming.reset()
        PassTiming.detail = false
        PassTiming.spineDetail = false
        PassTiming.enabled = true
        val result = try {
            compiler.build(project, noEmit = true)
        } finally {
            PassTiming.enabled = false
        }
        val net = LinkedHashMap(PassTiming.diagNetByPass).filterValues { it != 0 }
        return Sensitivity(
            nettingPasses = net.keys.sorted(),
            diagnostics = result.diagnostics.size,
            netTotal = net.values.sum(),
        )
    }

    /**
     * The sweep: for every file, does `recheckOnly = {file}` report for that file
     * exactly what the full build reports for it? Prints one line per disagreement
     * and returns how many files disagreed.
     */
    fun sweep(
        compiler: ProjectCompiler,
        project: String,
        files: List<String>,
        fullRows: Map<String, List<String>>,
    ): Int {
        var disagreed = 0
        for (file in files) {
            val part = compiler.build(project, noEmit = true, recheckOnly = setOf(file))
            val expected = fullRows[file] ?: emptyList()
            val actual = rowsOf(part.diagnostics)[file] ?: emptyList()
            if (expected != actual) {
                disagreed++
                println(
                    "DISAGREE ${file.substringAfterLast('/')}  " +
                        "full=${expected.size} partition=${actual.size}",
                )
                for (row in (expected - actual.toSet())) println("   only-full: ${row.take(160)}")
                for (row in (actual - expected.toSet())) println("   only-part: ${row.take(160)}")
            }
        }
        return disagreed
    }
}
