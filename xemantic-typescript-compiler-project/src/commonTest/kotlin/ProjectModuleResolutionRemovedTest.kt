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
 * (LEGACY.1)(e) The project-path half of `moduleResolution: classic` / `node` / `node10`
 * being REMOVED values in TypeScript 7: tsgo 7.0.2 reports them (TS5108, value-anchored)
 * and then resolves exactly as it does with the option UNSET — `GetModuleResolutionKind()`
 * derives Bundler / Node16 / NodeNext from the emit module kind, and no classic or node10
 * algorithm exists.
 *
 * **Every expected value is tsgo 7.0.2's**, measured 2026-09-15 on a 42-cell matrix
 * (`moduleResolution` ∈ {unset, classic, node, node10, bundler, node16, nodenext} ×
 * `module` ∈ {unset, commonjs, esnext, amd, system, nodenext}) with `--traceResolution`
 * for the resolved files and the LSP for the checker rows: in every `module` column the
 * classic / node / node10 cells equal the unset cell on both, bar the option row. The
 * resolved file is read here the way it was read there — every candidate file exports a
 * DISTINCT string-literal type and a wrong-typed `const` names it in its TS2322 message.
 *
 * Two facts about this compiler's own output the pins state rather than hide: at the
 * (P18.133) `"7.0"` default of `simulatedVersion` the removed-option row is tsgo's TS5108; TS5107
 * needs `"typeScriptVersion": "7.0"` ((LEGACY.1)'s BLOCKED-PENDING-USER default); and the
 * `pkg` fixture lists its `types` condition first, so every mode resolves it to
 * `types.d.ts` (measured — the `import`/`require` conditions never win).
 */
class ProjectModuleResolutionRemovedTest {

    private data class Row(val file: String?, val line: Int, val character: Int, val length: Int, val code: Int, val message: String)

    private val sources = mapOf(
        "/proj/src/main.ts" to
            "import { a } from \"./rel\";\nimport { d } from \"./dir\";\nimport { p } from \"pkg\";\nimport { dt } from \"dtpkg\";\n" +
            "const ta: 0 = a;\nconst td: 0 = d;\nconst tp: 0 = p;\nconst tdt: 0 = dt;\n",
        "/proj/src/rel.ts" to "export const a: \"rel-ts\" = \"rel-ts\";\n",
        "/proj/src/dir/index.ts" to "export const d: \"dir-index\" = \"dir-index\";\n",
        "/proj/node_modules/pkg/package.json" to
            """{ "name": "pkg", "version": "1.0.0", "main": "./main.js", "types": "./main.d.ts", "exports": { ".": { "types": "./types.d.ts", "import": "./esm.d.ts", "require": "./cjs.d.ts" } } }""",
        "/proj/node_modules/pkg/main.js" to "module.exports = { p: 'pkg-main' };\n",
        "/proj/node_modules/pkg/main.d.ts" to "export declare const p: \"pkg-main\";\n",
        "/proj/node_modules/pkg/types.d.ts" to "export declare const p: \"pkg-types\";\n",
        "/proj/node_modules/pkg/esm.d.ts" to "export declare const p: \"pkg-import\";\n",
        "/proj/node_modules/pkg/cjs.d.ts" to "export declare const p: \"pkg-require\";\n",
        "/proj/node_modules/dtpkg/package.json" to """{ "name": "dtpkg", "version": "1.0.0", "main": "./lib.js" }""",
        "/proj/node_modules/dtpkg/lib.js" to "module.exports = { dt: 'dtpkg-lib' };\n",
        "/proj/node_modules/dtpkg/lib.d.ts" to "export declare const dt: \"dtpkg-lib-dts\";\n",
        "/proj/node_modules/dtpkg/index.d.ts" to "export declare const dt: \"dtpkg-index-dts\";\n",
    )

    private val root = "/proj/tsconfig.json"

    private fun config(module: String?, resolution: String?, extra: String = ""): String {
        val parts = mutableListOf("\"target\": \"es2020\"", "\"strict\": true", "\"noEmit\": true", "\"resolveJsonModule\": true")
        if (module != null) parts += "\"module\": \"$module\""
        if (resolution != null) parts += "\"moduleResolution\": \"$resolution\""
        if (extra.isNotEmpty()) parts += extra
        return """{ "compilerOptions": { ${parts.joinToString(", ")} }, "include": ["src/**/*"] }"""
    }

    private fun cell(module: String?, resolution: String?, extra: String = ""): Pair<String, List<Row>> {
        val json = config(module, resolution, extra)
        val diagnostics: List<Diagnostic> = ProjectCompiler(InMemoryVfs(sources + mapOf(root to json))).build("/proj", noEmit = true).diagnostics
        val rows = diagnostics
            .map { Row(it.fileName?.substringAfterLast('/'), it.line ?: -1, it.character ?: -1, it.length ?: -1, it.code, it.message) }
            .sortedWith(compareBy({ it.file }, { it.line }, { it.character }, { it.code }))
        return json to rows
    }

    private val optionCodes = setOf(5107, 5108, 5095, 5109, 5110)
    private fun checkerRows(rows: List<Row>) = rows.filter { it.code !in optionCodes }

    /** One TS2322 per probe, naming the candidate tsgo resolved to. */
    private fun probeRow(line: Int, name: String, literal: String) =
        Row("main.ts", line, 7, name.length, 2322, "Type '\"$literal\"' is not assignable to type '0'.")

    private val resolvedAsTsgo = listOf(
        probeRow(5, "ta", "rel-ts"),
        probeRow(6, "td", "dir-index"),
        probeRow(7, "tp", "pkg-types"),
        probeRow(8, "tdt", "dtpkg-lib-dts"),
    )

    /** `json.indexOf(token) + 1`: the 1-based column of a token in the one-line config. */
    private fun column(json: String, token: String): Int {
        val i = json.indexOf(token)
        assert(i >= 0)
        return i + 1
    }

    // ── the licence: every removed and unset value resolves as tsgo, under commonjs and esnext ──

    @Test
    fun `unset classic node node10 and bundler resolve every specifier to the same files under commonjs`() {
        for (resolution in listOf(null, "classic", "node", "node10", "bundler")) {
            val (_, rows) = cell("commonjs", resolution)
            assert(checkerRows(rows) == resolvedAsTsgo)
        }
    }

    @Test
    fun `unset classic node node10 and bundler resolve every specifier to the same files under esnext`() {
        for (resolution in listOf(null, "classic", "node", "node10", "bundler")) {
            val (_, rows) = cell("esnext", resolution)
            assert(checkerRows(rows) == resolvedAsTsgo)
        }
    }

    @Test
    fun `control - an explicit bundler cell equals the unset cell row for row`() {
        for (module in listOf("commonjs", "esnext")) {
            assert(cell(module, "bundler").second == cell(module, null).second)
        }
    }

    @Test
    fun `no cell reports TS5070 or TS2792`() {
        for (module in listOf(null, "commonjs", "esnext", "amd", "system", "nodenext")) {
            for (resolution in listOf(null, "classic", "node", "node10", "bundler", "node16", "nodenext")) {
                val (_, rows) = cell(module, resolution)
                assert(rows.none { it.code == 5070 || it.code == 2792 })
            }
        }
    }

    // ── the removed-option rows, value-anchored as tsgo's ────────────────────────────

    /** tsgo: `tsconfig.json(1,C)` under `"classic"`, width 9, with tsgo's spelling `Classic` — (P18.133) TS5108 at the shipped default, TS5107 at an explicit `typeScriptVersion` below 7.0. */
    @Test
    fun `classic reports one TS5108 at its quoted value and nothing else moves`() {
        val (json, rows) = cell("commonjs", "classic")
        val removed = rows.filter { it.code in optionCodes }
        assert(removed.size == 1)
        val row = removed[0]
        assert(row.code == 5108)
        assert(row.message.contains("'moduleResolution=Classic'"))
        assert(row.file == "tsconfig.json")
        assert(row.line == 1)
        assert(row.character == column(json, "\"classic\""))
        assert(row.length == 9)
    }

    /** tsgo: `Option 'moduleResolution=Classic' has been removed. Please remove it from your configuration.` at `"classic"`. */
    @Test
    fun `under typeScriptVersion 7 0 classic node and node10 are tsgo's TS5108 byte for byte`() {
        for ((value, spelled) in listOf("classic" to "Classic", "node" to "node10", "node10" to "node10")) {
            val (json, rows) = cell("commonjs", value, "\"typeScriptVersion\": \"7.0\"")
            val removed = rows.filter { it.code in optionCodes }
            assert(removed.size == 1)
            val row = removed[0]
            assert(row.code == 5108)
            assert(row.message == "Option 'moduleResolution=$spelled' has been removed. Please remove it from your configuration.")
            assert(row.file == "tsconfig.json")
            assert(row.line == 1)
            assert(row.character == column(json, "\"$value\""))
            assert(row.length == value.length + 2)
            assert(checkerRows(rows) == resolvedAsTsgo)
        }
    }

    // ── the option-interaction rows read the derived kind and anchor as tsgo's ────────

    /** tsgo: `tsconfig.json(1,C): error TS5110` at the `"commonjs"` value, width 10; the `pkg` row is untouched. */
    @Test
    fun `node16 with module commonjs reports TS5110 at the module value`() {
        for ((resolution, display) in listOf("node16" to "Node16", "nodenext" to "NodeNext")) {
            val (json, rows) = cell("commonjs", resolution)
            val row = rows.singleOrNull { it.code == 5110 }
            assert(row != null)
            assert(row.message == "Option 'module' must be set to '$display' when option 'moduleResolution' is set to '$display'.")
            assert(row.file == "tsconfig.json")
            assert(row.line == 1)
            assert(row.character == column(json, "\"commonjs\""))
            assert(row.length == 10)
            assert(checkerRows(rows).contains(probeRow(7, "tp", "pkg-types")))
        }
    }

    /** tsgo: with no `module` written, TS5110 anchors at the root's `"compilerOptions"` key, width 17. */
    @Test
    fun `node16 with no module option anchors TS5110 at the compilerOptions key`() {
        val (json, rows) = cell(null, "node16")
        val row = rows.singleOrNull { it.code == 5110 }
        assert(row != null)
        assert(row.character == column(json, "\"compilerOptions\""))
        assert(row.length == 17)
    }

    /** tsgo: `module: amd` with no `moduleResolution` is a Bundler program — TS5095 at the `"compilerOptions"` key, beside the `module=AMD` row. */
    @Test
    fun `module amd with no moduleResolution is a Bundler program and reports TS5095 at the compilerOptions key`() {
        val (json, rows) = cell("amd", null)
        val row = rows.singleOrNull { it.code == 5095 }
        assert(row != null)
        assert(row.message == "Option 'bundler' can only be used when 'module' is set to 'preserve', 'commonjs', or 'es2015' or later.")
        assert(row.file == "tsconfig.json")
        assert(row.line == 1)
        assert(row.character == column(json, "\"compilerOptions\""))
        assert(row.length == 17)
        assert(rows.count { it.code == 5108 && it.message.contains("module=AMD") } == 1)
    }

    /** tsgo: an explicit removed value beside `amd` anchors TS5095 at THAT value (`classic`, `node10` cells: `(14,25)` in the matrix). */
    @Test
    fun `module amd with a removed moduleResolution anchors TS5095 at the removed value`() {
        for (value in listOf("classic", "node10")) {
            val (json, rows) = cell("amd", value)
            val row = rows.singleOrNull { it.code == 5095 }
            assert(row != null)
            assert(row.character == column(json, "\"$value\""))
            assert(row.length == value.length + 2)
        }
    }

    /** tsgo (`bundler` × `nodenext`): TS5095 and TS5109, both at the `"bundler"` value, width 9. */
    @Test
    fun `bundler with module nodenext reports TS5095 and TS5109 at the bundler value`() {
        val (json, rows) = cell("nodenext", "bundler")
        val at = column(json, "\"bundler\"")
        val r5095 = rows.singleOrNull { it.code == 5095 }
        val r5109 = rows.singleOrNull { it.code == 5109 }
        assert(r5095 != null)
        assert(r5109 != null)
        assert(r5109.message == "Option 'moduleResolution' must be set to 'NodeNext' (or left unspecified) when option 'module' is set to 'NodeNext'.")
        assert(r5095.character == at && r5095.length == 9)
        assert(r5109.character == at && r5109.length == 9)
    }

    /** tsgo (`node16` × `nodenext`, `nodenext` × `nodenext`): two node kinds, no row. */
    @Test
    fun `control - a node resolution with a node module kind reports no interaction row`() {
        for (resolution in listOf("node16", "nodenext")) {
            val (_, rows) = cell("nodenext", resolution)
            assert(rows.none { it.code in optionCodes })
        }
    }
}
