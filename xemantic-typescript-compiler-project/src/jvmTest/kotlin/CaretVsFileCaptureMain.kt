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
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags

/**
 * (INC.13) THE FREE ORACLE FOR STAGE 2, AND IT IS A DIFFERENT CLAIM FROM
 * `CaptureEquivalenceMain`'s.
 *
 * `scripts/capture-equivalence.sh` varies the PARTITION (whole-program versus
 * `recheckOnly = {file}`) at a FIXED request. This varies the REQUEST at a fixed
 * partition: one build asked about ONE caret's span, against one build asked about
 * the whole FILE's span set, both narrowed to that file exactly as `Project`
 * narrows them. Nothing else differs, so the two arms answer the same question and
 * **any divergence is a defect in one of them** — no baseline is needed and none is
 * kept.
 *
 * ## Why the answer could differ at all
 *
 * A capture TYPES A NODE THE CHECKER HAD NO REASON TO TYPE, and typing populates
 * caches: `symbolTypes` persists the first resolution (round 778), a union interns
 * under whichever alias touched it first (`aliasDisplayMap`), a target's member
 * table is lazy (round 833). So asking about 40,000 spans resolves types EARLIER
 * than asking about one, and a span's answer is a function of what was resolved
 * before the walk reached it. (INC.10) measured exactly this mechanism one layer
 * over: deferring `init:buildFileLocalTypeMaps` moved capture divergence from 5
 * spans to 2,722 without changing a single diagnostic.
 *
 * The expected failure mode is therefore **not** a missing answer but a subtly
 * different one, which is why every field that reaches a user is compared and why
 * the divergence is CLASSIFIED rather than merely counted.
 *
 * ## The sample, and why it is one
 *
 * The whole-file arm is one build; the per-caret arm is one build PER SPAN, so
 * sweeping every identifier of tsc's own `checker.ts` would be ~120,000 builds. A
 * deterministic evenly-spaced sample of [DEFAULT_CARETS] spans per file is taken
 * instead — evenly spaced rather than random so that a re-run compares the same
 * population, and so that the sample is spread over the file rather than clustered
 * in whichever region a random seed favours (first-touch order is a property of
 * POSITION, so a sample from one region would be the least informative one).
 *
 * A file with no occurrence nodes contributes nothing, and a run that samples no
 * spans at all REFUSES rather than reporting a vacuous green (CLAUDE.md rounds
 * 853/873/790).
 *
 * ```
 * scripts/caret-vs-file-capture.sh [<projectDir> [maxFiles [caretsPerFile]]]
 * ```
 */
