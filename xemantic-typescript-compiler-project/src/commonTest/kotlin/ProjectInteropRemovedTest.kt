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
 * (LEGACY.1)(d2) `esModuleInterop: false` and `allowSyntheticDefaultImports: false` are
 * REMOVED values in TypeScript 7, and — measured, not assumed — neither option has a
 * behavioural reader there at all: tsgo 7.0.2's `core.CompilerOptions` carries both fields
 * and consults them in exactly one place, `program.go:862-868`, the TS5108 *has been
 * removed* row. ES-module interop is always on (`checker.go:14478`) and a synthetic default
 * is a property of the TARGET (`canHaveSyntheticDefault`, `checker.go:14744`). So this
 * compiler's honouring of the `false` values — TS1259 on a default import of an `export =`
 * module, TS2617/TS2596/TS2598 on a named one, plain `require` instead of the interop
 * helpers — was tsc-6 behaviour, and its honouring of an explicit `true` as a BLANKET skip
 * of TS1192 was too.
 *
 * **EVERY EXPECTED VALUE IS tsgo 7.0.2's**, measured on the project path: 45 scratch
 * projects (`commonjs` / `esnext` / `moduleResolution: bundler` / `nodenext` under a
 * CommonJS and under a `"type": "module"` scope, each × the 3×3 matrix of
 * `esModuleInterop` and `allowSyntheticDefaultImports` ∈ {unset, false, true}), with
 * `tools/tsgo-7.0.2/lib/tsc -p .` for the rows and its `outDir` for the helpers; the
 * explicit-`false` cells' checker rows — hidden behind the CLI stopping at TS5108 —
 * were read through tsgo's LSP (`textDocument/diagnostic`). In every family the nine
 * cells are byte-identical on both channels except for the TS5108 row(s).
 *
 * Two facts about this compiler's own display that the pins state rather than hide:
 * a TS1192 message here spells the specifier (`Module '"esm"' …`) where tsgo spells the
 * resolved path without its extension — a pre-existing (LEGACY.0b) display divergence
 * outside this family, so the TS1192 pins check the message's shape and not its path;
 * and at the `"6.0"` default of `simulatedVersion` the removed-option row is TS5107,
 * not tsgo's TS5108 ((LEGACY.1)'s BLOCKED-PENDING-USER default) — its anchoring is
 * [ProjectTsconfigOptionAnchorTest]'s subject, so here the row is only counted.
 */
class ProjectInteropRemovedTest {

    /** One row of a cell, in the fields tsgo's `--pretty false` output lets us compare. */
    private data class Row(val file: String?, val line: Int, val character: Int, val length: Int, val code: Int, val message: String)

    private class Cell(val diagnostics: List<Diagnostic>, val js: Map<String, String>) {
        /** The checker rows — the removed-option row itself is [ProjectTsconfigOptionAnchorTest]'s. */
        val rows: List<Row> = diagnostics
            .filter { it.code != 5107 && it.code != 5108 }
            .map { Row(it.fileName?.substringAfterLast('/'), it.line ?: -1, it.character ?: -1, it.length ?: -1, it.code, it.message) }
            .sortedWith(compareBy({ it.file }, { it.line }, { it.character }, { it.code }))
        val removedOptionRows: List<Diagnostic> get() = diagnostics.filter { it.code == 5107 || it.code == 5108 }
        fun helpersOf(output: String): Set<String> =
            listOf("__importDefault(", "__importStar(", "__createBinding").filter { it in (js[output] ?: "") }.toSet()
        val helpers: Map<String, Set<String>> get() = js.keys.associateWith { helpersOf(it) }
    }

    private val sources = mapOf(
        // a TypeScript `export =` module (a CommonJS module in tsc's terms)
        "/proj/src/cjsfile.ts" to "function f(): void {}\nexport = f;\n",
        // a declaration file with named exports and no default — tsgo's declaration-file
        // synthetic default (no syntactic default, no `__esModule` marker)
        "/proj/src/decl.d.ts" to "export declare const q: number;\nexport declare function qf(): void;\n",
        // an ES module with no default — never has a synthetic default
        "/proj/src/esm.ts" to "export const e = 1;\nexport function g(): void {}\n",
        "/proj/src/d_cjsfile.ts" to "import d from \"./cjsfile\"; d;\n",
        "/proj/src/d_decl.ts" to "import d from \"./decl\"; d;\n",
        "/proj/src/d_esm.ts" to "import d from \"./esm\"; d;\n",
        "/proj/src/ns_cjsfile.ts" to "import * as ns from \"./cjsfile\"; ns;\n",
        "/proj/src/named_cjsfile.ts" to "import { f } from \"./cjsfile\"; f;\n",
    )

