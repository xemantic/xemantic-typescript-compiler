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
import com.xemantic.typescript.compiler.FrontEnd
import kotlin.test.Test

/**
 * (WARM.6) round 859 — pins the `frontend` tier `BenchMain` gained this round.
 *
 * The front end is **11.1% of the warm artifact** and the only attribution it
 * has ever had was COLD (`docs/perf/front-end-attribution.md`, round 738). The
 * `rows` tier can only price it as a RESIDUAL — `wall - checkerInitNanos` — so
 * splitting it needs [FrontEnd], and [FrontEnd] had no tier name, which is
 * exactly the reason round 851 gave for the largest warm handler going
 * un-partitioned for three rounds.
 *
 * **These pins are built to fail if the tier were INERT**, which is the one
 * property a tier pin can get wrong. A tier that is listed, dispatches, and
 * prints a well-formed header while never arming [FrontEnd.mode] produces a
 * report full of zeros — structurally valid, and a silent measurement of
 * nothing. So the fixture RECORDS through the probe's own public entry points
 * inside `measureTier`'s build lambda and then asserts the recorded values are
 * in the text: [FrontEnd.addCrawlFile] and [FrontEnd.close] both begin with
 * `if (mode != ON) return`, so dropping the arm from [tierBegin] takes the
 * `files read:` line to `0 (0 chars)` and deletes the bind row entirely (the
 * report skips every phase whose `calls` is zero).
 *
 * They arm no compile and cost nothing; [FrontEnd.mode] is saved and restored
 * rather than reset to a guessed default (round 619).
 */
class BenchFrontEndTierTest {

    private fun <T> withSavedMode(block: () -> T): T {
        val saved = FrontEnd.mode
        try {
            return block()
        } finally {
            FrontEnd.mode = saved
            FrontEnd.reset()
        }
    }

    /**
     * A probe with no tier name cannot be taken warm at all — that is the whole
     * gap this round closed, so the name itself is pinned.
     */
    @Test
    fun `the front end has a tier name`() {
        assert("frontend" in TIERS)
    }

    /**
     * THE DISCRIMINATING PIN. The recorded 4242 chars and the bind row can only
     * appear in the text if [tierBegin] armed the probe BEFORE the build ran and
     * [tierReport] was taken BEFORE [tierStop] disarmed and zeroed it.
     */
    @Test
    fun `the frontend tier arms the probe for the build and reports what it recorded`() =
        withSavedMode {
            val (value, text) = measureTier("frontend") {
                FrontEnd.addCrawlFile(readNanos = 1_000_000L, parseNanos = 2_000_000L, makeNanos = 0L, chars = 4242)
                val t0 = FrontEnd.t()
                FrontEnd.close(FrontEnd.BIND, t0)
                7
            }
            assert(value == 7)
            assert("(FRONT.1) front-end attribution" in text)
            assert("files read: 1 (4242 chars)" in text)
            assert("bind (all program files)" in text)
            assert("== (FRONT.1) csv ==" in text)
            assert("== (FRONT.1) csv end ==" in text)
        }

    /**
     * NEGATIVE CONTROL for the pin above — off the tier, the same recording
     * calls do nothing at all, so a green pin above cannot be explained by the
     * probe simply always being on in this process.
     */
    @Test
    fun `negative control - off the tier the same recording calls are no-ops`() = withSavedMode {
        FrontEnd.mode = FrontEnd.OFF
        FrontEnd.reset()
        FrontEnd.addCrawlFile(readNanos = 1_000_000L, parseNanos = 2_000_000L, makeNanos = 0L, chars = 4242)
        FrontEnd.close(FrontEnd.BIND, FrontEnd.t())
        assert(FrontEnd.filesRead == 0L)
        assert(FrontEnd.charsRead == 0L)
        assert(FrontEnd.calls[FrontEnd.BIND] == 0L)
        assert("files read: 0 (0 chars)" in FrontEnd.report())
    }

    /** …and [tierStop] must both disarm the probe and release its counters. */
    @Test
    fun `tierStop disarms the frontend probe and zeroes its counters`() = withSavedMode {
        tierBegin("frontend")
        assert(FrontEnd.mode == FrontEnd.ON)
        FrontEnd.addCrawlFile(readNanos = 1L, parseNanos = 1L, makeNanos = 0L, chars = 11)
        assert(FrontEnd.filesRead == 1L)
        tierReport("frontend")
        assert(FrontEnd.mode == FrontEnd.ON)
        tierStop()
        assert(FrontEnd.mode == FrontEnd.OFF)
        assert(FrontEnd.filesRead == 0L)
    }
}
