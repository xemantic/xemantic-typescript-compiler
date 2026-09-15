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
 * (LEGACY.1)(f) The project-path half of `module: amd` / `umd` / `system` being REMOVED
 * values in TypeScript 7: tsgo 7.0.2 reports them (TS5108, value-anchored) and then emits
 * and checks exactly as under `commonjs` — its `getModuleTransformer` (`emitter.go:82-101`)
 * sends the three to the CommonJS module transform.
 *
 * **Every expected value is tsgo 7.0.2's**, measured 2026-09-15 on 20 scratch projects ×
 * `module` ∈ {amd, umd, system, commonjs, esnext, unset} × `target` ∈ {es2020, esnext}
 * through `tsc -p . --outDir` (the emit and the option rows) and the LSP's
 * `textDocument/diagnostic` (the checker rows — the CLI stops at the option rows): the
 * `amd` / `umd` / `system` emit is byte-identical to the `commonjs` emit of the same
 * program, TS2882 fires under every kind, a `.json` import resolves and is emitted under
 * every kind, and TS5071 has no emitter anywhere in tsgo. At the `"6.0"` default of
 * `simulatedVersion` the option row is TS5107; tsgo's TS5108 needs
 * `"typeScriptVersion": "7.0"` ((LEGACY.1)'s BLOCKED-PENDING-USER default).
 */
class ProjectRemovedModuleKindsTest {

    private data class Row(val file: String?, val line: Int, val character: Int, val length: Int, val code: Int, val message: String)
    private class Cell(val json: String, val rows: List<Row>, val js: Map<String, String>)

    private val removedKinds = listOf("amd" to "AMD", "umd" to "UMD", "system" to "System")

    private val sources = mapOf(
        "/proj/src/helper.ts" to "export function helper(): number { return 1; }\nexport default 42;\nexport const c = \"c\";\n",
        "/proj/src/a.ts" to
            "import { helper } from \"./helper\";\nimport * as ns from \"./helper\";\nimport dflt from \"./helper\";\nimport \"./helper\";\n" +
            "export * from \"./helper\";\nexport { c as cc } from \"./helper\";\nexport const answer = helper() + ns.helper() + dflt;\nexport default answer;\n",
    )

    private fun config(module: String?, extra: String = ""): String {
        val parts = mutableListOf("\"target\": \"es2020\"", "\"strict\": true", "\"outDir\": \"./out\"", "\"rootDir\": \"./src\"")
        if (module != null) parts += "\"module\": \"$module\""
        if (extra.isNotEmpty()) parts += extra
        return """{ "compilerOptions": { ${parts.joinToString(", ")} }, "include": ["src/**/*"] }"""
    }

    private fun cell(module: String?, extra: String = "", files: Map<String, String> = sources): Cell {
        val json = config(module, extra)
        val vfs = InMemoryVfs(files + mapOf("/proj/tsconfig.json" to json))
        val result = ProjectCompiler(vfs).build("/proj", noEmit = false)
        val diagnostics: List<Diagnostic> = result.diagnostics
        val rows = diagnostics
            .map { Row(it.fileName?.substringAfterLast('/'), it.line ?: -1, it.character ?: -1, it.length ?: -1, it.code, it.message) }
            .sortedWith(compareBy({ it.file }, { it.line }, { it.character }, { it.code }))
        val js = result.written.map { it.first }
            .filter { it.endsWith(".js") }
            .associate { it.substringAfterLast('/') to (vfs.readText(it) ?: "") }
        return Cell(json, rows, js)
    }

    private val optionCodes = setOf(5107, 5108, 5095, 5071)
    private fun checkerRows(rows: List<Row>) = rows.filter { it.code !in optionCodes }

    /** `json.indexOf(token) + 1`: the 1-based column of a token in the one-line config. */
    private fun column(json: String, token: String): Int {
        val i = json.indexOf(token)
        assert(i >= 0)
        return i + 1
    }

    // ── the fold: emitted files equal the commonjs cell's, byte for byte ─────────────

    @Test
    fun `a removed module kind writes the commonjs JavaScript byte for byte`() {
        val commonjs = cell("commonjs")
        assert(commonjs.js.keys == setOf("a.js", "helper.js"))
        assert((commonjs.js["a.js"] ?: "").contains("const helper_1 = require(\"./helper\");"))
        for ((kind, _) in removedKinds) {
            val c = cell(kind)
            assert(c.js == commonjs.js)
            assert(checkerRows(c.rows) == checkerRows(commonjs.rows))
        }
    }

    @Test
    fun `control - an esnext cell keeps its import statements`() {
        val a = cell("esnext").js["a.js"] ?: ""
        assert(a.contains("import { helper } from \"./helper\";"))
        assert(!a.contains("require("))
    }

    // ── the option row, value-anchored as tsgo's ──────────────────────────────────────

    /** tsgo: `tsconfig.json(1,C)` under the quoted value, width = value length + 2; the row beside it is (e)'s TS5095 for the derived Bundler. */
    @Test
    fun `a removed module kind reports TS5107 at its quoted value beside TS5095 and nothing else`() {
        for ((kind, spelled) in removedKinds) {
            val c = cell(kind)
            val option = c.rows.filter { it.code in optionCodes }
            assert(option.map { it.code }.sorted() == listOf(5095, 5107))
            val row = option.single { it.code == 5107 }
            assert(row.message.contains("'module=$spelled'"))
            assert(row.file == "tsconfig.json")
            assert(row.line == 1)
            assert(row.character == column(c.json, "\"$kind\""))
            assert(row.length == kind.length + 2)
        }
    }

    /** tsgo: `Option 'module=AMD' has been removed. Please remove it from your configuration.` at `"amd"`; `System` and `UMD` spelled as tsgo spells them. */
    @Test
    fun `under typeScriptVersion 7 0 a removed module kind is tsgo's TS5108 byte for byte at its value`() {
        for ((kind, spelled) in removedKinds) {
            val c = cell(kind, "\"typeScriptVersion\": \"7.0\"")
            val row = c.rows.single { it.code == 5108 }
            assert(row.message == "Option 'module=$spelled' has been removed. Please remove it from your configuration.")
            assert(row.file == "tsconfig.json")
            assert(row.line == 1)
            assert(row.character == column(c.json, "\"$kind\""))
            assert(row.length == kind.length + 2)
            assert(c.rows.none { it.code == 5107 })
        }
    }

    // ── TS5071 has no emitter in tsgo; a .json import resolves and emits under every kind ──

    private val jsonSources = mapOf(
        "/proj/src/data.json" to "{ \"k\": 1 }\n",
        "/proj/src/main.ts" to "import data from \"./data.json\";\nexport const k: number = data.k;\n",
    )

    @Test
    fun `resolveJsonModule under umd or system no longer reports TS5071 and the json import folds onto its commonjs emit`() {
        val commonjs = cell("commonjs", "\"resolveJsonModule\": true", jsonSources)
        assert(checkerRows(commonjs.rows).isEmpty())
        assert((commonjs.js["main.js"] ?: "").contains("require(\"./data.json\")"))
        for ((kind, _) in removedKinds) {
            val c = cell(kind, "\"resolveJsonModule\": true", jsonSources)
            assert(c.rows.none { it.code == 5071 })
            assert(checkerRows(c.rows).isEmpty())
            assert(c.js == commonjs.js)
        }
    }

    // ── the deleted TS2882 exemption ───────────────────────────────────────────────────

    /** tsgo: `main.ts(1,8): error TS2882: Cannot find module or type declarations for side-effect import of 'nonexistent-pkg-zz'.` in every cell. */
    @Test
    fun `a side-effect import of an unresolvable package is TS2882 under every removed kind`() {
        val files = mapOf("/proj/src/main.ts" to "import \"nonexistent-pkg-zz\";\nexport const s = 1;\n")
        val expected = Row("main.ts", 1, 8, 20, 2882, "Cannot find module or type declarations for side-effect import of 'nonexistent-pkg-zz'.")
        assert(checkerRows(cell("commonjs", files = files).rows) == listOf(expected))
        for ((kind, _) in removedKinds) {
            assert(checkerRows(cell(kind, files = files).rows) == listOf(expected))
        }
    }
}
