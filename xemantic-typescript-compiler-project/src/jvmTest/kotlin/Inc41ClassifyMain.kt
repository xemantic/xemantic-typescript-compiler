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

import com.xemantic.typescript.compiler.CapturedType
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.RecheckHolder
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags
import java.io.File

/**
 * (INC.41) step 1 — DUMP every diverging captured-type row of the replay-vs-fresh
 * differential, so the population can be classified PER ELEMENT offline.
 *
 * [ReplayDifferentialMain] is a GATE: it prints five truncated examples per file and
 * a verdict. That is the right shape for a gate and the wrong shape for the question
 * (INC.41) asks, which is *which arm is right*. (INC.23) is the standing warning —
 * `narrowRendersMoreAny` over-reported a 168-row population at 100%, and only a
 * per-ELEMENT, nesting-aware classification of the actual renderings found the one
 * real cause. So this writes the WHOLE population out, with the source text of each
 * span and its line/character, which is what a tsgo LSP cross-check needs
 * (`scripts/lsp_hover.py`) — CLAUDE.md forbids hand-writing the expectation while
 * that oracle exists.
 *
 * The arms are set up exactly as the differential sets them up (same seed, same
 * spans handed to both arms, same `recheckOnly` shape), so this population IS that
 * one and not a second sample of a similar question.
 *
 * ```
 * java -cp ... Inc41ClassifyMainKt <projectDir> <outTsv> [maxFiles]
 * ```
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <projectDir> <outTsv> [maxFiles]" }
    val out = File(args[1])
    val limit = if (args.size > 2 && args[2].toInt() > 0) args[2].toInt() else Int.MAX_VALUE

    val vfs = SystemVfs
    val compiler = ProjectCompiler(vfs)
    val project = vfs.resolveAbsolute(args[0])
    val configPath = if (vfs.isDirectory(project)) "$project/tsconfig.json" else project
    val options = TsConfigLoader(vfs).load(configPath).options

    compiler.build(project, noEmit = true)
    val programFiles = compiler.build(project, noEmit = true).programFiles

    val spansOf = LinkedHashMap<String, List<TypeCaptureSpan>>()
    val textOf = LinkedHashMap<String, String>()
    for (file in programFiles) {
        val text = vfs.readText(file) ?: continue
        val index = SourceIndex.of(text, file, computeParserFlags(file, text, options))
        val spans = index.identifiers().map { TypeCaptureSpan(file, it.pos, it.end) }.distinct()
        if (spans.isNotEmpty()) {
            spansOf[file] = spans
            textOf[file] = text
        }
    }
    val files = spansOf.keys.toList()
    val seed = files.first()
    val targets = files.drop(1).take(limit)
    println("project: $project  seed=${seed.substringAfterLast('/')} targets=${targets.size}")

    fun typeRows(captured: List<CapturedType>, file: String): Map<Long, CapturedType> {
        val rows = HashMap<Long, CapturedType>()
        for (c in captured) {
            if (c.fileName != file) continue
            rows[(c.start.toLong() shl 32) or (c.end.toLong() and 0xffff_ffffL)] = c
        }
        return rows
    }

    val holder = RecheckHolder()
    compiler.build(
        project,
        noEmit = true,
        recheckOnly = setOf(seed),
        typeCapture = TypeCaptureRequest(spansOf.getValue(seed)),
        recheckHolder = holder,
    )
    val recheck = requireNotNull(holder.recheck) { "REFUSED: no ProgramRecheck" }

    val replayAnswers = LinkedHashMap<String, List<CapturedType>>()
    for (file in targets) {
        replayAnswers[file] = recheck.recheck(
            setOf(file),
            TypeCaptureRequest(spansOf.getValue(file)),
        ).capturedTypes
    }

    var compared = 0
    var diverged = 0
    val sb = StringBuilder()
    sb.append("file\tstart\tend\tline\tchar\tkind\tspanText\tfresh\treplay\n")
    for (file in targets) {
        val fresh = compiler.build(
            project,
            noEmit = true,
            recheckOnly = setOf(file),
            typeCapture = TypeCaptureRequest(spansOf.getValue(file)),
        )
        val expected = typeRows(fresh.capturedTypes, file)
        val actual = typeRows(replayAnswers.getValue(file), file)
        val text = textOf.getValue(file)
        // A per-file line-start table, for the LSP cross-check's (line, character).
        val lineStarts = ArrayList<Int>()
        lineStarts.add(0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                i++
                lineStarts.add(i)
            } else if (c == '\r') {
                i++
                if (i < text.length && text[i] == '\n') i++
                lineStarts.add(i)
            } else {
                i++
            }
        }
        fun lineOf(pos: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (lineStarts[mid] <= pos) lo = mid else hi = mid - 1
            }
            return lo
        }
        val keys = expected.keys + actual.keys
        compared += keys.size
        // The PROJECT-RELATIVE path, never the basename: tsc's own sources carry
        // THREE `utilities.ts`, so a basename column silently merges three files —
        // which is exactly the column an LSP cross-check has to resolve back to a
        // path (`scripts/lsp_hover_project.py`), and it cannot.
        val short = if (file.startsWith("$project/")) file.removePrefix("$project/") else file
        for (k in keys) {
            val e = expected[k]
            val a = actual[k]
            if ((e?.kind to e?.typeText) == (a?.kind to a?.typeText)) continue
            diverged++
            val start = (k shr 32).toInt()
            val end = (k and 0xffff_ffffL).toInt()
            val line = lineOf(start)
            val ch = start - lineStarts[line]
            val span = if (start in 0..text.length && end in start..text.length) {
                text.substring(start, minOf(end, text.length))
            } else ""
            fun clean(s: String?): String =
                (s ?: "<ABSENT>").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            sb.append(short).append('\t').append(start).append('\t').append(end).append('\t')
                .append(line).append('\t').append(ch).append('\t')
                .append(clean(e?.kind ?: a?.kind)).append('\t')
                .append(clean(span)).append('\t')
                .append(clean(e?.typeText)).append('\t')
                .append(clean(a?.typeText)).append('\n')
        }
    }
    out.writeText(sb.toString())
    println("compared=$compared divergentRows=$diverged  -> ${out.path}")
}
