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
 * (JIT.1)(c) round 811 — `checkSingleCallExpressionTypesCore` was 15,567
 * bytecodes, HotSpot never JIT-compiled it, and it is now an entry at 5,149 plus
 * four helpers carved out of the committed `CallSections` partition
 * (round 734's (CALL.1)(a) instrument):
 *
 *  * [Checker.ccetPrologueWalkers] — the seven dedicated walkers behind round
 *    793's `ccetPrologueMayFire` gate, which STAYS in the entry;
 *  * [Checker.ccetUnionCalleeChecks] — B60.14's union-callee branch;
 *  * [Checker.ccetNoCallSignatureDiagnostics] — the `signatures.isEmpty()` branch;
 *  * [Checker.ccetExplicitTypeArguments] — the explicit-type-argument branch.
 *
 * `HugeMethodLimitTest` reads the compiled `Code` attribute lengths and is the
 * guard for the SIZE. This class pins what a size check cannot see: that each
 * helper still runs and still says its own distinctive thing, that the entry
 * honours the signals the helpers return, and that the emission ORDER within a
 * file is unchanged. Each arm pin asserts the distinctive MESSAGE and a COUNT,
 * because a double emission is exactly the failure mode a dropped return signal
 * produces.
 *
 * **Discrimination, measured — 2 of the 4 seams, each mistake injected ALONE on
 * its own build, control first (18 pins, 0 failed):**
 *
 *  * dropping the entry's `return` after [Checker.ccetNoCallSignatureDiagnostics]
 *    fails **6** pins;
 *  * ignoring [Checker.ccetExplicitTypeArguments]' `true` fails **2** (its own
 *    seam pin and the ordering pin);
 *  * ignoring [Checker.ccetPrologueWalkers]' `true` fails **0**, twice — once
 *    against the `super` shapes and once against a purpose-built `reduce<U>`
 *    retry — because `getCalleeType("super")` is `anyType` and the walkers'
 *    other continuations meet `any`-typed lib parameters;
 *  * ignoring [Checker.ccetUnionCalleeChecks]' `true` fails **0**, twice — the
 *    duplicate emitter is unreachable by construction (see the arm pin), and
 *    B516's combined-signature retry is green as well.
 *
 * Both undiscriminated signals are REDUNDANT GUARDS on today's code; they are
 * kept because the monolith had them, and the pins written for them are named
 * as arm pins rather than seam pins, per the standing rule.
 */
class CcetSplitTest {

    // ── ccetPrologueWalkers ─────────────────────────────────────────────────

    @Test
    fun `prologue arm - a super call with explicit type arguments is TS2754`() {
        val d = diagnose(
            """
            class B { constructor() {} }
            class D extends B {
                constructor() { super<number>(); }
            }
            """
        )
        assert(d.count { it.code == 2754 } == 1)
        assert(d.first { it.code == 2754 }.message == "'super' may not use type arguments.")
    }

    @Test
    fun `prologue arm - Object create with a primitive argument is TS2345 against 'object'`() {
        val d = diagnose("const o = Object.create(1);")
        assert(d.count { it.code == 2345 } == 1)
        assert(d.first { it.code == 2345 }.message ==
            "Argument of type 'number' is not assignable to parameter of type 'object'.")
    }

