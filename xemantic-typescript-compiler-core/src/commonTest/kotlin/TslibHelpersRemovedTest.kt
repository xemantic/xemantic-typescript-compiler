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
 * (LEGACY.1)(j3), 2026-09-16 — the `importHelpers` ES5 helper arms (TS2354 / TS2343 for
 * `__extends`, `__generator`, `__makeTemplateObject`, `__assign`), decided against tsgo 7.0.2's
 * helper table.
 *
 * The table is tsgo's `checkExternalEmitHelpers` (`checker.go:28333`, the ONE site emitting
 * TS2354/TS2343) and the `ExternalEmitHelpers` flags it can be asked for (`checker/types.go:
 * 114-137`): `__rest` (< ES2018), `__awaiter` (< ES2017), `__await`/`__asyncGenerator`/
 * `__asyncDelegator`/`__asyncValues` (< ES2018), `__decorate`/`__metadata`/`__param` (legacy
 * decorators, any target), `__exportStar`/`__importStar`/`__importDefault` (CommonJS emit),
 * the class-private-field helpers, `__setFunctionName`/`__propKey`, the `using` helpers and
 * `__makeTemplateObject` — the last with a flag and NO caller. `__extends`, `__generator` and
 * `__assign` have no flag at all; the tsgo checker never spells those names. Measured over 30
 * cells (tslib absent / exporting nothing / exporting `__awaiter` only, x es5/es2015/es2016/
 * es2017/esnext, x commonjs/esnext) through tsgo's LSP: `class B extends A`, `{ ...o }`,
 * `` tag`x` `` and `function* g()` are named in NO cell, including a written es5, where this
 * compiler printed a TS2354 at the heritage clause and — with a tslib in the program — a
 * TS2343 for each of the four. DELETED, each pinned silent at es5 below.
 *
 * What survives is exactly tsgo's table, and the controls pin it at its bounds: `__awaiter`
 * (TS2354 at es5/es2016, silent at es2017), `__rest` at es5, the async generator pair without
 * its former `__generator` companion, and `__decorate` on a class that ALSO extends (the
 * former TS2354 `__extends` arm used to pre-empt it). Two more alignments the same table
 * forced, each pinned RED-before: the variable-form `__rest` walk now carries tsgo's ES2018
 * bound (only the parameter form had it), and a missing helper is reported ONCE PER FILE at
 * its first site (`sourceFileLinks.requestedExternalEmitHelpers`, `checker.go:28346`) where
 * ours dedup'd `__awaiter` per tslib install and nothing else at all — which is what had kept
 * `tslibMissingHelper` and `tslibMultipleMissingHelper` in `tsgoPendingBaselines`; both are
 * active corpus rows again.
 *
 * The two `effectiveTarget` reads in this family (`needsAwaiterHelper`, the parameter `__rest`
 * gate) now read [CompilerOptions.defaultedTarget] — the checker's language version — which
 * answers identically on every input (both map an unset target to ES2024 and a written es5
 * lands below ES2017/ES2018 either way).
 *
 * `@ignoreDeprecations: 6.0` ([DOWNLEVEL_ES5]) keeps TS5107 out of the exact-list assertions.
 * The corpus is a CONTROL here: every `@importHelpers` case at es5 is a `usesUnsupportedOption`
 * skip (35 active subtests carry the option, all at es2015+), so these pins are the whole
 * gate; every non-control pin was RED on the pre-change binary.
 */
class TslibHelpersRemovedTest {

    private val helpers = "\n// @module: commonjs\n// @importHelpers: true"
    private val es5 = DOWNLEVEL_ES5 + helpers
    private fun at(target: String) = "// @strict: true\n// @target: $target$helpers"

    /** The two-file prefix: an installed tslib exporting NOTHING, then the module under test. */
    private val emptyTslib = "// @Filename: node_modules/tslib/index.d.ts\n            export {};\n"

    private val ts2354 = "This syntax requires an imported helper but module 'tslib' cannot be found."
    private fun ts2343(helper: String) =
        "This syntax requires an imported helper named '$helper' which does not exist in 'tslib'. Consider upgrading your version of 'tslib'."

