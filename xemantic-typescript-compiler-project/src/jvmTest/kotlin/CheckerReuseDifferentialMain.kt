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
 * **EDITOR ORDER — the gap the first census left, closed by the `editor` arm.** The
 * `program` arm walks the files in program order and groups consecutive ones, which
 * models a set of queries but not an editor's SEQUENCE: a real host asks about
 * whatever buffer the user touched next, and it comes BACK to buffers it has already
 * asked about. So the `editor` arm builds a deterministic shuffled query SEQUENCE
 * WITH REVISITS (every third query re-asks a file asked two queries ago), chunks
 * THAT into groups, and compares POSITION BY POSITION rather than file by file — so
 * a file answered by one checker and then again by a later one is compared at both
 * of its positions. It additionally runs the COLD arm over the same sequence, which
 * gives two controls the file-keyed census could not have: `coldSelfDiverged` (does a
 * fresh checker answer a revisited file the same way twice?) and `sharedSelfDiverged`
 * (does a REUSED one?). A non-zero `coldSelfDiverged` would mean the reference arm is
 * itself order-dependent and the comparison is not attributable.
 *
 * ```
 * scripts/checker-reuse-differential.sh [<projectDir> [groupSize [maxFiles [dumpFile [order]]]]]
 * ```
 *
 * `order` is `program` (the default) or `editor`.
 *
 * Exit 1 on any divergence, exit 2 on a refusal.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [groupSize [maxFiles [dumpFile [order]]]]" }
    val groupSize = if (args.size > 1) args[1].toInt() else 8
    require(groupSize >= 2) { "REFUSED: a group of 1 IS the cold arm, so it compares nothing" }
    val limit = if (args.size > 2) args[2].toInt() else Int.MAX_VALUE
    val dumpFile = if (args.size > 3 && args[3].isNotEmpty()) args[3] else null
    val order = if (args.size > 4) args[4] else "program"
    require(order == "program" || order == "editor") {
        "REFUSED: order must be 'program' or 'editor', not '$order'"
    }
    val editorOrder = order == "editor"

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

    // --- the QUERY SEQUENCE -------------------------------------------------------
    // `program`: every file once, in program order — the original census, unchanged
    // byte for byte so its numbers stay comparable.
    // `editor`: a DETERMINISTIC shuffle with REVISITS. The seed is fixed so a rerun
    // compares the same thing; the revisit rule (every third query re-asks the file
    // asked two queries earlier) is what puts a file in TWO different checkers'
    // hands, which program order structurally cannot do.
    val sequence: List<String> =
        if (!editorOrder) files
        else {
            var seed = 20260823L
            fun nextInt(bound: Int): Int {
                // xorshift64*, so the shuffle is reproducible on every platform and
                // does not depend on any stdlib RNG's implementation.
                seed = seed xor (seed shl 13)
                seed = seed xor (seed ushr 7)
                seed = seed xor (seed shl 17)
                return ((seed ushr 1) % bound).toInt()
            }
            val pool = files.toMutableList()
            val shuffled = ArrayList<String>(pool.size)
            while (pool.isNotEmpty()) shuffled.add(pool.removeAt(nextInt(pool.size)))
            val seq = ArrayList<String>(shuffled.size * 4 / 3)
            for ((i, file) in shuffled.withIndex()) {
                seq.add(file)
                if (i >= 2 && i % 3 == 2) seq.add(shuffled[i - 2])
            }
            seq
        }
    val revisits = sequence.size - sequence.toSet().size
    println("order: $order  queries=${sequence.size}  distinct=${sequence.toSet().size}  revisits=$revisits")
    require(!editorOrder || revisits > 0) {
        "REFUSED: the editor arm produced no revisit, so its whole added claim would be vacuous"
    }

    // --- COLD arm: one build per QUERY -------------------------------------------
    val coldTypes = ArrayList<Map<Long, String>>(sequence.size)
    val coldDefinitions = ArrayList<Map<Long, String>>(sequence.size)
    val coldDiagnostics = ArrayList<List<String>>(sequence.size)
    var coldMs = 0L
    for (file in sequence) {
        val t0 = System.nanoTime()
        val cold = compiler.build(
            project, noEmit = true, recheckOnly = setOf(file),
            typeCapture = TypeCaptureRequest(spansOf.getValue(file)),
        )
        coldMs += (System.nanoTime() - t0) / 1_000_000
        coldTypes.add(typeRows(cold, file))
        coldDefinitions.add(definitionRows(cold, file))
        coldDiagnostics.add(diagnosticRows(cold, file))
    }

    // --- SHARED arm: one build per GROUP of `groupSize` queries -------------------
    val sharedTypes = arrayOfNulls<Map<Long, String>>(sequence.size)
    val sharedDefinitions = arrayOfNulls<Map<Long, String>>(sequence.size)
    val sharedDiagnostics = arrayOfNulls<List<String>>(sequence.size)
    var sharedMs = 0L
    var sharedBuilds = 0
    var position = 0
    for (group in sequence.chunked(groupSize)) {
        val distinct = group.toSet()
        val spans = distinct.flatMap { spansOf.getValue(it) }
        val t0 = System.nanoTime()
        val shared = compiler.build(
            project, noEmit = true, recheckOnly = distinct,
            typeCapture = TypeCaptureRequest(spans),
        )
        sharedMs += (System.nanoTime() - t0) / 1_000_000
        sharedBuilds++
        for (file in group) {
            sharedTypes[position] = typeRows(shared, file)
            sharedDefinitions[position] = definitionRows(shared, file)
            sharedDiagnostics[position] = diagnosticRows(shared, file)
            position++
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

    // Self-divergence controls, meaningful only in the `editor` arm: does an arm
    // answer a REVISITED file the same way at both of its positions? A non-zero
    // `coldSelfDiverged` means the REFERENCE arm is order-dependent and no comparison
    // against it is attributable — so it is checked before the arms are compared.
    var coldSelfDiverged = 0
    var sharedSelfDiverged = 0
    run {
        val firstColdAt = HashMap<String, Int>()
        for ((i, file) in sequence.withIndex()) {
            val first = firstColdAt.getOrPut(file) { i }
            if (first == i) continue
            if (coldTypes[first] != coldTypes[i] ||
                coldDefinitions[first] != coldDefinitions[i] ||
                coldDiagnostics[first] != coldDiagnostics[i]
            ) coldSelfDiverged++
            if (sharedTypes[first] != sharedTypes[i] ||
                sharedDefinitions[first] != sharedDefinitions[i] ||
                sharedDiagnostics[first] != sharedDiagnostics[i]
            ) sharedSelfDiverged++
        }
    }

    for ((positionIndex, file) in sequence.withIndex()) {
        val ct = coldTypes[positionIndex]
        val st = sharedTypes[positionIndex]!!
        val cd = coldDefinitions[positionIndex]
        val sd = sharedDefinitions[positionIndex]!!
        val cx = coldDiagnostics[positionIndex]
        val sx = sharedDiagnostics[positionIndex]!!
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
            println("DIVERGED [$positionIndex] ${file.substringAfterLast('/')}: $here row(s)")
        }
    }

    if (dumpFile != null) {
        vfs.writeText(dumpFile, dump.toString())
        println("dump: $dumpFile (${dump.length} chars)")
    }

    println(
        "compared: types=$typesCompared definitions=$definitionsCompared " +
            "diagnostics=$diagnosticsCompared over ${sequence.size} quer(ies) in " +
            "${files.size} file(s)",
    )
    println("self-consistency: coldSelfDiverged=$coldSelfDiverged sharedSelfDiverged=$sharedSelfDiverged")
    require(coldSelfDiverged == 0) {
        "REFUSED: the COLD arm answered a revisited file differently at two positions " +
            "($coldSelfDiverged), so it is not a reference and nothing below is attributable"
    }
    require(typesCompared > 0) {
        "REFUSED: the cold arm captured no types at all, so every comparison agreed vacuously"
    }
    require(diagnosticsCompared > 0) {
        "REFUSED: the cold arm reported no diagnostic at all — the diagnostics channel of " +
            "this census would be vacuous. Point it at a project that reports something."
    }
    println(
        "cost: cold=${coldMs}ms over ${sequence.size} build(s)  " +
            "shared=${sharedMs}ms over $sharedBuilds build(s)  " +
            "ratio=${"%.2f".format(coldMs.toDouble() / sharedMs.coerceAtLeast(1))}x",
    )
    println(
        if (divergences == 0) {
            "EQUIVALENT: a checker shared by $groupSize queries ($order order) answers " +
                "every one of them exactly as a fresh checker does"
        } else {
            "DIVERGED: $divergences row(s) in $divergingFiles of ${sequence.size} quer(ies) — " +
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
