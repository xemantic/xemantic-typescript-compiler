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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (LEGACY.1)(h) `outFile` is a REMOVED option in TypeScript 7. tsgo 7.0.2 reads it in
 * exactly three places (`program.go:836` reports `TS5102 Option 'outFile' has been
 * removed. Please remove it from your configuration.` at the KEY; `:1052-1073` name it in
 * the TS5011 layout row; `tsconfigparsing.go:1588` substitutes `${configDir}`) and then
 * compiles as if it were absent: one JavaScript (and one `.d.ts`) PER SOURCE, no bundle, in
 * the program's own order. **Every expected value here is tsgo 7.0.2's**, measured
 * 2026-09-15 on 34 scratch projects — `outFile: "bundle.js"` × `module` ∈ {unset, commonjs,
 * esnext, system, amd, none} × with/without `/// <reference path>` × `declaration` — through
 * `tsc -p . --outDir --listFiles`: every cell emits `a.js b.js m.js n.js` (plus the four
 * `.d.ts` under `declaration`), never a `bundle.js`, and a referenced file precedes its
 * referrer in `--listFiles` WITH and WITHOUT `outFile`. TS6082 (*Only 'amd' and 'system'
 * modules are supported alongside --outFile*) has no emitter anywhere in tsgo; `module: none`
 * is not a value at all (TS6046); `out` is an UNKNOWN option (TS5023).
 *
 * The bundling concatenation itself was deleted 2026-07-02; what this round deleted is the
 * ORDERING and GATING that only an `outFile` build reached — the single-file output NAME
 * (`cpcCompileSingleFile`), the `commonSourceDir` skip, the outFile-only topological
 * transform order, the `.js`-input admission by `outFile` alone, the `module: none` +
 * `outFile` drop of a `.js` module file, and the TS5074 `outFile` conjunct (tsgo's rule is
 * `TsBuildInfoFile == "" && Incremental && ConfigFilePath == ""`, `program.go:912`). The
 * `/// <reference path>` dependency edges are KEPT: they decide program order without
 * `outFile` too (`--listFiles` on tsgo, `sortedTsFiles` here). The parse of `outFile`, the
 * `out` option and the TS5101/TS5102 row survive: this round removes BEHAVIOUR only.
 *
 * [ProjectOutFileRemovedTest] in the `-project` module pins the key-anchored row and the
 * written files on a real directory.
 */
class OutFileRemovedTest {

    private fun compile(source: String): CompilationResult =
        TypeScriptCompiler().compile(source.trimIndent(), "input.ts")

    private fun names(r: CompilationResult): List<String> = r.jsOutputs.map { it.first }

    // ── the single-file output is named after the SOURCE, never after outFile ─────────────

    /** tsgo: `outFile: bundle.js` over one `solo.ts` writes `solo.js` (measured `onefile_outfile`). Before this round the harness answered `bundle.js`. */
    @Test
    fun `a single-file compile under outFile names its JavaScript after the source`() {
        val r = compile(
            """
            // @outFile: bundle.js
            var solo: number = 1;
            """
        )
        assert(names(r) == listOf("input.js"))
    }

    // ── per-file emit equal to the no-outFile emit ────────────────────────────────────────

    private val twoFile = """
        // @Filename: helper.ts
        export function helper(): number { return 1; }
        // @Filename: a.ts
        import { helper } from "./helper";
        export const answer = helper();
    """

    /** Control since the 2026-07-02 concatenation removal: the JavaScript of an `outFile` program IS the JavaScript of the program without it. */
    @Test
    fun `control - a multi-file outFile program emits one JavaScript per source equal to the no-outFile emit`() {
        val plain = compile("// @module: commonjs\n$twoFile")
        val bundled = compile("// @module: commonjs\n// @outFile: bundle.js\n$twoFile")
        assert(plain.jsOutputs.size == 2)
        assert(bundled.jsOutputs == plain.jsOutputs)
        assert(names(bundled).none { it.endsWith("bundle.js") })
    }

    // ── a .js input is admitted to the emit by outDir, never by outFile alone ─────────────

    private val jsInput = """
        // @allowJs: true
        // @Filename: aux.js
        export const q = 1;
        // @Filename: entry.ts
        export const e: number = 1;
    """

    /** tsgo emits per file, so with no `outDir` a `.js` input would overwrite itself and is never written; `outFile` used to stand in for an output location here. */
    @Test
    fun `a js input with no outDir is not emitted whatever outFile says`() {
        val r = compile("// @module: esnext\n// @outFile: bundle.js\n$jsInput")
        assert(names(r) == listOf("entry.js"))
    }

    @Test
    fun `control - a js input with an outDir is emitted`() {
        val r = compile("// @module: esnext\n// @outDir: out\n$jsInput")
        assert(names(r).sorted() == listOf("aux.js", "entry.js"))
    }

    // ── module none + outFile no longer drops a .js module file ───────────────────────────

