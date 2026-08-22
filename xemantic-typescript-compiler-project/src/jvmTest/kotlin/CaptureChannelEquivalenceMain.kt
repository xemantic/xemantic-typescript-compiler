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

import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SignatureCaptureSpan
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags

/**
 * (INC.2b) `CaptureEquivalenceMain` FOR THE OTHER THREE CAPTURE CHANNELS.
 *
 * That runner sweeps the two channels `quickInfoAt` / `definitionsAt` /
 * `semanticsAt` read — captured TYPES and DEFINITIONS at every identifier. It says
 * nothing about the three a completion and a signature help read, and those are not
 * the same claim: `capturedMembers` and `capturedSignatures` render TYPE TEXT, so
 * they carry exactly the first-touch identity risk that sweep exists to measure,
 * while `capturedScopes` renders names and kinds and carries a different one (which
 * bindings the scope chain offers). Wiring those queries to a partition on the
 * strength of a green types-and-definitions sweep would be reading a control as a
 * gate (CLAUDE.md, rounds 853/873).
 *
 * It is kept SEPARATE from `CaptureEquivalenceMain` rather than folded into it for
 * one reason: adding spans to a request changes what the checker types and therefore
 * the first-touch order, so a merged runner's type-and-definition numbers would no
 * longer be comparable to the ones every (INC.2)/(INC.5)/(INC.6) round quoted.
 *
 * ## The population is SAMPLED, and says so
 *
 * A caret population here is not a node set: a member completion happens after a
 * `.`, a signature help inside a `(`, a free-name completion at an identifier's
 * start. Every such caret in tsc's own sources is hundreds of thousands of member
 * enumerations, so each channel is capped per file at [PER_CHANNEL] carets taken at
 * an even STRIDE across the file — a sample that covers the whole file rather than
 * its first screenful, and a stated limitation rather than a silent one. The count
 * actually compared is printed.
 *
 * ## Diagnosing a divergence
 *
 * A fourth argument is a file-name SUFFIX: only that file is swept, and rows are
 * printed in full rather than truncated, with the first differing ELEMENT of the two
 * lists called out. A summary row cut at 160 characters names the span and hides the
 * difference, which is the one thing a divergence report must not do.
 *
 * ```
 * scripts/capture-channel-equivalence.sh [<projectDir> [maxFiles [perChannel [fileSuffix]]]]
 * ```
 *
 * Exit 1 on any divergence, exit 2 on a refusal.
 */
