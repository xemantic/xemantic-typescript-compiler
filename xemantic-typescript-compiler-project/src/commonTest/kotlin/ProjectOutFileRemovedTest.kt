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
 * (LEGACY.1)(h) The project-path half of `outFile` being a REMOVED option in TypeScript 7:
 * tsgo 7.0.2 reports it (`program.go:836`, TS5102 at the KEY — `tsconfig.json(5,5)` with a
 * 9-character squiggle under `"outFile"`) and then emits one file per source into `outDir`
 * exactly as it does without the option: no bundle, no reordering, and a `/// <reference
 * path>` target still precedes its referrer in `--listFiles` in both cells.
 *
 * **Every expected value is tsgo 7.0.2's**, measured 2026-09-15 on 34 scratch projects
 * (`outFile: "bundle.js"` × `module` ∈ {unset, commonjs, esnext, system, amd, none} ×
 * reference directives × `declaration`) through `tsc -p . --outDir --listFiles`. At the
 * `"6.0"` default of `simulatedVersion` the row is TS5101 at the same anchor; tsgo's TS5102
 * wording needs `"typeScriptVersion": "7.0"` ((LEGACY.1)'s BLOCKED-PENDING-USER default).
 * `out` is an UNKNOWN option in tsgo (TS5023) and deliberately never sets `outFile`.
 *
 * The emit pins are RELATIONAL (the `outFile` cell's written files equal the no-`outFile`
 * cell's, byte for byte and in the same order) so they state that the option is inert and
 * nothing about the emit itself. **Every pin here is a CONTROL** — measured 2026-09-15, all
 * six were GREEN on the pre-change binary too: the project path never reached any of the
 * six `outFile`-keyed arms (LEGACY.1)(h) deleted (they lived in the single-file compile, the
 * `fullEmitPaths` layout, the harness's `.js` admission, the `module: none` drop and TS5074's
 * non-tsconfig context), so this class is the tsgo-shaped receipt that the row and the emit
 * on a real directory are right, and [com.xemantic.typescript.compiler.OutFileRemovedTest] in
 * the core module is the discriminating half. Two pre-existing project-path divergences the
 * matrix showed are NOT this round's and are recorded in its note: TS5011 (tsgo anchors it at
 * `"outFile"` when the option is present) is never reported here, and TS5074 is reported in
 * a tsconfig context where tsgo's `ConfigFilePath == ""` guard keeps it silent.
 */
class ProjectOutFileRemovedTest {

    private data class Row(val file: String?, val line: Int, val character: Int, val length: Int, val code: Int, val message: String)
    private class Cell(val json: String, val rows: List<Row>, val written: List<Pair<String, String>>)

    private val sources = mapOf(
        "/proj/src/a.ts" to "/// <reference path=\"b.ts\" />\nvar aa: number = bb + 1;\n",
        "/proj/src/b.ts" to "var bb: number = 1;\n",
        "/proj/src/m.ts" to "export const m: number = 1;\n",
        "/proj/src/n.ts" to "import { m } from \"./m\";\nexport const n: number = m + 1;\n",
    )

    private fun config(extra: List<String>): String {
        val parts = listOf("\"target\": \"es2020\"", "\"strict\": true", "\"outDir\": \"./out\"", "\"rootDir\": \"./src\"") + extra
        return """{ "compilerOptions": { ${parts.joinToString(", ")} }, "include": ["src/**/*"] }"""
    }

    private fun cell(vararg extra: String, files: Map<String, String> = sources): Cell {
        val json = config(extra.toList())
        val vfs = InMemoryVfs(files + mapOf("/proj/tsconfig.json" to json))
        val result = ProjectCompiler(vfs).build("/proj", noEmit = false)
        val diagnostics: List<Diagnostic> = result.diagnostics
        val rows = diagnostics
            .map { Row(it.fileName?.substringAfterLast('/'), it.line ?: -1, it.character ?: -1, it.length ?: -1, it.code, it.message) }
            .sortedWith(compareBy({ it.file }, { it.line }, { it.character }, { it.code }))
        // `written` keeps the compiler's own emit order — the ORDER is part of what is pinned.
        val written = result.written.map { it.first }.map { it to (vfs.readText(it) ?: "") }
        return Cell(json, rows, written)
    }

    /** `json.indexOf(token) + 1`: the 1-based column of a token in the one-line config. */
    private fun column(json: String, token: String): Int {
        val i = json.indexOf(token)
        assert(i >= 0)
        return i + 1
    }

    // ── the option is inert: the outFile cell writes what the plain cell writes ──────────

    @Test
    fun `control - an outFile project writes one JavaScript per source equal to the plain project byte for byte and in the same order`() {
        val plain = cell()
        val bundled = cell("\"outFile\": \"bundle.js\"")
        assert(plain.written.map { it.first.substringAfterLast('/') } == listOf("b.js", "a.js", "m.js", "n.js"))
        assert(bundled.written == plain.written)
        assert(bundled.written.none { it.first.endsWith("bundle.js") })
    }

    /** tsgo `--listFiles`: `src/b.ts` before `src/a.ts` with and without `outFile`. */
    @Test
    fun `control - a referenced file precedes its referrer in both cells`() {
        for (c in listOf(cell(), cell("\"outFile\": \"bundle.js\""))) {
            val order = c.written.map { it.first.substringAfterLast('/') }
            assert(order.indexOf("b.js") < order.indexOf("a.js"))
        }
    }

    @Test
    fun `control - an outFile project under commonjs and esnext writes the plain project's files`() {
        val plain = cell()
        for (module in listOf("commonjs", "esnext")) {
            val p = cell("\"module\": \"$module\"")
            val b = cell("\"module\": \"$module\"", "\"outFile\": \"bundle.js\"")
            assert(b.written == p.written)
            assert(p.written.size == plain.written.size)
        }
    }

    // ── the option row, key-anchored as tsgo's ─────────────────────────────────────────────

    /** tsgo: `tsconfig.json(1,C): error TS5102` under `"outFile"`, squiggle width 9 (`"outFile"` with its quotes); at the 6.0 default the code is TS5101 and nothing else is reported. */
    @Test
    fun `control - outFile reports TS5101 at its quoted key and nothing else`() {
        val c = cell("\"outFile\": \"bundle.js\"")
        val row = c.rows.single()
        assert(row.code == 5101)
        assert(row.file == "tsconfig.json")
        assert(row.line == 1)
        assert(row.character == column(c.json, "\"outFile\""))
        assert(row.length == "\"outFile\"".length)
    }

    /** tsgo: `Option 'outFile' has been removed. Please remove it from your configuration.` at `"outFile"`. */
    @Test
    fun `control - under typeScriptVersion 7 0 outFile is tsgo's TS5102 byte for byte at its key`() {
        val c = cell("\"outFile\": \"bundle.js\"", "\"typeScriptVersion\": \"7.0\"")
        val row = c.rows.single { it.code == 5102 }
        assert(row.message == "Option 'outFile' has been removed. Please remove it from your configuration.")
        assert(row.file == "tsconfig.json")
        assert(row.line == 1)
        assert(row.character == column(c.json, "\"outFile\""))
        assert(row.length == "\"outFile\"".length)
        assert(c.rows.none { it.code == 5101 })
        assert(c.written == cell().written)
    }

    /** tsgo: `TS5023 Unknown compiler option 'out'.` — `out` is not in TypeScript 7's option table at all. */
    @Test
    fun `control - out is an unknown option that sets nothing`() {
        val c = cell("\"out\": \"bundle.js\"")
        val row = c.rows.single()
        assert(row.code == 5023)
        assert(row.message == "Unknown compiler option 'out'.")
        assert(c.written == cell().written)
    }
}
