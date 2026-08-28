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

import com.xemantic.typescript.compiler.AliasDisplayCensus
import com.xemantic.typescript.compiler.CapturedDeclaration
import com.xemantic.typescript.compiler.FltmDefer
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SymTypeOrderCensus
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags

/**
 * (INC.2) THE GATE, and it deliberately does NOT inherit (INC.1)'s.
 *
 * `scripts/partition-equivalence.sh` compared DIAGNOSTICS between a full build and
 * one narrowed by `recheckOnly`. This compares CAPTURED TYPES AND DEFINITIONS, which
 * is a different claim entirely: a diagnostic is a decision the checker either
 * reaches or does not, while a captured type is a claim about FIRST-TOUCH TYPE
 * IDENTITY — which instance a union interned as, which alias `aliasDisplayMap`
 * records for it, which declaration a symbol was merged into. Those are
 * order-dependent by construction in this compiler (CLAUDE.md's rounds 754/776/778
 * are all first-touch order changes with no output diff to find them by), and a
 * narrowed walk visits the program's nodes in a different order because it visits
 * fewer of them. So a hover could plausibly render a DIFFERENT type NAME for the
 * same span under a narrowed build while every diagnostic still agrees.
 *
 * That is the thing this measures, and it gates the wiring: a hover that renders a
 * different type under a narrowed build is a worse defect than a slow hover.
 *
 * For every file of a real project, and using the population `Project.fileSemantics`
 * uses — every `Identifier`, via `SourceIndex.identifiers()` — this builds TWICE
 * with the SAME `TypeCaptureRequest`, once whole-program and once with
 * `recheckOnly = {file}`, and compares every field that reaches a user: the type's
 * display string and kind, and each definition's `(fileName, start, length, kind)`.
 *
 * A file with no captures agrees VACUOUSLY, so the run REFUSES a verdict when the
 * project yields no captured types at all — a green sweep over an empty capture
 * population tests nothing (CLAUDE.md rounds 853/873).
 *
 * ```
 * scripts/capture-equivalence.sh [<projectDir> [maxFiles]]
 * ```
 *
 * Prints every divergence (capped) plus a summary; exit 1 on any divergence, exit 2
 * on a refusal.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [maxFiles]" }
    val limit = if (args.size > 1) args[1].toInt() else Int.MAX_VALUE
    // (INC.11) arms for `init:buildFileLocalTypeMaps`. Unset means the shipped
    // behaviour, so an unset sweep is the baseline and needs no separate binary.
    FltmDefer.eager = FltmDefer.fromName(System.getenv("XTSC_FLTM_EAGER"))
    // (INC.23) the SECOND, independent axis — which FILES the eager pass covers.
    FltmDefer.scope = FltmDefer.scopeFromName(System.getenv("XTSC_FLTM_SCOPE"))
    if (FltmDefer.eager != FltmDefer.Phases.ALL || FltmDefer.scope != FltmDefer.Scope.PROGRAM) {
        println("FLTM eager phases: ${FltmDefer.eager} scope: ${FltmDefer.scope}")
    }
    AliasDisplayCensus.on = System.getenv("XTSC_ALIAS_CENSUS") == "1"
    // (INC.28) the `declaredTypes` writer ledger; `XTSC_DECLARED=<name,name>` names
    // the symbols whose first declared-type write is to be attributed, per arm.
    System.getenv("XTSC_DECLARED")?.let {
        SymTypeOrderCensus.on = true
        SymTypeOrderCensus.declaredFilter = it
    }
    // (INC.24) How many divergent rows to PRINT, and how wide. The CLASSIFICATION of a
    // divergence is the finding, not its count, and 40 rows truncated at 140 characters
    // is not a classification of a few thousand — so both caps are liftable for an
    // investigation WITHOUT changing anything the gate measures.
    val armDump: java.io.Writer? = System.getenv("XTSC_CAPEQ_DUMP")
        ?.let { java.io.BufferedWriter(java.io.FileWriter(it)) }
    val printCap = System.getenv("XTSC_CAPEQ_PRINT")?.toIntOrNull() ?: 40
    val rowCap = System.getenv("XTSC_CAPEQ_ROWCHARS")?.toIntOrNull() ?: 140
    val vfs = SystemVfs
    val compiler = ProjectCompiler(vfs)
    val project = vfs.resolveAbsolute(args[0])
    val configPath = if (vfs.isDirectory(project)) "$project/tsconfig.json" else project
    val options = TsConfigLoader(vfs).load(configPath).options

    // A warm-up build, discarded, for `PartitionEquivalenceMain`'s reason: without it
    // the first arm below is the slowest draw in this repo by a wide margin.
    compiler.build(project, noEmit = true)
    val programFiles = compiler.build(project, noEmit = true).programFiles
    println("program: files=${programFiles.size}  config=$configPath")

    /** One captured type as the string a user would see, keyed by its span. */
    fun typeRows(result: ProjectCompiler.Result, file: String): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (captured in result.capturedTypes) {
            if (captured.fileName != file) continue
            rows[key(captured.start, captured.end)] =
                "@${captured.start}..${captured.end} ${captured.kind}|${captured.typeText}"
        }
        return rows
    }

    fun declarations(declarations: List<CapturedDeclaration>): String =
        declarations.map { "${it.fileName}:${it.start}+${it.length}:${it.kind}" }
            .sorted().joinToString(",")

    /** One captured definition as every field that reaches a user. */
    fun definitionRows(result: ProjectCompiler.Result, file: String): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (captured in result.capturedDefinitions) {
            if (captured.fileName != file) continue
            rows[key(captured.start, captured.end)] = "@${captured.start}..${captured.end} " +
                captured.name +
                "|loc=" + declarations(captured.locations) +
                "|rel=" + declarations(captured.related) +
                "|sho=" + declarations(captured.shorthand)
        }
        return rows
    }

    /**
     * (INC.24) A DETERMINISTIC FOLD OVER ONE ARM'S WHOLE ANSWER SET, so two arms of
     * this runner can be compared on what they AGREE about and not only on where the
     * full and the narrowed build of ONE arm differ.
     *
     * The divergence report below is a full-vs-narrow comparison WITHIN one arm. It
     * cannot say whether the FULL build still answers what it answered before a
     * change — and "an unpartitioned compile is unchanged by construction" is exactly
     * the kind of claim that is true of the code and false of the binary (CLAUDE.md
     * rounds 853/873). Running this main twice and comparing the FULL digests is that
     * verification; the NARROW digest is the half a partition-shaped change is allowed
     * to move. Ordering is by span key so the fold is a property of the ANSWERS and
     * not of `HashMap` iteration.
     */
    fun digest(rows: Map<Long, String>): Long {
        var h = 1125899906842597L
        for (k in rows.keys.sorted()) {
            h = h * 1000003L + k
            for (c in rows.getValue(k)) h = h * 1000003L + c.code
        }
        return h
    }

    var fullDigestAll = 0L
    var narrowDigestAll = 0L

    var divergences = 0
    var printed = 0
    var divergingFiles = 0
    // The CLASSIFICATION of a divergence is the finding, not its count: "the narrowed
    // build renders a DIFFERENT NAME for the same type" and "the narrowed build
    // renders `any` where the full one renders the type" are different defects with
    // different blast radii, and only the second is a lost resolution.
    var typeDivergences = 0
    var definitionDivergences = 0
    var widenedToAny = 0
    var absentInNarrow = 0
    var absentInFull = 0
    var otherShape = 0
    var capturedTypes = 0L
    var capturedDefinitions = 0L
    var spansAsked = 0L
    val fullMs = ArrayList<Long>()
    val narrowMs = ArrayList<Long>()
    val targets = programFiles.take(limit)

    for (file in targets) {
        val text = vfs.readText(file) ?: continue
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        // `Project.semanticsOf`'s population and its de-duplication: a span is
        // recorded once, by the deepest node carrying it, so asking twice is
        // harmless but comparing twice is noise.
        val spans = index.identifiers()
            .map { TypeCaptureSpan(file, it.pos, it.end) }
            .distinct()
        if (spans.isEmpty()) continue
        spansAsked += spans.size
        val request = TypeCaptureRequest(spans)

        if (SymTypeOrderCensus.declaredFilter.isNotEmpty()) SymTypeOrderCensus.reset()
        val t0 = System.nanoTime()
        val full = compiler.build(project, noEmit = true, typeCapture = request)
        fullMs.add((System.nanoTime() - t0) / 1_000_000)
        if (SymTypeOrderCensus.declaredFilter.isNotEmpty()) {
            print(SymTypeOrderCensus.declaredReport("full ${file.substringAfterLast('/')}"))
            SymTypeOrderCensus.reset()
        }
        val t1 = System.nanoTime()
        val narrow = compiler.build(
            project,
            noEmit = true,
            recheckOnly = setOf(file),
            typeCapture = request,
        )
        narrowMs.add((System.nanoTime() - t1) / 1_000_000)
        if (SymTypeOrderCensus.declaredFilter.isNotEmpty()) {
            print(SymTypeOrderCensus.declaredReport("narrow ${file.substringAfterLast('/')}"))
        }

        val fullTypes = typeRows(full, file)
        val narrowTypes = typeRows(narrow, file)
        val fullDefinitions = definitionRows(full, file)
        val narrowDefinitions = definitionRows(narrow, file)
        capturedTypes += fullTypes.size
        capturedDefinitions += fullDefinitions.size

        // (CHK.64) ARM DUMP — `XTSC_CAPEQ_DUMP=<path>` appends the FULL arm's own rows so
        // two BINARIES can be diffed per span. The ARM DIGEST answers "did anything
        // move"; only this answers "which spans, and how", which is what classifying a
        // digest move per element needs. Off by default and read once per process.
        armDump?.let { out ->
            for ((span, t) in fullTypes) out.append("T\t").append(file).append('\t')
                .append(span.toString()).append('\t').append(t).append('\n')
            for ((span, d) in fullDefinitions) out.append("D\t").append(file).append('\t')
                .append(span.toString()).append('\t').append(d).append('\n')
            out.flush()
        }

        // (INC.24) Folded per file and in `programFiles` order, so the whole-program
        // digest is a function of the program rather than of the sweep's scheduling.
        fullDigestAll = fullDigestAll * 1000003L + (digest(fullTypes) * 31 + digest(fullDefinitions))
        narrowDigestAll =
            narrowDigestAll * 1000003L + (digest(narrowTypes) * 31 + digest(narrowDefinitions))

        var here = 0
        for (span in (fullTypes.keys + narrowTypes.keys)) {
            val a = fullTypes[span]
            val b = narrowTypes[span]
            if (a == b) continue
            here++
            typeDivergences++
            when {
                b == null -> absentInNarrow++
                a == null -> absentInFull++
                b.split("any").size > a.split("any").size -> widenedToAny++
                else -> otherShape++
            }
            if (printed < printCap) {
                printed++
                println(
                    "TYPE ${file.substringAfterLast('/')} " +
                        "full=${a?.take(rowCap) ?: "<absent>"}  narrow=${b?.take(rowCap) ?: "<absent>"}",
                )
            }
        }
        for (span in (fullDefinitions.keys + narrowDefinitions.keys)) {
            val a = fullDefinitions[span]
            val b = narrowDefinitions[span]
            if (a == b) continue
            here++
            definitionDivergences++
            when {
                b == null -> absentInNarrow++
                a == null -> absentInFull++
                else -> otherShape++
            }
            if (printed < printCap) {
                printed++
                println(
                    "DEF  ${file.substringAfterLast('/')} " +
                        "full=${a?.take(rowCap) ?: "<absent>"}  narrow=${b?.take(rowCap) ?: "<absent>"}",
                )
            }
        }
        if (here > 0) {
            divergingFiles++
            divergences += here
            println("DIVERGED ${file.substringAfterLast('/')}: $here span(s)")
        }
    }

    println("ARM DIGEST full=$fullDigestAll narrow=$narrowDigestAll")
    if (AliasDisplayCensus.on) println(AliasDisplayCensus.report())
    println(
        "captures: spansAsked=$spansAsked  types=$capturedTypes  definitions=$capturedDefinitions " +
            "over ${fullMs.size} file(s)",
    )
    require(capturedTypes > 0) {
        "REFUSED: the full builds captured no types at all, so every comparison above " +
            "agreed vacuously. Point this at a project whose files carry identifiers."
    }

    fun report(label: String, each: List<Long>) {
        val sorted = each.sorted()
        println(
            "$label: min=${sorted.first()}ms  median=${sorted[sorted.size / 2]}ms  " +
                "mean=${sorted.sum() / sorted.size}ms  slowest=${sorted.last()}ms",
        )
    }
    report("full-build capture   ", fullMs)
    report("narrowed capture     ", narrowMs)

    // THE TIMING ARMS RUN LAST, AND ROTATED. Position in the process is worth more
    // than the effect here: a "floor" arm measured at slot 3 read 1,632 ms where the
    // median partition it is a strict subset of read 1,107. The per-file numbers
    // above are the honest RATIO — each file's two arms are adjacent — but their
    // ABSOLUTE level is contaminated by the ramp across the sweep, so the warm figure
    // to quote is this one, taken after the JVM has performed 2N builds.
    val warmFile = targets.firstOrNull { vfs.readText(it) != null } ?: return
    val warmText = vfs.readText(warmFile)!!
    val warmSpans = SourceIndex.of(warmText, warmFile, computeParserFlags(warmFile, warmText, options))
        .identifiers().map { TypeCaptureSpan(warmFile, it.pos, it.end) }.distinct()
    val warmRequest = TypeCaptureRequest(warmSpans)
    fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }
    val warmFull = ArrayList<Long>()
    val warmNarrow = ArrayList<Long>()
    repeat(2) {
        warmFull.add(timed { compiler.build(project, noEmit = true, typeCapture = warmRequest) })
        warmNarrow.add(
            timed {
                compiler.build(
                    project, noEmit = true, recheckOnly = setOf(warmFile), typeCapture = warmRequest,
                )
            },
        )
    }
    repeat(2) {
        warmNarrow.add(
            timed {
                compiler.build(
                    project, noEmit = true, recheckOnly = setOf(warmFile), typeCapture = warmRequest,
                )
            },
        )
        warmFull.add(timed { compiler.build(project, noEmit = true, typeCapture = warmRequest) })
    }
    val f = warmFull.sorted()[warmFull.size / 2]
    val n = warmNarrow.sorted()[warmNarrow.size / 2]
    println(
        "warm rotated on ${warmFile.substringAfterLast('/')} (${warmSpans.size} spans): " +
            "full=${f}ms $warmFull  narrowed=${n}ms $warmNarrow  " +
            "ratio=${"%.2f".format(f.toDouble() / n.coerceAtLeast(1))}x",
    )

    println(
        if (divergences == 0) {
            "EQUIVALENT: all $capturedTypes captured type(s) and $capturedDefinitions " +
                "definition(s) across ${fullMs.size} file(s) agree"
        } else {
            "DIVERGED: $divergences span(s) in $divergingFiles of ${fullMs.size} file(s) — " +
                "types=$typeDivergences definitions=$definitionDivergences; " +
                "narrowRendersMoreAny=$widenedToAny absentInNarrow=$absentInNarrow " +
                "absentInFull=$absentInFull other=$otherShape"
        },
    )
    if (divergences != 0) kotlin.system.exitProcess(1)
}

/**
 * `(start, end)` as one key, finalized by an odd multiply for round 889's reason —
 * a plain pack hashes to `start xor end`, and a node's end is its start plus a token
 * or two, so a whole file's spans would pile onto a few dozen buckets. The KEY is
 * therefore not unpackable and the span is carried in the row TEXT instead.
 */
private fun key(start: Int, end: Int): Long =
    ((start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)) * -0x61c8864680b583ebL
