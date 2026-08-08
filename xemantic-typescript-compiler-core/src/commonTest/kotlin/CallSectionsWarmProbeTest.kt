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
 * (WARM.5) round 851: pins the two things added to [CallSections] so the
 * ~60% of `ccetSpineLeave` that `ArgSections` does not reach could be
 * attributed WARM — the [CallSections.COARSE] calibration twin, and the EXIT
 * CENSUS.
 *
 * ## Why each pin is built to fail if the change is inert
 *
 * A probe's whole claim is that it measures without perturbing, and both
 * halves of that are silently breakable:
 *
 *  * **COARSE** exists only to price the probe's own boundary DIFFERENTIALLY
 *    (round 734 — never by an empty-span loop). If the anchor test were dropped
 *    it would still produce a plausible table, just with ON's boundary count,
 *    and the differential would read ~0 ns/boundary and the whole warm
 *    calibration would silently be a cold one. `a COARSE arm crosses far fewer
 *    boundaries` is what notices.
 *  * **The nested sub-measures must be ON-only.** [CallSections.t] returns 0
 *    under COARSE, so a `close()` that still recorded would charge the row a
 *    span measured from the epoch — a number so large it swamps everything,
 *    while nothing in the report says which row is bogus.
 *  * **The exit census is only a partition if EVERY invocation closes exactly
 *    one row.** `Σ exitInvRow == invocations` is exact by construction (round
 *    796) and is the assertion a missed exit path breaks.
 *  * **And a census can be right in total and wrong per row.** The two shapes
 *    below leave the function at two DIFFERENT rows, which is the only pin here
 *    that can see the census attributing to the wrong one.
 *
 * Everything is save-and-restored around the probe's global `mode` — never
 * assigned back to a guessed default (round 619).
 */
class CallSectionsWarmProbeTest {

    /** A call whose callee resolves to `any`: the core bails in `EARLY_GATES`. */
    private val anyCalleeSource = """
        declare const f: any;
        f(1);
    """.trimIndent()

    /** A plain call with one signature: the core runs to `SINGLE_SIG` and ends there. */
    private val singleSigSource = """
        function g(x: number): void {}
        g(1);
        g(2);
    """.trimIndent()

    /**
     * A call whose PROLOGUE actually runs. The round-793 pre-gate
     * (`ccetPrologueMayFire`) refuses ~98% of all call expressions, so a plain
     * `g(1)` never closes [CallSections.N_PROLOGUE] at all and a pin written on
     * one would assert against an empty population. A property-access callee
     * with a string-literal argument is one of the gate's four keys, and none of
     * the seven walkers fires for this shape, so the prologue also COMPLETES —
     * which is what makes the span closeable.
     */
    private val prologueSource = """
        declare const o: { m(s: string): void };
        o.m("x");
    """.trimIndent()

    /** Run [source] with the probe armed at [mode], restoring whatever was there. */
    private fun withProbe(mode: Int, source: String): List<Diagnostic> {
        val saved = CallSections.mode
        try {
            CallSections.reset()
            CallSections.mode = mode
            return diagnose(source)
        } finally {
            CallSections.mode = saved
        }
    }

    private fun closes(): Long = CallSections.calls.sum()

    @Test
    fun `the probe is behaviour-free - OFF and ON and COARSE agree on every diagnostic`() {
        val source = """
            declare const f: any;
            function g(x: number): void {}
            f(1);
            g("nope");
            g();
        """.trimIndent()
        val off = withProbe(CallSections.OFF, source).map { "${it.code}@${it.start}:${it.message}" }
        val on = withProbe(CallSections.ON, source).map { "${it.code}@${it.start}:${it.message}" }
        val coarse = withProbe(CallSections.COARSE, source).map { "${it.code}@${it.start}:${it.message}" }
        assert(on == off)
        assert(coarse == off)
        assert(off.isNotEmpty())
    }

    @Test
    fun `a COARSE arm crosses far fewer boundaries than an ON arm on the same source`() {
        withProbe(CallSections.ON, singleSigSource)
        val onCloses = closes()
        val onInvocations = CallSections.invocations
        withProbe(CallSections.COARSE, singleSigSource)
        val coarseCloses = closes()
        val coarseInvocations = CallSections.invocations
        // Non-vacuous: both arms compiled the same program and saw the same calls.
        assert(onInvocations > 0)
        assert(coarseInvocations == onInvocations)
        // The differential exists at all — this is what an unenforced anchor
        // test would destroy.
        assert(coarseCloses < onCloses)
    }

    @Test
    fun `COARSE keeps exactly the four partition anchors`() {
        val anchors = CallSections.coarseAnchor.indices.filter { CallSections.coarseAnchor[it] }
        assert(
            anchors == listOf(
                CallSections.B216, CallSections.CALLEE_TYPE,
                CallSections.CALL_SIGS, CallSections.ENTRY,
            ).sorted()
        )
    }

