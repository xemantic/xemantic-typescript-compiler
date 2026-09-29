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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.176)(a) The signature-based arity reader, `Checker.reportSignatureArity`: tsgo's
 * `getArgumentArityError` for a call that every candidate signature rejects by argument
 * count, reached from the ordinary argument reader (`checkArgumentsAgainstSignature`, the
 * overload reader, and the generic-overload bail) — i.e. for the callees the name-based
 * walkers never see: a function-typed parameter or variable, an array element, a
 * construct-signature variable, an overloaded constructor, an optional call, a tuple rest,
 * a real-lib function or member.
 *
 * Too MANY squiggles the excess arguments; too FEW anchors at the callee (its name for a
 * property access, the whole expression for `new`); a count in a gap between overloads is
 * TS2575; a rest parameter makes it TS2555; a surviving spread is TS2556. A call another
 * arity emitter already reported (same span) is reported ONCE.
 *
 * Every expectation was measured against tsgo 7.0.2 (cells under
 * `build/bench/p18223-agent/m/`); lines and columns are tsgo's (1-based column).
 */
class SignatureArityReaderTest {

    private fun rows(source: String, directives: String = "// @strict: true"): List<String> =
        diagnose(source, directives).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private val realLibs = "// @strict: true\n// @useRealLibs: true"