    @Test
    fun `prologue arm - a super method call checks its arguments against the base signature`() {
        val d = diagnose(
            """
            class B { m(a: string): void {} }
            class D extends B {
                n(): void { super.m(1); }
            }
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(d.first { it.code == 2345 }.message ==
            "Argument of type 'number' is not assignable to parameter of type 'string'.")
    }

    // ── ccetUnionCalleeChecks ───────────────────────────────────────────────

    @Test
    fun `union arm - a partly-callable union callee is TS2349 naming the non-callable member`() {
        val d = diagnose(
            """
            declare const f: (() => void) | number;
            f();
            """
        )
        assert(d.count { it.code == 2349 } == 1)
        val e = d.first { it.code == 2349 }
        assert(e.message == "This expression is not callable.")
        assert(e.messageChain.any { it.contains("Not all constituents of type") })
        assert(e.messageChain.any { it.contains("has no call signatures") })
    }

    // ── ccetNoCallSignatureDiagnostics ──────────────────────────────────────

    @Test
    fun `no-signatures arm - calling a class value is TS2348 with the new suggestion`() {
        val d = diagnose(
            """
            class C {}
            const c = C();
            """
        )
        assert(d.count { it.code == 2348 } == 1)
        assert(d.first { it.code == 2348 }.message ==
            "Value of type 'typeof C' is not callable. Did you mean to include 'new'?")
    }

    @Test
    fun `no-signatures arm - a wholly non-callable union callee names NO constituent`() {
        val d = diagnose(
            """
            declare const g: number | string;
            g();
            """
        )
        assert(d.count { it.code == 2349 } == 1)
        assert(d.first { it.code == 2349 }.messageChain.any {
            it.contains("No constituent of type")
        })
    }

    @Test
    fun `no-signatures arm - calling a primitive names the wrapper interface`() {
        val d = diagnose(
            """
            declare const n: number;
            n();
            """
        )
        assert(d.count { it.code == 2349 } == 1)
        assert(d.first { it.code == 2349 }.messageChain.any {
            it.contains("Type 'Number' has no call signatures.")
        })
    }

    // ── ccetExplicitTypeArguments ───────────────────────────────────────────

    @Test
    fun `type-argument arm - an explicit type argument violating its constraint is TS2344`() {
        val d = diagnose(
            """
            function f<T extends string>(x: T): void {}
            f<number>(1);
            """
        )
        assert(d.count { it.code == 2344 } == 1)
        assert(d.first { it.code == 2344 }.message ==
            "Type 'number' does not satisfy the constraint 'string'.")
    }

    @Test
    fun `type-argument arm - the arguments are checked against the INSTANTIATED signature`() {
        val d = diagnose(
            """
            declare function h<T>(x: T): number;
            const r = h<number>("a");
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(d.first { it.code == 2345 }.message ==
            "Argument of type 'string' is not assignable to parameter of type 'number'.")
    }

    // ── the two seams that DISCRIMINATE, and the four single-emission pins
    //    written as seams that measurably do not (see the class doc) ────────

    @Test
    fun `union arm - a partly-callable union yields exactly one diagnostic`() {
        // NOT a seam pin, and it was written as one: round 811 measured that
        // ignoring ccetUnionCalleeChecks' `true` leaves every pin GREEN. The
        // duplicate emitter in the `signatures.isEmpty()` branch is unreachable
        // for this shape BY CONSTRUCTION — getCallSignaturesOfType concatenates
        // the constituents', so a union with any callable member has a non-empty
        // signature list. What this pin really guards is single emission against a
        // future second emitter.
        val d = diagnose(
            """
            declare const f: (() => void) | number;
            f();
            """
        )
        assert(d.size == 1)
    }

    @Test
    fun `type-argument seam - the call is argument-checked ONCE, not again un-instantiated`() {
        // ccetExplicitTypeArguments ends by checking the arguments against the
        // instantiated signature and returning; the single-signature path below
        // would check the same arguments again against the UN-instantiated one,
        // whose parameter is the CONSTRAINT rather than the supplied type argument.
        val d = diagnose(
            """
            function f<T extends string>(x: T): void {}
            f<number>(1);
            """
        )
        assert(d.size == 1)
    }

    @Test
    fun `no-signatures seam - the entry returns after the branch instead of resolving overloads`() {
        val d = diagnose(
            """
            class C {}
            const c = C();
            """
        )
        assert(d.size == 1)
    }

    @Test
    fun `prologue arm - a super call's arguments are reported exactly once`() {
        // NOT a seam pin either (round 811, measured): `getCalleeType("super")` is
        // anyType, so an entry that ignored ccetPrologueWalkers' `true` would bail
        // at the any/error gate before any second emitter could run.
        val d = diagnose(
            """
            class B { constructor(a: string) {} }
            class D extends B {
                constructor() { super(1); }
            }
            """
        )
        assert(d.count { it.code == 2345 } == 1)
    }

    @Test
    fun `prologue arm - a firing reduce walker yields exactly one diagnostic`() {
        // Written as the prologue seam's purpose-built RETRY, because the `super`
        // shapes provably cannot discriminate it: this is the one prologue walker
        // whose continuation would reach a real signature (`reduce<U>` with a
        // `keyof` callback parameter, which the explicit-type-argument path below
        // would check again against the instantiated lib signature). Measured: the
        // ablation leaves it GREEN too, so the prologue's return signal is a
        // redundant guard on today's code and this is an ARM pin.
        val d = diagnose(
            """
            interface X { a: number }
            declare const arr: string[];
            const r = arr.reduce<number>((acc: number, key: keyof X) => acc, 0);
            """
        )
        assert(d.count { it.code == 2345 } == 1)
    }

    @Test
    fun `union arm - a combined-signature union call is reported ONCE`() {
        // The union seam's purpose-built RETRY: B516's combined signature is the
        // one case that leaves a NON-EMPTY signature list and still ends in an
        // emission, so ignoring the return signal would reach the overload path.
        // Measured: it stays GREEN too — recorded as NOT DISCRIMINATED, and this
        // is therefore an arm pin over B516's combined-parameter intersection.
        val d = diagnose(
            """
            declare const f: ((x: number) => void) | ((x: boolean) => void);
            f(1);
            """
        )
        assert(d.size == 1)
    }

    // ── order, recursion, control ───────────────────────────────────────────

    @Test
    fun `the emission order across four helpers follows source order`() {
        val d = diagnose(
            """
            class B { constructor() {} }
            class D extends B {
                constructor() { super<number>(); }
            }
            declare const f: (() => void) | number;
            f();
            class C {}
            const c = C();
            function g<T extends string>(x: T): void {}
            g<number>(1);
            """
        )
        assert(d.map { it.code } == listOf(2754, 2349, 2348, 2344))
    }

    @Test
    fun `a helper's checks reach a call nested in another call's argument`() {
        val d = diagnose(
            """
            declare function take(x: number): number;
            class C {}
            const v = take(take(C()));
            """
        )
        assert(d.count { it.code == 2348 } == 1)
    }

    @Test
    fun `negative control - ordinary calls through every stayed-inline path are silent`() {
        val d = diagnose(
            """
            function one(x: number): number { return x; }
            function two<T>(x: T): T { return x; }
            declare const callable: (() => void) | ((a?: number) => void);
            const a = one(1);
            const b = two<string>("s");
            callable();
            """
        )
        assert(d.isEmpty())
    }
}
