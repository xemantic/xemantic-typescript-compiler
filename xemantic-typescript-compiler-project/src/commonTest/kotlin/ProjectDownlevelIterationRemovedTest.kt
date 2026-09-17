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

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (LEGACY.1)(i) The project-path half of `downlevelIteration` being a REMOVED option in
 * TypeScript 7: tsgo 7.0.2 reports it (`program.go:875`, TS5102 at the quoted KEY, width 20)
 * and then checks and emits exactly as it does without the option — measured 2026-09-15 over 16
 * scratch projects through `tsc -p` + the LSP's `textDocument/diagnostic` pull (the CLI stops
 * at the option rows): NO checker row and NO emitted byte differs between `downlevelIteration`
 * unset, `true` and `false`, at `target: es5`, `es2015` and unset alike.
 *
 * **Every expected value is tsgo 7.0.2's.** At the `"6.0"` default of `simulatedVersion` the
 * (P18.133) the shipped default IS `"7.0"`, so tsgo's TS5102/TS5108 wording is what this
 * compiler prints; an explicit `"typeScriptVersion"` below 7.0 gives TS5101 at the same anchor
 * (and a written `target: es5` TS5107 at its quoted VALUE). Superseded note ((LEGACY.1)'s
 * BLOCKED-PENDING-USER default).
 *
 * The discriminating pins are the TS2802 ones: before this round a written `"target": "es5"`
 * put the project path through the tsc-6 target-gated block (`for (const a of arguments)` and
 * `const [p] = arguments` read TS2802 unless `downlevelIteration` was `true`); tsgo never
 * reports TS2802 at a written es5 with a default lib, because TypeScript 7's `lib.d.ts` pulls
 * es2015 in through `lib.dom.d.ts`. The emit pins are RELATIONAL (the option cells write what
 * the plain cell writes, byte for byte) and were GREEN on the pre-change binary too — the
 * Transformer never read the option — so they are named `control -`, as are the row pins the
 * (P18.104) anchoring already carried. [com.xemantic.typescript.compiler.DownlevelIterationRemovedTest]
 * in the core module is the harness-path half.
 */
class ProjectDownlevelIterationRemovedTest {

    private data class Row(val file: String?, val line: Int, val character: Int, val length: Int, val code: Int, val message: String)
    private class Cell(val json: String, val rows: List<Row>, val written: List<Pair<String, String>>)

    private val sources = mapOf(
        "/proj/src/a.ts" to "function f() { for (const a of arguments) { a; } const [p] = arguments; p; }\n",
        "/proj/src/u.ts" to "const u = new Uint8Array(2); [...u];\nfor (const b of u) { b; }\n",
        "/proj/src/m.ts" to "export const m: number[] = [1, 2];\nfor (const x of m) { x; }\nexport const s = [...m];\n",
    )

    private fun config(extra: List<String>): String {
        val parts = listOf("\"strict\": true", "\"outDir\": \"./out\"", "\"rootDir\": \"./src\"") + extra
        return """{ "compilerOptions": { ${parts.joinToString(", ")} }, "include": ["src/**/*"] }"""
    }

    private fun cell(vararg extra: String): Cell {
        val json = config(extra.toList())
        val vfs = InMemoryVfs(sources + mapOf("/proj/tsconfig.json" to json))
        val result = ProjectCompiler(vfs).build("/proj", noEmit = false)
        val diagnostics: List<Diagnostic> = result.diagnostics
        val rows = diagnostics
            .map { Row(it.fileName?.substringAfterLast('/'), it.line ?: -1, it.character ?: -1, it.length ?: -1, it.code, it.message) }
            .sortedWith(compareBy({ it.file }, { it.line }, { it.character }, { it.code }))
        val written = result.written.map { it.first }.map { it to (vfs.readText(it) ?: "") }
        return Cell(json, rows, written)
    }

    /** `json.indexOf(token) + 1`: the 1-based column of a token in the one-line config. */
    private fun column(json: String, token: String): Int {
        val i = json.indexOf(token)
        assert(i >= 0)
        return i + 1
    }

