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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.33) `Checker.signatureDeclaredArity` — the arity a signature's DECLARATION states,
 * shared by the two readers that need it.
 *
 * A call whose resolved signature takes a DESTRUCTURED parameter printed the impossible
 * `Expected 1-0 arguments, but got 1.` on legal TypeScript — 8 rows in `marked`, a library
 * tsgo 7.0.2 reports ZERO errors for, plus two more shapes measured in the same round
 * (a rest parameter that is ITSELF a binding pattern, and a member typed by a function TYPE).
 *
 * Round 446 fixed exactly this at the property-access reader
 * (`checkTs2554ForPropertyAccessCall`, pinned by [DestructuredParamArityTest]); (CHK.97)'s
 * later union-callee reader (`unionCalleeArityDiagnostic`) reintroduced it by taking its
 * MAXIMUM from `Signature.parameters.size` — which `getParameterSymbols` leaves EMPTY for a
 * binding-pattern parameter — while its MINIMUM comes from `minArgumentCount`, which counts
 * that parameter. The shared recovery is `Checker.signatureDeclaredArity`.
 *
 * Every expectation below was measured against tsgo 7.0.2 first (`tools/tsgo-7.0.2/lib/tsc
 * --noEmit`), including the two rows tsgo DOES report, so the pins assert an adjudicated
 * answer rather than this compiler's own.
 *
 * The union receiver is load-bearing: the non-union call of the same method was already
 * correct, so a fixture written without one is vacuous.
 */
class SignatureDeclaredArityTest {

    private val prelude =
        """
        interface O { a: string; b: number; }
        declare const o: O;
        """.trimIndent() + "\n"

