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

import com.xemantic.typescript.compiler.CapturedDeclaration
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.RecheckAnswer
import com.xemantic.typescript.compiler.RecheckHolder
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags

/**
 * (INC.17) step 2 — THE REPLAY-vs-FRESH-BUILD DIFFERENTIAL, this round's primary
 * correctness instrument.
 *
 * A re-entrant recheck answers about a file its checker's first walk did not cover
 * by re-entering only the partition-DEPENDENT `init` passes. Its correctness claim
 * is exactly one sentence: **for a file the checker was not originally asked about,
 * the replayed answer equals a fresh narrowed build's answer.** This compares the
 * two, per file, over TWO channels:
 *
 * * **diagnostics** — every row naming the file, position, code and message.
 * * **captured types and definitions** — one row per identifier in the file.
 *
 * The second channel is not decoration. (INC.18)'s arm a3 is a recorded NEGATIVE:
 * a round-609-style starved COLLECTOR (`init:buildFileLocalTypeMaps`) is invisible
 * to a diagnostics comparison in BOTH arms of the partition gate, because that
 * map's product feeds type DISPLAY — (INC.10) measured its deferral at 2,722 moved
 * capture spans and ZERO moved diagnostics. A replay that lost a producer/consumer
 * pair fails here and nowhere else, and the type channel carries three orders of
 * magnitude more rows than the diagnostic one.
 *
 * ## The oracle is FREE
 *
 * Both arms are this compiler; no baseline is recorded and none can go stale. What
 * makes the comparison meaningful rather than trivially true is that the two arms
 * reach their answer by DIFFERENT routes: the fresh arm runs all ~416 passes with
 * the partition it is given, the replay arm runs ~211 of them once (with a
 * different partition) and re-enters the rest.
 *
 * ## The controls, printed and enforced
 *
 * A differential over an empty population agrees vacuously, which is (INC.18)'s
 * whole finding one instrument over. So this REFUSES unless the fresh arm produced
 * something to compare: at least one file carrying a diagnostic, and a non-empty
 * type population. It also refuses if `walkedFiles` did not grow — a recheck that
 * silently answered from the first build's partition would agree with everything.
 *
 * ```
 * scripts/replay-differential.sh [<projectDir> [maxFiles]]
 * ```
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [maxFiles] [all]" }
    val limit = if (args.size > 1 && args[1].toInt() > 0) args[1].toInt() else Int.MAX_VALUE
    // (INC.17) the ATTRIBUTION arm: re-enter EVERY pass, not only the classified
    // ones. A divergence that survives it is not a classification defect — it is the
    // seed build's first-touch cache order, which is (INC.2)/(INC.5)'s subject and
    // which no re-entry can undo. See [PassTiming.replayAllPasses].
    val replayAll = args.size > 2 && args[2] == "all"
    // An optional TARGET FILTER, so the attribution arm can be pointed at the files
    // a previous run reported diverging instead of re-entering every pass for all of
    // them. It narrows what is COMPARED, never what the seed build walked.
    val only = if (args.size > 3 && args[3].isNotEmpty()) args[3].split(',') else emptyList()
    PassTiming.replayAllPasses = replayAll
    println("mode: ${if (replayAll) "replay ALL passes (attribution arm)" else "replay the classified passes"}")

    val vfs = SystemVfs
    val compiler = ProjectCompiler(vfs)
    val project = vfs.resolveAbsolute(args[0])
    val configPath = if (vfs.isDirectory(project)) "$project/tsconfig.json" else project
    val options = TsConfigLoader(vfs).load(configPath).options

    // The first build in a process is the slowest draw here; discard one.
    compiler.build(project, noEmit = true)
    val programFiles = compiler.build(project, noEmit = true).programFiles
    require(programFiles.size >= 2) {
        "REFUSED: a program of one file has no file OUTSIDE the seed partition, so " +
            "the replay would never be entered and every comparison would be vacuous"
    }

    // Spans are computed ONCE per file and handed to BOTH arms, so the arms cannot
    // differ in what they were ASKED (CheckerReuseDifferentialMain's rule).
    val spansOf = LinkedHashMap<String, List<TypeCaptureSpan>>()
    for (file in programFiles) {
        val text = vfs.readText(file) ?: continue
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        val spans = index.identifiers().map { TypeCaptureSpan(file, it.pos, it.end) }.distinct()
        if (spans.isNotEmpty()) spansOf[file] = spans
    }
    require(spansOf.size >= 2) {
        "REFUSED: fewer than two files yielded an identifier, so the type channel " +
            "would compare nothing"
    }

    // THE SEED PARTITION — the first file, which stands for "the buffer the host
    // named". Every other file is then one the checker was NOT asked about, which is
    // the population (INC.17) exists for.
    val files = spansOf.keys.toList()
    val seed = files.first()
    val targets = files.drop(1)
        .filter { f -> only.isEmpty() || only.any { f.endsWith(it) } }
        .take(limit)
    require(targets.isNotEmpty()) { "REFUSED: the target filter $only matched no file" }
    println("project: $project")
    println("program: files=${programFiles.size}  withIdentifiers=${files.size}")
    println("seed: ${seed.substringAfterLast('/')}  targets=${targets.size}")

    fun declarations(declarations: List<CapturedDeclaration>): String =
        declarations.map { "${it.fileName}:${it.start}+${it.length}:${it.kind}" }
            .sorted().joinToString(",")

    fun diagnosticRows(rows: List<Diagnostic>, file: String): List<String> =
        rows.filter { it.fileName == file }
            .map { "TS${it.code}@${it.start}+${it.length} ${it.message}" }
            .sorted()

    fun typeRows(
        captured: List<com.xemantic.typescript.compiler.CapturedType>,
        file: String,
    ): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (c in captured) {
            if (c.fileName != file) continue
            rows[spanKey(c.start, c.end)] = "${c.kind}|${c.typeText}"
        }
        return rows
    }

    fun definitionRows(
        captured: List<com.xemantic.typescript.compiler.CapturedDefinition>,
        file: String,
    ): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (c in captured) {
            if (c.fileName != file) continue
            rows[spanKey(c.start, c.end)] = c.name +
                "|loc=" + declarations(c.locations) +
                "|rel=" + declarations(c.related) +
                "|sho=" + declarations(c.shorthand)
        }
        return rows
    }

    // ---- the REPLAY arm: ONE build for the seed, then one recheck per target.
    val holder = RecheckHolder()
    val seedBuild = compiler.build(
        project,
        noEmit = true,
        recheckOnly = setOf(seed),
        typeCapture = TypeCaptureRequest(spansOf.getValue(seed)),
        recheckHolder = holder,
    )
    val recheck = requireNotNull(holder.recheck) {
        "REFUSED: the build handed back no ProgramRecheck, so the replay arm would " +
            "silently be a second copy of the fresh arm"
    }
    require(seedBuild.programFiles.size == programFiles.size) {
        "REFUSED: the seed build saw a different program than the reference build"
    }
    val walkedBefore = recheck.walkedFiles.size

    val replayAnswers = LinkedHashMap<String, RecheckAnswer>()
    var replayNanos = 0L
    for (file in targets) {
        val at = System.nanoTime()
        val answer = recheck.recheck(setOf(file), TypeCaptureRequest(spansOf.getValue(file)))
        replayNanos += System.nanoTime() - at
        replayAnswers[file] = answer
    }
    val walkedAfter = recheck.walkedFiles.size
    // THE RECEIPT: how many of the checker's 417 `init` rows a replay re-enters, and
    // therefore how much of the floor it does NOT pay. A COUNT, not a ms.
    val replayed = recheck.replayedPasses
    println("replayedPasses=${replayed.size} of 417 init rows")
    require(walkedAfter == walkedBefore + targets.size) {
        "REFUSED: walkedFiles went $walkedBefore -> $walkedAfter over ${targets.size} " +
            "recheck(s) — the replay did not actually widen its partition, so every " +
            "comparison below would be about work that never happened"
    }

    // ---- the FRESH arm, and the comparison.
    var divergedFiles = 0
    var diagRows = 0
    var typeRowCount = 0
    var defRowCount = 0
    var filesCarryingDiagnostics = 0
    var freshNanos = 0L
    for (file in targets) {
        val at = System.nanoTime()
        val fresh = compiler.build(
            project,
            noEmit = true,
            recheckOnly = setOf(file),
            typeCapture = TypeCaptureRequest(spansOf.getValue(file)),
        )
        freshNanos += System.nanoTime() - at
        val answer = replayAnswers.getValue(file)

        val expectedDiag = diagnosticRows(fresh.diagnostics, file)
        val actualDiag = diagnosticRows(answer.diagnostics, file)
        val expectedTypes = typeRows(fresh.capturedTypes, file)
        val actualTypes = typeRows(answer.capturedTypes, file)
        val expectedDefs = definitionRows(fresh.capturedDefinitions, file)
        val actualDefs = definitionRows(answer.capturedDefinitions, file)
        diagRows += expectedDiag.size
        typeRowCount += expectedTypes.size
        defRowCount += expectedDefs.size
        if (expectedDiag.isNotEmpty()) filesCarryingDiagnostics++

        val short = file.substringAfterLast('/')
        var bad = false
        if (expectedDiag != actualDiag) {
            bad = true
            println("DIVERGE-DIAG $short  fresh=${expectedDiag.size} replay=${actualDiag.size}")
            for (row in (expectedDiag - actualDiag.toSet()).take(5)) println("   only-fresh: ${row.take(150)}")
            for (row in (actualDiag - expectedDiag.toSet()).take(5)) println("   only-replay: ${row.take(150)}")
        }
        val typeKeys = expectedTypes.keys + actualTypes.keys
        val typeDiff = typeKeys.filter { expectedTypes[it] != actualTypes[it] }
        if (typeDiff.isNotEmpty()) {
            bad = true
            println("DIVERGE-TYPE $short  spans=${typeDiff.size} of ${typeKeys.size}")
            for (k in typeDiff.take(5)) {
                println("   fresh=${expectedTypes[k]?.take(90)}  replay=${actualTypes[k]?.take(90)}")
            }
        }
        val defKeys = expectedDefs.keys + actualDefs.keys
        val defDiff = defKeys.filter { expectedDefs[it] != actualDefs[it] }
        if (defDiff.isNotEmpty()) {
            bad = true
            println("DIVERGE-DEF $short  spans=${defDiff.size} of ${defKeys.size}")
            for (k in defDiff.take(5)) {
                println("   fresh=${expectedDefs[k]?.take(90)}  replay=${actualDefs[k]?.take(90)}")
            }
        }
        if (bad) divergedFiles++
    }

    // THE CONTROLS. A differential over an empty population agrees vacuously.
    println(
        "compared: files=${targets.size} diagnosticRows=$diagRows " +
            "filesCarryingDiagnostics=$filesCarryingDiagnostics " +
            "typeSpans=$typeRowCount definitionSpans=$defRowCount",
    )
    println(
        "cost: replay=${replayNanos / 1_000_000} ms  freshBuilds=${freshNanos / 1_000_000} ms " +
            "over ${targets.size} question(s)",
    )
    require(typeRowCount > 0) {
        "REFUSED: the fresh arm captured NO type, so the sensitive channel compared " +
            "nothing and this run is the round-806 vacuity"
    }
    require(filesCarryingDiagnostics > 0) {
        "REFUSED: no target file carries a diagnostic, so the diagnostic channel " +
            "agrees vacuously. Point this at a project that reports something."
    }
    println(
        if (divergedFiles == 0) "EQUIVALENT: all ${targets.size} files agree (diagnostics, types, definitions)"
        else "DIVERGED: $divergedFiles of ${targets.size} file(s)",
    )
    if (divergedFiles != 0) kotlin.system.exitProcess(1)
}

/** A span as one key — file-scoped, exactly as the sibling differentials do it. */
private fun spanKey(start: Int, end: Int): Long =
    (start.toLong() shl 32) or (end.toLong() and 0xffff_ffffL)
