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
 * (INC.44) THE GATE FOR THE SPELLING-NARROWED REFERENCE SWEEP — the two arms answer
 * the same question over a real project, so they must return the same list.
 *
 * A differential, which is why it needs no baseline and cannot go stale: the narrowed
 * arm and the whole-program arm are the SAME BINARY with
 * [Project.narrowReferenceSweeps] flipped, and any disagreement is this round's
 * defect by construction. Its one failure direction is the silent one — a narrowed
 * sweep that DROPS an occurrence renders nothing and reports nothing — so the
 * comparison is element for element on `(fileName, start, end, isDeclaration, use)`
 * and a caret whose two arms differ is printed in full rather than counted.
 *
 * ## Read the REFUSAL count as well as the divergence count
 *
 * A caret whose spellings cannot be bounded falls back to the whole-program sweep and
 * then trivially agrees with itself, so a run in which everything refuses is GREEN and
 * has tested nothing. `narrowed=` is the control: it is how many carets actually took
 * the new path, and a run is only evidence to the extent that number is large. The
 * same trap CLAUDE.md records for every skip-shaped change ("a verifier reads 0 both
 * when the skip is sound and when the instrument is dead").
 *
 * ```
 * java -Xmx6g -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.ReferenceNarrowingDifferentialMainKt \
 *   build/bench/tsc-project-<sha> 400
 * ```
 *
 * The second argument is how many carets to draw. They are drawn by STRIDE over every
 * occurrence of every file in program order — deterministic, reproducible from the
 * arguments alone, and spread over the whole program rather than over whichever file
 * happens to be first.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <projectDir> <caretCount>" }
    val wanted = args[1].toInt()
    val project = Project.open(args[0])
    val files = project.files.sorted()

    // Every caret this run may draw from, as (file, offset), in program order. The
    // population is [SourceIndex.occurrenceNodes] — the API's own occurrence set —
    // read syntactically, because asking it through `fileSemantics` would cost a build
    // per file before the measurement has started.
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
    var diverged = 0
    var hits = 0L
    var partitionFiles = 0L
    var narrowMs = 0L
    var wholeMs = 0L
    for ((file, offset) in drawn) {
        project.narrowReferenceSweeps = true
        var at = System.nanoTime()
        val narrow = project.referencesAt(file, offset)
        narrowMs += (System.nanoTime() - at) / 1_000_000
        project.narrowReferenceSweeps = false
        at = System.nanoTime()
        val whole = project.referencesAt(file, offset)
        wholeMs += (System.nanoTime() - at) / 1_000_000
        hits += whole.size
        // A caret that took the new path is one whose arms are not the same code, and
        // the only observable that says so from out here is the wall: the narrowed arm
        // builds a smaller partition. Rather than guess, ask the project directly.
        val partition = project.narrowedSweepFiles(file, offset)
        if (partition >= 0) {
            narrowed++
            partitionFiles += partition
        }
        if (!sameAnswer(narrow, whole)) {
            diverged++
            println("DIVERGE $file:$offset narrow=${narrow.size} whole=${whole.size}")
            for (row in whole.filter { it !in narrow }) println("  ONLY-WHOLE $row")
            for (row in narrow.filter { it !in whole }) println("  ONLY-NARROW $row")
        }
    }
    project.close()
    println(
        "carets=${drawn.size} narrowed=$narrowed diverged=$diverged hits=$hits " +
            "partitionFilesTotal=$partitionFiles of ${files.size * narrowed} " +
            "narrowMs=$narrowMs wholeMs=$wholeMs",
    )
    println(if (diverged == 0) "EQUIVALENT" else "DIVERGED")
}

private fun sameAnswer(a: List<ReferenceLocation>, b: List<ReferenceLocation>): Boolean =
    a.size == b.size && a.indices.all { a[it] == b[it] }
