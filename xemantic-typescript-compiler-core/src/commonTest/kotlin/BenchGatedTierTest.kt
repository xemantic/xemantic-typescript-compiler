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
import com.xemantic.typescript.compiler.SpineDispatch
import kotlin.test.Test

/**
 * (WARM.13) round 866 — pins the `gated` / `plain` A/B pair of [TIERS].
 *
 * These two tiers exist to answer one question inside ONE warm process: what
 * does the round-732 per-kind table do to a warm rebuild's WALL time? Every
 * other tier here arms a probe and its `ms` is therefore probe-inflated;
 * `gated` arms a behaviour-preserving DISPATCH change that counts nothing, and
 * `plain` arms nothing at all, so the two rebuilds are directly comparable and
 * `gated,plain,gated,plain` is a rotated interleave rather than a tier rebuild
 * held against a median measured before it.
 *
 * Two of these pins are built to REDDEN IF THE ARM IS INERT rather than to
 * describe it (round 859's `BenchFrontEndTierTest`, round 851's ablation):
 *
 * * The `plain` arm's whole content is the ABSENCE of instrumentation, and
 *   [tierBegin]'s `else` branch **enables the pass probe**. So a `plain` arm
 *   deleted from that `when` does not fail to run — it silently becomes a
 *   `full` arm, and the null arm of the A/B then costs ~1% MORE than the
 *   treated one, inverting the answer. `the plain arm arms nothing` is the pin
 *   that sees it, and it asserts both probes, not just the mode.
 * * A mode set before the rebuild and cleared after it is not the same claim as
 *   a mode that is LIVE DURING the rebuild: `the arm is live inside the build
 *   lambda` reads `SpineDispatch.mode` from inside [measureTier]'s own lambda,
 *   which is where the compile would see it.
 *
 * That the GATED path itself is behaviour-preserving is NOT pinned here — it is
 * `SpineDispatchProbeTest`'s `gated mode reproduces production diagnostics
 * exactly`, plus the whole-corpus verification recorded in
 * `docs/perf/dispatch-table.md` § 4.
 */
class BenchGatedTierTest {

    private fun <T> withSavedModes(block: () -> T): T {
        // Save-and-restore, never "assign the default back" (round 619): the
        // dispatch mode is fork-global, and the corpus-wide GATED verification
        // flips its initialiser.
        val dispatch = SpineDispatch.mode
        val timing = PassTiming.enabled
        try {
            return block()
        } finally {
            SpineDispatch.mode = dispatch
            PassTiming.enabled = timing
        }
    }

    @Test
    fun `both arms of the A-B pair are tier names the harness accepts`() {
        assert("gated" in TIERS)
        assert("plain" in TIERS)
    }

    @Test
    fun `the gated arm arms the derived table and nothing else`() = withSavedModes {
        tierBegin("gated")
        assert(SpineDispatch.mode == SpineDispatch.GATED)
        // A timing probe in the treated arm and not in the control would BE the
        // measured difference.
        assert(!PassTiming.enabled)
        tierStop()
        assert(SpineDispatch.mode == SpineDispatch.OFF)
    }

    /**
     * THE DISCRIMINATING PIN. Drop the `"plain" -> { }` arm from [tierBegin] and
     * this is the only thing that fails: the tier is still accepted, still runs
     * a rebuild, still prints an `ms` — it just runs it with the pass probe on.
     */
    @Test
    fun `the plain arm arms nothing`() = withSavedModes {
        // Deliberately arm something first, so a `tierBegin` that merely failed
        // to CLEAR state would also be caught.
        tierBegin("gated")
        tierStop()
        tierBegin("plain")
        assert(!PassTiming.enabled)
        assert(SpineDispatch.mode == SpineDispatch.OFF)
        tierStop()
        assert(!PassTiming.enabled)
        assert(SpineDispatch.mode == SpineDispatch.OFF)
    }

    /**
     * The arm must be live where the compile is, not merely around it. A stub
     * build is enough — [measureTier] is the harness's own order, extracted for
     * exactly this reason (round 851).
     */
    @Test
    fun `the arm is live inside the build lambda`() = withSavedModes {
        val (gatedInside, _) = measureTier("gated") { SpineDispatch.mode }
        assert(gatedInside == SpineDispatch.GATED)
        val (plainInside, _) = measureTier("plain") { SpineDispatch.mode to PassTiming.enabled }
        assert(plainInside == (SpineDispatch.OFF to false))
    }

    /**
     * Each arm's report must name the arm it ACTUALLY ran, from the live mode
     * (round 850's label defect), and must not be the probe's own table — a
     * GATED rebuild records nothing, so `SpineDispatch.report()` there prints an
     * all-zero table that reads as a failed measurement rather than as an arm
     * that measures by wall time.
     */
    @Test
    fun `each arm labels itself from its live mode and prints no zero table`() = withSavedModes {
        tierBegin("gated")
        val gatedText = tierReport("gated")
        tierStop()
        tierBegin("plain")
        val plainText = tierReport("plain")
        tierStop()
        assert("mode: ${SpineDispatch.GATED}" in gatedText)
        assert("pairs kept" in gatedText)
        assert("no probe armed" in plainText)
        assert("passTiming: false" in plainText)
        // The probe table's own header must appear in neither.
        assert("spine handler probe" !in gatedText)
        assert("spine handler probe" !in plainText)
    }

    /**
     * The shape figure the `gated` report quotes is the table's, so it must be
     * the table's: 46 enter handlers x 138 kinds is the denominator, and the
     * kept count has to be strictly smaller (that is the whole premise) and
     * strictly positive (an empty table would run no handler at all).
     */
    @Test
    fun `the gated report quotes the real table shape`() = withSavedModes {
        tierBegin("gated")
        val text = tierReport("gated")
        tierStop()
        val keptEnter = SpineDispatch.enterTable.sumOf { it.size }
        val allEnter = SpineDispatch.KINDS * SpineDispatch.enterNames.size
        assert(keptEnter > 0)
        assert(keptEnter < allEnter)
        assert("enter $keptEnter/$allEnter" in text)
    }
}
