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
 * (WARM.14) round 867 — pins the amplified rejecting-consultation instrument.
 *
 * The measurement it serves is a SLOPE over a population that no output shows,
 * so every way it can be wrong is silent. Four of them are pinned here, each
 * chosen because it REDDENS against a specific mistake rather than because it
 * describes the happy path:
 *
 *  - **an inverted mask** would make the amplifier re-run the handlers the
 *    table KEEPS — which both measures the wrong population and re-emits their
 *    diagnostics. `the skip mask is exactly the complement of the table` is the
 *    static half and `amplification does not change what the compiler answers`
 *    the dynamic half.
 *  - **an inert loop** — a `reps` never read, a pass that consults nothing, a
 *    control arm that is silently the real one — is what the exact-multiple
 *    arithmetic sees, and it is the same falsification round 759 used, for the
 *    same reason: a slope is a plausible-looking number whether or not the work
 *    happened.
 *  - **a leaked arm** is the (SERVE.1) failure class: `reps` is a process-global
 *    that costs every LATER compile in the process `r` extra passes per node,
 *    which is why the CLI writes it through the ledger and `tierStop` clears it.
 *
 * The population itself is asserted non-empty (round 794: a pin whose
 * population is zero passes vacuously and measures nothing).
 */
class SpineAmpProbeTest {

    private val source = """
        interface Box<T> { value: T }
        enum E { A, B }
        function pick(b: Box<string>, e: E): string {
            if (e === E.A) { return b.value }
            for (const c of b.value) { if (c === "x") return c }
            switch (e) { case E.B: break; default: break }
            return b.value.length > 0 ? b.value : ""
        }
        class C { p = 1; m(n: number) { return n + this.p } }
        const c = new C()
        const r = pick({ value: "s" }, E.A) + c.m(2)
    """.trimIndent()

    private fun <T> withReps(reps: Int, body: () -> T): T {
        val saved = SpineAmp.reps
        SpineAmp.reset()
        SpineAmp.reps = reps
        try {
            return body()
        } finally {
            SpineAmp.reps = saved
            SpineAmp.reset()
        }
    }

    @Test
    fun `the skip mask is exactly the complement of the dispatch table`() {
        var enterSkipped = 0L
        var leaveSkipped = 0L
        for (kind in 0 until SpineDispatch.KINDS) {
            val keptEnter = SpineDispatch.enterTable[kind].toSet()
            for (h in 0 until SpineDispatch.enterCount) {
                val skipBit = (SpineDispatch.enterSkipMask[kind] and (1L shl h)) != 0L
                assert(skipBit == (h !in keptEnter))
            }
            val keptLeave = SpineDispatch.leaveTable[kind].toSet()
            for (h in 0 until SpineDispatch.leaveCount) {
                val skipBit = (SpineDispatch.leaveSkipMask[kind] and (1L shl h)) != 0L
                assert(skipBit == (h !in keptLeave))
            }
            enterSkipped += SpineDispatch.enterSkipMask[kind].countOneBits()
            leaveSkipped += SpineDispatch.leaveSkipMask[kind].countOneBits()
        }
        // No bit may be set above the handler counts — the masks are handed to
        // passes that bit-test 46 and 13 slots, so a stray high bit would be a
        // consultation of a handler that does not exist.
        for (kind in 0 until SpineDispatch.KINDS) {
            assert(SpineDispatch.enterSkipMask[kind] ushr SpineDispatch.enterCount == 0L)
            assert(SpineDispatch.leaveSkipMask[kind] ushr SpineDispatch.leaveCount == 0L)
        }
        // The population is what the whole measurement divides by: an empty one
        // would make every assertion below vacuously true.
        assert(enterSkipped > 0L)
        assert(leaveSkipped > 0L)
    }

    @Test
    fun `nothing is recorded while the amplifier is off`() {
        withReps(0) {
            diagnose(source)
            assert(SpineAmp.nodes == 0L)
            assert(SpineAmp.nanos == 0L)
            assert(SpineAmp.consults == 0L)
            assert(SpineAmp.expected == 0L)
        }
    }

    @Test
    fun `amplification does not change what the compiler answers`() {
        val plain = withReps(0) { diagnose(source) }.map { "${it.code}@${it.start}" }
        val amplified = withReps(4) { diagnose(source) }.map { "${it.code}@${it.start}" }
        val controlled = withReps(-4) { diagnose(source) }.map { "${it.code}@${it.start}" }
        assert(amplified == plain)
        assert(controlled == plain)
    }

    @Test
    fun `the real arm performs exactly reps times the would-consult population`() {
        withReps(3) {
            diagnose(source)
            val nodes = SpineAmp.nodes
            val expected = SpineAmp.expected
            val consults = SpineAmp.consults
            assert(nodes > 0L)
            assert(expected > 0L)
            // The arithmetic falsification (round 759): an elided or short-run
            // loop cannot satisfy an EXACT multiple, and `expected` is counted
            // by a different code path (a population count of the masks) than
            // the passes count with (a per-slot branch).
            assert(consults == 3L * expected)
        }
        withReps(7) {
            diagnose(source)
            assert(SpineAmp.consults == 7L * SpineAmp.expected)
        }
    }

    @Test
    fun `the control arm suppresses every consultation over the same population`() {
        var realExpected = 0L
        var realNodes = 0L
        withReps(5) {
            diagnose(source)
            realExpected = SpineAmp.expected
            realNodes = SpineAmp.nodes
        }
        withReps(-5) {
            diagnose(source)
            // Same nodes, same would-consult population — the control differs
            // from the real arm in the mask CONTENTS and in nothing else, which
            // is what makes its slope the loop skeleton's price rather than a
            // different loop's.
            assert(SpineAmp.nodes == realNodes)
            assert(SpineAmp.expected == realExpected)
            assert(SpineAmp.expected > 0L)
            assert(SpineAmp.consults == 0L)
        }
    }
}
