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

import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.TypeScriptCompiler
import java.io.File

/**
 * (INC.18) THE MINER — which checker pass NETS a diagnostic for which shape.
 *
 * The partition gate is only as sharp as the number of DISTINCT passes whose
 * output it compares. On tsc's own 78 sources that number is ONE (`checkSpine`),
 * so the gate compares an essentially empty population and cannot see a starved
 * replay. To build a fixture that re-arms it, one has to know which SHAPE each
 * of the ~400 tail walkers owns — and the cheapest source of shapes is the
 * TypeScript conformance corpus, which this walks case by case, in memory,
 * honouring each case's own `// @directive` header (CLAUDE.md: a fixed scratch
 * tsconfig manufactures false positives).
 *
 * Attribution comes from [PassTiming.diagNetByPass] — the SIGNED per-pass delta,
 * so a retracting pass is not clamped away — which is exactly the receipt
 * (INC.18) is graded by.
 *
 * ```
 * java … PassDiagMineMainKt <casesDir> <outFile> [limit]
 * ```
 *
 * Emits one `CASE <relPath> <files> <ndiag> <pass>,<pass>,…` line per case that
 * nets anything, for offline greedy selection.
 */
private const val FIXTURE_HEADER = """// @useRealLibs: true
// @strict: true
// @target: es2020
// @lib: es2020
// @module: esnext
// @skipLibCheck: true
"""

fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <casesDir> <outFile> [limit]" }
    val root = File(args[0])
    require(root.isDirectory) { "REFUSED: not a directory: $root" }
    val limit = if (args.size > 2) args[2].toInt() else Int.MAX_VALUE
    val out = File(args[1]).bufferedWriter()
    val cases = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".ts") && !it.name.endsWith(".d.ts") }
        .sortedBy { it.path }
        .take(limit)
        .toList()
    println("cases: ${cases.size}")
    var mined = 0
    var failed = 0
    var skipped = 0
    for ((i, f) in cases.withIndex()) {
        val rel = f.relativeTo(root).path
        val raw = try { f.readText() } catch (_: Exception) { continue }
        // Multi-file cases and cases too large to be a readable fixture file are out:
        // the fixture composes ONE case per project file, under ONE tsconfig.
        if (raw.length > 4_000 || raw.contains("@Filename", ignoreCase = true) ||
            raw.contains("@filename")
        ) { skipped++; continue }
        // The case's OWN `// @directive` header is DROPPED and the fixture's own
        // header substituted, because the fixture is one tsconfig and every file in
        // it is compiled under the same options. (Mining under the case's own
        // directives would rank shapes the composed project can never reproduce.)
        val body = raw.lineSequence()
            .filterNot { it.trimStart().startsWith("// @") || it.trimStart().startsWith("//@") }
            .joinToString("\n")
        if (body.isBlank()) { skipped++; continue }
        val text = FIXTURE_HEADER + body
        PassTiming.reset()
        PassTiming.detail = false
        PassTiming.spineDetail = false
        PassTiming.enabled = true
        val result = try {
            TypeScriptCompiler().compile(text, f.name)
        } catch (_: Exception) {
            failed++; PassTiming.enabled = false; continue
        } catch (_: StackOverflowError) {
            failed++; PassTiming.enabled = false; continue
        }
        PassTiming.enabled = false
        val netting = PassTiming.diagNetByPass.filterValues { it != 0 }.keys.sorted()
        if (netting.isNotEmpty()) {
            mined++
            out.write("CASE\t$rel\t${result.diagnostics.size}\t${netting.joinToString(",")}\n")
        }
        if (i % 500 == 0) {
            println("… $i/${cases.size} mined=$mined skipped=$skipped failed=$failed")
            out.flush()
        }
    }
    out.close()
    println("done: mined=$mined skipped=$skipped failed=$failed of ${cases.size}")
}
