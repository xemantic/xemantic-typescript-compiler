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
import java.io.File

/**
 * (INC.46) THE GATE for the incremental project-wide diagnostics path: over REAL
 * edits, the answer [Project.diagnostics] gives after an edit must equal the answer
 * a project opened fresh on the edited text gives, row for row.
 *
 * It needs no baseline, which is the point — both arms answer the same question and
 * the reference arm is a full rebuild, so a divergence is a defect by construction
 * rather than by comparison with a recorded file.
 *
 * ## Why it is driven by the edit corpus and not by fixtures
 *
 * The queue's own requirement: the sequence must contain a signature-CHANGING edit
 * and a body-only one, or the gate is vacuous in exactly the way (INC.45)'s arm b2
 * was. 40 real commits to tsc's own `src/compiler` contain both by construction —
 * measured, 27 of them move no fingerprint and 13 do — and they also contain the
 * shapes nobody writes into a fixture, which is how (INC.46)(2) found the
 * `export as namespace`-in-a-comment defect.
 *
 * ## The control
 *
 * `served` counts the cases actually answered INCREMENTALLY. An implementation that
 * always fell back would agree on every case and prove nothing (round 790: a
 * verifier reads 0 both when the skip is sound and when the instrument is dead), so
 * a run whose `served` is 0 is REFUSED rather than reported as equivalent.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <corpusDir> <projectDir>" }
    val corpus = File(args[0])
    val projectDir = File(args[1])
    val srcCompiler = File(projectDir, "src/compiler")
    require(srcCompiler.isDirectory) { "no src/compiler under $projectDir" }

    val cases = corpus.listFiles { f: File -> f.isDirectory }!!.sortedBy { it.name }
    require(cases.isNotEmpty()) { "REFUSED: no cases in $corpus" }

    fun materialize(tree: File) {
        for (f in srcCompiler.listFiles() ?: emptyArray()) if (f.isFile) f.delete()
        var n = 0
        for (f in tree.listFiles() ?: emptyArray()) {
            if (!f.isFile) continue
            f.copyTo(File(srcCompiler, f.name), overwrite = true)
            n++
        }
        require(n > 0) { "REFUSED: empty tree $tree" }
    }

    fun rows(diagnostics: List<Diagnostic>): List<String> =
        diagnostics.map { "${it.fileName}|${it.start}|${it.code}|${it.message}" }.sorted()

    var agreed = 0
    var diverged = 0
    var served = 0
    var incrementalMs = 0L
    var fullMs = 0L
    val divergentCases = ArrayList<String>()
    println("cases=${cases.size}")
    for (case in cases) {
        val touched = File(case, "touched.txt").takeIf { it.isFile }?.readLines()
            ?.filter { it.isNotBlank() } ?: emptyList()
        val before = File(case, "before")
        val after = File(case, "after")
        if (!before.isDirectory || !after.isDirectory || touched.isEmpty()) continue

        // ---- the INCREMENTAL arm: build the parent tree, then edit through the
        //      overlay exactly as an editor's unsaved buffers would ----------------
        materialize(before)
        val project = Project.open(projectDir.path)
        val incremental: List<String>
        val wasServed: Boolean
        try {
            project.diagnostics()
            val servedBefore = project.incrementalAnswers
            for (name in touched) {
                val text = File(after, name).takeIf { it.isFile }?.readText() ?: continue
                project.updateFile(File(srcCompiler, name).path, text)
            }
            val at = System.nanoTime()
            incremental = rows(project.diagnostics())
            incrementalMs += (System.nanoTime() - at) / 1_000_000
            wasServed = project.incrementalAnswers > servedBefore
        } finally {
            project.close()
        }

        // ---- the REFERENCE arm: a project opened fresh on the edited text --------
        materialize(after)
        val fresh = Project.open(projectDir.path)
        val full: List<String>
        try {
            val at = System.nanoTime()
            full = rows(fresh.diagnostics())
            fullMs += (System.nanoTime() - at) / 1_000_000
        } finally {
            fresh.close()
        }

        if (wasServed) served++
        if (incremental == full) {
            agreed++
            println("${case.name} AGREE   served=$wasServed rows=${full.size}")
        } else {
            diverged++
            divergentCases.add(case.name)
            val onlyIncremental = incremental - full.toSet()
            val onlyFull = full - incremental.toSet()
            println(
                "${case.name} DIVERGE served=$wasServed " +
                    "+${onlyIncremental.size} -${onlyFull.size} ${onlyIncremental.take(2)} ${onlyFull.take(2)}",
            )
        }
    }
    println("---")
    if (served == 0) {
        println("REFUSED: nothing was answered incrementally, so agreement proves nothing")
    }
    println(
        "VERDICT ${if (diverged == 0 && served > 0) "EQUIVALENT" else "DIVERGED"} — " +
            "$agreed agreed / ${agreed + diverged} compared, served=$served",
    )
    if (divergentCases.isNotEmpty()) println("divergent: $divergentCases")
    println("incremental total ${incrementalMs}ms against full ${fullMs}ms")
}
