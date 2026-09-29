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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.175) A call whose signature fails ARITY relates none of its arguments: tsgo's
 * `resolveCall` never reaches `getSignatureApplicabilityError` for a candidate that failed
 * `hasCorrectArity`, so `function g(s: string) {}; g(1, 2)` is the lone TS2554 and not also a
 * TS2345 on the `1`. When EVERY overload fails arity there is no TS2769 either.
 *
 * The gate is `Checker.callArityFails`, consulted by `checkArgumentsAgainstSignature` and by
 * `checkArgumentsAgainstOverloads`; the B498 pin walker (`checkGenericDefaultParamCall`)
 * carries its own copy. It answers `false` wherever the count is not decidable (a spread,
 * no declaration, a JS declaration, an embedded-lib member) and honours tsgo's `acceptsVoid`
 * loop for too-few calls.
 *
 * Every expectation was measured against tsgo 7.0.2 (the cells under `build/bench/p18218-agent/m/`). Lines and
 * columns are tsgo's (the harness strips its directive line; the column is 1-based).
 */
class ArityFailedCallRelatesNoArgumentTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `too many arguments is the lone TS2554`() {
        val r = rows(
            """
            function g(s: string) {}
            g(1, 2);
            """
        )
        assert(r == listOf("2:6 TS2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `a this parameter is not counted toward arity`() {
        val r = rows(
            """
            function g(this: void, s: string) {}
            g(1, 2);
            """
        )
        assert(r == listOf("2:6 TS2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `too few arguments is the lone TS2554`() {
        val r = rows(
            """
            function g(s: string, n: number) {}
            g(1);
            """
        )
        assert(r == listOf("2:1 TS2554 Expected 2 arguments, but got 1."))
    }

    @Test
    fun `too many past an optional parameter is the lone TS2554`() {
        val r = rows(
            """
            function g(s: string, n?: number) {}
            g(1, 2, 3);
            """
        )
        assert(r == listOf("2:9 TS2554 Expected 1-2 arguments, but got 3."))
    }

    @Test
    fun `a method a constructor and a super call relate nothing when arity fails`() {
        val r = rows(
            """
            class C { m(s: string) {} }
            new C().m(1, 2);
            class D { constructor(s: string) {} }
            new D(1, 2);
            class E extends D {
              constructor() {
                super(1, 2);
              }
            }
            """
        )
        assert(
            r == listOf(
                "2:14 TS2554 Expected 1 arguments, but got 2.",
                "4:10 TS2554 Expected 1 arguments, but got 2.",
                "7:14 TS2554 Expected 1 arguments, but got 2.",
            )
        )
    }

    @Test
    fun `an object literal or function argument is not elaborated when arity fails`() {
        val r = rows(
            """
            function g(s: string) {}
            g({ a: 1 }, 2);
            g((x: number) => x, 2);
            function w({ a }: { a: string }, b: number) {}
            w({ a: 1 });
            """
        )
        assert(
            r == listOf(
                "2:13 TS2554 Expected 1 arguments, but got 2.",
                "3:21 TS2554 Expected 1 arguments, but got 2.",
                "5:1 TS2554 Expected 2 arguments, but got 1.",
            )
        )
    }

    @Test
    fun `a call through a function-typed parameter relates nothing when arity fails`() {
        // tsgo reports TS2554 at 2:9 here; this checker's name-based arity walker does not
        // reach a callee typed by a parameter, so the silence is a residue (a missing row) —
        // what is pinned is only that no argument is related.
        diagnose(
            """
            function f(cb: (s: string) => void) {
              cb(1, 2);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `an explicit type argument call is the lone TS2554 when arity fails`() {
        val r = rows(
            """
            function g<T>(a: T) {}
            g<string>(1, 2);
            """
        )
        assert(r == listOf("2:14 TS2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `an explicit type argument call still relates when arity holds`() {
        diagnose(
            """
            function h<T, U = T>(a: T, b: U) {}
            h<number>(1, "x");
            """
        ) should {
            have(any { it.code == 2345 && it.line == 2 && it.character == 14 })
        }
    }

    @Test
    fun `every overload failing arity is TS2554 without TS2769`() {
        val r = rows(
            """
            function h(s: string): void;
            function h(s: string, n: number): void;
            function h(a: any, b?: any) {}
            h(1, 2, 3);
            """
        )
        assert(r == listOf("4:9 TS2554 Expected 1-2 arguments, but got 3."))
    }

    @Test
    fun `an arity gap between overloads is TS2575 without TS2769`() {
        val r = rows(
            """
            function h(o: { a: string }): void;
            function h(o: { a: string }, n: number, b: boolean): void;
            function h(a: any, b?: any, c?: any) {}
            h({ a: 1 }, 2);
            """
        )
        assert(
            r == listOf(
                "4:1 TS2575 No overload expects 2 arguments, but overloads do exist that expect either 1 or 3 arguments.",
            )
        )
    }

    @Test
    fun `an overload that is arity compatible still reports its argument`() {
        val r = rows(
            """
            function h(s: string): void;
            function h(s: string, n: number): void;
            function h(a: any, b?: any) {}
            h(1);
            """
        )
        assert(r == listOf("4:3 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `negative control - a void accepting missing parameter keeps the argument check`() {
        // tsgo: `g(1)` against `(s: string, v: void)` has CORRECT arity and reports TS2345.
        diagnose(
            """
            function g(s: string, v: void) {}
            g(1);
            function k(s: string, v: void | number) {}
            k(1);
            """
        ) should {
            have(any { it.code == 2345 && it.line == 2 && it.character == 3 })
            have(any { it.code == 2345 && it.line == 4 && it.character == 3 })
        }
    }

    @Test
    fun `negative control - a spread or rest argument list keeps the argument check`() {
        val r = rows(
            """
            function g(s: string, n: number) {}
            g(1, ...[2]);
            function r(...a: string[]) {}
            r(1, 2);
            function t(s: string, ...rest: number[]) {}
            t(1, "x");
            function u(s: string, a: number, b: number) {}
            const pair: [number, number] = [2, 3];
            u(1, ...pair);
            """
        )
        assert(
            r == listOf(
                "2:3 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
                "4:3 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
                "6:3 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
                "9:3 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            )
        )
    }

    @Test
    fun `negative control - a correct arity call keeps the argument check`() {
        val r = rows(
            """
            function g(s: string, n: number) {}
            g(1, 2);
            """
        )
        assert(r == listOf("2:3 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `negative control - an embedded lib member keeps the argument check`() {
        // The embedded test lib declares `localeCompare(that)` without `locales`, so its arity
        // is not the real one; tsgo (real lib) reports the argument's TS2345 here.
        diagnose(
            """
            "x".localeCompare(1, "en");
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
