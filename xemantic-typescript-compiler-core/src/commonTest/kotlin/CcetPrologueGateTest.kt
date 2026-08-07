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
 * (ENGINE.2g) round 793: pins the PROLOGUE pre-gate of
 * `checkSingleCallExpressionTypes` — the seven dedicated walkers that run at
 * every call expression in the program and fire, on the compiler profile, never.
 *
 * **Why this gate is not round 792's.** That one worked because all ~42
 * emissions of `checkMemberAccessMissing` assert the SAME proposition, so one
 * refutation killed them all. These seven do not: they emit TS2345 / TS18048 /
 * TS2339 / TS2349 / TS2754, five different claims. What they share is a KEY —
 * each is reachable only for a narrow syntactic shape of the CALLEE — so a
 * single classification of the callee refutes all seven at once. The
 * generalisation that transfers is "one cheap question in front of many
 * emissions", not "one proposition".
 *
 * **Each pin here is one leg of that classification, and each is the shape a
 * future widening would break.** A gate that refuted everything (fault A) loses
 * all four diagnostics below; a gate that dropped only its string-literal leg
 * (fault B) loses the first. They are behavioural, not probe-based, on purpose:
 * the gate's own effect is invisible by design — there is nothing to assert
 * about a call whose prologue correctly did nothing.
 */
class CcetPrologueGateTest {

    /**
     * B216's dependent indexed-access constraint (`V extends O[K1][K2]`). The
     * gate's leg for it is "at least one argument is a string literal": B216
     * cannot emit without one (`expr.arguments[pIdx] as? StringLiteralNode ?:
     * break`) and — the property that makes the skip safe rather than merely
     * quiet — it cannot reach any side-effecting call without one either.
     */
    private val dependentIndexedSource = """
        class C<O> {
            m<K1 extends keyof O, V extends O[K1]>(k: K1, v: V): void {}
        }
        declare const c: C<{ a: string }>;
        c.m("a", 1);
    """.trimIndent()

    /** B232's `Object.create(<primitive>)` — the gate's NAME leg (`create`). */
    private val objectCreateSource = """
        declare var o1: any;
        o1 = Object.create(5);
    """.trimIndent()

    /**
     * The two `super` legs in one fixture: `super<T>(…)` is TS2754, and
     * `super.m(…)`'s argument check exists only in the prologue at all —
     * `getCalleeType("super")` answers `any`, so the general path bails.
     */
    private val superSource = """
        class B2 { constructor(x: number) {} m(y: string): void {} }
        class D2 extends B2 {
            constructor() { super<number>(2); }
            n() { super.m(3); }
        }
    """.trimIndent()

    @Test
    fun `a dependent indexed-access constraint still reports TS2345`() {
        val d = diagnose(dependentIndexedSource)
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'number' is not assignable to parameter of type 'string'."
        })
    }

    @Test
    fun `Object create with a primitive still reports TS2345`() {
        val d = diagnose(objectCreateSource)
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'number' is not assignable to parameter of type 'object'."
        })
    }

    @Test
    fun `a super call with type arguments still reports TS2754`() {
        val d = diagnose(superSource)
        assert(d.any { it.code == 2754 })
    }

    @Test
    fun `a super method call still argument-checks`() {
        // This one has no other source in the compiler: the general path treats
        // `super` as `any` and returns before argument checking, so a gate that
        // skipped the prologue would make the call silent rather than wrong.
        val d = diagnose(superSource)
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'number' is not assignable to parameter of type 'string'."
        })
    }

    @Test
    fun `negative control - a refuted call keeps its ordinary diagnostics`() {
        // The gate refutes this whole file (no super, no keyed name, no
        // string-literal argument), so nothing in the prologue runs — and the
        // ordinary argument check must still fire. Without this the suite could
        // not tell "the prologue was skipped" from "call checking was skipped".
        val d = diagnose(
            """
            function f(y: string): void {}
            f(1);
            """.trimIndent(),
        )
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'number' is not assignable to parameter of type 'string'."
        })
    }

    /**
     * The equivalence itself, measured on a fixture where the prologue actually
     * FIRES — which no dashboard profile does, so this pin is the live falsifier
     * the profiles cannot be.
     */
    @Test
    fun `no call the gate would skip reaches a prologue emission`() {
        val src = """
            declare var o2: any;
            function g(y: string): void {}
            o2 = Object.create(5);
            g(1);
            o2.h(2);
        """.trimIndent()
        val savedMode = CallSections.mode
        val savedProbe = CallSections.preGateProbe
        CallSections.reset()
        CallSections.mode = CallSections.ON
        CallSections.preGateProbe = true
        try {
            diagnose(src)
            val skipped = CallSections.pgSkipCalls
            val skippedFired = CallSections.pgSkipFired
            val keptFired = CallSections.pgKeepFired
            // THE invariant: nothing the gate refuses ever had anything to say.
            assert(skippedFired == 0L)
            // Non-vacuity in both halves — the gate refutes something here, and
            // the firing detector is alive on this fixture.
            assert(skipped > 0L)
            assert(keptFired > 0L)
        } finally {
            CallSections.preGateProbe = savedProbe
            CallSections.mode = savedMode
            CallSections.reset()
        }
    }

    /**
     * The probe's positive CONTROL, in process: with the gate refuting EVERY
     * call, the falsifier column must become non-zero. A zero here would mean
     * the pin above is measuring nothing (CLAUDE.md's round-765 rule) — and on
     * the compiler profile it WOULD be zero, because nothing in that program
     * reaches a prologue emission at all.
     */
    @Test
    fun `the bogus control makes the falsifier column fire`() {
        val savedMode = CallSections.mode
        val savedProbe = CallSections.preGateProbe
        val savedBogus = CallSections.preGateBogus
        CallSections.reset()
        CallSections.mode = CallSections.ON
        CallSections.preGateProbe = true
        CallSections.preGateBogus = true
        try {
            diagnose(objectCreateSource)
            assert(CallSections.pgSkipFired > 0L)
            assert(CallSections.pgKeepCalls == 0L)
        } finally {
            CallSections.preGateBogus = savedBogus
            CallSections.preGateProbe = savedProbe
            CallSections.mode = savedMode
            CallSections.reset()
        }
    }

}
