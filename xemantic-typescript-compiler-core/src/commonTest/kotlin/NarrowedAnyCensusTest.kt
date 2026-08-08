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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (NARROW.2)(e), round 854 — the PRODUCED-vs-CONSUMED census of round 852's
 * narrowed-`any` receiver opening, and its own price.
 *
 * Round 853 attributed `narrow.walks` 17,851 → 31,961 (+79.04% on the compiler
 * profile) to that one opening by ablation. A count is not a cost (round 801),
 * and the obvious instrument cannot settle it: `narrowWalks=<ms>` reads 1,423 /
 * 1,460 / 1,602 ms across three runs of ONE binary, so an arm difference is
 * smaller than its own noise. The census below is the in-situ alternative — one
 * timestamp pair around the opening's flow read, plus the three counts that say
 * whether anything came back.
 *
 * These pins exist because a counter that reports 0 with no positive control is
 * a dead instrument (round 765), and because the two populations this round has
 * to tell apart — an `any` receiver the flow DID narrow, and the far larger one
 * it did not — are indistinguishable in the compiler's output: the second emits
 * nothing whether the opening runs or not, which is exactly why it went unpriced
 * for two rounds.
 *
 * The counters are `PassTiming.detailed`-gated, so a production run pays a
 * not-taken branch; `negative control - a disabled run records nothing` is what
 * pins that.
 */
class NarrowedAnyCensusTest {

    private val prelude = """
        // @useRealLibs: true
        // @strict: false
        // @target: es2015
        declare var x: any;
        declare function isError(v: any): v is Error;
    """.trimIndent() + "\n"

    /** Compiles with the tier-3 counters armed, leaving them readable. The
     *  save-and-restore is not optional: `PassTiming.enabled` is fork-global and
     *  a test that assigns the default back re-enables it for every
     *  alphabetically later class (the round-619 false green). */
    private fun census(@Language("typescript") source: String): List<Diagnostic> {
        PassTiming.reset()
        PassTiming.enabled = true
        return try {
            diagnose(prelude + source.trimIndent(), directives = "")
        } finally {
            PassTiming.enabled = false
        }
    }

    @Test
    fun `an any receiver the flow narrowed is counted through all three stages`() {
        val diagnostics = census(
            """
            if (isError(x)) {
                x.zzzq;
            }
            """,
        )
        val openings = PassTiming.cmamAnyOpenings
        val narrowed = PassTiming.cmamAnyNarrowed
        val accepted = PassTiming.cmamAnyAccepted
        PassTiming.reset()
        // The emission is round 852's own result — asserted by MESSAGE, since a
        // bare "TS2339 appears" passes on a build that never narrowed too.
        diagnostics should {
            have(any { it.code == 2339 && it.message == "Property 'zzzq' does not exist on type 'Error'." })
        }
        assert(openings > 0L)
        assert(narrowed > 0L)
        assert(accepted > 0L)
        assert(accepted <= narrowed)
        assert(narrowed <= openings)
    }

    @Test
    fun `an any receiver the flow did NOT narrow pays an opening and buys nothing`() {
        // THE POPULATION THIS ROUND IS PRICING. It is invisible in the output —
        // the walker is silent with and without the opening — so only the census
        // can see that the flow read happened and answered `any`.
        val diagnostics = census(
            """
            x.zzzq;
            """,
        )
        val openings = PassTiming.cmamAnyOpenings
        val narrowed = PassTiming.cmamAnyNarrowed
        val accepted = PassTiming.cmamAnyAccepted
        PassTiming.reset()
        diagnostics should {
            have(none { it.code == 2339 })
        }
        assert(openings > 0L)
        assert(narrowed == 0L)
        assert(accepted == 0L)
    }

    @Test
    fun `the opening's cost is attributed to its own span, not to the walk row`() {
        census(
            """
            if (isError(x)) {
                x.zzzq;
            }
            """,
        )
        val cost = PassTiming.cmamAnyNanos
        val costNarrowed = PassTiming.cmamAnyNanosNarrowed
        val walkOnly = PassTiming.cmamAnyWalkNanos
        val walkOnlyNarrowed = PassTiming.cmamAnyWalkNanosNarrowed
        val openings = PassTiming.cmamAnyOpenings
        val walkNanos = PassTiming.narrowWalkNanos
        PassTiming.reset()
        // A span that never closed would read 0 while `openings` climbed; a span
        // that swallowed the whole compile would exceed the narrowing row it is
        // a subset of.
        assert(openings > 0L)
        assert(cost > 0L)
        assert(cost <= walkNanos + 1_000_000L)
        // The walk-only figure is a SUB-span of the wall one and its narrowed
        // half is a sub-span of itself; a mis-wired delta shows up here as an
        // inversion rather than as a plausible-looking number.
        assert(walkOnly <= cost)
        assert(costNarrowed <= cost)
        assert(walkOnlyNarrowed <= walkOnly)
        assert(walkOnly <= walkNanos)
    }

    @Test
    fun `negative control - a disabled run records nothing`() {
        PassTiming.reset()
        PassTiming.enabled = false
        val diagnostics = diagnose(
            prelude +
                """
                if (isError(x)) {
                    x.zzzq;
                }
                """.trimIndent(),
            directives = "",
        )
        // Same emission as the armed run — the census may not change what the
        // compiler answers.
        diagnostics should {
            have(any { it.code == 2339 && it.message == "Property 'zzzq' does not exist on type 'Error'." })
        }
        assert(PassTiming.cmamAnyOpenings == 0L)
        assert(PassTiming.cmamAnyNarrowed == 0L)
        assert(PassTiming.cmamAnyAccepted == 0L)
        assert(PassTiming.cmamAnyNanos == 0L)
        assert(PassTiming.cmamAnyWalkNanos == 0L)
    }
}