    /** tsgo (`outfile_none_allowJs_auxjs`): `module: none` is TS6046 and `src/aux.js` is emitted beside `src/entry.js`; the old arm skipped every `.js` carrying module statements under that pair. */
    @Test
    fun `module none plus outFile no longer drops a js module file from the emit`() {
        val r = compile("// @module: none\n// @outDir: out\n// @outFile: bundle.js\n$jsInput")
        assert(names(r).sorted() == listOf("aux.js", "entry.js"))
        val aux = r.jsOutputs.first { it.first == "aux.js" }.second
        assert(aux.contains("q = 1"))
    }

    // ── the commonSourceDir layout does not depend on outFile ─────────────────────────────

    private val nested = """
        // @outDir: /out
        // @fullEmitPaths: true
        // @Filename: /src/a/x.ts
        export const x: number = 1;
        // @Filename: /src/b/y.ts
        export const y: number = 2;
    """

    /** tsgo keeps `src/a/x.js` / `src/b/y.js` under `outDir` in every `outFile` cell; the `commonSourceDir` computation used to be skipped "because outFile concatenates". */
    @Test
    fun `outFile no longer flattens the outDir layout`() {
        val plain = names(compile("// @module: esnext\n$nested"))
        val bundled = names(compile("// @module: esnext\n// @outFile: bundle.js\n$nested"))
        assert(plain.sorted() == listOf("/out/a/x.js", "/out/b/y.js"))
        assert(bundled == plain)
    }

    // ── TS5074 has no outFile conjunct in tsgo ────────────────────────────────────────────

    /** tsgo `program.go:912`: `TsBuildInfoFile == "" && Incremental && ConfigFilePath == ""` — `outFile` is not read. */
    @Test
    fun `incremental with outFile and no config file still reports TS5074`() {
        val r = compile(
            """
            // @incremental: true
            // @outFile: bundle.js
            var i: number = 1;
            """
        )
        assert(r.diagnostics.count { it.code == 5074 } == 1)
    }

    @Test
    fun `control - incremental alone reports TS5074`() {
        val r = compile(
            """
            // @incremental: true
            var i: number = 1;
            """
        )
        assert(r.diagnostics.count { it.code == 5074 } == 1)
    }

    // ── the option row survives: TS5101 at the 6.0 default and tsgo's TS5102 at 7.0 ───────
    //
    // Stash-ablation 2026-09-15 on the pre-change binary: RED — the single-file name, the
    // no-outDir `.js` admission, the `module: none` drop, the outDir layout, the TS5074
    // conjunct and the 7.0 row's name half (6 of 13); the seven `control -` pins are green on
    // both arms by design. The topological transform order has no observable and so no pin.

    @Test
    fun `control - outFile reports TS5101 at the 6 0 default`() {
        val r = compile(
            """
            // @outFile: bundle.js
            var d: number = 1;
            """
        )
        val row = r.diagnostics.single { it.code == 5101 || it.code == 5102 }
        assert(row.code == 5101)
        assert(row.message.startsWith("Option 'outFile' is deprecated"))
    }

    /** tsgo: `Option 'outFile' has been removed. Please remove it from your configuration.` — and the emit beside the row is per file; the name half is what the pre-change binary fails. */
    @Test
    fun `under typeScriptVersion 7 0 outFile is tsgo's TS5102 byte for byte and the emit is named after the source`() {
        val r = compile(
            """
            // @typeScriptVersion: 7.0
            // @outFile: bundle.js
            var d: number = 1;
            """
        )
        val row = r.diagnostics.single { it.code == 5101 || it.code == 5102 }
        assert(row.code == 5102)
        assert(row.message == "Option 'outFile' has been removed. Please remove it from your configuration.")
        assert(names(r) == listOf("input.js"))
    }

    /** tsgo: `out` is not in the option table at all — `TS5023 Unknown compiler option 'out'.` */
    @Test
    fun `control - out is an unknown option and never sets outFile`() {
        val r = compile(
            """
            // @out: bundle.js
            var d: number = 1;
            """
        )
        assert(r.diagnostics.count { it.code == 5023 } == 1)
        assert(r.diagnostics.single { it.code == 5023 }.message == "Unknown compiler option 'out'.")
        assert(r.diagnostics.none { it.code == 5101 || it.code == 5102 })
        assert(r.options.outFile == null)
        assert(names(r) == listOf("input.js"))
    }

    // ── /// <reference path> ORDER is a property of the program, kept without outFile ─────

    private val referenced = """
        // @Filename: a.ts
        /// <reference path="b.ts" />
        var aa: number = bb + 1;
        // @Filename: b.ts
        var bb: number = 1;
    """

    /** tsgo `--listFiles`: `src/b.ts` before `src/a.ts` in the `control_noOutFile_ref` cell and in every `outFile` cell alike. */
    @Test
    fun `control - a referenced file precedes its referrer without outFile`() {
        assert(names(compile(referenced)) == listOf("b.js", "a.js"))
    }

    @Test
    fun `control - a referenced file precedes its referrer under outFile too`() {
        assert(names(compile("// @outFile: bundle.js\n$referenced")) == listOf("b.js", "a.js"))
    }
}
