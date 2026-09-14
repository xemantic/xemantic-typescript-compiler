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
 * (LEGACY.0b) step 14, round (P18.99): three mechanisms where TypeScript 7 (tsgo 7.0.2, the
 * ONLY compatibility target) differs from the tsc-6 transcription this checker carried. Every
 * expectation below was read off `tools/tsgo-7.0.2/lib/tsc -p .` over the SAME fixture text
 * (the fixtures are the round's probe projects verbatim), never hand-derived — with ONE
 * stated exception: tsgo's CLI stops at syntactic errors, so a checker-side row that sits
 * beside a parser-side one in the same fixture is taken from tsgo's HARNESS baselines
 * (`importTypeAssertionDeprecation` for the dynamic-import TS2880 rows,
 * `jsEnumCrossFileExport` for the JSDoc-parser TS1003 row) and the KDoc of such a pin says so.
 *
 *  * **M1** — TS2880 *Import assertions have been replaced by import attributes* is an
 *    ERROR the tsgo PARSER reports at the `assert` keyword (width 6) for every clause form,
 *    and the checker reports at the `assert` KEY of a dynamic `import()` options object,
 *    once per object. Nothing consults `ignoreDeprecations` and nothing consults the module
 *    kind (tsc 6 silenced it under `"6.0"` and fired only for esnext-family modules).
 *  * **M2** — a JSDoc `@typedef {T}` with NO name is TS1003 *Identifier expected.* twice in a
 *    checkJs JS file: the REPARSER's row on the character BEFORE the missing name's position
 *    (`reparser.go` `checkNonIdentifierName`) and the JSDoc parser's row on the token AT
 *    that position (`program.go` appends `JSDocDiagnostics()` for checkJs files only). A
 *    plain `allowJs` file shows the reparser row alone. And a JS `type X` beside a
 *    `const X` is TS8008 alone — tsc 6's JS-only TS2451 pair is gone.
 *  * **M3** — TS2749 *'X' refers to a value, but is being used as a type here* reaches a
 *    value named in a NESTED JSDoc type position (a function-type parameter, a generic
 *    head, a type argument, an array element, a union member, a type-literal member, a
 *    function-type return, the root of a `@type`/`@returns`/`@typedef`), and the
 *    index-signature parameter type of a `@typedef {{ [key: foo] … }}` is TS1268 (TS1337
 *    only for a literal type or a type parameter) plus that TS2749.
 */
class TsgoStep14MechanismsTest {

    private val m1Directives = "// @strict: true\n// @module: esnext\n// @ignoreDeprecations: 6.0"

    private val m1Message =
        "Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'."

    // ── M1: TS2880 is unconditional, at the `assert` keyword ─────────────────────────────

    private val m1ClauseSource = """
        import json from "./p.json" assert { type: "json" };
        import * as ns from "./p.json" assert { type: "json" };
        export { default as q } from "./p.json" assert { type: "json" };
        import "./p.json" assert { type: "json" };
        import type X from "./p.json" assert { type: "json" };
        export * from "./p.json" assert { type: "json" };
        import w from "./p.json" with { type: "json" };
    """

    /**
     * tsgo `(1,29) (2,32) (3,41) (4,19) (5,31) (6,26)` under `ignoreDeprecations: "6.0"` —
     * the setting that silenced tsc 6 — including the side-effect `import "x" assert` and
     * the `export …` forms; the `with` clause on line 7 is silent.
     */
    @Test
    fun `M1 - every assert clause form is TS2880 at the keyword under ignoreDeprecations`() {
        val rows = diagnose(m1ClauseSource, directives = m1Directives).filter { it.code == 2880 }
        assert(rows.map { it.line } == listOf(1, 2, 3, 4, 5, 6))
        assert(rows.map { it.character } == listOf(29, 32, 41, 19, 31, 26))
        assert(rows.all { it.length == 6 })
        assert(rows.all { it.message == m1Message })
    }

    /** tsgo `(1,29)` under `module: commonjs` — the module kind does not gate the row. */
    @Test
    fun `M1 - the module kind does not gate TS2880`() {
        val rows = diagnose(
            """import json from "./p.json" assert { type: "json" };""",
            directives = "// @strict: true\n// @module: commonjs",
        ).filter { it.code == 2880 }
        assert(rows.size == 1)
        assert(rows.single().line == 1)
        assert(rows.single().character == 29)
        assert(rows.single().length == 6)
    }

    private val m1CallSource = """
        type A = import("./p.json", { assert: { "resolution-mode": "import" } }).X;
        const a = import("./p.json", { assert: { "resolution-mode": "import" } });
        const b = import("./p.json", { assert: { type: "json" }, assert: { type: "json" } });
        const c = import("./p.json", { "assert": { type: "json" } });
        const d = import("./p.json", { with: { type: "json" } });
        type B = import("./p.json", { with: { "resolution-mode": "import" } }).X;
        declare const assert: any;
        const e = import("./p.json", { assert });
    """

    private fun m1Call(): List<Diagnostic> = diagnose(m1CallSource, directives = m1Directives)

    /** tsgo `(1,31)` width 6 — the `assert` keyword, where tsc 6 squiggled the inner `{`. */
    @Test
    fun `M1 - the import type form anchors on the assert keyword`() {
        val row = m1Call().single { it.code == 2880 && it.line == 1 }
        assert(row.character == 31)
        assert(row.length == 6)
        assert(row.message == m1Message)
    }

    /**
     * The dynamic-import rows are tsgo's CHECKER rows (`checkImportCallExpression`, which
     * `break`s after the first `assert` property), so the CLI hides them behind line 1's
     * parser row; the harness baseline `importTypeAssertionDeprecation` carries them at the
     * key — `(4,31)`/`(5,31)` there, `(2,32)`/`(3,32)` for this fixture's longer specifier —
     * and the duplicated key on line 3 is ONE TS2880 beside the literal's own TS1117 `(3,58)`.
     */
    @Test
    fun `M1 - the call form reports the assert key once per options object`() {
        val rows = m1Call()
        val line2 = rows.filter { it.code == 2880 && it.line == 2 }
        val line3 = rows.filter { it.code == 2880 && it.line == 3 }
        assert(line2.size == 1)
        assert(line2.single().character == 32)
        assert(line2.single().length == 6)
        assert(line3.size == 1)
        assert(line3.single().character == 32)
        assert(rows.any { it.code == 1117 && it.line == 3 && it.character == 58 })
    }

    /** `with` clauses, a string-named `"assert"` key and a shorthand `{ assert }` are silent. */
    @Test
    fun `M1 negative control - with clauses and a string-named or shorthand assert stay silent`() {
        val rows = m1Call().filter { it.code == 2880 }
        assert(rows.none { it.line in setOf(4, 5, 6, 8) })
        val clause = diagnose(m1ClauseSource, directives = m1Directives).filter { it.code == 2880 }
        assert(clause.none { it.line == 7 })
    }

    /**
     * tsgo `tryParseImportAttributes` demands `!hasPrecedingLineBreak()` for `assert`: on a
     * new line it is not a clause at all — the statement ends and tsgo's TS1435 `(2,1)`
     * *Unknown keyword or identifier. Did you mean 'asserts'?* is what the leftover reports.
     */
    @Test
    fun `M1 negative control - an assert on a new line is not a clause`() {
        val rows = diagnose(
            "import json from \"./p.json\"\nassert { type: \"json\" };",
            directives = m1Directives,
        )
        assert(rows.none { it.code == 2880 })
        assert(rows.any { it.code == 1435 && it.line == 2 && it.character == 1 })
    }

    // ── M2: the nameless `@typedef` ──────────────────────────────────────────────────────

    private val jsCheck = "// @strict: false\n// @allowJs: true\n// @checkJs: true"
    private val jsPlain = "// @strict: false\n// @allowJs: true"

    private val m2Source = """
        /**
         * @typedef {string}
         */
        var a = 1;
        /** @typedef {string} 5 */
        var b = 1;
        /**
         * @typedef {Object}
         * @property {string} p
         */
        var c = 1;
        /** @typedef */
        var d = 1;
        /** @typedef {string} Name */
        var e = 1;
        /** @typedef {number} class */
        var f = 1;
        /** @typedef {{ endTime: number}} A.<b>*/
        var g = 1;
        /** @typedef {string}*/
        var h = 1;
    """

    private fun m2(): List<Diagnostic> = diagnose(m2Source, directives = jsCheck, fileName = "t.js").filter { it.code == 1003 }

    private fun m2Rows(line: Int): List<Pair<Int, Int>> = m2().filter { it.line == line }.map { (it.character ?: -1) to (it.length ?: -1) }.sortedBy { it.first }

    /**
     * tsgo `(2,20)` is the reparser's row on the closing `}`; `(2,21)` — the width-1
     * NEWLINE — is the JSDoc parser's row, which the harness prints for a checkJs file
     * (`jsEnumCrossFileExport` lines 14,20 and 14,21) and the CLI hides behind the first.
     */
    @Test
    fun `M2 - a nameless typedef at a line end is TS1003 on the closing brace and on the newline`() {
        assert(m2Rows(2) == listOf(20 to 1, 21 to 1))
        assert(m2().all { it.message == "Identifier expected." })
    }

    /** tsgo `(5,22)`: the space before the `5`; the parser row is the `5` itself, `(5,23)`. */
    @Test
    fun `M2 - a nameless typedef followed by a non-name reports the character before it`() {
        assert(m2Rows(5) == listOf(22 to 1, 23 to 1))
    }

    /** tsgo `(9,3)`: the space before the `@property` tag's `@` — a line-leading `*` is skipped. */
    @Test
    fun `M2 - a nameless object typedef with property children reports before the next tag`() {
        assert(m2Rows(9) == listOf(3 to 1, 4 to 1))
    }

    /**
     * A type-less `@typedef` has no type expression, so the reparser `break`s and only the
     * JSDoc parser's row remains — tsgo `(1,13)` on a fixture holding that tag alone.
     */
    @Test
    fun `M2 - a typedef with neither type nor name has the parser row only`() {
        assert(m2Rows(12) == listOf(13 to 1))
    }

    /**
     * tsgo `(20,21)`: the comment closes right after the `}`, so both rows would be the same
     * character and tsgo's `SortAndDeduplicateDiagnostics` keeps ONE.
     */
    @Test
    fun `M2 - a brace closed by the comment end is one row on the brace`() {
        assert(m2Rows(20) == listOf(21 to 1))
    }

    /** A named typedef, a keyword name (`class`), and a namespaced `A.<b>` name are silent. */
    @Test
    fun `M2 negative control - a named typedef stays silent`() {
        assert(m2().none { it.line in setOf(14, 16, 18) })
    }

    /**
     * `jsdocTypedefNoCrash`'s shape under `allowJs` alone: tsgo `(3,5)`, the second `}` of
     * the object type — and NO parser row, because `JSDocDiagnostics()` is checkJs-only.
     */
    @Test
    fun `M2 - without checkJs only the reparser row remains`() {
        val rows = diagnose(
            "/**\n * @typedef {{\n * }}\n */\nexport const foo = 5;",
            directives = jsPlain, fileName = "t.js",
        ).filter { it.code == 1003 }
        assert(rows.size == 1)
        assert(rows.single().line == 3)
        assert(rows.single().character == 5)
        assert(rows.single().length == 1)
    }

    /** A `.ts` file reparses no JSDoc and appends no JSDoc diagnostics — tsgo reports nothing. */
    @Test
    fun `M2 negative control - a ts file reports nothing`() {
        val rows = diagnose(m2Source, directives = "// @strict: false\n// @checkJs: true", fileName = "t.ts")
        assert(rows.none { it.code == 1003 })
    }

    /** `jsdocTypedefNoCrash2`'s pair: tsgo prints the TS8008 `(1,13)` and no TS2451. */
    @Test
    fun `M2 - a JS type alias beside a const is TS8008 alone`() {
        val rows = diagnose("export type foo = 5;\nexport const foo = 5;", directives = jsCheck, fileName = "t.js")
        assert(rows.none { it.code == 2451 })
        val row = rows.single { it.code == 8008 }
        assert(row.line == 1)
        assert(row.character == 13)
    }

    /** In a `.ts` file the alias and the const coexist (tsgo silent) while two consts stay TS2451. */
    @Test
    fun `M2 negative control - a ts type alias beside a const stays silent and two consts stay TS2451`() {
        val rows = diagnose("type bar = 5;\nconst bar = 5;\nconst baz = 1;\nconst baz = 2;", directives = "// @strict: true")
        assert(rows.none { (it.line ?: 0) <= 2 })
        assert(rows.filter { it.code == 2451 }.map { it.line to it.character } == listOf(3 to 7, 4 to 7))
    }

    // ── M3: TS2749 in nested JSDoc type positions ────────────────────────────────────────

    private val m3Source = """
        function Thing() {}
        class C {}
        /** @param {(x: Thing) => void} x */
        function f(x) {}
        /** @param {(x: C) => void} x */
        function g(x) {}
        /** @param {Thing} x */
        function h(x) {}
        function fn() {}
        /** @param {fn<T>} s */
        function k(s) {}
        /** @param {fn<string>} s */
        function k2(s) {}
        /**
         * @template T
         * @param {(x: T) => void} x
         */
        function tpl(x) {}
        /** @typedef {Object} Named */
        const Named = 1;
        /** @param {(x: Named) => void} x */
        function nm(x) {}
    """

    private fun m3(): List<Diagnostic> = diagnose(m3Source, directives = jsCheck, fileName = "t.js")

    private fun thingMessage(name: String) =
        "'$name' refers to a value, but is being used as a type here. Did you mean 'typeof $name'?"

    /** tsgo `(3,17)` inside the function type — beside the top-level `(7,13)` this checker had. */
    @Test
    fun `M3 - a value in a function-type parameter is TS2749`() {
        val rows = m3().filter { it.code == 2749 && it.message == thingMessage("Thing") }
        assert(rows.map { it.line to it.character }.sortedBy { it.first } == listOf(3 to 17, 7 to 13))
        assert(rows.all { it.length == 5 })
    }

    /** A class (`C`), a `@template T` and a `@typedef`-declared `Named` in the same position are silent. */
    @Test
    fun `M3 negative control - a type name in the same nested position stays silent`() {
        val rows = m3().filter { it.code == 2749 }
        assert(rows.none { it.line in setOf(5, 16, 21) })
    }

    /** tsgo `(10,13)` TS2749 on the head `fn` beside `(10,16)` TS2304 on its argument `T`. */
    @Test
    fun `M3 - a value as a generic head is TS2749 beside the unresolved argument`() {
        val rows = m3().filter { it.line == 10 }
        val head = rows.single { it.code == 2749 }
        assert(head.character == 13)
        assert(head.length == 2)
        assert(head.message == thingMessage("fn"))
        val arg = rows.single { it.code == 2304 }
        assert(arg.character == 16)
        assert(arg.message == "Cannot find name 'T'.")
    }

    /** tsgo `(12,13)` alone: `string` is a keyword type, not a name to find (an ours-only TS2304 before this round). */
    @Test
    fun `M3 - a keyword type argument is not an unresolved name`() {
        val rows = m3().filter { it.line == 12 }
        assert(rows.size == 1)
        assert(rows.single().code == 2749)
        assert(rows.single().character == 13)
    }

    private val m3PositionsSource = """
        function Thing() {}
        /** @param {Array<Thing>} a */
        function f1(a) {}
        /** @param {Thing[]} a */
        function f2(a) {}
        /** @param {Thing|string} a */
        function f3(a) {}
        /** @type {{a: Thing}} */
        var o = {a: 1};
        /** @returns {Thing} */
        function f4() {}
        /** @typedef {(x: Thing) => void} Cb */
        /** @param {function(Thing): void} a */
        function f5(a) {}
        /** @type {Thing} */
        var v = 1;
    """

    /**
     * tsgo `(2,19) (4,13) (6,13) (8,16) (10,15) (12,19) (15,12)`: type argument, array
     * element, union member, type-literal member, `@returns` root, function-type parameter
     * inside a `@typedef`, `@type` root. The closure-syntax line 13 is a tsgo parse error
     * (`'}' expected`) this checker does not reproduce and carries no TS2749 on either side.
     */
    @Test
    fun `M3 - every nested position tsgo reports is reported`() {
        val rows = diagnose(m3PositionsSource, directives = jsCheck, fileName = "t.js").filter { it.code == 2749 }
        assert(rows.map { it.line to it.character }.sortedBy { it.first } ==
            listOf(2 to 19, 4 to 13, 6 to 13, 8 to 16, 10 to 15, 12 to 19, 15 to 12))
        assert(rows.all { it.message == thingMessage("Thing") })
    }

    private val m3IndexSource = """
        /** @type {unique symbol} */
        const foo = Symbol();
        /** @typedef {{ [foo]: boolean }} A */
        /** @typedef {{ [key: foo] boolean }} B */
        /** @typedef {{ [key: foo]: boolean }} C */
        /** @typedef {"a"} L */
        /** @typedef {{ [key: L] boolean }} D */
        class K {}
        /** @typedef {{ [key: K] boolean }} E */
    """

    private fun m3Index(): List<Diagnostic> = diagnose(m3IndexSource, directives = jsCheck, fileName = "t.js")

    /**
     * `uniqueSymbolJs`' row: tsgo `(4,18)` TS1268 on the parameter NAME, `(4,23)` TS2749 on
     * the value `foo`, `(4,28)` TS1005 on the value token that follows a missing `:`; the
     * well-formed `[key: foo]: boolean` on line 5 keeps the first two and drops the TS1005.
     */
    @Test
    fun `M3 - an index signature parameter naming a value is TS1268 and TS2749`() {
        val rows = m3Index()
        val line4 = rows.filter { it.line == 4 }.sortedBy { it.character }
        assert(line4.map { it.code to it.character } == listOf(1268 to 18, 2749 to 23, 1005 to 28))
        assert(line4[0].message == "An index signature parameter type must be 'string', 'number', 'symbol', or a template literal type.")
        assert(line4[0].length == 3)
        assert(line4[1].message == thingMessage("foo"))
        assert(line4[2].message == "';' expected.")
        assert(line4[2].length == 7)
        val line5 = rows.filter { it.line == 5 }.sortedBy { it.character }
        assert(line5.map { it.code to it.character } == listOf(1268 to 18, 2749 to 23))
    }

    /** tsgo `(7,18)` TS1337 for a literal-typedef parameter type and `(9,18)` TS1268 for a class. */
    @Test
    fun `M3 - a literal typedef as the parameter type keeps TS1337 and a class is TS1268`() {
        val rows = m3Index()
        val line7 = rows.filter { it.line == 7 }.sortedBy { it.character }
        assert(line7.map { it.code to it.character } == listOf(1337 to 18, 1005 to 26))
        assert(line7[0].message == "An index signature parameter type cannot be a literal type or generic type. Consider using a mapped object type instead.")
        val line9 = rows.filter { it.line == 9 }.sortedBy { it.character }
        assert(line9.map { it.code to it.character } == listOf(1268 to 18, 1005 to 26))
        assert(rows.none { it.code == 2749 && it.line != 4 && it.line != 5 })
    }
}
