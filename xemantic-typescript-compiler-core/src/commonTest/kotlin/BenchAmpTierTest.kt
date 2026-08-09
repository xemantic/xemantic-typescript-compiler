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

package com.xemantic.typescript.compiler.bench

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.SpineAmp
import com.xemantic.typescript.compiler.SpineDispatch
import kotlin.test.Test

/**
 * (WARM.14) round 867 — pins the `amp<N>` / `ampc<N>` tier family.
 *
 * This is the harness's first PARAMETERISED tier, and the parameter is the
 * whole point: the instrument's answer is a slope, so one process must be able
 * to run `amp8,ampc8,amp16,ampc16,amp32,ampc32` and hold both arms at three
 * amplification factors at ONE warmth. Three failure modes are pinned, each
 * silent in the harness's own output:
 *
 *  - **an unparsed tier**: `amp16` accepted but read as reps `0` would run an
 *    ordinary rebuild, print a zero table, and read as "the consultations cost
 *    nothing" — the answer this round could most easily reach by accident.
 *  - **a mixed-up sign**: `ampc16` is the control arm ONLY because its reps are
 *    negative. If it parsed as `+16` the two arms would be identical and their
 *    difference — which IS `s_p` — would be noise around zero.
 *  - **a leaked arm**: `reps` left set makes every LATER rebuild in the process
 *    pay `r` extra passes per node. That is a large, plausible-looking
 *    slowdown attributed to whatever tier follows, and [tierStop] clearing it
 *    is the only thing between this instrument and the (SERVE.1) failure class.
 */
class BenchAmpTierTest {

    private fun <T> withSavedModes(block: () -> T): T {
        val reps = SpineAmp.reps
        val dispatch = SpineDispatch.mode
        val timing = PassTiming.enabled
        try {
            return block()
        } finally {
            SpineAmp.reps = reps
            SpineDispatch.mode = dispatch
            PassTiming.enabled = timing
        }
    }

    @Test
    fun `the tier parser reads the amplification factor and the arm's sign`() {
        assert(ampReps("amp1") == 1)
        assert(ampReps("amp16") == 16)
        assert(ampReps("amp32") == 32)
        assert(ampReps("ampc16") == -16)
        assert(ampReps("ampc1") == -1)
    }

    /**
     * The vocabulary stays CLOSED around the family: a typo must be an error,
     * not a silently inert rebuild. `amp0` is in the list because a zero factor
     * is exactly the shape of "the amplifier never ran" and would otherwise be
     * accepted as a measurement of nothing.
     */
    @Test
    fun `anything that is not of the family is refused`() {
        for (bad in listOf("amp", "ampc", "amp0", "ampc0", "ampx8", "amp8x", "amp-8", "gated", "rows")) {
            assert(ampReps(bad) == null)
        }
        // …and the tiers that ARE accepted must not collide with the family.
        for (tier in TIERS) assert(ampReps(tier) == null)
    }

    /**
     * THE DISCRIMINATING PIN for the arm being LIVE. `tierBegin` arming a mode
     * that is cleared before the rebuild, or armed after it, is round 866's own
     * `BenchGatedTierTest` lesson; here the same shape decides whether the
     * amplification happened at all.
     */
    @Test
    fun `the amp arm is armed with its factor and arms no other probe`() = withSavedModes {
        tierBegin("amp16")
        assert(SpineAmp.reps == 16)
        // A timing probe armed alongside would add boundaries that do NOT
        // cancel between two `r` values taken in different rebuilds — the whole
        // derivation assumes the bracket is the only instrument in the arm.
        assert(!PassTiming.enabled)
        assert(SpineDispatch.mode == SpineDispatch.OFF)
        tierStop()
        assert(SpineAmp.reps == 0)

        tierBegin("ampc16")
        assert(SpineAmp.reps == -16)
        assert(!PassTiming.enabled)
        tierStop()
        assert(SpineAmp.reps == 0)
    }

    /**
     * A leaked `reps` is not a wrong answer — it is a wrong answer for the NEXT
     * tier, which is worse. Arm the amplifier, then arm an ordinary tier, and
     * the ordinary tier must be un-amplified.
     */
    @Test
    fun `a following tier is not silently amplified`() = withSavedModes {
        tierBegin("amp32")
        tierStop()
        tierBegin("rows")
        assert(SpineAmp.reps == 0)
        tierStop()
        assert(SpineAmp.reps == 0)
    }

    /** The report must label its own arm, from its own state (round 850). */
    @Test
    fun `the report names the arm it ran`() = withSavedModes {
        tierBegin("amp8")
        val real = tierReport("amp8")
        tierStop()
        assert("arm: REAL" in real)
        assert("reps: 8" in real)
        assert("amplified rejecting-consultation price" in real)

        tierBegin("ampc8")
        val control = tierReport("ampc8")
        tierStop()
        assert("arm: CONTROL" in control)
        assert("reps: -8" in control)
    }
}