    // ── DELETED: `__extends` — no `ExternalEmitHelpers` flag, never requested ──────────

    @Test
    fun `an es5 module class extends with no tslib in the program reports no TS2354`() {
        val d = diagnose(
            """
            export {};
            class A {}
            class B extends A {}
            """,
            es5,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an es5 module class extends with an empty tslib reports no TS2343 __extends`() {
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: a.ts
            export {};
            class A {}
            class B extends A {}
            """,
            es5,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an es5 decorated class that also extends is TS2354 at the decorator - the extends arm no longer pre-empts it`() {
        // tsgo: `decoext.ts(4,1): error TS2354` at es5, es2016 and es2017 alike (the decorator
        // row is target-free); ours used to report the heritage clause instead.
        val d = diagnose(
            """
            export {};
            declare function dec(t: any): any;
            class A {}
            @dec class C extends A {}
            """,
            es5 + "\n// @experimentalDecorators: true",
        )
        assert(d.size == 1)
        assert(d[0].code == 2354)
        assert(d[0].message == ts2354)
        assert(d[0].line == 4)
        assert(d[0].character == 1)
        assert(d[0].length == 4)
    }

    // ── DELETED: `__generator` — no flag; tsgo lowers no generator at any target ────────

    @Test
    fun `an es5 async function with an empty tslib reports __awaiter alone - no __generator companion`() {
        // tsgo (empty-es5-*): exactly `asyncfn.ts(2,16): TS2343 '__awaiter'`.
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: asyncfn.ts
            export {};
            async function asyF() { await 1; }
            """,
            es5,
        )
        assert(d.size == 1)
        assert(d[0].code == 2343)
        assert(d[0].message == ts2343("__awaiter"))
        assert(d[0].line == 2)
        assert(d[0].character == 16)
        assert(d[0].length == 4)
    }

    @Test
    fun `an es5 async function with a tslib exporting __awaiter reports nothing`() {
        val d = diagnose(
            """
            // @Filename: node_modules/tslib/index.d.ts
            export declare function __awaiter(thisArg: any, _arguments: any, P: Function, generator: Function): any;

            // @Filename: asyncfn.ts
            export {};
            async function asyF() { await 1; }
            """,
            es5,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an es5 async generator with an empty tslib reports __asyncGenerator and __await - no __generator`() {
        // tsgo (empty-es5-*): `asyncgen.ts(2,17)` twice — `__asyncGenerator` and `__await`.
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: asyncgen.ts
            export {};
            async function* asyG() { yield 1; }
            """,
            es5,
        )
        val rows = d.map { Triple(it.code, it.message, it.line to it.character) }
        assert(rows == listOf(
            Triple(2343, ts2343("__asyncGenerator"), 2 to 17),
            Triple(2343, ts2343("__await"), 2 to 17),
        ))
    }

    @Test
    fun `control - a plain generator function at es5 never had a helper arm`() {
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: gen.ts
            export {};
            function* genG() { yield 1; }
            """,
            es5,
        )
        assert(d.isEmpty())
    }

    // ── DELETED: `__makeTemplateObject` — a flag with no caller ─────────────────────────

    @Test
    fun `an es5 tagged template with an empty tslib reports no __makeTemplateObject in either position`() {
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: tpl.ts
            export {};
            declare function tplTag(s: TemplateStringsArray): string;
            const tplR = tplTag`x`;
            tplTag`y`;
            const tplP = (tplTag`z`) as string;
            """,
            es5,
        )
        assert(d.isEmpty())
    }

    // ── DELETED: `__assign` — emitted by tsgo's spread lowering below ES2018, never checked ──

