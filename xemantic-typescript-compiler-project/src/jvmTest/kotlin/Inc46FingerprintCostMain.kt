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
import java.io.File

/**
 * (INC.46) STEP 1 — the COST half, which the queue entry left as an argument and
 * demanded be measured before anything downstream of it is built.
 *
 * The queue's own refusal threshold, quoted: *"Hook the fingerprint cost on a full
 * build and read it — if it is not single-digit ms on `types.ts`'s 874 exports,
 * stop."* This prints exactly that, plus the whole-program total and the walk's own
 * population (structural type nodes visited), against the rebuild the fingerprint
 * would ride on.
 *
 * It also prints the two things a cost figure alone cannot decide:
 *
 *  - **STABILITY UNDER RE-BUILD.** Two builds of IDENTICAL text must produce
 *    IDENTICAL fingerprints, or the mechanism invalidates everything always and no
 *    edit corpus is worth measuring. This is the id-freedom claim under test — a
 *    hash carrying a `Type.id` passes every unit test and fails here.
 *  - **THE ESCAPE POPULATION.** How many files cannot be fingerprinted exactly and
 *    must therefore invalidate the whole program whatever they contain.
 *
 * Not a gate and not a pin; it asserts only its own non-vacuity.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [rotations]" }
    val dir = args[0]
    val rotations = if (args.size > 1) args[1].toInt() else 3

    val project = Project.open(dir)
    val files = project.files
    val texts = HashMap<String, String>()
    for (f in files) texts[f] = File(f).readText()
    val dirtyFile = files.first { it.endsWith("src/compiler/binder.ts") }
    fun dirty() = project.updateFile(dirtyFile, texts.getValue(dirtyFile))

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime()
        block()
        return (System.nanoTime() - at) / 1_000_000
    }

    // ---- warm-up, both arms, discarded ---------------------------------------
    ExportSignatures.enabled = false
    repeat(4) { dirty(); project.diagnostics() }
    ExportSignatures.enabled = true
    repeat(4) { dirty(); ExportSignatures.reset(); project.diagnostics() }
    ExportSignatures.enabled = false
    repeat(2) { dirty(); project.diagnostics() }
    println("warmup done — files=${files.size}")

    // ---- ABBA-rotated arms, so the first-instrumented-rebuild bias (round 869)
    //      does not land wholly on one of them ----------------------------------
    val off = ArrayList<Long>()
    val on = ArrayList<Long>()
    val fpMs = ArrayList<Long>()
    repeat(rotations) { r ->
        val order = if (r % 2 == 0) listOf(false, true) else listOf(true, false)
        for (armOn in order) {
            ExportSignatures.enabled = armOn
            ExportSignatures.reset()
            dirty()
            val wall = ms { project.diagnostics() }
            if (armOn) {
                on.add(wall)
                fpMs.add(ExportSignatures.nanos / 1_000_000)
            } else off.add(wall)
        }
    }
    ExportSignatures.enabled = false

    fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]
    println("rebuild.off  ms  ${off.sorted()}  median=${median(off)}")
    println("rebuild.on   ms  ${on.sorted()}  median=${median(on)}")
    println("fingerprint  ms  ${fpMs.sorted()}  median=${median(fpMs)}")

    // ---- the last ON build's census -------------------------------------------
    ExportSignatures.enabled = true
    ExportSignatures.reset()
    dirty()
    project.diagnostics()
    val first = LinkedHashMap(ExportSignatures.fingerprints)
    println(
        "census exports=${ExportSignatures.exports} typeNodes=${ExportSignatures.typeNodes} " +
            "nanos=${ExportSignatures.nanos / 1_000_000}ms budgetStops=${ExportSignatures.budgetStops} " +
            "whole=${ExportSignatures.whole.size} " +
            "of ${ExportSignatures.fingerprints.size} files",
    )
    val byCost = ExportSignatures.fileNanos.entries.sortedByDescending { it.value }.take(8)
    for ((f, n) in byCost) {
        println(
            "  cost ${n / 1_000_000}.${(n / 1_000) % 1000}ms " +
                "exports=${ExportSignatures.fileExports[f]} ${f.substringAfterLast('/')}",
        )
    }
    val byExports = ExportSignatures.fileExports.entries.sortedByDescending { it.value }.take(5)
    for ((f, e) in byExports) {
        val n = ExportSignatures.fileNanos[f] ?: 0
        println(
            "  exports=$e cost ${n / 1_000_000}.${(n / 1_000) % 1000}ms " +
                f.substringAfterLast('/'),
        )
    }
    println("whole-program escapes: " +
        ExportSignatures.whole.map { it.substringAfterLast('/') }.sorted())

    // ---- THE STABILITY CONTROL: identical text, identical fingerprints ---------
    ExportSignatures.reset()
    dirty()
    project.diagnostics()
    val second = LinkedHashMap(ExportSignatures.fingerprints)
    var same = 0
    val moved = ArrayList<String>()
    for ((f, h) in first) {
        if (second[f] == h) same++ else moved.add(f.substringAfterLast('/'))
    }
    println("STABILITY identical-text rebuild: $same/${first.size} fingerprints equal")
    if (moved.isNotEmpty()) println("  MOVED: ${moved.sorted()}")

    // ---- THE CONTROL THAT DECIDES WHETHER THE MECHANISM CAN CONVERGE ----------
    // The baseline fingerprint of a file comes from a WHOLE-PROGRAM build; the one
    // an edit produces comes from a NARROWED build of that file alone. If those two
    // disagree systematically, every first edit reads as a signature change, falls
    // back to a full rebuild, and the mechanism never pays — so this is not an
    // optimization detail, it is the feasibility question.
    //
    // (INC.2) says the two resolvers DO diverge: 45 of 381,666 captured spans, and
    // in 5 of those the FULL build is the wrong one. A small disagreement is
    // affordable (it costs a spurious rebuild); a large one is fatal.
    var agree = 0
    val disagree = ArrayList<String>()
    val narrowMs = ArrayList<Long>()
    val narrowedFpMs = ArrayList<Long>()
    val narrowedFiles = ArrayList<Int>()
    for (f in files.take(24)) {
        ExportSignatures.reset()
        project.updateFile(f, texts.getValue(f))
        narrowMs.add(ms { project.diagnosticsOf(listOf(f)) })
        val narrowed = ExportSignatures.fingerprints[f]
        narrowedFpMs.add(ExportSignatures.nanos / 1_000_000)
        narrowedFiles.add(ExportSignatures.fingerprints.size)
        if (narrowed != null && narrowed == second[f]) agree++
        else disagree.add(f.substringAfterLast('/'))
    }
    println("PARTITION-AGREEMENT narrowed-vs-whole: $agree/${minOf(24, files.size)} equal")
    if (disagree.isNotEmpty()) println("  DISAGREE: ${disagree.sorted()}")
    println("narrowed build ms ${narrowMs.sorted()}")
    println("narrowed fingerprint ms ${narrowedFpMs.sorted()}")
    println("narrowed files fingerprinted ${narrowedFiles.distinct().sorted()}")

    ExportSignatures.enabled = false
    project.close()
}
