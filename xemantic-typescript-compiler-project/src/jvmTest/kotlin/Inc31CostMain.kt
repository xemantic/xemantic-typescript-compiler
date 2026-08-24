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
import java.lang.management.ManagementFactory

/**
 * (INC.31) step 1 — RE-TAKE the whole `docs/language-service.md` cost table, in ONE
 * process, with today's binary.
 *
 * Every wall figure in that page's §3 / §10a / §10b / §14 was taken at round 930,
 * BEFORE (INC.2b) narrowed the capture path and before the incremental floor fell
 * 1,092 -> 58 ms. This reproduces those cells so the two are comparable cell for
 * cell, and adds the four the round-930 runners never had: `completionsAt`,
 * `signatureHelpAt`, the per-caret RESIDUE decomposition, and a real PEAK-HEAP
 * reading for the whole-program sweeps.
 *
 * Not a gate and not a pin — it asserts nothing but its own non-vacuity.
 */
private fun ms(block: () -> Unit): Long {
    val at = System.nanoTime()
    block()
    return (System.nanoTime() - at) / 1_000_000
}

private fun us(block: () -> Unit): Long {
    val at = System.nanoTime()
    block()
    return (System.nanoTime() - at) / 1_000
}

private val rows = LinkedHashMap<String, MutableList<Long>>()

private fun record(arm: String, v: Long) {
    rows.getOrPut(arm) { ArrayList() }.add(v)
}

/**
 * The SUM of every heap pool's peak `used`, in MB.
 *
 * Deliberately quoted as an UPPER BOUND and never as "the heap this needs": the pools
 * peak at different instants, so the sum can exceed `-Xmx` (measured — it reads ~9 GB
 * under `-Xmx6g` for a rename sweep). The honest reading is per POOL plus a live-set
 * reading after a full GC, which is `Inc31ResidueMain`'s `heap` mode: measured
 * 2026-08-24, a `referencesAt` sweep on tsc's own sources peaks at 1,077 – 1,125 MB in
 * G1 old gen, retains 264 MB after GC, runs green at `-Xmx2g` and dies at `-Xmx1g`.
 */
private fun peakHeapMb(): Long {
    var peak = 0L
    for (pool in ManagementFactory.getMemoryPoolMXBeans()) {
        if (pool.type == java.lang.management.MemoryType.HEAP) peak += pool.peakUsage.used
    }
    return peak / (1024 * 1024)
}