private const val DEFAULT_CARETS = 12

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [maxFiles [caretsPerFile]]" }
    val limit = if (args.size > 1) args[1].toInt() else Int.MAX_VALUE
    val carets = if (args.size > 2) args[2].toInt() else DEFAULT_CARETS
    require(carets > 0) { "caretsPerFile must be positive" }
    val vfs = SystemVfs
    val compiler = ProjectCompiler(vfs)
    val project = vfs.resolveAbsolute(args[0])
    val configPath = if (vfs.isDirectory(project)) "$project/tsconfig.json" else project
    val options = TsConfigLoader(vfs).load(configPath).options

    // A warm-up build, discarded, for `CaptureEquivalenceMain`'s reason.
    compiler.build(project, noEmit = true)
    val programFiles = compiler.build(project, noEmit = true).programFiles
    println("program: files=${programFiles.size}  config=$configPath  carets/file=$carets")

    fun declarations(declarations: List<CapturedDeclaration>): String =
        declarations.map { "${it.fileName}:${it.start}+${it.length}:${it.kind}" }
            .sorted().joinToString(",")

    /** Every field of the TYPE answer at one span that reaches a user. */
    fun typeRow(result: ProjectCompiler.Result, file: String, span: TypeCaptureSpan): String? =
        result.capturedTypes.firstOrNull {
            it.fileName == file && it.start == span.start && it.end == span.end
        }?.let { "${it.kind}|${it.typeText}" }

    /** Every field of the DEFINITION answer at one span that reaches a user. */
    fun definitionRow(result: ProjectCompiler.Result, file: String, span: TypeCaptureSpan): String? =
        result.capturedDefinitions.firstOrNull {
            it.fileName == file && it.start == span.start && it.end == span.end
        }?.let {
            it.name + "|loc=" + declarations(it.locations) +
                "|rel=" + declarations(it.related) + "|sho=" + declarations(it.shorthand)
        }

    var divergences = 0
    var printed = 0
    var divergingFiles = 0
    var typeDivergences = 0
    var definitionDivergences = 0
    // The CLASSIFICATION is the finding. `fileRendersMoreAny` is the one direction
    // that is a LOST resolution rather than a differently-spelled one; `absentInFile`
    // is the direction (INC.13) has to refuse outright, because it is a hover that
    // renders nothing where a caret-scoped one rendered a type.
    var absentInFile = 0
    var absentInCaret = 0
    var fileRendersMoreAny = 0
    var caretRendersMoreAny = 0
    var otherShape = 0
    var spansCompared = 0L
    var typeAnswers = 0L
    var definitionAnswers = 0L
    val caretMs = ArrayList<Long>()
    val fileMs = ArrayList<Long>()
    val fileSpanCounts = ArrayList<Int>()
    val targets = programFiles.take(limit)

    for (file in targets) {
        val text = vfs.readText(file) ?: continue
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        val spans = index.occurrenceNodes()
            .map { TypeCaptureSpan(file, it.pos, it.end) }
            .distinct()
        if (spans.isEmpty()) continue
        fileSpanCounts.add(spans.size)
        // Evenly spaced, deterministic, and always including the first and last.
        val sample = if (spans.size <= carets) spans else
            (0 until carets).map { spans[(it.toLong() * (spans.size - 1) / (carets - 1)).toInt()] }
                .distinct()

        val t1 = System.nanoTime()
        val whole = compiler.build(
            project, noEmit = true, recheckOnly = setOf(file),
            typeCapture = TypeCaptureRequest(spans),
        )
        fileMs.add((System.nanoTime() - t1) / 1_000_000)

        var here = 0
        for (span in sample) {
            val t0 = System.nanoTime()
            val caret = compiler.build(
                project, noEmit = true, recheckOnly = setOf(file),
                typeCapture = TypeCaptureRequest(listOf(span)),
            )
            caretMs.add((System.nanoTime() - t0) / 1_000_000)
            spansCompared++

            val caretType = typeRow(caret, file, span)
            val wholeType = typeRow(whole, file, span)
            if (caretType != null) typeAnswers++
            if (caretType != wholeType) {
                here++
                typeDivergences++
                when {
                    wholeType == null -> absentInFile++
                    caretType == null -> absentInCaret++
                    wholeType.split("any").size > caretType.split("any").size -> fileRendersMoreAny++
                    caretType.split("any").size > wholeType.split("any").size -> caretRendersMoreAny++
                    else -> otherShape++
                }
                if (printed < 60) {
                    printed++
                    println(
                        "TYPE ${file.substringAfterLast('/')}@${span.start}..${span.end} " +
                            "caret=${caretType?.take(160) ?: "<absent>"}  " +
                            "file=${wholeType?.take(160) ?: "<absent>"}",
                    )
                }
            }

            val caretDefinition = definitionRow(caret, file, span)
            val wholeDefinition = definitionRow(whole, file, span)
            if (caretDefinition != null) definitionAnswers++
            if (caretDefinition != wholeDefinition) {
                here++
                definitionDivergences++
                when {
                    wholeDefinition == null -> absentInFile++
                    caretDefinition == null -> absentInCaret++
                    else -> otherShape++
                }
                if (printed < 60) {
                    printed++
                    println(
                        "DEF  ${file.substringAfterLast('/')}@${span.start}..${span.end} " +
                            "caret=${caretDefinition?.take(160) ?: "<absent>"}  " +
                            "file=${wholeDefinition?.take(160) ?: "<absent>"}",
                    )
                }
            }
        }
        if (here > 0) {
            divergingFiles++
            divergences += here
            println("DIVERGED ${file.substringAfterLast('/')}: $here of ${sample.size * 2} comparison(s)")
        }
    }

    println(
        "compared: spans=$spansCompared over ${fileSpanCounts.size} file(s); " +
            "caret-arm type answers=$typeAnswers definition answers=$definitionAnswers; " +
            "file-wide request sizes min=${fileSpanCounts.minOrNull()} " +
            "median=${fileSpanCounts.sorted()[fileSpanCounts.size / 2]} " +
            "max=${fileSpanCounts.maxOrNull()}",
    )
    require(spansCompared > 0) {
        "REFUSED: no span was compared, so every assertion above held vacuously."
    }
    require(typeAnswers > 0) {
        "REFUSED: the caret arm answered no type at all — the sample reaches nothing " +
            "the checker types, so a green verdict would test nothing."
    }

    fun report(label: String, each: List<Long>) {
        if (each.isEmpty()) return
        val sorted = each.sorted()
        println(
            "$label: min=${sorted.first()}ms  median=${sorted[sorted.size / 2]}ms  " +
                "mean=${sorted.sum() / sorted.size}ms  slowest=${sorted.last()}ms  n=${sorted.size}",
        )
    }
    report("one-caret capture    ", caretMs)
    report("whole-file capture   ", fileMs)

    println(
        if (divergences == 0) {
            "EQUIVALENT: every one of $spansCompared sampled span(s) answers identically " +
                "whether asked alone or as part of its file's whole span set"
        } else {
            "DIVERGED: $divergences comparison(s) in $divergingFiles of " +
                "${fileSpanCounts.size} file(s) — types=$typeDivergences " +
                "definitions=$definitionDivergences; absentInFile=$absentInFile " +
                "absentInCaret=$absentInCaret fileRendersMoreAny=$fileRendersMoreAny " +
                "caretRendersMoreAny=$caretRendersMoreAny other=$otherShape"
        },
    )
    if (divergences != 0) kotlin.system.exitProcess(1)
}
