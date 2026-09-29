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
 * (CHK.176)(b)/(c) CALL arity honours tsgo's `getMinArgumentCount`: a trailing run of
 * required parameters whose type accepts `void` (`void`, or a union with a `void`
 * constituent) may be omitted, so `g("a")` for `g(s: string, v: void)` is legal and `g()`
 * reads `Expected 1-2 arguments, but got 0.` The rule has one home,
 * `Checker.callMinArgumentCount` (with `relationMinArgumentCount` for a signature that has no
 * declared list), consulted by every too-few emitter: the name-based call / `new` / overload
 * walkers, the property-access reader, the method-overload reader and the union reader.
 *
 * (c): the B498 walker `checkGenericDefaultParamCall` reports only a DEFAULT-resolved
 * parameter; an explicitly supplied type argument is the ordinary argument reader's row, so
 * `g<string>(1)` prints one TS2345, not two.
 *
 * Every expectation was measured against tsgo 7.0.2 (cells under
 * `build/bench/p18219-agent/m/`); lines and columns are tsgo's (1-based column).
 */
class CallArityTrailingVoidTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a trailing void parameter may be omitted`() {
        val r = rows(
            """
            function g(s: string, v: void) {}
            g("a");
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a union with a void constituent may be omitted`() {
        val r = rows(
            """
            function g(s: string, v: void | number) {}
            g("a");
            function h(s: string, v: void | undefined) {}
            h("a");
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a void alias may be omitted`() {
        val r = rows(
            """
            type V = void;
            function g(s: string, v: V) {}
            g("a");
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a trailing run of void parameters may all be omitted`() {
        val r = rows(
            """
            function g(s: string, v: void, w: void) {}
            g("a");
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `too few below the trimmed minimum reads the trimmed range`() {
        val r = rows(
            """
            function g(s: string, v: void) {}
            g();
            """
        )
        assert(r == listOf("2:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `negative control - a void in the middle is still required`() {
        val r = rows(
            """
            function g(s: string, v: void, b: number) {}
            g("a");
            """
        )
        assert(r == listOf("2:1 TS2554 Expected 3 arguments, but got 1."))
    }

    @Test
    fun `negative control - undefined any and unknown do not accept void`() {
        val r = rows(
            """
            function g(s: string, v: undefined) {}
            g("a");
            function h(s: string, v: any) {}
            h("a");
            function k(s: string, v: unknown) {}
            k("a");
            """
        )
        assert(
            r == listOf(
                "2:1 TS2554 Expected 2 arguments, but got 1.",
                "4:1 TS2554 Expected 2 arguments, but got 1.",
                "6:1 TS2554 Expected 2 arguments, but got 1.",
            )
        )
    }

    @Test
    fun `a wrong argument before an omitted void parameter is the lone TS2345`() {
        val r = rows(
            """
            function g(s: string, v: void) {}
            g(1);
            """
        )
        assert(r == listOf("2:3 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `an arrow const and a nested function honour the rule`() {
        val r = rows(
            """
            const g = (s: string, v: void) => {};
            g("a");
            function outer() { function inner(a: number, v: void) {} inner(1); }
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `an optional parameter ends the trimmed run`() {
        val r = rows(
            """
            function g(s: string, v?: number, w: void) {}
            g("a");
            g();
            """
        )
        assert(
            r == listOf(
                "1:35 TS1016 A required parameter cannot follow an optional parameter.",
                "2:1 TS2554 Expected 2-3 arguments, but got 1.",
                "3:1 TS2554 Expected 2-3 arguments, but got 0.",
            )
        )
    }

    @Test
    fun `a constructor honours the rule`() {
        val r = rows(
            """
            class C { constructor(s: string, v: void) {} }
            new C("a");
            new C();
            class D extends C { constructor() { super("a"); } }
            """
        )
        assert(r == listOf("3:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `a method honours the rule`() {
        val r = rows(
            """
            class C { m(s: string, v: void) {} }
            new C().m("a");
            new C().m();
            """
        )
        assert(r == listOf("3:9 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `an overloaded function honours the rule per overload`() {
        val r = rows(
            """
            function g(s: string, v: void): void;
            function g(n: number, a: number, b: number): void;
            function g(...a: any[]) {}
            g("a");
            g();
            """
        )
        assert(r == listOf("5:1 TS2554 Expected 1-3 arguments, but got 0."))
    }

    @Test
    fun `an overloaded call with explicit type arguments honours the rule`() {
        val r = rows(
            """
            function g<T>(s: T, v: void): void;
            function g(n: number, a: number, b: number): void;
            function g(...a: any[]) {}
            g<string>("a");
            g<string>();
            """
        )
        assert(r == listOf("5:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `an overloaded method honours the rule`() {
        val r = rows(
            """
            interface I { m(s: string, v: void): void; m(n: number, a: number, b: number): void }
            declare const i: I;
            i.m("a");
            i.m();
            """
        )
        assert(r == listOf("4:3 TS2554 Expected 1-3 arguments, but got 0."))
    }

    @Test
    fun `a union callee honours the rule and anchors at the member name`() {
        val r = rows(
            """
            declare const u: { m(s: string, v: void): void } | { m(s: string, v: void | number): void };
            u.m("a");
            u.m();
            declare const w: { m(s: string): void } | { m(s: string, n?: number): void };
            w.m();
            """
        )
        assert(
            r == listOf(
                "3:3 TS2554 Expected 1-2 arguments, but got 0.",
                "5:3 TS2554 Expected 1-2 arguments, but got 0.",
            )
        )
    }

    @Test
    fun `a synthesized call signature honours the rule`() {
        val r = rows(
            """
            interface W { w: number }
            function g(this: W, s: string, v: void) {}
            declare const w: W;
            g.call(w, "a");
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `an explicit type argument mismatch is reported once`() {
        val r = rows(
            """
            function g<T>(a: T) {}
            g<string>(1);
            function k<T = string>(a: T) {}
            k<string>(1);
            """
        )
        assert(
            r == listOf(
                "2:11 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
                "4:11 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            )
        )
    }

    @Test
    fun `a default-resolved type parameter mismatch is still reported`() {
        val r = rows(
            """
            function h<T, U = T>(a: T, b: U) {}
            h<number>(1, "x");
            h<number>("y", 2);
            """
        )
        assert(
            r == listOf(
                "2:14 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
                "3:11 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            )
        )
    }
}
