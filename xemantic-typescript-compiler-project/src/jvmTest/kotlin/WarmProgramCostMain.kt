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

import com.xemantic.typescript.compiler.FrontEnd
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * (INC.12) step 1 — PRICE THE WARM PROGRAM, before anything is built.
 *
 * Two prizes, both about work a query redoes that a previous query in the same
 * process already did:
 *
 * * **(P1) the program is UNCHANGED.** A second query — a different file's
 *   diagnostics, or a hover at a different caret — pays the whole floor again.
 * * **(P2) exactly ONE buffer changed.** The editor's own case.
 *
 * Neither is answerable from the floor decomposition alone, because the floor is a
 * property of a BUILD and these are properties of a SEQUENCE of builds. So this
 * runner drives the public [Project] API in the order an editor would, and prints
 * a FrontEnd phase table for a narrowed build beside it, which is what says how much
 * of a query's floor is program-wide (and therefore reusable when nothing changed)
 * and how much is the queried file's own.
 *
 * Every arm is drawn in ONE process, warm, and the plain arms are drawn EARLY and
 * LATE so this process's residual drift is quoted rather than assumed (CLAUDE.md
 * round 869; an impossible ordering is the cheapest tell that a ramp is being
 * measured).
 *
 * ```
 * scripts/warm-program-cost.sh [<projectDir> [<fileA> [<fileB> [rotations]]]]
 * ```
 *
 * Not a gate and not a pin — it asserts nothing except that its own arms are
 * measuring something (a query that reports no diagnostics for a file it was asked
 * about would make every row below a measurement of an empty answer).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [fileA] [fileB] [rotations]" }
    val dir = args[0]
    val suffixA = if (args.size > 1) args[1] else "src/compiler/checker.ts"
    val suffixB = if (args.size > 2) args[2] else "src/compiler/binder.ts"
    val rotations = if (args.size > 3) args[3].toInt() else 3

    val project = Project.open(dir)
    val fileA = project.files.first { it.endsWith(suffixA) }
    val fileB = project.files.first { it.endsWith(suffixB) }
    val textA = File(fileA).readText()
    println("files=${project.files.size} A=$fileA B=$fileB")

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime()
        block()
        return (System.nanoTime() - at) / 1_000_000
    }

    // Two carets in A, the FIRST and LAST occurrence of one identifier needle, so
    // both are certainly identifiers. A caret that resolves to no node would make
    // `quickInfoAt` return without building, and every hover row below would then be
    // a timing of the index lookup — the round-806 vacuity, one API over.
    val needle = "SyntaxKind"
    val caret1 = textA.indexOf(needle)
    val caret2 = textA.lastIndexOf(needle)
    require(caret1 >= 0 && caret2 > caret1) { "needle '$needle' not twice in $fileA" }
    require(project.quickInfoAt(fileA, caret1) != null) {
        "REFUSED: caret1 answers null, so the hover arms would measure an index lookup"
    }
    require(project.quickInfoAt(fileA, caret2) != null) {
        "REFUSED: caret2 answers null, so the hover arms would measure an index lookup"
    }
    // The PROGRAM must report something: a project whose whole answer is empty is a
    // project the checker never really walked, and every row below would be a timing
    // of a no-op. Deliberately NOT asserted per-file — a query's cost does not depend
    // on whether its own answer is empty, and on this profile 78 files carry 46 rows
    // between them, so a per-file assertion would only be picking a file.
    val textB = File(fileB).readText()
    val caretB1 = textB.indexOf(needle)
    val caretB2 = textB.lastIndexOf(needle)
    require(caretB1 >= 0 && caretB2 > caretB1) { "needle '$needle' not twice in $fileB" }
    require(project.quickInfoAt(fileB, caretB1) != null) {
        "REFUSED: caretB1 answers null, so the B arms would measure an index lookup"
    }
    require(project.documentHighlightsAt(fileB, caretB2).isNotEmpty()) {
        "REFUSED: caretB2 highlights nothing, so the highlight arms measure an empty answer"
    }
    require(project.diagnostics().isNotEmpty()) {
        "REFUSED: the whole program reports nothing, so every row below is a no-op"
    }

    // ---- warm up and discard: the first build in a process is the slowest draw here.
    project.diagnostics()
    project.updateFile(fileA, textA)
    project.diagnosticsOf(listOf(fileA))

    val rows = LinkedHashMap<String, MutableList<Long>>()
    fun record(arm: String, v: Long) { rows.getOrPut(arm) { ArrayList() }.add(v) }

    repeat(rotations) { rotation ->
        // (P1) The program is UNCHANGED between every arm of this block. The edit
        // that opens it re-writes A's OWN bytes, so the content-keyed parse cache
        // still hits everywhere — this is a keystroke, not a cold start.
        project.updateFile(fileA, textA)
        val diagA = ms { project.diagnosticsOf(listOf(fileA)) }
        val diagARepeat = ms { project.diagnosticsOf(listOf(fileA)) }
        val diagB = ms { project.diagnosticsOf(listOf(fileB)) }
        val diagARepeat2 = ms { project.diagnosticsOf(listOf(fileA)) }
        // Hover, twice, at two different carets — the program has not changed
        // between them and neither has the FILE the partition names.
        val hover1 = ms { project.quickInfoAt(fileA, caret1) }
        val hover2 = ms { project.quickInfoAt(fileA, caret2) }
        val hover1Again = ms { project.quickInfoAt(fileA, caret1) }
        // (INC.12) the two sequences the memo exists for, on the SMALLER file so the
        // file-wide highlight request stays affordable to draw three times.
        val hoverB = ms { project.quickInfoAt(fileB, caretB1) }
        val defB = ms { project.definitionsAt(fileB, caretB1) }
        // (INC.13) the arm stage 2 exists for: a caret NOBODY has visited, in a buffer
        // that has already been hovered once.
        val hoverB2 = ms { project.quickInfoAt(fileB, caretB2) }
        val semanticsB = ms { project.fileSemantics(fileB) }
        val highlightsB1 = ms { project.documentHighlightsAt(fileB, caretB1) }
        val highlightsB2 = ms { project.documentHighlightsAt(fileB, caretB2) }
        // (P2) exactly one buffer changed, and the query is about THAT buffer.
        project.updateFile(fileA, textA)
        val afterEdit = ms { project.diagnosticsOf(listOf(fileA)) }
        // ...and about ANOTHER buffer, which is the reverse-dependency question.
        project.updateFile(fileA, textA)
        val afterEditOther = ms { project.diagnosticsOf(listOf(fileB)) }

        record("diagA", diagA); record("diagA.repeat", diagARepeat)
        record("diagB.sameState", diagB); record("diagA.repeat2", diagARepeat2)
        record("hover1", hover1); record("hover2.sameState", hover2)
        record("hover1.again", hover1Again)
        record("hoverB", hoverB); record("defB.afterHoverB", defB)
        record("hoverB2.otherCaret", hoverB2)
        record("fileSemanticsB.afterHoverB", semanticsB)
        record("highlightsB1", highlightsB1)
        record("highlightsB2.otherCaret", highlightsB2)
        record("diagA.afterEditA", afterEdit)
        record("diagB.afterEditA", afterEditOther)
        println("rotation=$rotation diagA=$diagA repeat=$diagARepeat diagB=$diagB " +
            "hover1=$hover1 hover2=$hover2 hoverAgain=$hover1Again " +
            "hoverB=$hoverB defB=$defB hoverB2=$hoverB2 semB=$semanticsB " +
            "hlB1=$highlightsB1 hlB2=$highlightsB2 " +
            "afterEditA=$afterEdit afterEditA_askB=$afterEditOther")
    }

    // ---- (INC.14) THE PREPARED RHYTHM -----------------------------------------
    //
    // The block above is now a CONTROL: `prepare` is opt-in, so nothing in it may
    // move. This is the arrangement (INC.14) adds — a host declares its open buffers
    // once per edit and then queries them — measured against the same queries with no
    // `prepare` in front of them, in the same process, rotated.
    //
    // The working set is six MID-SIZED program files, chosen by size rather than by
    // name so the row is not a statement about one file, and deliberately excluding
    // the giant (`checker.ts` alone is ~1.65 s of per-file checking and would bury
    // the floor the arm exists to measure).
    val needle2 = needle
    val candidates = project.files
        .filter { it != fileA }
        .mapNotNull { file ->
            val text = runCatching { File(file).readText() }.getOrNull() ?: return@mapNotNull null
            val first = text.indexOf(needle2)
            val last = text.lastIndexOf(needle2)
            if (first < 0 || last <= first) null else Triple(file, first, text.length)
        }
        .sortedBy { it.third }
    require(candidates.size >= 12) {
        "REFUSED: only ${candidates.size} file(s) carry the needle twice — the working-set " +
            "arm would be a statement about whichever few they are"
    }
    val working = candidates.drop(candidates.size / 2).take(6)
    val workingFiles = working.map { it.first }
    val workingCaret = working.associate { it.first to it.second }
    println(
        "workingSet=" + workingFiles.joinToString(", ") {
            it.substringAfterLast('/') + "(" + (working.first { w -> w.first == it }.third / 1024) + "k)"
        },
    )
    for (file in workingFiles) {
        require(project.quickInfoAt(file, workingCaret.getValue(file)) != null) {
            "REFUSED: $file's caret answers null, so the working-set arms measure an index lookup"
        }
    }

    repeat(rotations) { wsRotation ->
        // OFF — today's behaviour: one narrowed build per buffer, each paying the
        // whole floor.
        project.updateFile(fileA, textA)
        val hoverOff = ms { for (f in workingFiles) project.quickInfoAt(f, workingCaret.getValue(f)) }
        // The SAME second pass, unprepared — partly free already, because the
        // two-entry capture LRU still holds the last two buffers hovered.
        val secondOff = ms {
            for (f in workingFiles) {
                project.definitionsAt(f, workingCaret.getValue(f))
                project.documentHighlightsAt(f, workingCaret.getValue(f))
            }
        }
        // ON — one build for the working set, then six free queries.
        project.updateFile(fileA, textA)
        // The heap CONTROL, read here: the edit has just dropped every cached answer,
        // so what stands between this reading and the one below is the prepared check
        // and the queries served from it. A JVM heap reading is not an RSS reading and
        // `gc()` is a request, so the pair is quoted as a magnitude, never gated on.
        val runtimeBefore = Runtime.getRuntime()
        repeat(3) { runtimeBefore.gc() }
        val heldBeforeMb = (runtimeBefore.totalMemory() - runtimeBefore.freeMemory()) / (1024 * 1024)
        val prepareMs = ms { project.prepare(workingFiles) }
        val hoverOn = ms { for (f in workingFiles) project.quickInfoAt(f, workingCaret.getValue(f)) }
        // …and the queries a host asks NEXT in the same buffers, which are the ones
        // the prepared check exists for.
        val secondPass = ms {
            for (f in workingFiles) {
                project.definitionsAt(f, workingCaret.getValue(f))
                project.documentHighlightsAt(f, workingCaret.getValue(f))
            }
        }
        // What a prepared check COSTS to hold. Crude by construction — a JVM heap
        // reading is not an RSS reading and `gc()` is a request — so it is quoted as
        // an order of magnitude and never as a number to gate on.
        val runtime = Runtime.getRuntime()
        repeat(3) { runtime.gc() }
        val heldMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        // The error-reporting rhythm, the same two ways.
        project.updateFile(fileA, textA)
        val diagOff = ms { for (f in workingFiles) project.diagnosticsOf(listOf(f)) }
        project.updateFile(fileA, textA)
        val diagWide = ms { project.diagnosticsOf(workingFiles) }
        val diagEach = ms { for (f in workingFiles) project.diagnosticsOf(listOf(f)) }

        record("ws.hover6.noPrepare", hoverOff)
        record("ws.defs+highlights12.noPrepare", secondOff)
        record("ws.heldMb.beforePrepare", heldBeforeMb)
        record("ws.heldMb.withPrepared", heldMb)
        record("ws.prepare", prepareMs)
        record("ws.hover6.prepared", hoverOn)
        record("ws.defs+highlights12.prepared", secondPass)
        record("ws.diag6.perFile", diagOff)
        record("ws.diag1.wholeSet", diagWide)
        record("ws.diag6.afterWholeSet", diagEach)
        println(
            "ws rotation=$wsRotation hover6off=$hoverOff second12off=$secondOff " +
                "prepare=$prepareMs hover6on=$hoverOn second12=$secondPass heldMb=$heldBeforeMb->$heldMb " +
                "diag6off=$diagOff diagWide=$diagWide diag6after=$diagEach",
        )
    }

    project.close()

    println("== medians (ms) ==")
    for ((arm, vs) in rows) {
        vs.sort()
        println("MED $arm ${vs[vs.size / 2]}  $vs")
    }

    // ---- the phase table of a NARROWED build, which is what says how much of a
    // query is program-wide. Taken with the same compiler the Project uses, on the
    // same warm process, and drawn twice (a probe's own cost warms up too).
    val compiler = ProjectCompiler(SystemVfs)
    fun narrowed() = compiler.build(dir, noEmit = true, recheckOnly = setOf(fileA))
    fun floor() = compiler.build(
        dir, noEmit = true, recheckOnly = setOf("/no/such/file.ts"),
    )
    repeat(2) { narrowed(); floor() }
    fun dump(tag: String, build: () -> Unit) {
        FrontEnd.reset(); FrontEnd.mode = FrontEnd.ON
        val wall = ms { build() }
        FrontEnd.mode = FrontEnd.OFF
        println("--- FrontEnd $tag wall=${wall}ms ---")
        for (s in 0 until FrontEnd.N) {
            if (FrontEnd.calls[s] == 0L) continue
            println("FE $tag ${FrontEnd.nanos[s] / 1_000_000} ms  calls=${FrontEnd.calls[s]}  ${FrontEnd.names[s].trim()}")
        }
    }
    dump("narrowed", ::narrowed)
    dump("floor", ::floor)
    dump("floor2", ::floor)
    dump("narrowed2", ::narrowed)
}