    @Test
    fun `a union receiver whose members destructure accepts the right argument count`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O): string { return a; } }
            class B { m({ a }: O): string { return a; } }
            declare const u: A | B;
            u.m(o);
            """
        ) should {
            have(none { it.code == 2554 })
            have(none { it.code == 2555 })
        }
    }

    /**
     * The structural signal the whole family is named for: `Expected MIN-MAX` where
     * `MIN > MAX` describes no signature that can exist, so it needs no reference to
     * adjudicate. Asserted over the WHOLE diagnostic list rather than at one call, so a
     * future reader that mixes the two lists again fails here whatever shape produces it.
     */
    @Test
    fun `no diagnostic ever states an inverted argument range`() {
        val invertedRange = Regex("""Expected (\d+)-(\d+) arguments""")
        diagnose(
            prelude +
            """
            class A { m({ a }: O): string { return a; } n(x: O, { a }: O): string { return a; } }
            class B { m({ a }: O): string { return a; } n(x: O, { a }: O): string { return a; } }
            declare const u: A | B;
            u.m(o);
            u.m();
            u.m(o, o);
            u.n(o, o);
            u.n(o);
            """
        ) should {
            have(none { d ->
                val match = invertedRange.find(d.message)
                match != null && match.groupValues[1].toInt() > match.groupValues[2].toInt()
            })
        }
    }

    @Test
    fun `too few arguments through a destructuring union states the declared arity`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O): string { return a; } }
            class B { m({ a }: O): string { return a; } }
            declare const u: A | B;
            u.m();
            """
        ) should {
            have(any { it.code == 2554 && it.message == "Expected 1 arguments, but got 0." })
        }
    }

    @Test
    fun `too many arguments through a destructuring union states the declared arity`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O): string { return a; } }
            class B { m({ a }: O): string { return a; } }
            declare const u: A | B;
            u.m(o, o);
            """
        ) should {
            have(any { it.code == 2554 && it.message == "Expected 1 arguments, but got 2." })
        }
    }

    @Test
    fun `a leading destructured parameter does not shift the declared arity`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O, b: string): string { return a + b; } }
            class B { m({ a }: O, b: string): string { return a + b; } }
            declare const u: A | B;
            u.m(o, "s");
            """
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `a leading destructured parameter still reports its own too-few row`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O, b: string): string { return a + b; } }
            class B { m({ a }: O, b: string): string { return a + b; } }
            declare const u: A | B;
            u.m(o);
            """
        ) should {
            have(any { it.code == 2554 && it.message == "Expected 2 arguments, but got 1." })
        }
    }

    @Test
    fun `a trailing destructured parameter accepts both arguments`() {
        diagnose(
            prelude +
            """
            class A { m(x: O, { a }: O): string { return a + x.a; } }
            class B { m(x: O, { a }: O): string { return a + x.a; } }
            declare const u: A | B;
            u.m(o, o);
            """
        ) should {
            have(none { it.code == 2554 })
        }
    }

    /**
     * tsgo answers a rest-bearing signature with TS2555 and the *at least* wording; TS2554's
     * fixed range is wrong there. Reachable before this round — the surviving rest symbol is
     * still the parameter list's last, so `sigHasRestParameter` already answered true.
     */
    @Test
    fun `a rest tail beside a destructured parameter reports TS2555`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O, ...r: string[]): string { return a + r[0]; } }
            class B { m({ a }: O, ...r: string[]): string { return a + r[0]; } }
            declare const u: A | B;
            u.m();
            """
        ) should {
            have(any { it.code == 2555 && it.message == "Expected at least 1 arguments, but got 0." })
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `a rest tail beside a destructured parameter accepts its arguments`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O, ...r: string[]): string { return a + r[0]; } }
            class B { m({ a }: O, ...r: string[]): string { return a + r[0]; } }
            declare const u: A | B;
            u.m(o, "x", "y");
            """
        ) should {
            have(none { it.code == 2554 })
            have(none { it.code == 2555 })
        }
    }

    /**
     * A REST parameter whose own name is a binding pattern (`...[a, b]: [string, number]`)
     * is dropped from `parameters` too, so `sigHasRestParameter` — which reads that list's
     * LAST entry — answered false and the call was measured against a maximum of ZERO:
     * `Expected 0 arguments, but got 2.` on legal TypeScript. The declaration's own
     * `hasRest` is what suppresses it. Measured: tsgo 7.0.2 is silent here.
     */
    @Test
    fun `a rest parameter that is itself a binding pattern is not a zero-arity signature`() {
        diagnose(
            """
            class A { m(...[a, b]: [string, number]): string { return a + b; } }
            class B { m(...[a, b]: [string, number]): string { return a + b; } }
            declare const u: A | B;
            u.m("x", 1);
            """
        ) should {
            have(none { it.code == 2554 })
            have(none { it.code == 2555 })
        }
    }

    /**
     * A member whose type is written as a FUNCTION TYPE carries that node as its signature's
     * declaration, and a function type may spell a binding pattern. Reported
     * `Expected 1-0 arguments, but got 1.` before the `FunctionType` arm; tsgo 7.0.2 is
     * silent. This one is decided by the round-446 reader
     * (`checkTs2554ForPropertyAccessCall`), not the union one — which is the receipt that
     * the extracted helper serves BOTH.
     */
    @Test
    fun `a function-type member with a destructured parameter accepts its argument`() {
        diagnose(
            """
            interface O2 { a: string; }
            declare const o2: O2;
            declare const host: { m: ({ a }: O2) => void };
            host.m(o2);
            """
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `a named function-type alias member with a destructured parameter accepts its argument`() {
        diagnose(
            """
            interface O2 { a: string; }
            declare const o2: O2;
            type FT = ({ a }: O2) => void;
            declare const host: { m: FT };
            host.m(o2);
            """
        ) should {
            have(none { it.code == 2554 })
        }
    }

    /**
     * Negative control for the new arms: a function-type member with ORDINARY parameters
     * must still report its own arity rows, or the arms have disabled the reader.
     */
    @Test
    fun `negative control - a function-type member still reports too many arguments`() {
        diagnose(
            """
            interface O2 { a: string; }
            declare const o2: O2;
            declare const host: { m: (x: O2) => void };
            host.m(o2, o2);
            """
        ) should {
            have(any { it.code == 2554 })
        }
    }

    @Test
    fun `a function-type member with a destructured parameter reports too many with the declared arity`() {
        diagnose(
            """
            interface O2 { a: string; }
            declare const o2: O2;
            declare const host: { m: ({ a }: O2) => void };
            host.m(o2, o2);
            """
        ) should {
            have(any { it.code == 2554 && it.message == "Expected 1 arguments, but got 2." })
        }
    }

    @Test
    fun `a function-type member with a destructured parameter reports too few with the declared arity`() {
        diagnose(
            """
            interface O2 { a: string; }
            declare const o2: O2;
            declare const host: { m: ({ a }: O2) => void };
            host.m();
            """
        ) should {
            have(any { it.code == 2554 && it.message == "Expected 1 arguments, but got 0." })
        }
    }

    /**
     * Negative control — the reader must still REPORT for a union whose members declare
     * ordinary identifier parameters, or the round has disabled it rather than fixed it.
     */
    @Test
    fun `negative control - an identifier-parameter union still reports too many`() {
        diagnose(
            prelude +
            """
            class A { m(x: O): string { return x.a; } }
            class B { m(x: O): string { return x.a; } }
            declare const u: A | B;
            u.m(o, o);
            """
        ) should {
            have(any { it.code == 2554 && it.message == "Expected 1 arguments, but got 2." })
        }
    }

    /**
     * Negative control for the ARITY DIFFERENCE the union combiner exists to compute: two
     * members whose minimums differ take the MAXIMUM of the two, so one argument is too few.
     * Measured on tsgo 7.0.2.
     */
    @Test
    fun `negative control - differing member arities still take the larger minimum`() {
        diagnose(
            prelude +
            """
            class A { m({ a }: O): string { return a; } }
            class B { m({ a }: O, b: string): string { return a + b; } }
            declare const u: A | B;
            u.m(o);
            """
        ) should {
            have(any { it.code == 2554 })
        }
    }
}
