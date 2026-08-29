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

import java.io.File

/**
 * (INC.45) What the spelling-narrowed RENAME costs, per named symbol, both arms in one
 * process — [ReferenceNarrowingCostMainKt]'s twin, and it exists separately because a
 * rename is two or three builds per arm where a reference search is one.
 *
 * The measurement is paired and interleaved because nothing else on this box is
 * quotable: a wall figure taken in one process and compared with another round's is
 * worth nothing (CLAUDE.md's ±13%), while `narrow` and `whole` measured back to back
 * at the same caret in the same JVM differ only in the policy. Three rotations per
 * caret and the MEDIAN of each arm is printed; the first rotation is kept and
 * reported separately, because the first reference sweep in a process is the one that
 * warms the sweep's own code and is systematically the slowest draw.
 *
 * Each caret is given as `fileSuffix#needle#skip`, where the needle is found by
 * `indexOf` in that file and `skip` occurrences of it are passed over first. The
 * PARTITION SIZE is printed beside the times because it is a counter and therefore
 * the part of this table that transfers between boxes and between rounds.
 *
 * ```
 * java -Xmx6g -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.RenameNarrowingCostMainKt \
 *   build/bench/tsc-project-<sha> \
 *   'src/compiler/checker.ts#function createTypeChecker#0' \
 *   'src/compiler/emitter.ts#function emitFiles#0'
 * ```
 *
 * Not a gate and not a pin — it asserts nothing, and every millisecond it prints is
 * wall time on one box.
 */
private const val NEW_NAME = "zzzRenamedByTheHarness"

fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <projectDir> <fileSuffix#needle#skip>..." }
    val project = Project.open(args[0])
    val files = project.files
    // One reference sweep before anything is timed: the first one in a process pays
    // for the whole capture path's warm-up and would otherwise land entirely on
    // whichever arm happens to run first.
    val warmFile = files.first()
    project.referencesAt(warmFile, File(warmFile).readText().indexOf("import").coerceAtLeast(0))


    println("caret | partition | narrowMs (first, best of 2) | wholeMs | ratio | edits | refusal")
    for (spec in args.drop(1)) {
        val parts = spec.split('#')
        require(parts.size == 3) { "bad caret spec '$spec'" }
        val target = files.first { it.endsWith(parts[0]) }
        val text = File(target).readText()
        var at = -1
        repeat(parts[2].toInt() + 1) { at = text.indexOf(parts[1], at + 1) }
        require(at >= 0) { "needle '${parts[1]}' not found in $target" }
        // The caret goes on the LAST identifier of the needle, so a needle may carry
        // the keyword that disambiguates it (`function createTypeChecker`).
        val offset = at + parts[1].lastIndexOf(' ') + 1
        val partition = project.narrowedRenameFiles(target, offset, NEW_NAME)

        val narrowDraws = ArrayList<Long>()
        val wholeDraws = ArrayList<Long>()
        var hits = 0
        var refusal = "-"
        // TWO rotations, not three: a rename holds a whole-program sweep per arm and a
        // long run at -Xmx6g has been OOM-killed once already.
        repeat(2) {
            project.narrowReferenceSweeps = true
            var started = System.nanoTime()
            val plan = project.renameAt(target, offset, NEW_NAME)
            hits = plan.files.sumOf { it.edits.size }
            refusal = plan.refusal?.name ?: "-"
            narrowDraws.add((System.nanoTime() - started) / 1_000_000)
            project.narrowReferenceSweeps = false
            started = System.nanoTime()
            project.renameAt(target, offset, NEW_NAME)
            wholeDraws.add((System.nanoTime() - started) / 1_000_000)
        }
        project.narrowReferenceSweeps = true
        val narrow = narrowDraws.min()
        val whole = wholeDraws.min()
        println(
            "${parts[1]} | ${if (partition < 0) "REFUSED" else "$partition of ${files.size}"} | " +
                "${narrowDraws[0]}, $narrow | $whole | " +
                "${if (narrow > 0) (whole.toDouble() / narrow) else 0.0} | $hits | $refusal",
        )
    }
    project.close()
}