private fun resetPeak() {
    for (pool in ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage()
}

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [rotations]" }
    val dir = args[0]
    val rotations = if (args.size > 1) args[1].toInt() else 4

    val project = Project.open(dir)
    val files = project.files
    val big = files.first { it.endsWith("src/compiler/checker.ts") }
    val mid = files.first { it.endsWith("src/compiler/binder.ts") }
    val texts = HashMap<String, String>()
    var characters = 0L
    for (f in files) {
        val t = File(f).readText()
        texts[f] = t
        characters += t.length
    }
    println("files=${files.size} characters=$characters")

    val options = TsConfigLoader(SystemVfs).load(project.configPath).options
    fun indexOf(file: String): SourceIndex {
        val text = texts.getValue(file)
        return SourceIndex.of(text, file, computeParserFlags(file, text, options))
    }

    // ---- carets, each VALIDATED so no arm below times an index lookup ----------
    class Carets(val file: String, val c1: Int, val c2: Int, val member: Int, val sig: Int)

    fun caretsIn(file: String): Carets {
        val text = texts.getValue(file)
        val index = indexOf(file)
        val needle = "SourceFile"
        val c1 = text.indexOf(needle)
        val c2 = text.lastIndexOf(needle)
        require(c1 >= 0 && c2 > c1) { "needle not twice in $file" }
        // A MEMBER completion caret: just past a `.` whose anchor really resolves a
        // receiver. Scanned rather than guessed — a refused kind does not build, and
        // every completion row would then be a timing of a syntactic refusal.
        var member = -1
        var at = 0
        while (member < 0) {
            at = text.indexOf('.', at + 1)
            if (at < 0) break
            val anchor = index.completionAnchorAt(at + 1)
            if (anchor.kind == CompletionKind.MEMBER && anchor.receiver != null) member = at + 1
        }
        require(member > 0) { "no MEMBER completion caret in $file" }
        // A signature-help caret: inside an argument list the anchor accepts.
        var sig = -1
        at = 0
        while (sig < 0) {
            at = text.indexOf('(', at + 1)
            if (at < 0) break
            if (index.signatureAnchorAt(at + 1) != null) sig = at + 1
        }
        require(sig > 0) { "no signature-help caret in $file" }
        return Carets(file, c1, c2, member, sig)
    }

    val midC = caretsIn(mid)
    val bigC = caretsIn(big)
    require(project.quickInfoAt(mid, midC.c1) != null) { "mid caret1 answers null" }
    require(project.quickInfoAt(mid, midC.c2) != null) { "mid caret2 answers null" }
    require(project.completionsAt(mid, midC.member).items.isNotEmpty()) {
        "REFUSED: the completion caret answers no item, so every completion row is empty"
    }
    require(project.signatureHelpAt(mid, midC.sig) != null) {
        "REFUSED: the signature caret answers null"
    }
    require(project.diagnostics().isNotEmpty()) { "REFUSED: the program reports nothing" }
    println(
        "carets mid=$mid c1=${midC.c1} c2=${midC.c2} member=${midC.member} sig=${midC.sig} | " +
            "big=$big c1=${bigC.c1} member=${bigC.member}",
    )

    // ---- WARM-UP: six full rebuilds plus six narrowed ones, all discarded -------
    repeat(6) {
        project.updateFile(mid, texts.getValue(mid))
        project.diagnostics()
        project.diagnosticsOf(listOf(mid))
        project.quickInfoAt(mid, midC.c1)
    }
    println("warmup done")

    fun dirty() = project.updateFile(mid, texts.getValue(mid))

    // ================= BATTERY 1 — the §14 per-query table ======================
    repeat(rotations) { r ->
        dirty(); record("rebuild.full", ms { project.diagnostics() })

        dirty()
        record("diagnosticsOf.mid.fresh", ms { project.diagnosticsOf(listOf(mid)) })
        record("diagnosticsOf.mid.repeat", ms { project.diagnosticsOf(listOf(mid)) })
        record("diagnosticsOf.big.fresh", ms { project.diagnosticsOf(listOf(big)) })
        record("diagnosticsOf.mid.afterBig", ms { project.diagnosticsOf(listOf(mid)) })

        dirty()
        record("quickInfo.mid.first", ms { project.quickInfoAt(mid, midC.c1) })
        record("definitions.mid.sameCaret", ms { project.definitionsAt(mid, midC.c1) })
        record("quickInfo.mid.secondCaret", ms { project.quickInfoAt(mid, midC.c2) })
        record("highlights.mid.first", ms { project.documentHighlightsAt(mid, midC.c1) })
        record("highlights.mid.secondCaret", ms { project.documentHighlightsAt(mid, midC.c2) })
        record("fileSemantics.mid.afterHover", ms { project.fileSemantics(mid) })
        // Does a hover's file-wide capture serve a COMPLETION in the same buffer?
        record("completions.mid.afterHover", ms { project.completionsAt(mid, midC.member) })
        record("completions.mid.repeat", ms { project.completionsAt(mid, midC.member) })
        record("signatureHelp.mid.afterCompletion", ms { project.signatureHelpAt(mid, midC.sig) })
        record("signatureHelp.mid.repeat", ms { project.signatureHelpAt(mid, midC.sig) })
        // ...and does a completion evict the hover's capture? (LRU is two entries.)
        record("quickInfo.mid.afterTwoOtherChannels", ms { project.quickInfoAt(mid, midC.c1) })

        dirty()
        record("completions.mid.cold", ms { project.completionsAt(mid, midC.member) })
        dirty()
        record("signatureHelp.mid.cold", ms { project.signatureHelpAt(mid, midC.sig) })

        // checker.ts, the extreme, once per rotation
        dirty(); record("diagnosticsOf.big.cold", ms { project.diagnosticsOf(listOf(big)) })
        dirty(); record("quickInfo.big.first", ms { project.quickInfoAt(big, bigC.c1) })
        record("quickInfo.big.secondCaret", ms { project.quickInfoAt(big, bigC.c2) })
        record("highlights.big.secondCaret", ms { project.documentHighlightsAt(big, bigC.c2) })
        println("battery1 rotation=$r done")
    }

    // ================= BATTERY 2 — medians over ALL files ========================
    run {
        val diag = ArrayList<Long>()
        val hover = ArrayList<Long>()
        val hover2 = ArrayList<Long>()
        for (f in files) {
            dirty()
            diag.add(ms { project.diagnosticsOf(listOf(f)) })
        }
        for (f in files) {
            val text = texts.getValue(f)
            val i1 = text.indexOf("SourceFile").let { if (it >= 0) it else text.indexOf("const") }
            val i2 = text.lastIndexOf("SourceFile").let { if (it >= 0) it else text.lastIndexOf("const") }
            if (i1 < 0 || i2 <= i1) continue
            dirty()
            hover.add(ms { project.quickInfoAt(f, i1) })
            hover2.add(ms { project.quickInfoAt(f, i2) })
        }
        diag.sort(); hover.sort(); hover2.sort()
        println(
            "sweep diagnosticsOf n=${diag.size} med=${diag[diag.size / 2]} " +
                "p90=${diag[(diag.size * 9) / 10]} max=${diag.last()}",
        )
        println(
            "sweep quickInfo.first n=${hover.size} med=${hover[hover.size / 2]} " +
                "p90=${hover[(hover.size * 9) / 10]} max=${hover.last()}",
        )
        println(
            "sweep quickInfo.second n=${hover2.size} med=${hover2[hover2.size / 2]} " +
                "p90=${hover2[(hover2.size * 9) / 10]} max=${hover2.last()}",
        )
        record("sweep.diag.median", diag[diag.size / 2])
        record("sweep.hover1.median", hover[hover.size / 2])
        record("sweep.hover2.median", hover2[hover2.size / 2])
    }

    // ================= BATTERY 3 — prepare, the working set =====================
    run {
        val candidates = files.filter { it != big }
            .mapNotNull { f ->
                val t = texts.getValue(f)
                val i = t.indexOf("SourceFile")
                if (i < 0) null else Triple(f, i, t.length)
            }.sortedBy { it.third }
        val working = candidates.drop(candidates.size / 2).take(6)
        val wf = working.map { it.first }
        val wc = working.associate { it.first to it.second }
        println("workingSet=" + wf.joinToString(",") { it.substringAfterLast('/') })
        repeat(rotations) {
            dirty()
            record("ws.hover6.noPrepare", ms { for (f in wf) project.quickInfoAt(f, wc.getValue(f)) })
            dirty()
            record("ws.prepare", ms { project.prepare(wf) })
            record("ws.hover6.prepared", ms { for (f in wf) project.quickInfoAt(f, wc.getValue(f)) })
            record(
                "ws.defs+hl12.prepared",
                ms {
                    for (f in wf) {
                        project.definitionsAt(f, wc.getValue(f))
                        project.documentHighlightsAt(f, wc.getValue(f))
                    }
                },
            )
            // Do completions/signature help ride a PREPARED check? (the claim to refute)
            record("ws.completions.afterPrepare", ms { project.completionsAt(mid, midC.member) })
            dirty()
            record("ws.diag6.perFile", ms { for (f in wf) project.diagnosticsOf(listOf(f)) })
            dirty()
            record("ws.diag1.wholeSet", ms { project.diagnosticsOf(wf) })
            record("ws.diag6.afterWholeSet", ms { for (f in wf) project.diagnosticsOf(listOf(f)) })
        }
    }

    // ================= BATTERY 4 — whole-program sweeps + PEAK HEAP =============
    run {
        val text = texts.getValue(mid)
        val refCaret = text.indexOf("SyntaxKind").let { if (it >= 0) it else midC.c1 }
        repeat(2) {
            dirty(); project.diagnostics() // clean state
            resetPeak()
            record("referencesAt.clean", ms { project.referencesAt(mid, refCaret) })
            record("referencesAt.clean.peakHeapMb", peakHeapMb())
            dirty()
            resetPeak()
            record("referencesAt.dirty", ms { project.referencesAt(mid, refCaret) })
            record("referencesAt.dirty.peakHeapMb", peakHeapMb())
            dirty()
            resetPeak()
            record("renameAt.dirty", ms { project.renameAt(mid, refCaret, "XtscRenamedProbe") })
            record("renameAt.dirty.peakHeapMb", peakHeapMb())
            project.diagnostics()
            resetPeak()
            record("renameAt.clean", ms { project.renameAt(mid, refCaret, "XtscRenamedProbe") })
            record("renameAt.clean.peakHeapMb", peakHeapMb())
        }
    }

    project.close()

    // ================= BATTERY 5 — the PER-CARET RESIDUE ========================
    //
    // Everything a second caret in an unchanged buffer still pays. Measured on the
    // SourceIndex directly, because that is where the suspects live, and the index
    // itself is cached by `Project` so only these calls repeat.
    println("== residue (us per call, median of 20) ==")
    for (f in listOf(mid, big, files.first { it.endsWith("src/compiler/utilities.ts") })) {
        val index = indexOf(f)
        val chars = texts.getValue(f).length
        // one untimed call each, so the first-call ramp is not the measurement
        index.identifiers(); index.occurrenceNodes()
        val ids = ArrayList<Long>()
        val occ = ArrayList<Long>()
        val prologue = ArrayList<Long>()
        repeat(20) {
            ids.add(us { index.identifiers() })
            occ.add(us { index.occurrenceNodes() })
            prologue.add(
                us {
                    val nodes = index.occurrenceNodes()
                    val covered = HashSet<Long>(nodes.size * 2)
                    for (n in nodes) covered.add((n.pos.toLong() shl 32) or n.end.toLong())
                    covered.contains(0L)
                },
            )
        }
        ids.sort(); occ.sort(); prologue.sort()
        println(
            "residue ${f.substringAfterLast('/')} chars=$chars ids=${index.identifiers().size} " +
                "occ=${index.occurrenceNodes().size} " +
                "identifiers=${ids[10]}us occurrenceNodes=${occ[10]}us " +
                "captureAroundPrologue=${prologue[10]}us",
        )
    }

    println("== medians (ms unless named) ==")
    for ((arm, vs) in rows) {
        vs.sort()
        println("MED $arm ${vs[vs.size / 2]}  $vs")
    }
}