    @Test
    fun `an es5 object spread with an empty tslib reports no __assign in any position`() {
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: spread.ts
            export {};
            declare const spO: { a: number };
            const spR = { ...spO };
            const spN = { a: { ...spO } };
            ({ ...spO });
            const spC = ({ ...spO }) as {};
            const spS = { ...spO } satisfies {};
            """,
            es5,
        )
        assert(d.isEmpty())
    }

    // ── KEPT: `__rest` below ES2018 — tsgo's `ObjectSpreadRest` bound, now on BOTH binding forms ──

    @Test
    fun `control - an es5 object rest binding with an empty tslib is TS2343 __rest at the name`() {
        // tsgo (empty-es5-*): exactly `objrest.ts(3,20): TS2343 '__rest'`.
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: objrest.ts
            export {};
            declare const orO: { a: number; b: number };
            const { a: orA, ...orRest } = orO;
            """,
            es5,
        )
        assert(d.size == 1)
        assert(d[0].code == 2343)
        assert(d[0].message == ts2343("__rest"))
        assert(d[0].line == 3)
        assert(d[0].character == 20)
    }

    @Test
    fun `the variable-form __rest walk is bounded at ES2018 - the same object rest binding at es2018 reports nothing`() {
        // tsgo (`checker.go:5801`) gates EVERY binding element on `< ObjectSpreadRest`; ours
        // gated the parameter form only and reported a variable pattern's `__rest` at every
        // target (RED on the pre-change binary).
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: objrest.ts
            export {};
            declare const orO: { a: number; b: number };
            const { a: orA, ...orRest } = orO;
            """,
            at("es2018"),
        )
        assert(d.isEmpty())
    }

    // ── ALIGNED: a missing helper is reported ONCE PER FILE, at its first site ───────────

    @Test
    fun `a missing __awaiter is reported once per file - two files are two rows and two sites in one file are one`() {
        // The corpus's `tslibMissingHelper`, in miniature: tsgo dedups on the SOURCE FILE's
        // links (`checker.go:28346`), so `/package1/index.ts(2,16)` AND `/package2/index.ts(2,16)`,
        // the second async function of package1 adding nothing. The former per-tslib-install key
        // lost the package2 row (a `tsgoPendingBaselines` entry until this round).
        val d = diagnose(
            """
            // @Filename: /node_modules/tslib/index.d.ts
            export {};
            // @Filename: /package1/index.ts
            export {};
            async function foo(): Promise<void> {}
            async function bar(): Promise<void> {}
            // @Filename: /package2/index.ts
            export {};
            async function foo(): Promise<void> {}
            """,
            at("es2016"),
        )
        val rows = d.map { Triple(it.fileName, it.line to it.character, it.message) }
        assert(rows == listOf(
            Triple("/package1/index.ts", 2 to 16, ts2343("__awaiter")),
            Triple("/package2/index.ts", 2 to 16, ts2343("__awaiter")),
        ))
    }

    @Test
    fun `a missing __rest is reported once per file at the first of four sites`() {
        // tsgo at es5 on this file: exactly `objrest.ts(3,20)`; ours used to print all four.
        val d = diagnose(
            """
            $emptyTslib
            // @Filename: objrest.ts
            export {};
            declare const orO: { a: number; b: number };
            const { a: orA, ...orRest } = orO;
            const { a, ...rest } = orO;
            let { ...r2 } = orO;
            function pf({ a: pa, ...prest }: { a: number; b: number }) { }
            """,
            es5,
        )
        assert(d.map { it.code } == listOf(2343))
        assert(d[0].message == ts2343("__rest"))
        assert(d[0].line == 3)
        assert(d[0].character == 20)
    }

    // ── KEPT: `__awaiter` below ES2017 — tsgo's `AsyncFunctions` bound, read on defaultedTarget ──

    private val asyncShape = """
        export {};
        async function asyF() { await 1; }
    """

    @Test
    fun `control - an es5 async function with no tslib in the program is TS2354 at the name`() {
        // tsgo (none-es5-*): exactly `asyncfn.ts(2,16): TS2354`.
        val d = diagnose(asyncShape, es5)
        assert(d.size == 1)
        assert(d[0].code == 2354)
        assert(d[0].message == ts2354)
        assert(d[0].line == 2)
        assert(d[0].character == 16)
        assert(d[0].length == 4)
    }

    @Test
    fun `control - the awaiter bound is ES2017 - es2016 reports the TS2354 and es2017 reports nothing`() {
        val below = diagnose(asyncShape, at("es2016"))
        assert(below.map { it.code } == listOf(2354))
        val atBound = diagnose(asyncShape, at("es2017"))
        assert(atBound.isEmpty())
    }
}