private const val PER_CHANNEL = 150

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [maxFiles [perChannel [fileSuffix]]]" }
    val limit = if (args.size > 1) args[1].toInt() else Int.MAX_VALUE
    val perChannel = if (args.size > 2) args[2].toInt() else PER_CHANNEL
    val only = if (args.size > 3 && args[3].isNotEmpty()) args[3] else null
    val vfs = SystemVfs
    val compiler = ProjectCompiler(vfs)
    val project = vfs.resolveAbsolute(args[0])
    val configPath = if (vfs.isDirectory(project)) "$project/tsconfig.json" else project
    val options = TsConfigLoader(vfs).load(configPath).options

    // A discarded warm-up build, for `CaptureEquivalenceMain`'s reason.
    compiler.build(project, noEmit = true)
    val programFiles = compiler.build(project, noEmit = true).programFiles
    println("program: files=${programFiles.size}  perChannel=$perChannel  config=$configPath")

    /** [candidates] thinned to at most [perChannel], at an even stride. */
    fun <T> sample(candidates: List<T>): List<T> {
        if (candidates.size <= perChannel) return candidates
        val stride = candidates.size.toDouble() / perChannel
        return (0 until perChannel).map { candidates[(it * stride).toInt()] }
    }

    fun memberRows(result: ProjectCompiler.Result, file: String): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (captured in result.capturedMembers) {
            if (captured.fileName != file) continue
            rows[spanKey(captured.start, captured.end)] =
                "@${captured.start}..${captured.end} " +
                    captured.members.joinToString(",") {
                        "${it.name}:${it.kind}:${it.typeText}:${it.optional}:${it.readonly}:" +
                            it.accessibility
                    }
        }
        return rows
    }

    fun scopeRows(result: ProjectCompiler.Result, file: String): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (captured in result.capturedScopes) {
            if (captured.fileName != file) continue
            rows[spanKey(captured.start, captured.end)] =
                "@${captured.start}..${captured.end} " +
                    captured.names.joinToString(",") { "${it.name}:${it.kind}" }
        }
        return rows
    }

    fun signatureRows(result: ProjectCompiler.Result, file: String): Map<Long, String> {
        val rows = HashMap<Long, String>()
        for (captured in result.capturedSignatures) {
            if (captured.fileName != file) continue
            rows[spanKey(captured.start, captured.end)] =
                "@${captured.start}..${captured.end} active=${captured.activeSignature} " +
                    captured.signatures.joinToString(";") { signature ->
                        signature.label + "->" + signature.returnTypeText + "(" +
                            signature.parameters.joinToString(",") {
                                "${it.name}:${it.typeText}:${it.optional}:${it.isRest}"
                            } + ")@" + signature.activeParameter
                    }
        }
        return rows
    }

    var divergences = 0
    var printed = 0
    var divergingFiles = 0
    var memberDivergences = 0
    var scopeDivergences = 0
    var signatureDivergences = 0
    var absentInNarrow = 0
    var absentInFull = 0
    var widenedToAny = 0
    var otherShape = 0
    // The MECHANISM census. A row here is a LIST, so one display mechanism reaches as
    // many rows as the program has carets on that receiver — 285 member rows turned out
    // to be a handful of causes. Counting the rows answers "how many", which is not the
    // question; counting the distinct first DIFFERING ELEMENT answers "which".
    val mechanisms = LinkedHashMap<String, Int>()
    var memberCaptures = 0L
    var scopeCaptures = 0L
    var signatureCaptures = 0L
    var filesCompared = 0

    for (file in programFiles.filter { only == null || it.endsWith(only) }.take(limit)) {
        val text = vfs.readText(file) ?: continue
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))

        // The three caret populations, each at the position a host would really ask
        // from: after a `.`, at an identifier's first character, just inside a `(`.
        val memberSpans = LinkedHashMap<Long, TypeCaptureSpan>()
        for (at in sample(text.indices.filter { text[it] == '.' })) {
            val anchor = index.completionAnchorAt(at + 1)
            val receiver = anchor.receiver ?: continue
            if (anchor.kind != CompletionKind.MEMBER) continue
            memberSpans[spanKey(receiver.pos, receiver.end)] =
                TypeCaptureSpan(file, receiver.pos, receiver.end)
        }
        val scopeSpans = LinkedHashMap<Long, TypeCaptureSpan>()
        for (id in sample(index.identifiers())) {
            val anchor = index.completionAnchorAt(id.pos)
            if (anchor.kind != CompletionKind.FREE_NAME) continue
            val scopeAnchor = anchor.scopeAnchor ?: continue
            scopeSpans[spanKey(scopeAnchor.pos, scopeAnchor.end)] =
                TypeCaptureSpan(file, scopeAnchor.pos, scopeAnchor.end)
        }
        val signatureSpans = LinkedHashMap<Long, SignatureCaptureSpan>()
        for (at in sample(text.indices.filter { text[it] == '(' })) {
            val anchor = index.signatureAnchorAt(at + 1) ?: continue
            signatureSpans[spanKey(anchor.call.pos, anchor.call.end)] =
                SignatureCaptureSpan(file, anchor.call.pos, anchor.call.end, anchor.activeArgument)
        }
        if (memberSpans.isEmpty() && scopeSpans.isEmpty() && signatureSpans.isEmpty()) continue

        val request = TypeCaptureRequest(
            spans = emptyList(),
            memberSpans = memberSpans.values.toList(),
            scopeSpans = scopeSpans.values.toList(),
            signatureSpans = signatureSpans.values.toList(),
        )
        val full = compiler.build(project, noEmit = true, typeCapture = request)
        val narrow = compiler.build(
            project, noEmit = true, recheckOnly = setOf(file), typeCapture = request,
        )
        filesCompared++

        var here = 0
        fun compare(label: String, a: Map<Long, String>, b: Map<Long, String>) {
            for (span in (a.keys + b.keys)) {
                val left = a[span]
                val right = b[span]
                if (left == right) continue
                here++
                when (label) {
                    "MEMBERS" -> memberDivergences++
                    "SCOPE" -> scopeDivergences++
                    else -> signatureDivergences++
                }
                // The CLASSIFICATION is the finding, not the count — `CaptureEquivalenceMain`'s
                // rule. A row here is a LIST (a type's members, a scope's names, an
                // overload set), so one display mechanism reaches as many rows as the
                // program has carets on that receiver; only the class says which it is.
                when {
                    right == null -> absentInNarrow++
                    left == null -> absentInFull++
                    right.split("any").size > left.split("any").size -> widenedToAny++
                    else -> otherShape++
                }
                if (left != null && right != null) {
                    val mechanism = "$label ${firstDifference(left, right)}"
                    mechanisms[mechanism] = (mechanisms[mechanism] ?: 0) + 1
                }
                if (printed < 40) {
                    printed++
                    val width = if (only == null) 160 else Int.MAX_VALUE
                    println(
                        "$label ${file.substringAfterLast('/')} " +
                            "full=${left?.take(width) ?: "<absent>"}  " +
                            "narrow=${right?.take(width) ?: "<absent>"}",
                    )
                }
            }
        }
        val fullMembers = memberRows(full, file)
        val fullScopes = scopeRows(full, file)
        val fullSignatures = signatureRows(full, file)
        memberCaptures += fullMembers.size
        scopeCaptures += fullScopes.size
        signatureCaptures += fullSignatures.size
        compare("MEMBERS", fullMembers, memberRows(narrow, file))
        compare("SCOPE", fullScopes, scopeRows(narrow, file))
        compare("SIGS", fullSignatures, signatureRows(narrow, file))
        if (here > 0) {
            divergingFiles++
            divergences += here
            println("DIVERGED ${file.substringAfterLast('/')}: $here span(s)")
        }
    }

    println(
        "captures: members=$memberCaptures scopes=$scopeCaptures signatures=$signatureCaptures " +
            "over $filesCompared file(s)",
    )
    // A green run over an empty capture population tests nothing.
    require(memberCaptures > 0 && scopeCaptures > 0 && signatureCaptures > 0) {
        "REFUSED: a channel captured nothing at all, so its comparisons agreed " +
            "vacuously — members=$memberCaptures scopes=$scopeCaptures " +
            "signatures=$signatureCaptures"
    }
    if (mechanisms.isNotEmpty()) {
        println("mechanisms (distinct first differing element), most frequent first:")
        for ((mechanism, count) in mechanisms.entries.sortedByDescending { it.value }.take(20)) {
            println("  x$count  ${mechanism.take(400)}")
        }
    }
    println(
        if (divergences == 0) {
            "EQUIVALENT: all $memberCaptures member, $scopeCaptures scope and " +
                "$signatureCaptures signature capture(s) across $filesCompared file(s) agree"
        } else {
            "DIVERGED: $divergences span(s) in $divergingFiles of $filesCompared file(s) — " +
                "members=$memberDivergences scopes=$scopeDivergences " +
                "signatures=$signatureDivergences; narrowRendersMoreAny=$widenedToAny " +
                "absentInNarrow=$absentInNarrow absentInFull=$absentInFull other=$otherShape"
        },
    )
    if (divergences != 0) kotlin.system.exitProcess(1)
}

/**
 * The first ELEMENT of two rows that differs, named rather than left to be found in
 * two 4,000-character strings. Rows are built as `,`/`;`-separated element lists, so
 * splitting on both recovers the members, the names and the signatures.
 */
private fun firstDifference(left: String, right: String): String {
    val a = left.split(',', ';')
    val b = right.split(',', ';')
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrNull(i)
        val y = b.getOrNull(i)
        if (x != y) return "first difference at element $i: full=${x ?: "<none>"} narrow=${y ?: "<none>"}"
    }
    return "no element differs (the rows differ only in separators)"
}

/** `(start, end)` as one key, finalized for round 889's reason — see `CaptureEquivalenceMain`. */
private fun spanKey(start: Int, end: Int): Long =
    ((start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)) * -0x61c8864680b583ebL
