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
 * (LEGACY.1)(d) On the PROJECT path — a real directory with a `tsconfig.json`, through
 * `TsConfigLoader` / [ProjectCompiler] — every deprecation / removed-option row
 * (TS5101/TS5102 at the option's KEY, TS5107/TS5108 at its VALUE) is anchored where
 * tsgo 7.0.2 anchors it. Until this class existed the loader never populated
 * `tsconfigOptionPositions`, so every such row a real build reported was FILE-LESS
 * (` - error TS5107: …`) while tsgo printed `tsconfig.json(1,112): error TS5108: …`.
 *
 * **WHY THIS IS A PROJECT FIXTURE.** The corpus harness's embedded
 * `@Filename: tsconfig.json` fixtures go through a different producer
 * (`applyTsconfigOptions`), which always carried positions — which is why every
 * earlier pin exercised that path and the project path stayed file-less for the
 * whole life of the diagnostics. Both producers now share one scanner
 * (`tsconfigOptionPositionsOf`), and this class is the only harness with a directory
 * to read a `tsconfig.json` from.
 *
 * **EVERY EXPECTED VALUE IS tsgo 7.0.2's**, read with `tools/tsgo-7.0.2/lib/tsc
 * --pretty false -p .` over the SAME texts written to disk (line/column), with the
 * squiggle width read off `--pretty`: a TS5108 anchors at the VALUE token and spans
 * exactly it (`false` = 5, `"amd"` = 5, `"node"` = 6); a TS5102 anchors at the KEY
 * including its quotes (`"downlevelIteration"` = 20, `"baseUrl"` = 9); an option set
 * but not WRITTEN in the root `tsconfig.json` (inherited through `extends`) anchors at
 * the root's `"compilerOptions"` key (17), in the ROOT file; and when the root has no
 * `compilerOptions` at all the row is file-less — tsgo's `createDiagnosticForOption`
 * / `createCompilerOptionsDiagnostic` (`program.go:772-790`). The CODE differs from
 * (P18.133) tsgo's own at the `"7.0"` default `simulatedVersion` (TS5108/TS5102 — the
 * queue's BLOCKED-PENDING-USER default); the `typeScriptVersion` pin shows the row is
 * tsgo's byte for byte once that flag says 7.0.
 */
class ProjectTsconfigOptionAnchorTest {

    private fun build(vararg files: Pair<String, String>): List<Diagnostic> =
        ProjectCompiler(InMemoryVfs(mapOf("/proj/a.ts" to "export const a = 1;\n", *files)))
            .build("/proj", noEmit = true)
            .diagnostics

    private fun oneLine(option: String) =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true, "noEmit": true, $option } }"""

    private val root = "/proj/tsconfig.json"

    /** tsgo: `tsconfig.json(1,112)`, squiggle `~~~~~` under `false`. */
    @Test
    fun `alwaysStrict false anchors at its value token in the project's tsconfig`() {
        val row = build(root to oneLine(""""alwaysStrict": false""")).singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 112)
        assert(row.start == 111)
        assert(row.length == 5)
    }

    /**
     * With the compiler told it is TypeScript 7 the row is tsgo's exactly:
     * `tsconfig.json(1,112): error TS5108: Option 'alwaysStrict=false' has been
     * removed. Please remove it from your configuration.` — the trailing
     * `typeScriptVersion` key is after the anchored token, so every position above
     * is unchanged by it.
     *
     * (P18.133) Since the default moved to `"7.0"` this states the same answer as the pin
     * above; it is kept because it says so EXPLICITLY, which is what distinguishes "the
     * default selects the removed ladder" from "the removed ladder is all there is". The
     * control below is the other half of that.
     */
    @Test
    fun `under typeScriptVersion 7 0 the alwaysStrict row is tsgo's TS5108 byte for byte`() {
        val diagnostics = build(root to oneLine(""""alwaysStrict": false, "typeScriptVersion": "7.0""""))
        val row = diagnostics.singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(
            row.message == "Option 'alwaysStrict=false' has been removed. " +
                "Please remove it from your configuration.",
        )
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 112)
        assert(row.length == 5)
        assert(diagnostics.none { it.code == 5107 })
    }

    /**
     * (P18.133) Control: an EXPLICIT `typeScriptVersion` below `7.0` still selects the
     * TypeScript 6 ladder, **at exactly the same anchor** — which is what makes the whole
     * class's subject (WHERE a removed-option row lands) independent of WHICH ladder produced
     * it, and what an ablation restoring the old `?: "6.0"` default cannot redden.
     */
    @Test
    fun `control - an explicit typeScriptVersion 6 0 keeps the TS5107 ladder at the same anchor`() {
        val diagnostics = build(root to oneLine(""""alwaysStrict": false, "typeScriptVersion": "6.0""""))
        val row = diagnostics.singleOrNull { it.code == 5107 }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 112)
        assert(row.length == 5)
        assert(diagnostics.none { it.code == 5108 })
    }

    /** tsgo: `tsconfig.json(1,115)`, width 5. */
    @Test
    fun `esModuleInterop false anchors at its value token`() {
        val row = build(root to oneLine(""""esModuleInterop": false""")).singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 115)
        assert(row.length == 5)
    }

    /** tsgo: `tsconfig.json(1,128)`, width 5. */
    @Test
    fun `allowSyntheticDefaultImports false anchors at its value token`() {
        val row = build(root to oneLine(""""allowSyntheticDefaultImports": false""")).singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 128)
        assert(row.length == 5)
    }

    /**
     * The KEY-anchored ladder: tsgo reports `downlevelIteration` (TS5102 there, TS5101
     * here at the 6.0 default) at `tsconfig.json(1,96)` with the squiggle under the
     * whole quoted key, 20 characters.
     */
    @Test
    fun `downlevelIteration anchors at its key token including the quotes`() {
        val row = build(root to oneLine(""""downlevelIteration": true""")).singleOrNull { it.code == 5102 }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 96)
        assert(row.start == 95)
        assert(row.length == 20)
    }

    /** tsgo: `tsconfig.json(1,96)` under `"baseUrl"`, width 9. */
    @Test
    fun `baseUrl anchors at its key token`() {
        val row = build(root to oneLine(""""baseUrl": "."""")).singleOrNull { it.code == 5102 }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 96)
        assert(row.length == 9)
    }

    /** tsgo: `tsconfig.json(1,54)` under `"amd"`, width 5 (a string value keeps its quotes). */
    @Test
    fun `module amd anchors at its quoted value`() {
        val config = """{ "compilerOptions": { "target": "es2020", "module": "amd", "strict": true, "noEmit": true } }"""
        val row = build(root to config).singleOrNull { it.code == 5108 && it.message.contains("module=AMD") }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 54)
        assert(row.length == 5)
    }

    /** tsgo: `tsconfig.json(1,34)` under `"es5"`, width 5. */
    @Test
    fun `target es5 anchors at its quoted value`() {
        val config = """{ "compilerOptions": { "target": "es5", "module": "esnext", "strict": true, "noEmit": true } }"""
        val row = build(root to config).singleOrNull { it.code == 5108 && it.message.contains("target=ES5") }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 34)
        assert(row.length == 5)
    }

    /** tsgo: `tsconfig.json(1,118)` under `"node"`, width 6. */
    @Test
    fun `moduleResolution node anchors at its quoted value`() {
        val config = """{ "compilerOptions": { "target": "es2020", "module": "commonjs", "strict": true, "noEmit": true, "moduleResolution": "node" } }"""
        val row = build(root to config).singleOrNull { it.code == 5108 && it.message.contains("moduleResolution=node10") }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 1)
        assert(row.character == 118)
        assert(row.length == 6)
    }

    /**
     * A `compilerOptions` spread over several lines: tsgo `tsconfig.json(7,23)` for the
     * `false` after `"alwaysStrict":   ` (the extra spaces are deliberate — the anchor
     * is the VALUE token, not `key.length + 3`) and `(8,24)` for `esModuleInterop`'s.
     */
    @Test
    fun `a multi-line compilerOptions anchors each option on its own line`() {
        val config = "{\n  \"compilerOptions\": {\n    \"target\": \"es2020\",\n    \"module\": \"esnext\",\n" +
            "    \"strict\": true,\n    \"noEmit\": true,\n    \"alwaysStrict\":   false,\n" +
            "    \"esModuleInterop\": false\n  }\n}"
        val diagnostics = build(root to config)
        val strict = diagnostics.singleOrNull { it.code == 5108 && it.message.contains("alwaysStrict=false") }
        val interop = diagnostics.singleOrNull { it.code == 5108 && it.message.contains("esModuleInterop=false") }
        assert(strict != null)
        assert(strict.fileName == root)
        assert(strict.line == 7)
        assert(strict.character == 23)
        assert(strict.length == 5)
        assert(interop != null)
        assert(interop.fileName == root)
        assert(interop.line == 8)
        assert(interop.character == 24)
        assert(interop.length == 5)
    }

    /** A CRLF config: tsgo `tsconfig.json(4,21)` — the `\r` is not a column. */
    @Test
    fun `a CRLF tsconfig counts lines by CRLF and columns without the CR`() {
        val config = "{\r\n  \"compilerOptions\": {\r\n    \"target\": \"es2020\", \"module\": \"esnext\", " +
            "\"strict\": true, \"noEmit\": true,\r\n    \"alwaysStrict\": false\r\n  }\r\n}"
        val row = build(root to config).singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.fileName == root)
        assert(row.line == 4)
        assert(row.character == 21)
        assert(row.length == 5)
    }

    private val base = "/proj/base.json"
    private val baseConfig =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true, "noEmit": true, "alwaysStrict": false } }"""

    /**
     * An option INHERITED through `extends` is reported in the ROOT `tsconfig.json`, at
     * its `"compilerOptions"` key (tsgo `tsconfig.json(1,29)`, squiggle 17 wide); an
     * option the root writes ITSELF anchors at that value (`(1,69)`, 5). The extended
     * file is never named — tsgo consults only the root's object literal.
     */
    @Test
    fun `an option inherited through extends anchors at the root's compilerOptions key`() {
        val diagnostics = build(
            base to baseConfig,
            root to """{ "extends": "./base.json", "compilerOptions": { "esModuleInterop": false } }""",
        )
        val inherited = diagnostics.singleOrNull { it.code == 5108 && it.message.contains("alwaysStrict=false") }
        val own = diagnostics.singleOrNull { it.code == 5108 && it.message.contains("esModuleInterop=false") }
        assert(inherited != null)
        assert(inherited.fileName == root)
        assert(inherited.line == 1)
        assert(inherited.character == 29)
        assert(inherited.start == 28)
        assert(inherited.length == 17)
        assert(own != null)
        assert(own.fileName == root)
        assert(own.line == 1)
        assert(own.character == 69)
        assert(own.length == 5)
        assert(diagnostics.none { it.fileName == base })
    }

    /**
     * The same fallback carries the KEY ladder: `downlevelIteration` and `baseUrl`
     * inherited from `base.json` into a root whose `compilerOptions` is on line 3 —
     * tsgo `tsconfig.json(3,3)` for all three rows, width 17.
     */
    @Test
    fun `inherited key-anchored options fall back to the root's compilerOptions key too`() {
        val diagnostics = build(
            base to oneLine(""""alwaysStrict": false, "downlevelIteration": true, "baseUrl": ".""""),
            root to "{\n  \"extends\": \"./base.json\",\n  \"compilerOptions\": {\n    \"strict\": true\n  }\n}",
        )
        val rows = diagnostics.filter { it.code == 5102 || it.code == 5108 }
        assert(rows.size == 3)
        assert(rows.all { it.fileName == root && it.line == 3 && it.character == 3 && it.length == 17 })
    }

    /**
     * Control — a row with NO tsconfig provenance keeps its file-less form: the root
     * `{ "extends": "./base.json" }` has no `compilerOptions` key to fall back to, so
     * tsgo prints ` error TS5108: Option 'alwaysStrict=false' …` with no file, and so
     * do we. Pinned so the fallback can never invent a position out of the extended
     * file, which is the one place a wrong implementation would anchor it.
     */
    @Test
    fun `control - an inherited option with no compilerOptions in the root stays file-less`() {
        val diagnostics = build(
            base to oneLine(""""alwaysStrict": false, "downlevelIteration": true"""),
            root to """{ "extends": "./base.json" }""",
        )
        val rows = diagnostics.filter { it.code == 5102 || it.code == 5108 }
        assert(rows.size == 2)
        assert(rows.all { it.fileName == null && it.line == null && it.character == null && it.length == null })
    }

    /**
     * An option the root OVERRIDES is reported against the root's own value and the
     * base's setting draws nothing: tsgo `tsconfig.json(1,91)` for `esModuleInterop`
     * and no `alwaysStrict` row at all.
     */
    @Test
    fun `a root override anchors at the root's own value and silences the base's setting`() {
        val diagnostics = build(
            base to baseConfig,
            root to """{ "extends": "./base.json", "compilerOptions": { "alwaysStrict": true, "esModuleInterop": false } }""",
        )
        val rows = diagnostics.filter { it.code == 5108 }
        assert(rows.size == 1)
        assert(rows[0].message.contains("esModuleInterop=false"))
        assert(rows[0].fileName == root)
        assert(rows[0].line == 1)
        assert(rows[0].character == 91)
        assert(rows[0].length == 5)
    }

    @Test
    fun `control - alwaysStrict true draws no row`() {
        assert(build(root to oneLine(""""alwaysStrict": true""")).none { it.code == 5107 || it.code == 5108 })
    }
}
