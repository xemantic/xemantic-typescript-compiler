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
 * (ENGINE.2h) round 795: the TS2793 "the implementation would have succeeded"
 * probe is DEFERRED from every single-signature call to its single reader.
 *
 * The probe — `getOverloadImplementationRelated` + `getImplementationSignature`
 * + `allArgumentsMatch` — used to run before `checkArgumentsAgainstSignature`
 * for all 23,214 single-signature calls of the compiler profile, to build a
 * piece of RELATED INFO that only an argument diagnostic ever attaches (57 of
 * those calls reach one). This is round 791's deferral shape, not round 792's
 * gate shape: there is no cheap refutation to compute, only a reader to move
 * the computation to.
 *
 * **What the pins have to catch, in both directions.** A deferral that never
 * forces loses the related info (fault A — the TS2793 disappears); a deferral
 * that forces without the `allArgumentsMatch` gate attaches it where the
 * implementation would have rejected the call too (fault B — a TS2793 appears
 * on a call it does not describe). The two fixtures below differ ONLY in
 * whether the implementation accepts the argument, which is exactly that gate.
 */
class ImplRelatedDeferralTest {

    /**
     * One bodyless overload plus an implementation that WOULD have accepted
     * `"HI"`. `getCallSignaturesOfType` exposes only the overload, so this is a
     * single-SIGNATURE call — the population the deferral covers — and tsc
     * attaches TS2793 to its TS2345.
     */
    private val implAcceptsSource = """
        function foo(name: "SPAN"): void;
        function foo(name: string): void {}
        foo("HI");
    """.trimIndent()

    /**
     * The same shape with an implementation that rejects `"HI"` too. The
     * `allArgumentsMatch` gate is the whole reason this differs, and it is the
     * part of the answer a cache-mutation-order difference could move.
     */
    private val implRejectsSource = """
        function bar(name: "SPAN"): void;
        function bar(name: "DIV"): void {}
        bar("HI");
    """.trimIndent()

    @Test
    fun `an overload whose implementation would have accepted still carries TS2793`() {
        val d = diagnose(implAcceptsSource)
        assert(d.any {
            it.code == 2345 && it.relatedInformation.any { r ->
                r.code == 2793 && r.message ==
                    "The call would have succeeded against this implementation, but " +
                    "implementation signatures of overloads are not externally visible."
            }
        })
    }

    @Test
    fun `negative control - an implementation that rejects too carries no TS2793`() {
        val d = diagnose(implRejectsSource)
        assert(d.any { it.code == 2345 })
        assert(d.none { it.relatedInformation.any { r -> r.code == 2793 } })
    }

    @Test
    fun `negative control - a non-overloaded call carries no TS2793`() {
        val d = diagnose(
            """
            function baz(name: "SPAN"): void {}
            baz("HI");
            """.trimIndent(),
        )
        assert(d.any { it.code == 2345 })
        assert(d.none { it.relatedInformation.any { r -> r.code == 2793 } })
    }

    /**
     * THE DEFERRAL ITSELF. Before this round the probe ran once per
     * single-signature call; now it runs once per call that reaches its reader.
     * The fixture deliberately mixes many clean single-signature calls with one
     * failing overload call, so the two counts cannot coincide.
     */
    @Test
    fun `the probe is evaluated only where its reader is reached`() {
        val src = """
            function foo(name: "SPAN"): void;
            function foo(name: string): void {}
            function clean(n: number): number { return n; }
            clean(1);
            clean(2);
            clean(3);
            clean(4);
            foo("HI");
        """.trimIndent()
        val savedMode = CallSections.mode
        CallSections.reset()
        CallSections.mode = CallSections.ON
        try {
            diagnose(src)
            val singleSig = CallSections.calls[CallSections.SINGLE_SIG]
            val evaluated = CallSections.implRelatedCalls
            // Non-vacuity first: the fixture reaches both populations.
            assert(singleSig >= 5L)
            assert(evaluated > 0L)
            // The invariant: strictly fewer evaluations than single-signature
            // calls. Eagerly evaluated, the two are equal by construction.
            assert(evaluated < singleSig)
        } finally {
            CallSections.mode = savedMode
            CallSections.reset()
        }
    }