    @Test
    fun `a function-typed parameter called with too many arguments`() {
        val r = rows(
            """
            function f(cb: (s: string) => void) {
              cb(1, 2);
            }
            """
        )
        assert(r == listOf("2:9 TS2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `a function-typed parameter called with too few arguments names the missing parameter`() {
        val d = diagnose(
            """
            function f(cb: (s: string, n: number) => void) {
              cb("a");
            }
            """
        )
        val row = d.single()
        val related = row.relatedInformation.map { it.message }
        assert(row.code == 2554 && row.line == 2 && row.character == 3 && row.length == 2)
        assert(row.message == "Expected 2 arguments, but got 1.")
        assert(related == listOf("An argument for 'n' was not provided."))
    }

    @Test
    fun `an array element callee is arity-checked`() {
        val r = rows(
            """
            declare const a: ((s: string) => void)[];
            a[0]("a", 2);
            a[0]();
            """
        )
        assert(r == listOf("2:11 TS2554 Expected 1 arguments, but got 2.", "3:1 TS2554 Expected 1 arguments, but got 0."))
    }

    @Test
    fun `a call-signature overload set on a variable reports the range`() {
        val r = rows(
            """
            declare const o: { (s: string): void; (s: string, n: number): void };
            o("a", 2, 3);
            """
        )
        assert(r == listOf("2:11 TS2554 Expected 1-2 arguments, but got 3."))
    }

    @Test
    fun `a generic overload set is arity-checked before inference`() {
        val r = rows(
            """
            declare const o: { <T>(t: T): void; <T>(t: T, u: T): void };
            o(1, 2, 3);
            o();
            """
        )
        assert(r == listOf("2:9 TS2554 Expected 1-2 arguments, but got 3.", "3:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `a count between two overloads is TS2575`() {
        val r = rows(
            """
            declare const o: { (): void; (a: string, b: string): void };
            o("a");
            """
        )
        assert(r == listOf("2:1 TS2575 No overload expects 1 arguments, but overloads do exist that expect either 0 or 2 arguments."))
    }

    @Test
    fun `a construct-signature variable - too many squiggles the excess and too few the whole new`() {
        val r = rows(
            """
            declare const C: new (s: string, n: number) => object;
            new C("a", 2, 3);
            new C("a");
            """
        )
        assert(r == listOf("2:15 TS2554 Expected 2 arguments, but got 3.", "3:1 TS2554 Expected 2 arguments, but got 1."))
    }

    @Test
    fun `an overloaded class constructor reports the range`() {
        val r = rows(
            """
            class C {
              constructor(s: string);
              constructor(s: string, n: number);
              constructor(a: any, b?: any) {}
            }
            new C("a", 2, 3);
            """
        )
        assert(r == listOf("6:15 TS2554 Expected 1-2 arguments, but got 3."))
    }

    @Test
    fun `optional calls are arity-checked`() {
        val r = rows(
            """
            declare const f: ((a: string) => void) | undefined;
            f?.("a", 2);
            f?.();
            """
        )
        assert(r == listOf("2:10 TS2554 Expected 1 arguments, but got 2.", "3:1 TS2554 Expected 1 arguments, but got 0."))
    }

    @Test
    fun `a tuple rest parameter has fixed arity`() {
        val r = rows(
            """
            function g(...a: [string, number]) {}
            g("a");
            declare const h: (...a: [string, number?]) => void;
            h();
            h("a", 1, 2);
            """
        )
        assert(
            r == listOf(
                "2:1 TS2554 Expected 2 arguments, but got 1.",
                "4:1 TS2554 Expected 1-2 arguments, but got 0.",
                "5:11 TS2554 Expected 1-2 arguments, but got 3.",
            )
        )
    }

    @Test
    fun `a rest parameter makes too few TS2555`() {
        val r = rows(
            """
            declare const g: (a: string, ...r: number[]) => void;
            g();
            """
        )
        assert(r == listOf("2:1 TS2555 Expected at least 1 arguments, but got 0."))
    }

    @Test
    fun `a surviving spread into a callback without a rest parameter is TS2556`() {
        val r = rows(
            """
            function h(cb: (a: string, b: string) => void, xs: string[]) {
              cb(...xs);
            }
            """
        )
        assert(r == listOf("2:6 TS2556 A spread argument must either have a tuple type or be passed to a rest parameter."))
    }

    @Test
    fun `a trailing void parameter of a function type may be omitted`() {
        val r = rows(
            """
            declare const g: (s: string, v: void) => void;
            g("a");
            g();
            """
        )
        assert(r == listOf("3:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `real-lib functions and members are arity-checked`() {
        val r = rows(
            """
            parseInt("1", 2, 3);
            "x".charAt(1, 2);
            JSON.parse();
            """,
            realLibs,
        )
        assert(
            r == listOf(
                "1:18 TS2554 Expected 1-2 arguments, but got 3.",
                "2:15 TS2554 Expected 1 arguments, but got 2.",
                "3:6 TS2554 Expected 1-2 arguments, but got 0.",
            )
        )
    }

    @Test
    fun `a call the name walker reports is reported once`() {
        val r = rows(
            """
            function g(s: string) {}
            g("a", 2);
            g();
            function h(s: string): void;
            function h(s: string, n: number): void;
            function h(a: any, b?: any) {}
            h("a", 2, 3);
            """
        )
        assert(
            r == listOf(
                "2:8 TS2554 Expected 1 arguments, but got 2.",
                "3:1 TS2554 Expected 1 arguments, but got 0.",
                "7:11 TS2554 Expected 1-2 arguments, but got 3.",
            )
        )
    }

    @Test
    fun `a call the property-access reader reports is reported once`() {
        val r = rows(
            """
            declare const o: { m(s: string): void };
            o.m("a", 2);
            o.m();
            """
        )
        assert(r == listOf("2:10 TS2554 Expected 1 arguments, but got 2.", "3:3 TS2554 Expected 1 arguments, but got 0."))
    }

    @Test
    fun `a super call is reported once`() {
        val r = rows(
            """
            class A { constructor(s: string) {} }
            class B extends A {
              constructor() {
                super("a", 2);
              }
            }
            """
        )
        assert(r == listOf("4:16 TS2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `a call on a too-short call keeps both rows at one start`() {
        val r = rows(
            """
            declare function h(s: string): (n: number) => void;
            h()();
            """
        )
        assert(r == listOf("2:1 TS2554 Expected 1 arguments, but got 0.", "2:1 TS2554 Expected 1 arguments, but got 0."))
    }

    @Test
    fun `a nested call and its outer call are both reported`() {
        val r = rows(
            """
            declare const cb: (s: string) => void;
            cb(cb("a", 2), 3);
            """
        )
        assert(r == listOf("2:12 TS2554 Expected 1 arguments, but got 2.", "2:16 TS2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `a parameter that shadows an outer function is not read as it`() {
        // tsgo 7.0.2: silent — `f` is the `any` parameter. This checker resolves the callee
        // to the OUTER function (an `any` parameter is in no walk-scoped table), so the
        // reader must refuse rather than report the outer arity.
        val r = rows(
            """
            function f(a: number, b: number) {}
            function g(f: any) { f(); }
            function h({ f }: any) { f(); }
            class C { m(f: any) { f(); } }
            const k = (a: number) => {};
            function j(k: any) { k(1, 2); }
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a function expression's own name is not read as the outer function`() {
        // tsgo 7.0.2 reports `Expected 0 arguments, but got 3.` here; this checker resolves
        // `f` to the outer declaration, so the reader refuses — never `Expected 2`.
        val d = diagnose(
            """
            function f(a: number, b: number) {}
            const v = function f() {
              f(1, 2, 3);
            };
            """
        )
        assert(d.none { it.message == "Expected 2 arguments, but got 3." })
    }

    @Test
    fun `an any parameter shadowing a construct-signature variable is not read as it`() {
        val r = rows(
            """
            declare const K: new (a: number) => object;
            function g(K: any) { new K(); }
            new K();
            """
        )
        assert(r == listOf("3:1 TS2554 Expected 1 arguments, but got 0."))
    }

    @Test
    fun `an alias that keeps one overload of a set is not arity-checked as that overload`() {
        // tsc's own `src/harness/vpathUtil.ts` shape (`export import extname =
        // ts.getAnyExtensionFromPath`): the alias types as the FIRST overload alone, so the
        // reader refuses rather than print `Expected 1 arguments` for a legal 3-argument call.
        // tsgo 7.0.2 reports only `4:1 TS2575` (for the 2-argument call) — a residue here.
        val d = diagnose(
            """
            // @filename: lib.ts
            export function h(p: string): string;
            export function h(p: string, e: string, i: boolean): string;
            export function h(p: string, e?: string, i?: boolean) { return p; }
            // @filename: t.ts
            import * as L from "./lib";
            export import h2 = L.h;
            h2("a", ".x", false);
            h2("a", ".x");
            """
        )
        assert(d.none { it.code == 2554 })
    }

    @Test
    fun `negative control - correct arity and untyped callees stay silent`() {
        val r = rows(
            """
            declare const g: (s: string, n?: number) => void;
            g("a");
            g("a", 1);
            declare const f: Function;
            f(1, 2);
            declare const v: (v: void) => void;
            v();
            declare const r: (...xs: number[]) => void;
            r();
            r(1, 2, 3);
            """
        )
        assert(r.isEmpty())
    }
}
