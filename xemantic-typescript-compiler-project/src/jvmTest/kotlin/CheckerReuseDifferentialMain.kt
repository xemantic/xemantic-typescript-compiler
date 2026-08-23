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
 * (INC.14) THE ORDER-DEPENDENCE DIFFERENTIAL — the census that decides whether a
 * `Checker` may be REUSED across queries, run BEFORE any checker surgery.
 *
 * **The model, and why it needs no re-entrant entry point.** (INC.14) proposes that
 * the ~190 program-wide `init` passes run ONCE and that a surviving `Checker` then
 * answers query after query. The thing that makes that unsound is not the refactor
 * but the CACHES such a checker would carry: `symbolTypes` persists the FIRST
 * resolution (round 778), `aliasDisplayMap` records the alias under which a type was
 * FIRST interned, and member tables materialize on first ask — so a reused checker
 * makes WHICH QUERY RAN FIRST observable, which is exactly the mechanism (INC.2),
 * (INC.5) and (INC.6) spent three rounds closing and (INC.10) refused 66 ms over.
 *
 * A checker that has already answered `k − 1` queries and is asked a `k`-th **is** a
 * checker whose partition is those `k` files, because `recheckOnly` is a SET and the
 * spine walks it in program order either way. So the two arms are:
 *
 *  - **COLD** — one build per file, `recheckOnly = {file}`: today's language service,
 *    a fresh `Checker` per query.
 *  - **SHARED** — one build per GROUP of `k` files, `recheckOnly = group`, capturing
 *    every file of the group in the one walk: `k` queries answered by ONE checker.
 *
 * They must agree file for file, span for span, row for row. Any divergence is a
 * span whose answer a reused checker would change, and the census of those is this
 * round's deliverable — the same shape `scripts/capture-equivalence.sh` uses, and
 * like it needing NO recorded baseline, because both arms claim to answer the same
 * question.
 *
 * **What it prices at the same time.** The COLD arm performs `n` builds where the
 * SHARED arm performs `n / k`, and each shared build pays the floor once for `k`
 * queries. Its wall ratio is therefore the (INC.14) PRIZE measured directly, with no
 * model in between — an upper bound, since a real reused checker also has to reset
 * whatever a per-file pass wrote.
 *
 * **The one thing it does NOT model** is query ORDER: a reused checker answers in
 * the order the editor asks, this arm walks the group in program order. It is
 * therefore a census of one specific order, and the magnitude is the finding rather
 * than the identity of any single row.
 *
 * ```
 * scripts/checker-reuse-differential.sh [<projectDir> [groupSize [maxFiles [dumpFile]]]]
 * ```
 *
 * Exit 1 on any divergence, exit 2 on a refusal.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [groupSize [maxFiles [dumpFile]]]" }
    val groupSize = if (args.size > 1) args[1].toInt() else 8
    require(groupSize >= 2) { "REFUSED: a group of 1 IS the cold arm, so it compares nothing" }
    val limit = if (args.size > 2) args[2].toInt() else Int.MAX_VALUE
    val dumpFile = if (args.size > 3) args[3] else null

    val vfs = SystemVfs
    val compiler = ProjectCompiler(vfs)
    val project = vfs.resolveAbsolute(args[0])
    val configPath = if (vfs.isDirectory(project)) "$project/tsconfig.json" else project
    val options = TsConfigLoader(vfs).load(configPath).options

    // The first instrumented build in a process is the slowest draw in this repo;
    // discard one before either arm (`PartitionEquivalenceMain`'s reason).
    compiler.build(project, noEmit = true)
    val programFiles = compiler.build(project, noEmit = true).programFiles
    println("program: files=${programFiles.size}  config=$configPath  groupSize=$groupSize")

    val targets = programFiles.take(limit)
    // Spans are computed ONCE per file and handed to BOTH arms, so the two arms
    // cannot differ in what they were asked.
    val spansOf = LinkedHashMap<String, List<TypeCaptureSpan>>()
    for (file in targets) {
        val text = vfs.readText(file) ?: continue
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        val spans = index.identifiers().map { TypeCaptureSpan(file, it.pos, it.end) }.distinct()
        if (spans.isNotEmpty()) spansOf[file] = spans
    }
    require(spansOf.isNotEmpty()) {
        "REFUSED: no file yielded an identifier, so every comparison would agree vacuously"
    }

    fun declarations(declarations: List<CapturedDeclaration>): String =
        declarations.map { "${it.fileName}:${it.start}+${it.length}:${it.kind}" }
            .sorted().joinToString(",")

    fun typeRows(result: ProjectCompiler.Result, file: String): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (captured in result.capturedTypes) {
            if (captured.fileName != file) continue
            rows[spanKey(captured.start, captured.end)] =
                "@${captured.start}..${captured.end} ${captured.kind}|${captured.typeText}"
        }
        return rows
    }

    fun definitionRows(result: ProjectCompiler.Result, file: String): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (captured in result.capturedDefinitions) {
            if (captured.fileName != file) continue
            rows[spanKey(captured.start, captured.end)] = "@${captured.start}..${captured.end} " +
                captured.name +
                "|loc=" + declarations(captured.locations) +
                "|rel=" + declarations(captured.related) +
                "|sho=" + declarations(captured.shorthand)
        }
        return rows
    }

    /** Every diagnostic the arm reports FOR this file, as the row a user would read. */
    fun diagnosticRows(result: ProjectCompiler.Result, file: String): List<String> =
        result.diagnostics
            .filter { it.fileName == file }
            .map { "TS${it.code}@${it.start}+${it.length} ${it.message}" }
            .sorted()

    val files = spansOf.keys.toList()

    // --- COLD arm: one build per file -------------------------------------------
    val coldTypes = HashMap<String, Map<Long, String>>()
    val coldDefinitions = HashMap<String, Map<Long, String>>()
    val coldDiagnostics = HashMap<String, List<String>>()
    var coldMs = 0L
    for (file in files) {
        val t0 = System.nanoTime()
        val cold = compiler.build(
            project, noEmit = true, recheckOnly = setOf(file),
            typeCapture = TypeCaptureRequest(spansOf.getValue(file)),
        )
        coldMs += (System.nanoTime() - t0) / 1_000_000
        coldTypes[file] = typeRows(cold, file)
        coldDefinitions[file] = definitionRows(cold, file)
        coldDiagnostics[file] = diagnosticRows(cold, file)
    }

    // --- SHARED arm: one build per group of `groupSize` files --------------------
    val sharedTypes = HashMap<String, Map<Long, String>>()
    val sharedDefinitions = HashMap<String, Map<Long, String>>()
    val sharedDiagnostics = HashMap<String, List<String>>()
    var sharedMs = 0L
    var sharedBuilds = 0
    for (group in files.chunked(groupSize)) {
        val spans = group.flatMap { spansOf.getValue(it) }
        val t0 = System.nanoTime()
        val shared = compiler.build(
            project, noEmit = true, recheckOnly = group.toSet(),
            typeCapture = TypeCaptureRequest(spans),
        )
        sharedMs += (System.nanoTime() - t0) / 1_000_000
        sharedBuilds++
        for (file in group) {
            sharedTypes[file] = typeRows(shared, file)
            sharedDefinitions[file] = definitionRows(shared, file)
            sharedDiagnostics[file] = diagnosticRows(shared, file)
        }
    }

    // --- the census -------------------------------------------------------------
    val dump = StringBuilder()
    var divergences = 0
    var divergingFiles = 0
    var printed = 0
    var typeDivergences = 0
    var definitionDivergences = 0
    var diagnosticDivergences = 0
    var sharedRendersMoreAny = 0
    var absentInShared = 0
    var absentInCold = 0
    var otherShape = 0
    var typesCompared = 0L
    var definitionsCompared = 0L
    var diagnosticsCompared = 0L

    fun note(channel: String, file: String, a: String?, b: String?) {
        dump.append(channel).append('\t').append(file).append('\t')
            .append("cold=").append(a ?: "<absent>").append('\t')
            .append("shared=").append(b ?: "<absent>").append('\n')
        if (printed < 40) {
            printed++
            println(
                "$channel ${file.substringAfterLast('/')} " +
                    "cold=${a?.take(140) ?: "<absent>"}  shared=${b?.take(140) ?: "<absent>"}",
            )
        }
    }

    for (file in files) {
        val ct = coldTypes.getValue(file)
        val st = sharedTypes.getValue(file)
        val cd = coldDefinitions.getValue(file)
        val sd = sharedDefinitions.getValue(file)
        val cx = coldDiagnostics.getValue(file)
        val sx = sharedDiagnostics.getValue(file)
        typesCompared += ct.size
        definitionsCompared += cd.size
        diagnosticsCompared += cx.size
        var here = 0
        for (span in (ct.keys + st.keys)) {
            val a = ct[span]
            val b = st[span]
            if (a == b) continue
            here++
            typeDivergences++
            when {
                b == null -> absentInShared++
                a == null -> absentInCold++
                b.split("any").size > a.split("any").size -> sharedRendersMoreAny++
                else -> otherShape++
            }
            note("TYPE", file, a, b)
        }
        for (span in (cd.keys + sd.keys)) {
            val a = cd[span]
            val b = sd[span]
            if (a == b) continue
            here++
            definitionDivergences++
            when {
                b == null -> absentInShared++
                a == null -> absentInCold++
                else -> otherShape++
            }
            note("DEF ", file, a, b)
        }
        // Diagnostics are a LIST, not a span map: a row present in one arm and not
        // the other is the defect that matters, so both directions are reported.
        for (row in (cx.toSet() + sx.toSet())) {
            val inCold = row in cx
            val inShared = row in sx
            if (inCold && inShared) continue
            here++
            diagnosticDivergences++
            if (inCold) absentInShared++ else absentInCold++
            note("DIAG", file, if (inCold) row else null, if (inShared) row else null)
        }
        if (here > 0) {
            divergingFiles++
            divergences += here
            println("DIVERGED ${file.substringAfterLast('/')}: $here row(s)")
        }
    }

    if (dumpFile != null) {
        vfs.writeText(dumpFile, dump.toString())
        println("dump: $dumpFile (${dump.length} chars)")
    }

    println(
        "compared: types=$typesCompared definitions=$definitionsCompared " +
            "diagnostics=$diagnosticsCompared over ${files.size} file(s)",
    )
    require(typesCompared > 0) {
        "REFUSED: the cold arm captured no types at all, so every comparison agreed vacuously"
    }
    require(diagnosticsCompared > 0) {
        "REFUSED: the cold arm reported no diagnostic at all — the diagnostics channel of " +
            "this census would be vacuous. Point it at a project that reports something."
    }
    println(
        "cost: cold=${coldMs}ms over ${files.size} build(s)  " +
            "shared=${sharedMs}ms over $sharedBuilds build(s)  " +
            "ratio=${"%.2f".format(coldMs.toDouble() / sharedMs.coerceAtLeast(1))}x",
    )
    println(
        if (divergences == 0) {
            "EQUIVALENT: a checker shared by $groupSize queries answers every one of them " +
                "exactly as a fresh checker does"
        } else {
            "DIVERGED: $divergences row(s) in $divergingFiles of ${files.size} file(s) — " +
                "types=$typeDivergences definitions=$definitionDivergences " +
                "diagnostics=$diagnosticDivergences; " +
                "sharedRendersMoreAny=$sharedRendersMoreAny absentInShared=$absentInShared " +
                "absentInCold=$absentInCold other=$otherShape"
        },
    )
    if (divergences != 0) kotlin.system.exitProcess(1)
}

/**
 * `(start, end)` as one key, finalized by an odd multiply for round 889's reason — a
 * plain pack hashes to `start xor end`, and a node's end is its start plus a token or
 * two, so a whole file's spans would pile onto a few dozen buckets. The key is
 * therefore not unpackable and the span is carried in the row TEXT instead.
 */
private fun spanKey(start: Int, end: Int): Long =
    ((start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)) * -0x61c8864680b583ebL
