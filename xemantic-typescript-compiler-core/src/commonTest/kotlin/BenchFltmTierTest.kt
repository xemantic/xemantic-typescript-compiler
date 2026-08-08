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
import com.xemantic.typescript.compiler.FltmCensus
import com.xemantic.typescript.compiler.PassTiming
import kotlin.test.Test

/**
 * (WARM.9) round 861 — pins the `fltm` tier `BenchMain` gained this round.
 *
 * `init:buildFileLocalTypeMaps` is the only tail pass over 1% of a WARM rebuild
 * (268.4 ms = 3.56%), and the only census it has ever had was COLD (round 829,
 * `--fltmCensus`, `docs/perf/setup-phase-and-huge-methods.md` § 27). Like
 * [com.xemantic.typescript.compiler.FrontEnd] before round 859, the instrument
 * existed and had no tier name, so it could not be run warm at all.
 *
 * **The discrimination problem this pin has and [BenchFrontEndTierTest] does
 * not.** [FrontEnd]'s recording entry points self-gate (`if (mode != ON)
 * return`), so a fixture that records through them and finds its values in the
 * report has proved the arm. [FltmCensus]'s do NOT: every hook in `Checker` is
 * written `if (FltmCensus.on) FltmCensus.noteX(...)`, i.e. **the guard is at the
 * CALL SITE**, and a fixture calling `noteX` directly would record with the tier
 * armed and with it dropped alike — round 807's blind-pin mechanism, and a pin
 * that cannot fail is worse than none. So [recordAsCheckerWould] reproduces the
 * call-site idiom VERBATIM, guard included: drop the arm from [tierBegin] and
 * the guard is false, nothing is recorded, and the report reads
 * `direct resolves: 0`.
 *
 * The second thing this tier must get right is that it arms **two** probes —
 * the census, and [PassTiming]'s `rows` — because the census prices a
 * sub-population of a pass whose own row it does not measure, and the row's warm
 * draw spread is 41%. A tier that armed only the census would produce a number
 * with nothing to divide it by; both halves are therefore pinned in the report
 * text of ONE `measureTier` call.
 *
 * Every pin here saves and restores the modes rather than resetting them to a
 * guessed default (round 619).
 */
class BenchFltmTierTest {

    private fun <T> withSavedModes(block: () -> T): T {
        val savedOn = FltmCensus.on
        val savedEnabled = PassTiming.enabled
        val savedDetail = PassTiming.detail
        val savedSpineDetail = PassTiming.spineDetail
        try {
            return block()
        } finally {
            FltmCensus.on = savedOn
            FltmCensus.reset()
            PassTiming.enabled = savedEnabled
            PassTiming.detail = savedDetail
            PassTiming.spineDetail = savedSpineDetail
            PassTiming.reset()
        }
    }

    /**
     * The `Checker` call-site idiom, verbatim — the guard is what the tier arms,
     * and copying it is the only way this fixture can fail when the arm is gone.
     */
    private fun recordAsCheckerWould() {
        if (FltmCensus.on) FltmCensus.beginSetup()
        val prev = if (FltmCensus.on) FltmCensus.enterDirect(4242, "decl") else -1
        if (FltmCensus.on) FltmCensus.noteAsk(4242)
        if (FltmCensus.on) FltmCensus.leaveDirect(4242, prev, 1_000_000L)
        if (FltmCensus.on) FltmCensus.noteStored("t.ts", "Alpha", 4242)
        if (FltmCensus.on) FltmCensus.endSetup()
        if (FltmCensus.on) FltmCensus.noteRead("t.ts", "Alpha", hit = true)
        if (FltmCensus.on) FltmCensus.noteRead("t.ts", "Beta", hit = false)
    }

    /**
     * A probe with no tier name cannot be taken warm at all — that is the whole
     * gap this round closed, so the name itself is pinned.
     */
    @Test
    fun `the fltm census has a tier name`() {
        assert("fltm" in TIERS)
    }

    /**
     * THE DISCRIMINATING PIN. Nothing below can appear in the text unless
     * [tierBegin] set `FltmCensus.on` BEFORE the build ran and [tierReport] was
     * taken BEFORE [tierStop] released the counters.
     */
    @Test
    fun `the fltm tier arms the census for the build and reports what it recorded`() =
        withSavedModes {
            val (value, text) = measureTier("fltm") {
                recordAsCheckerWould()
                7
            }
            assert(value == 7)
            assert("(SETUP.2) buildFileLocalTypeMaps produced-vs-consumed census" in text)
            assert("direct resolves: 1" in text)
            assert("entries stored: 1" in text)
            assert("map reads: calls=1  distinct=1  misses=1" in text)
        }

    /**
     * …and the SAME rebuild must carry the pass table, or the census's ms has
     * nothing to be read against but another draw of a 41%-spread row.
     */
    @Test
    fun `the fltm tier reports the pass rows from the same rebuild`() = withSavedModes {
        val (_, text) = measureTier("fltm") { recordAsCheckerWould() }
        assert("== xtsc pass timing (INV.0) ==" in text)
        assert("tier: rows" in text)
    }

    /**
     * NEGATIVE CONTROL for both pins above — off the tier the identical
     * call-site-shaped recording does nothing at all, so a green pin cannot be
     * explained by the census simply always being on in this process.
     */
    @Test
    fun `negative control - off the tier the same guarded recording is a no-op`() = withSavedModes {
        FltmCensus.on = false
        FltmCensus.reset()
        recordAsCheckerWould()
        assert(FltmCensus.directResolves == 0L)
        assert(FltmCensus.storedEntries == 0)
        assert(FltmCensus.readHits == 0L)
        assert("direct resolves: 0" in FltmCensus.report())
    }

    /** …and [tierStop] must both disarm the census and release its counters. */
    @Test
    fun `tierStop disarms the fltm census and zeroes its counters`() = withSavedModes {
        tierBegin("fltm")
        assert(FltmCensus.on)
        assert(PassTiming.enabled)
        recordAsCheckerWould()
        assert(FltmCensus.directResolves == 1L)
        tierReport("fltm")
        assert(FltmCensus.directResolves == 1L)
        tierStop()
        assert(!FltmCensus.on)
        assert(FltmCensus.directResolves == 0L)
        assert(FltmCensus.storedEntries == 0)
    }
}