    // ── a written es5 target no longer reaches a TS2802 emitter ──────────────────────────────

    /** tsgo at `target: es5`: `args.ts` carries no row at all; the only rows in the project are the option ones. */
    @Test
    fun `a written es5 target reports no TS2802 for a for-of or a destructuring of arguments`() {
        val c = cell("\"target\": \"es5\"")
        assert(c.rows.none { it.code == 2802 })
        assert(c.rows.none { it.file == "a.ts" })
    }

    @Test
    fun `a written es5 target with downlevelIteration false reports no TS2802`() {
        val c = cell("\"target\": \"es5\"", "\"downlevelIteration\": false")
        assert(c.rows.none { it.code == 2802 })
        assert(c.rows.none { it.file == "a.ts" })
    }

    @Test
    fun `control - a written es5 target with downlevelIteration true reports no TS2802`() {
        val c = cell("\"target\": \"es5\"", "\"downlevelIteration\": true")
        assert(c.rows.none { it.code == 2802 })
        assert(c.rows.none { it.file == "a.ts" })
    }

    @Test
    fun `control - an es2015 target reports no TS2802 with or without the option`() {
        for (extra in listOf(emptyList(), listOf("\"downlevelIteration\": true"), listOf("\"downlevelIteration\": false"))) {
            val c = cell("\"target\": \"es2015\"", *extra.toTypedArray())
            assert(c.rows.none { it.code == 2802 })
        }
    }

    // ── the option is inert in the emit ──────────────────────────────────────────────────────

    /** tsgo: `diff -r` of the es5 cell against its `downlevelIteration` cells is empty, and against the es2015 cell too. */
    @Test
    fun `control - the downlevelIteration cells write what the plain cell writes byte for byte`() {
        for (target in listOf("\"target\": \"es5\"", "\"target\": \"es2015\"")) {
            val plain = cell(target)
            assert(plain.written.map { it.first.substringAfterLast('/') }.toSet() == setOf("a.js", "u.js", "m.js"))
            assert(cell(target, "\"downlevelIteration\": true").written == plain.written)
            assert(cell(target, "\"downlevelIteration\": false").written == plain.written)
        }
    }

    // ── the option row, key-anchored as tsgo's ───────────────────────────────────────────────

    /** tsgo: TS5102 under `"downlevelIteration"` (20 characters, quotes included) — (P18.133) the shipped default; at an explicit `typeScriptVersion` below 7.0 the code is TS5101. */
    @Test
    fun `downlevelIteration reports TS5102 at its quoted key for true and for false`() {
        for (value in listOf("true", "false")) {
            val c = cell("\"target\": \"es2015\"", "\"downlevelIteration\": $value")
            val row = c.rows.single()
            assert(row.code == 5102)
            assert(row.file == "tsconfig.json")
            assert(row.line == 1)
            assert(row.character == column(c.json, "\"downlevelIteration\""))
            assert(row.length == "\"downlevelIteration\"".length)
        }
    }

    /** tsgo: `tsconfig.json(3,13): error TS5108: Option 'target=ES5' has been removed…` at `"es5"` and `TS5102 Option 'downlevelIteration' has been removed. Please remove it from your configuration.` at the key. */
    @Test
    fun `control - under typeScriptVersion 7 0 the two rows are tsgo's TS5108 and TS5102 byte for byte`() {
        val c = cell("\"target\": \"es5\"", "\"downlevelIteration\": true", "\"typeScriptVersion\": \"7.0\"")
        val dli = c.rows.single { it.code == 5102 }
        assert(dli.message == "Option 'downlevelIteration' has been removed. Please remove it from your configuration.")
        assert(dli.character == column(c.json, "\"downlevelIteration\""))
        assert(dli.length == "\"downlevelIteration\"".length)
        val target = c.rows.single { it.code == 5108 }
        assert(target.message == "Option 'target=ES5' has been removed. Please remove it from your configuration.")
        assert(target.character == column(c.json, "\"es5\""))
        assert(c.rows.none { it.code == 5101 || it.code == 5107 || it.code == 2802 })
        assert(c.rows.none { it.file == "a.ts" })
    }
}