    private fun cell(module: String, flags: String = "", extra: Map<String, String> = emptyMap()): Cell {
        val vfs = InMemoryVfs(
            sources + extra + mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "module": "$module", "target": "es2020", "outDir": "./out"$flags }, "include": ["src/**/*"] }""",
            ),
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = false)
        val js = result.written.map { it.first }
            .filter { it.endsWith(".js") }
            .associate { it.substringAfterLast('/') to (vfs.readText(it) ?: "") }
        return Cell(result.diagnostics, js)
    }

    private fun Row.isTs1192For(file: String) =
        this.file == file && line == 1 && character == 8 && length == 1 && code == 1192 &&
            message.startsWith("Module '\"") && message.endsWith("' has no default export.")

    // ── the default cell, per module kind ─────────────────────────────────────────

    /**
     * tsgo `commonjs`, no flag: `named_cjsfile.ts(1,10): error TS2616: 'f' can only be
     * imported by using 'import f = require("./cjsfile")' or a default import.` and
     * `d_esm.ts(1,8): error TS1192` — nothing on `d_cjsfile.ts` (the `export =` module
     * has a synthetic default) and nothing on `d_decl.ts` (so does a declaration file).
     * Emit: `__importDefault` for the default imports, `__importStar` (+ `__createBinding`)
     * for the namespace import, a plain `require` for the named one.
     */
    @Test
    fun `commonjs - an export-equals module and a declaration file are default-importable and the named import is TS2616`() {
        val c = cell("commonjs")
        assert(c.rows.size == 2)
        assert(c.rows[0].isTs1192For("d_esm.ts"))
        assert(
            c.rows[1] == Row(
                "named_cjsfile.ts", 1, 10, 1, 2616,
                "'f' can only be imported by using 'import f = require(\"./cjsfile\")' or a default import.",
            ),
        )
        assert(c.helpersOf("d_cjsfile.js") == setOf("__importDefault("))
        assert(c.helpersOf("d_decl.js") == setOf("__importDefault("))
        assert(c.helpersOf("ns_cjsfile.js") == setOf("__importStar(", "__createBinding"))
        assert(c.helpersOf("named_cjsfile.js").isEmpty())
        assert((c.js["named_cjsfile.js"] ?: "").contains("require(\"./cjsfile\")"))
        assert(c.removedOptionRows.isEmpty())
    }

    /**
     * The default flip on its own: before (d2) a `.d.ts` target with named exports and
     * no default was TS1192 at the default (a synthetic default needed an explicit
     * `allowSyntheticDefaultImports: true`); tsgo accepts it in every cell.
     */
    @Test
    fun `commonjs - a declaration-file target with named exports and no default is default-importable at the default`() {
        val c = cell("commonjs")
        assert(c.rows.none { it.file == "d_decl.ts" })
        assert(c.rows.none { it.code == 1259 })
    }

    /**
     * tsgo `esnext`: `cjsfile.ts(2,1): error TS1203`, `named_cjsfile.ts(1,10): error
     * TS2595: 'f' can only be imported by using a default import.` and `d_esm.ts(1,8):
     * error TS1192`; ES-module output carries no interop helper.
     */
    @Test
    fun `esnext - the named import of an export-equals module is TS2595 and the ESM emit carries no helper`() {
        val c = cell("esnext")
        assert(c.rows.size == 3)
        assert(c.rows[0].file == "cjsfile.ts" && c.rows[0].line == 2 && c.rows[0].character == 1 && c.rows[0].code == 1203)
        assert(c.rows[1].isTs1192For("d_esm.ts"))
        assert(c.rows[2] == Row("named_cjsfile.ts", 1, 10, 1, 2595, "'f' can only be imported by using a default import."))
        assert(c.helpers.values.all { it.isEmpty() })
        assert((c.js["d_cjsfile.js"] ?: "").contains("import d from"))
    }

    /**
     * tsgo `nodenext` with no `package.json` (every file CommonJS-scoped): the emit is the
     * `commonjs` emit, but the named import reads **TS2595**, not TS2616 — tsgo's
     * `reportInvalidImportEqualsExportMember` picks the wording by `moduleKind >=
     * ES2015` (`nodenext` is), never by the importer's file format. Before (d2) this
     * compiler keyed it on the format and printed TS2616 here.
     */
    @Test
    fun `nodenext - a CommonJS-scoped importer reads TS2595 because the module option decides it`() {
        val c = cell("nodenext")
        assert(c.rows.size == 2)
        assert(c.rows[0].isTs1192For("d_esm.ts"))
        assert(c.rows[1] == Row("named_cjsfile.ts", 1, 10, 1, 2595, "'f' can only be imported by using a default import."))
        assert(c.helpersOf("d_cjsfile.js") == setOf("__importDefault("))
        assert(c.helpersOf("ns_cjsfile.js") == setOf("__importStar(", "__createBinding"))
    }

    // ── the removed values: a TS5107 row and NOTHING else ─────────────────────────

    /**
     * tsgo: the `esModuleInterop: false` cell is the unset cell plus `TS5108: Option
     * 'esModuleInterop=false' has been removed.` — same rows, same helpers. Before (d2)
     * this compiler dropped every interop helper (`const cjsfile_1 = require(…)`),
     * reported TS1259 on `d_cjsfile.ts` and TS2617 on `named_cjsfile.ts`.
     */
    @Test
    fun `esModuleInterop false is one removed-option row and changes neither the rows nor the emit`() {
        val base = cell("commonjs")
        val c = cell("commonjs", """, "esModuleInterop": false""")
        assert(c.removedOptionRows.size == 1)
        assert(c.removedOptionRows.single().message.contains("esModuleInterop=false"))
        assert(c.rows == base.rows)
        assert(c.helpers == base.helpers)
        assert(c.rows.none { it.code == 1259 || it.code == 2617 || it.code == 2596 || it.code == 2598 })
    }

    @Test
    fun `allowSyntheticDefaultImports false is one removed-option row and changes neither the rows nor the emit`() {
        val base = cell("commonjs")
        val c = cell("commonjs", """, "allowSyntheticDefaultImports": false""")
        assert(c.removedOptionRows.size == 1)
        assert(c.removedOptionRows.single().message.contains("allowSyntheticDefaultImports=false"))
        assert(c.rows == base.rows)
        assert(c.helpers == base.helpers)
        assert(c.rows.none { it.code == 1259 })
    }

    @Test
    fun `both removed values together are two removed-option rows and nothing else`() {
        val base = cell("commonjs")
        val c = cell("commonjs", """, "esModuleInterop": false, "allowSyntheticDefaultImports": false""")
        assert(c.removedOptionRows.size == 2)
        assert(c.rows == base.rows)
        assert(c.helpers == base.helpers)
    }

    /** The same three cells under `esnext`, where the family's row is TS2595 and there is no helper to drop. */
    @Test
    fun `esnext - the removed values change nothing but the removed-option rows`() {
        val base = cell("esnext")
        for (flags in listOf(
            """, "esModuleInterop": false""",
            """, "allowSyntheticDefaultImports": false""",
            """, "esModuleInterop": false, "allowSyntheticDefaultImports": false""",
        )) {
            val c = cell("esnext", flags)
            assert(c.rows == base.rows)
            assert(c.helpers == base.helpers)
            assert(c.rows.none { it.code == 2596 || it.code == 2617 })
        }
    }

    // ── the `true` values: a no-op, including the one tsc 6 honoured ──────────────

    /** Control: `esModuleInterop: true` was the default before and reads as unset now. */
    @Test
    fun `control - esModuleInterop true is the unset cell`() {
        val base = cell("commonjs")
        val c = cell("commonjs", """, "esModuleInterop": true""")
        assert(c.removedOptionRows.isEmpty())
        assert(c.rows == base.rows)
        assert(c.helpers == base.helpers)
    }

    /**
     * tsgo: `allowSyntheticDefaultImports: true` reads exactly as unset — in particular
     * `d_esm.ts(1,8): error TS1192` stays, because a TypeScript module with no `export =`
     * never has a synthetic default whatever the option says. Before (d2) an explicit
     * `true` was a blanket skip of every CommonJS-format target, which silenced it.
     */
    @Test
    fun `allowSyntheticDefaultImports true no longer silences TS1192 on a TypeScript module with no default`() {
        val base = cell("commonjs")
        val c = cell("commonjs", """, "allowSyntheticDefaultImports": true""")
        assert(c.removedOptionRows.isEmpty())
        assert(c.rows == base.rows)
        assert(c.rows.any { it.isTs1192For("d_esm.ts") })
        assert(c.helpers == base.helpers)
    }

    // ── the node16+ arms of tsgo's rule ───────────────────────────────────────────

    private val esmScope = mapOf(
        "/proj/src/package.json" to """{ "type": "module" }""",
        "/proj/src/cjs/package.json" to """{ "type": "commonjs" }""",
        "/proj/src/cjs/lib.ts" to "export const v = 1;\n",
        "/proj/src/cjs/libd.d.ts" to "export declare const w: number;\n",
        "/proj/src/other.ts" to "export const o = 1;\n",
        "/proj/src/d_lib.ts" to "import d from \"./cjs/lib.js\"; d;\n",
        "/proj/src/d_libd.ts" to "import d from \"./cjs/libd.js\"; d;\n",
        "/proj/src/d_other.ts" to "import d from \"./other.js\"; d;\n",
    )

    /**
     * tsgo `nodenext`, the importer in a `"type": "module"` scope: a CommonJS-scoped
     * module — a `.ts` with named exports only, or a `.d.ts` — ALWAYS has a synthetic
     * default when imported into ESM ("In Node.js, CommonJS modules always have a
     * synthetic default when imported into ESM"), while two ES modules never do:
     * measured, the only row is `d_other.ts(1,8): error TS1192`.
     */
    @Test
    fun `nodenext - a CommonJS-scoped module imported into an ES module always has a synthetic default and two ES modules never do`() {
        val c = cell("nodenext", extra = esmScope)
        val ours = c.rows.filter { it.file in setOf("d_lib.ts", "d_libd.ts", "d_other.ts") }
        assert(ours.size == 1)
        assert(ours.single().isTs1192For("d_other.ts"))
    }
}