    @Test
    fun `the nested sub-measures are ON-only - a COARSE arm records none of them`() {
        withProbe(CallSections.ON, prologueSource)
        val onProlog = CallSections.calls[CallSections.N_PROLOGUE]
        val onCallee = CallSections.calls[CallSections.N_CALLEE_BAIL] +
            CallSections.calls[CallSections.N_CALLEE_LIVE]
        withProbe(CallSections.COARSE, prologueSource)
        val coarseProlog = CallSections.calls[CallSections.N_PROLOGUE]
        val coarseCallee = CallSections.calls[CallSections.N_CALLEE_BAIL] +
            CallSections.calls[CallSections.N_CALLEE_LIVE]
        assert(onProlog > 0)
        assert(onCallee > 0)
        assert(coarseProlog == 0L)
        assert(coarseCallee == 0L)
    }

    /**
     * The round-793 pre-gate is what makes [CallSections.N_PROLOGUE] a SPARSE
     * row: a plain identifier callee never enters the prologue at all, so a
     * table reading "the prologue costs X over N calls" is a claim about the
     * ~2% the gate admits, never about every call expression.
     */
    @Test
    fun `the prologue span exists only for the calls the round-793 gate admits`() {
        withProbe(CallSections.ON, singleSigSource)
        assert(CallSections.invocations == 2L)
        assert(CallSections.calls[CallSections.N_PROLOGUE] == 0L)
        withProbe(CallSections.ON, prologueSource)
        assert(CallSections.invocations == 1L)
        assert(CallSections.calls[CallSections.N_PROLOGUE] == 1L)
    }

    @Test
    fun `the exit census is an EXACT partition of the invocations`() {
        withProbe(CallSections.ON, singleSigSource)
        val exits = CallSections.exitInvRow.sum()
        assert(CallSections.invocations > 0)
        assert(exits == CallSections.invocations)
    }

    @Test
    fun `the exit census is ON-only - a COARSE arm records no exits`() {
        withProbe(CallSections.COARSE, singleSigSource)
        assert(CallSections.invocations > 0)
        assert(CallSections.exitInvRow.sum() == 0L)
    }

    @Test
    fun `an any-typed callee leaves at the early-gates row and a plain call does not`() {
        withProbe(CallSections.ON, anyCalleeSource)
        val anyExitsEarly = CallSections.exitInvRow[CallSections.EARLY_GATES]
        withProbe(CallSections.ON, singleSigSource)
        val plainExitsEarly = CallSections.exitInvRow[CallSections.EARLY_GATES]
        val plainExitsAtSingleSig = CallSections.exitInvRow[CallSections.SINGLE_SIG]
        assert(anyExitsEarly == 1L)
        assert(plainExitsEarly == 0L)
        assert(plainExitsAtSingleSig == 2L)
    }

    @Test
    fun `the census charges the callee resolution it paid for to the row it left from`() {
        withProbe(CallSections.ON, anyCalleeSource)
        // The `any` callee WAS resolved — that resolution is the cost the exit
        // census exists to attribute — and its outcome was the discarded one.
        assert(CallSections.exitCalleeNanos[CallSections.EARLY_GATES] > 0L)
        assert(CallSections.exitCalleeBail[CallSections.EARLY_GATES] == 1L)
        assert(CallSections.exitCalleeBail.sum() == 1L)
    }

    @Test
    fun `the census charges the prologue to the row the paying invocation left from`() {
        withProbe(CallSections.ON, prologueSource)
        assert(CallSections.exitPrologueNanos[CallSections.SINGLE_SIG] > 0L)
        assert(CallSections.exitInvRow[CallSections.SINGLE_SIG] == 1L)
        // A plain call pays no prologue at all, so nothing is charged anywhere.
        withProbe(CallSections.ON, singleSigSource)
        assert(CallSections.exitPrologueNanos.sum() == 0L)
    }

    @Test
    fun `the census records whether the invocation bought a diagnostic`() {
        withProbe(CallSections.ON, singleSigSource)
        assert(CallSections.exitEmitRow.sum() == 0L)
        withProbe(
            CallSections.ON,
            """
                function g(x: number): void {}
                g("nope");
            """.trimIndent(),
        )
        assert(CallSections.exitEmitRow.sum() == 1L)
        assert(CallSections.exitInvRow.sum() == 1L)
    }

    @Test
    fun `the report labels a COARSE arm COARSE and an ON arm ON`() {
        withProbe(CallSections.ON, singleSigSource)
        val savedOn = CallSections.mode
        CallSections.mode = CallSections.ON
        val onText = CallSections.report()
        CallSections.mode = CallSections.COARSE
        val coarseText = CallSections.report()
        CallSections.mode = savedOn
        assert("mode: ON" in onText)
        assert("mode: COARSE" in coarseText)
        // The census block is ON-only, so a COARSE report must not claim one.
        assert("EXIT CENSUS" in onText)
        assert("EXIT CENSUS" !in coarseText)
    }

    @Test
    fun `the report states the partition check and the csv carries the census rows`() {
        withProbe(CallSections.ON, singleSigSource)
        val savedOn = CallSections.mode
        CallSections.mode = CallSections.ON
        val text = CallSections.report()
        val csv = CallSections.csv()
        CallSections.mode = savedOn
        assert("EXACT" in text)
        assert("exitPro: " in csv)
        assert("exitCallee: " in csv)
        assert("exitEmit: " in csv)
    }
}
