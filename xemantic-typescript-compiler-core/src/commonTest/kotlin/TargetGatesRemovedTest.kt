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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.1)(j2), 2026-09-15 — the remaining `target < ES2015` CHECKER gates, decided ONE BY
 * ONE against tsgo 7.0.2 at a WRITTEN `target: es5`.
 *
 * The fact that shaped the round: tsgo's `GetEmitScriptTarget` returns the written target
 * (`compileroptions.go:195`) and its checker reads it as `c.languageVersion` — so a written es5
 * is REPORTED (TS5108) and then HONOURED by every rule that compares the language version.
 * Which is why this was not a blanket deletion. Measured over every gate's own shape through
 * tsgo's LSP (`textDocument/diagnostic`; the CLI stops at TS5108) at es5 and es2015, with
 * `lib: ["es5"]` cells for the lib-shaped ones, and then read against tsgo's source:
 *
 *  - TypeScript 7's checker keeps exactly ONE `< ES2015` rule — the rest-only array binding
 *    pattern's implied `Iterable<any>` type (`checker.go:17879`, `languageVersion >=
 *    ScriptTargetES2015`, whose TS2318 fires at es2015 and not at es5). **KEPT**, and pinned
 *    here in both directions so a later round cannot blanket-delete it with the family.
 *  - The TS2488/TS2461 message fork, the never-destructure TS2488 and the iterable-operand
 *    TS2488 are gated in tsgo on the LIB (`iterableExists`, `getIteratedTypeOrElementType`),
 *    never on the target — a written es5 with the default lib prints TS2488, a `lib: ["es5"]`
 *    project prints TS2461 at es2015 too. **ALIGNED** to `Checker.uplevelIterationLib`.
 *  - TS18027 is gated `languageVersion <= ES2021` with NO lower bound (`checker.go:10516`) —
 *    a written es5 reports it. **FIXED**: the `< ES2015` lower bound is gone.
 *  - TS18045, TS2396, TS2659, TS2340 and the `u`/`y` rows of TS1501 have NO emitter in tsgo
 *    (zero references outside the message table; `regexp.go:44` has no `u`/`y` entry), and the
 *    ES5 parameter-scope hoist (TS2304 suppressed, TS2373+TS2454 emitted for a body `var`) is
 *    not tsgo's rule at ANY target — it resolves a parameter initializer against the parameters
 *    alone and answers TS2304. **DELETED**, each pinned as tsgo's exact answer at es5.
 *
 * `@ignoreDeprecations: 6.0` ([DOWNLEVEL_ES5]) keeps TS5107 out of the exact-list assertions.
 * The corpus cannot see any of this — 0 active subtests compile at es5 ((P18.110)'s census) —
 * so these pins are the whole gate; every non-control pin was RED on the pre-change binary.
 */
class TargetGatesRemovedTest {

    private val es5 = DOWNLEVEL_ES5
    private val es2015 = "// @strict: true\n// @target: es2015"

    // ── DELETED: TS18045 (`accessor` below ES2015) — tsgo has no emitter ─────────────────

    @Test
    fun `an es5 target reports nothing for an accessor property in a class declaration`() {
        val d = diagnose("class C { accessor x = 1; }", es5)
        assert(d.isEmpty())
    }

    @Test
    fun `an es5 target reports nothing for an accessor property in a class expression or an arrow body`() {
        val d = diagnose(
            """
            const E = class { accessor y = 2; };
            const f = () => { class K { accessor z = 3; } };
            """,
            es5,
        )
        assert(d.none { it.code == 18045 })
    }

    // ── DELETED: TS2396 (`arguments` beside a rest parameter below ES2015) — tsgo has no emitter ──

    @Test
    fun `an es5 target reports nothing for a rest-parameter function with a parameter named arguments`() {
        // tsgo reports TS1100 (`Invalid use of 'arguments' in strict mode.`) here — its
        // strict-always binder, a (LEGACY.0b) row this checker does not carry — and no TS2396.
        val d = diagnose("function f(arguments: string, ...rest: any[]) {}", es5)
        assert(d.none { it.code == 2396 })
        assert(d.none { it.code == 1215 })
        assert(d.map { it.code } == listOf(1100))
    }

    @Test
    fun `TS2396 is gone at es2015 too when the program holds a module file beside the script`() {
        // The legacy run gate was `target < ES2015 || <any module file>`, and the emission
        // leaf then reported TS2396 in the SCRIPT of a mixed program at every target — an
        // ours-only row tsgo never had. The TS1215 module row is the only survivor.
        val d = diagnose(
            """
            // @Filename: script.ts
            function s(arguments: string, ...rest: any[]) {}

            // @Filename: module.ts
            export {};
            function m(arguments: string) {}
            """,
            es2015,
        )
        assert(d.none { it.code == 2396 })
        assert(d.count { it.code == 1215 } == 1)
    }

    // ── DELETED: TS2659 (`super` in an object-literal method/accessor below ES2015) ──────

    @Test
    fun `an es5 target reports nothing for super in object-literal methods and accessors`() {
        val d = diagnose(
            """
            const o = {
                m() { return super.toString(); },
                get g() { return super.toString(); },
                set s(v: number) { super.toString(); },
            };
            """,
            es5,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `control - a function-expression property's super is still TS2660 at es5`() {
        val d = diagnose("const o = { p: function() { return super.toString(); } };", es5)
        assert(d.size == 1)
        assert(d[0].code == 2660)
        assert(d[0].message == "'super' can only be referenced in members of derived classes or object literal expressions.")
    }

    // ── DELETED: TS2340 (super property access in ES5) — tsgo has no emitter; TS2855 is the rule ──

    private val superShape = """
        class SpB { f = 1; get g() { return 1; } m() { return 1; } static sf = 2; }
        class SpD extends SpB {
            t() { return super.f + super.g + super.m(); }
            static st() { return super.sf; }
        }
    """

    @Test
    fun `an es5 target reports TS2855 for a base field via super and nothing for a base accessor`() {
        // tsgo at es5: exactly `superprop.ts(3,24): TS2855` — the ES2015+ rule; ours used to
        // print TS2340 twice (field AND accessor) plus a TS2576 for the static access.
        val d = diagnose(superShape, es5)
        assert(d.size == 1)
        assert(d[0].code == 2855)
        assert(d[0].message == "Class field 'f' defined by the parent class is not accessible in the child class via super.")
        assert(d[0].line == 3)
    }

    @Test
    fun `control - the same super shape at es2015 is the same single TS2855 row`() {
        val d = diagnose(superShape, es2015)
        assert(d.size == 1)
        assert(d[0].code == 2855)
    }

    // ── DELETED: the ES5 parameter-scope hoist (TS2304 suppressed; TS2373 + TS2454 emitted) ──

    @Test
    fun `an es5 target reports TS2304 for a body var referenced from a parameter default`() {
        // tsgo, es5 AND es2015: `hoist.ts(1,21): TS2304 Cannot find name 'hb'.` — a parameter
        // initializer resolves against the parameters alone at every target.
        val d = diagnose("function hoistF(a = hb) { var hb = 1; return a + hb; }", es5)
        assert(d.size == 1)
        assert(d[0].code == 2304)
        assert(d[0].message == "Cannot find name 'hb'.")
    }

    @Test
    fun `an es5 target reports TS2304 for a body let referenced from a parameter default`() {
        val d = diagnose("function hoistG(a = hc) { let hc = 1; return a + hc; }", es5)
        assert(d.count { it.code == 2304 && it.message == "Cannot find name 'hc'." } == 1)
        assert(d.none { it.code == 2373 || it.code == 2454 })
    }

    @Test
    fun `control - a later PARAMETER referenced from a parameter default is still TS2373 at es5`() {
        val d = diagnose("function later(a = b, b = 1) { return a + b; }", es5)
        assert(d.count { it.code == 2373 } == 1)
        assert(d[0].message == "Parameter 'a' cannot reference identifier 'b' declared after it.")
    }

    // ── DELETED: the `u`/`y` rows of TS1501 — tsgo's flag table has no entry for them ─────

    @Test
    fun `an es5 target reports nothing for the u and y regular-expression flags`() {
        val d = diagnose("const rxU = /a/u;\nconst rxY = /a/y;", es5)
        assert(d.isEmpty())
    }

    @Test
    fun `control - the s flag is still TS1501 at es5 - the ES2018 row tsgo keeps`() {
        val d = diagnose("const rxS = /a/s;", es5)
        assert(d.size == 1)
        assert(d[0].code == 1501)
        assert(d[0].message == "This regular expression flag is only available when targeting 'es2018' or later.")
    }

    // ── DELETED: the `>= ES2015` conjunct of the iterable-operand TS2488 — tsgo reads the lib ──

    @Test
    fun `an es5 target reports TS2488 for a for-of over a class whose Symbol iterator returns this`() {
        // tsgo at es5: `iterop.ts(7,11): TS2488 Type 'MyStringIterator' must have a
        // '[Symbol.iterator]()' method that returns an iterator.` — byte-identical to es2015.
        val d = diagnose(
            """
            class MyStringIterator {
                [Symbol.iterator]() {
                    return this;
                }
            }
            var v: string;
            for (v of new MyStringIterator) { }
            """,
            es5,
        )
        assert(d.size == 1)
        assert(d[0].code == 2488)
        assert(d[0].message == "Type 'MyStringIterator' must have a '[Symbol.iterator]()' method that returns an iterator.")
    }

    // ── FIXED: TS18027 has no lower bound in tsgo (`languageVersion <= ES2021`) ──────────

    @Test
    fun `an es5 target reports TS18027 for a WeakMap or WeakSet binding beside a private-field class`() {
        val d = diagnose(
            """
            function wmF() { class WmC { #p = 1; } const WeakMap = 1; return WeakMap; }
            function wsF() { class WsC { #q = 1; } let WeakSet = 2; return WeakSet; }
            """,
            es5,
        )
        assert(d.size == 2)
        assert(d[0].code == 18027)
        assert(d[0].message == "Compiler reserves name 'WeakMap' when emitting private identifier downlevel.")
        assert(d[1].code == 18027)
        assert(d[1].message == "Compiler reserves name 'WeakSet' when emitting private identifier downlevel.")
    }

    @Test
    fun `control - the same shape at es2022 reports no TS18027 - the upper bound is tsgo's`() {
        val d = diagnose(
            "function wmF() { class WmC { #p = 1; } const WeakMap = 1; return WeakMap; }",
            "// @strict: true\n// @target: es2022",
        )
        assert(d.none { it.code == 18027 })
    }

    // ── ALIGNED: the never-destructure TS2488 and the TS2488/TS2461 fork read the LIB ─────

    private val neverShape = """
        declare const itNever: { a: "foo" } & { a: "bar" };
        const [nvX] = itNever;
    """

    @Test
    fun `an es5 target with the default lib reports TS2488 for destructuring a never intersection`() {
        val d = diagnose(neverShape, es5)
        assert(d.size == 1)
        assert(d[0].code == 2488)
        assert(d[0].message == "Type 'never' must have a '[Symbol.iterator]()' method that returns an iterator.")
    }

    @Test
    fun `control - an es2015 target whose lib excludes Iterable reports nothing for the never destructure`() {
        // tsgo at es2015 + `lib: ["es5"]`: no row for this line (measured 2026-09-15).
        val d = diagnose(neverShape, "// @strict: true\n// @target: es2015\n// @lib: es5")
        assert(d.none { it.code == 2488 })
    }

    @Test
    fun `an es5 target with the default lib reports TS2488 not TS2461 for destructuring null`() {
        // tsgo at es5: `iter.ts(5,7): TS2488 Type 'null' must have a '[Symbol.iterator]()'
        // method that returns an iterator.` beside the TS2531.
        val d = diagnose("const [] = null;", es5)
        assert(d.count { it.code == 2488 && it.message == "Type 'null' must have a '[Symbol.iterator]()' method that returns an iterator." } == 1)
        assert(d.none { it.code == 2461 })
    }

    @Test
    fun `an es2015 target whose lib excludes Iterable reports TS2461 for destructuring null`() {
        // tsgo at es2015 + `lib: ["es5"]`: `TS2461 Type 'null' is not an array type.` — the fork
        // is the lib, so the ES2015 target changes nothing here.
        val d = diagnose("const [] = null;", "// @strict: true\n// @target: es2015\n// @lib: es5")
        assert(d.count { it.code == 2461 && it.message == "Type 'null' is not an array type." } == 1)
        assert(d.none { it.code == 2488 })
    }

    @Test
    fun `an es5 target with the default lib reports TS2488 for a named binding over an empty array`() {
        // tsgo prints ONE row here, `Type 'never' must have …` (the general iterability check,
        // which this checker also emits). The dedicated empty-array emitter adds a second,
        // ours-only `Type 'undefined' must have …` row beside it under `strict` — a standing
        // duplicate at EVERY target, unrelated to this round; what the target used to fork in
        // that emitter is the CODE (TS2461 below ES2015), and that is what is pinned.
        val d = diagnose("for (const [itX] of []) { }", es5)
        assert(d.any { it.code == 2488 && it.message == "Type 'never' must have a '[Symbol.iterator]()' method that returns an iterator." })
        assert(d.none { it.code == 2461 })
    }

    // ── KEPT: TS2318 for a rest-only array binding pattern — tsgo's one surviving `< ES2015` gate ──

    private val restOnly = """
        declare const roX: any;
        const [...roR] = roX;
    """

    @Test
    fun `KEPT - an es5 target whose lib lacks Iterable reports no TS2318 for a rest-only pattern`() {
        // tsgo `getTypeFromArrayBindingPattern` (checker.go:17879): `if c.languageVersion >=
        // core.ScriptTargetES2015 { createIterableType(any) } else anyArrayType` — at a written
        // es5 the Iterable global is never asked for. This pin is what stops a later round from
        // blanket-deleting `checkGlobalIterableRestOnlyBindingPattern`'s gate with the family.
        val d = diagnose(restOnly, "// @strict: true\n// @target: es5\n// @lib: es5\n// @ignoreDeprecations: 6.0")
        assert(d.none { it.code == 2318 })
    }

    @Test
    fun `KEPT - an es2015 target whose lib lacks Iterable reports TS2318 for a rest-only pattern`() {
        // tsgo's own baseline `sourceMapValidationDestructuringForArrayBindingPattern(target=es2015)`
        // carries this row (a location-less diagnostic, invisible to a per-file LSP pull).
        val d = diagnose(restOnly, "// @strict: true\n// @target: es2015\n// @lib: es5")
        assert(d.count { it.code == 2318 && it.message == "Cannot find global type 'Iterable'." } == 1)
    }

    // ── the whole family in one file: tsgo's es5 answer, byte for byte ──────────────────

    @Test
    fun `an es5 target over every deleted-gate shape reports exactly tsgo's rows`() {
        val d = diagnose(
            """
            class AccC { accessor ax = 1; }
            function argsF(...arguments: any[]) { }
            const osObj = { m() { return super.toString(); } };
            function hoistF(a = hb) { var hb = 1; return a + hb; }
            const rxU = /a/u;
            const rxY = /a/y;
            """,
            es5,
        )
        // tsgo at es5 (LSP, `strict: true`): exactly these two rows — TS1100 for the strict-mode
        // `arguments` name (which this checker also reports under `strict`) and the TS2304.
        val rows = d.map { "${it.line}:${it.code}:${it.message}" }.sorted()
        assert(rows == listOf("2:1100:Invalid use of 'arguments' in strict mode.", "4:2304:Cannot find name 'hb'."))
    }
}
