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

import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.computeParserFlags
import java.io.File

/**
 * (INC.45) THE GATE FOR THE SPELLING-NARROWED RENAME — the two arms must produce the
 * SAME PLAN, edit for edit, refusal for refusal, conflict for conflict.
 *
 * A rename is a bigger claim than a reference search and this compares the whole of
 * it: `RenamePlan` is a data class, so equality covers the edits (file, span, text),
 * the refusal and the conflict list, and a plan that merely *looks* the same size is
 * not accepted. Both arms are one binary with [Project.narrowReferenceSweeps] flipped,
 * so any disagreement belongs to the narrowing by construction.
 *
 * ## Read `narrowed=` as well as `diverged=`, and `applicable=` as well as both
 *
 * A caret whose spellings cannot be bounded falls back and then agrees with itself.
 * And a rename that REFUSES in both arms agrees trivially — a run where every plan is
 * a refusal has compared two empty edit lists — so the applicable count is the second
 * control: this is evidence to the extent that plans were actually produced.
 *
 * Each caret is renamed to its own name with a fixed suffix, which is a name the
 * program cannot already contain and therefore a rename that should SUCCEED wherever
 * the symbol is renameable at all.
 *
 * ```
 * java -Xmx6g -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.RenameNarrowingDifferentialMainKt \
 *   build/bench/tsc-project-<sha> 25
 * ```
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <projectDir> <caretCount>" }
    val wanted = args[1].toInt()
    val project = Project.open(args[0])
    val files = project.files.sorted()
    val options = TsConfigLoader(SystemVfs).load(project.configPath).options

    val carets = ArrayList<Pair<String, Int>>()
    for (file in files) {
        val text = File(file).readText()
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        for (node in index.occurrenceNodes()) carets.add(file to index.occurrenceSpanOf(node)[0])
    }
    val stride = maxOf(1, carets.size / maxOf(1, wanted))
    val drawn = carets.filterIndexed { index, _ -> index % stride == 0 }.take(wanted)
    println("occurrences=${carets.size} stride=$stride drawn=${drawn.size}")

    var narrowed = 0
    var applicable = 0
    var diverged = 0
    var edits = 0L
    var narrowMs = 0L
    var wholeMs = 0L
    for ((file, offset) in drawn) {
        project.narrowReferenceSweeps = true
        var at = System.nanoTime()
        val narrow = project.renameAt(file, offset, "zzzRenamedByTheGate")
        narrowMs += (System.nanoTime() - at) / 1_000_000
        project.narrowReferenceSweeps = false
        at = System.nanoTime()
        val whole = project.renameAt(file, offset, "zzzRenamedByTheGate")
        wholeMs += (System.nanoTime() - at) / 1_000_000
        project.narrowReferenceSweeps = true
        if (project.narrowedSweepFiles(file, offset) >= 0) narrowed++
        if (whole.isApplicable) {
            applicable++
            edits += whole.files.sumOf { it.edits.size }
        }
        if (narrow != whole) {
            diverged++
            println("DIVERGE $file:$offset")
            println("  NARROW refusal=${narrow.refusal} files=${narrow.files.size} " +
                "edits=${narrow.files.sumOf { it.edits.size }} conflicts=${narrow.conflicts.size}")
            println("  WHOLE  refusal=${whole.refusal} files=${whole.files.size} " +
                "edits=${whole.files.sumOf { it.edits.size }} conflicts=${whole.conflicts.size}")
            for (row in whole.conflicts.filter { it !in narrow.conflicts }) println("    ONLY-WHOLE $row")
            for (row in narrow.conflicts.filter { it !in whole.conflicts }) println("    ONLY-NARROW $row")
        }
    }
    project.close()
    println(
        "carets=${drawn.size} narrowed=$narrowed applicable=$applicable diverged=$diverged " +
            "edits=$edits narrowMs=$narrowMs wholeMs=$wholeMs",
    )
    println(if (diverged == 0) "EQUIVALENT" else "DIVERGED")
}
