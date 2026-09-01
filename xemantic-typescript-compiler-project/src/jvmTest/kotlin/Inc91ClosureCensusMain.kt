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

import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * (INC.91) THE CENSUS THAT DECIDES THE ITEM, TAKEN BEFORE ANYTHING IS BUILT.
 *
 * (INC.91) proposes tsgo's per-hop, signature-keyed walk: on a signature move,
 * re-check the edited file's DIRECT importers, re-fingerprint those, and stop
 * wherever a fingerprint did not move. Its prize is bounded by ONE count — how many
 * of the edited file's transitive importers actually move their own fingerprint —
 * and this repo's standing law is that such a prize is measured before it is built,
 * because every over-estimate here came from `hits x mean-call-cost`.
 *
 * ## The specific mechanism that could make the walk useless, and why it is measured
 * ## rather than assumed
 *
 * (INC.47)'s fingerprint CUTS at the file boundary: a type declared in another file
 * is not descended into, it is keyed by its declaration's `(fileName, pos, end)`
 * (`Checker.foreignKey`). So an importer's fingerprint carries the BYTE OFFSETS of
 * the declarations it imports — and any edit that shifts offsets in the edited file
 * moves every importer's fingerprint even when nothing semantic moved. That would
 * prune nothing for a large class of ordinary edits.
 *
 * The census therefore runs TWO signature edits that are semantically IDENTICAL and
 * differ only in byte layout:
 *
 *  - `sig.ts`    — one `export const` APPENDED at the END, shifting no existing
 *                  declaration's `pos`;
 *  - `sigtop.ts` — the same `export const` inserted as the file's FIRST line,
 *                  shifting every subsequent declaration's `pos` and `end`.
 *
 * The two declare the identical set of exported names with the identical types, so a
 * fingerprint that answers differently for them is answering about LAYOUT.
 *
 * ## Why the answer is taken from WHOLE-PROGRAM builds
 *
 * "How many files' fingerprints move as a result of this edit" is a property of the
 * PROGRAM, not of a partition. A narrowed build introduces a second variable — the
 * partition's own first-touch order ((INC.19)) — and (INC.46)'s convergence sweep
 * (24 of 24) is a measurement on a different profile, not a licence here. So the
 * primary arm is a whole-program build per edit, diffed against a whole-program
 * baseline, and the NARROWED arm — the partition (INC.91)'s walk would actually
 * build — is reported beside it as the corroboration.
 *
 * A CONTROL arm runs the same narrowed partition over the UNEDITED text: whatever it
 * reports moved is narrowing artifact and not the edit, and without it a nonzero
 * count cannot be attributed (round 902 — an arm can be dead, and an instrument can
 * be, too).
 *
 * ## Usage
 *
 * ```
 * <projectDir> <editFileRelative> <editsDir>
 * ```
 *
 * `<editsDir>` must hold `orig.ts`, `sig.ts` and `sigtop.ts`. The runner REFUSES a
 * tree that does not already hold the recorded original, and it touches no file on
 * disk: every edit is applied through [OverlayVfs], which is what keeps the fixture
 * pristine by construction rather than by a cleanup step that a crash can skip.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: <projectDir> <editFileRelative> <editsDir>" }
    val dir = File(args[0]).absolutePath
    val editRelative = args[1]
    val editsDir = args[2]

    val target = File(dir, editRelative)
    require(target.isFile) { "no file to edit at ${target.path}" }

    fun variant(name: String): String {
        val f = File(editsDir, "$name.ts")
        require(f.isFile) { "REFUSED: no '$name' variant at ${f.path}" }
        return f.readText()
    }
    val orig = variant("orig")
    val sigEnd = variant("sig")
    val sigTop = variant("sigtop")
    require(target.readText() == orig) {
        "REFUSED: ${target.path} does not hold the recorded original — the fixture is dirty"
    }
    require(sigEnd != orig && sigTop != orig) { "REFUSED: a variant is identical to the original" }
    require(sigEnd != sigTop) { "REFUSED: the two signature variants are identical" }

    val vfs = OverlayVfs(SystemVfs)
    val compiler = ProjectCompiler(vfs)
    val editedKey = vfs.resolveAbsolute(PathUtil.normalize(target.path))

    println("project      : $dir")
    println("edited       : $editRelative")
    println("edited key   : $editedKey")
    println("edits        : $editsDir")
    println()

    // ---- baseline: a whole-program build of the pristine tree.
    val base = compiler.build(dir, noEmit = true, exportSignatures = true)
    println("program files      : ${base.programFiles.size}")
    println("import edges       : ${base.importEdges.size}")
    println("fingerprinted files: ${base.exportSignatures.size}")
    println("escaping files     : ${base.exportSignatureEscapes.size}")
    println("baseline rows      : ${base.diagnostics.size}")
    require(editedKey in base.exportSignatures) {
        "REFUSED: the edited file has no baseline fingerprint — key mismatch, so every " +
            "count below would be about the wrong file"
    }
    println()

    // ---- the reverse-import graph, and the BFS that gives each importer its hop.
    //      `importEdges` is (importer, imported), so the reverse map is keyed by the
    //      imported file — which is the direction a per-hop walk travels.
    val importersOf = HashMap<String, MutableList<String>>()
    for ((importer, imported) in base.importEdges) {
        importersOf.getOrPut(imported) { ArrayList() }.add(importer)
    }
    val hop = LinkedHashMap<String, Int>()
    var frontier = listOf(editedKey)
    var distance = 0
    hop[editedKey] = 0
    while (frontier.isNotEmpty()) {
        distance++
        val next = ArrayList<String>()
        for (f in frontier) for (i in importersOf[f].orEmpty()) {
            if (hop.putIfAbsent(i, distance) == null) next.add(i)
        }
        frontier = next
    }
    val closure = hop.keys.toSet()
    val byHop = hop.entries.groupBy({ it.value }, { it.key }).toSortedMap()
    println("=== transitive importer closure of the edited file ===")
    println("closure size (incl. the edited file): ${closure.size} of ${base.programFiles.size}" +
        " (${"%.1f".format(100.0 * closure.size / base.programFiles.size)}% of the program)")
    for ((d, files) in byHop) println("  hop %2d: %5d file(s)".format(d, files.size))
    println()

    /** One arm's answer: which files' fingerprints moved, and at what hop. */
    fun diff(label: String, after: Map<String, Long>, escapes: Set<String>, rows: Int) {
        val moved = ArrayList<String>()
        val absent = ArrayList<String>()
        for (f in base.exportSignatures.keys) {
            val a = after[f]
            if (a == null) { absent.add(f); continue }
            if (a != base.exportSignatures[f]) moved.add(f)
        }
        val movedByHop = moved.groupBy { hop[it] ?: -1 }.toSortedMap()
        println("--- $label")
        println("    rows=$rows  fingerprinted=${after.size}  escapes=${escapes.size}" +
            "  not-fingerprinted-by-this-build=${absent.size}")
        println("    MOVED: ${moved.size} file(s)")
        for ((d, files) in movedByHop) {
            val where = if (d < 0) "outside the closure" else "hop $d"
            val sample = files.sorted().take(3).map { it.substringAfterLast('/') }
            println("      %-20s %5d  e.g. %s".format(where, files.size, sample))
        }
        if (moved.isEmpty()) println("      (none)")
        println()
    }

    // ---- ARM 1 (primary): whole-program builds, one per edit. No partition, so the
    //      only variable is the edit itself.
    println("=== ARM 1 — WHOLE-PROGRAM builds (the definitive count) ===")
    for ((label, text) in listOf(
        "CONTROL: unedited text, rebuilt" to orig,
        "EDIT (a): export const APPENDED at END" to sigEnd,
        "EDIT (b): same export const inserted at TOP" to sigTop,
    )) {
        vfs.put(editedKey, text)
        val r = compiler.build(dir, noEmit = true, exportSignatures = true)
        diff(label, r.exportSignatures, r.exportSignatureEscapes, r.diagnostics.size)
        vfs.revert(editedKey)
    }

    // ---- ARM 2: the partition (INC.91)'s walk would actually build — the edited file
    //      plus its whole transitive importer closure. Corroborates arm 1 and prices
    //      the walk's own build; the CONTROL row is what separates a narrowing
    //      artifact from the edit.
    println("=== ARM 2 — NARROWED builds over the closure (what the walk would see) ===")
    for ((label, text) in listOf(
        "CONTROL: unedited text, narrowed" to orig,
        "EDIT (a): export const APPENDED at END" to sigEnd,
        "EDIT (b): same export const inserted at TOP" to sigTop,
    )) {
        vfs.put(editedKey, text)
        val r = compiler.build(
            dir, noEmit = true, recheckOnly = closure, exportSignatures = true,
        )
        diff(label, r.exportSignatures, r.exportSignatureEscapes, r.diagnostics.size)
        vfs.revert(editedKey)
    }

    // ---- ARM 3: the FIRST HOP alone, which is the partition (INC.91)'s walk builds
    //      at its first step. If the answer is "the walk stops here", this is the
    //      whole cost of a signature edit under the proposed design.
    val firstHop = (byHop[0].orEmpty() + byHop[1].orEmpty()).toSet()
    println("=== ARM 3 — NARROWED to the edited file + its DIRECT importers only ===")
    println("    partition size: ${firstHop.size}")
    for ((label, text) in listOf(
        "EDIT (a): export const APPENDED at END" to sigEnd,
        "EDIT (b): same export const inserted at TOP" to sigTop,
    )) {
        vfs.put(editedKey, text)
        val r = compiler.build(
            dir, noEmit = true, recheckOnly = firstHop, exportSignatures = true,
        )
        diff(label, r.exportSignatures, r.exportSignatureEscapes, r.diagnostics.size)
        vfs.revert(editedKey)
    }

    require(target.readText() == orig) {
        "REFUSED: the fixture on disk changed during the run — no edit here touches disk"
    }
    println("fixture verified pristine on disk.")
}