    /**
     * The equivalence, in process: the probe evaluated EAGERLY (where it used to
     * live) and again DEFERRED (at its reader) must answer the same
     * `Diagnostic`. Under the flag the eager verdict is the one honoured, so
     * this run also reproduces the pre-deferral binary.
     */
    @Test
    fun `the eager and deferred evaluations agree at the reader`() {
        val saved = CallSections.verifyImplRelated
        CallSections.reset()
        CallSections.verifyImplRelated = true
        try {
            diagnose(implAcceptsSource)
            assert(CallSections.implRelatedVerified > 0L)
            assert(CallSections.implRelatedVerifyDiff == 0L)
        } finally {
            CallSections.verifyImplRelated = saved
            CallSections.reset()
        }
    }

    /**
     * Round 790's FREE control: the same comparison over the COMPLEMENT — every
     * single-signature call, not only the ones that reach a reader. The
     * emission-site population is 57 per compiler-profile compile, so on its own
     * it bounds almost nothing.
     */
    @Test
    fun `the eager and deferred evaluations agree over the whole population`() {
        val savedV = CallSections.verifyImplRelated
        val savedA = CallSections.verifyImplRelatedAll
        CallSections.reset()
        CallSections.verifyImplRelated = true
        CallSections.verifyImplRelatedAll = true
        try {
            diagnose(implRejectsSource)
            assert(CallSections.implRelatedVerifiedAll > 0L)
            assert(CallSections.implRelatedVerifyAllDiff == 0L)
        } finally {
            CallSections.verifyImplRelatedAll = savedA
            CallSections.verifyImplRelated = savedV
            CallSections.reset()
        }
    }

    /**
     * The POSITIVE control — without it the two zeros above are compatible with
     * a dead comparator (CLAUDE.md's round-765 rule). With the `allArgumentsMatch`
     * gate dropped from the deferred evaluation only, the diff column MUST fire
     * on the fixture whose implementation rejects the argument.
     */
    @Test
    fun `the bogus control makes the diff column fire`() {
        val savedV = CallSections.verifyImplRelated
        val savedA = CallSections.verifyImplRelatedAll
        val savedB = CallSections.verifyImplRelatedBogus
        CallSections.reset()
        CallSections.verifyImplRelated = true
        CallSections.verifyImplRelatedAll = true
        CallSections.verifyImplRelatedBogus = true
        try {
            diagnose(implRejectsSource)
            assert(CallSections.implRelatedVerifyAllDiff > 0L)
        } finally {
            CallSections.verifyImplRelatedBogus = savedB
            CallSections.verifyImplRelatedAll = savedA
            CallSections.verifyImplRelated = savedV
            CallSections.reset()
        }
    }

    /**
     * ... and the control must NOT fire where the two answers agree anyway: on
     * the accepting fixture the dropped gate would have said yes regardless, so
     * a diff there would mean the control is perturbing something else.
     */
    @Test
    fun `the bogus control is silent where the gate accepts anyway`() {
        val savedV = CallSections.verifyImplRelated
        val savedA = CallSections.verifyImplRelatedAll
        val savedB = CallSections.verifyImplRelatedBogus
        CallSections.reset()
        CallSections.verifyImplRelated = true
        CallSections.verifyImplRelatedAll = true
        CallSections.verifyImplRelatedBogus = true
        try {
            diagnose(implAcceptsSource)
            assert(CallSections.implRelatedVerifiedAll > 0L)
            assert(CallSections.implRelatedVerifyAllDiff == 0L)
        } finally {
            CallSections.verifyImplRelatedBogus = savedB
            CallSections.verifyImplRelatedAll = savedA
            CallSections.verifyImplRelated = savedV
            CallSections.reset()
        }
    }

}
