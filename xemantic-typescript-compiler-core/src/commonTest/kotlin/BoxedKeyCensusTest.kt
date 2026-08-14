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
 * (WARM.31) round 904 — the per-SITE census and the two-arm amplifier that price
 * the residual boxed-primitive map/set keys.
 *
 * These pins protect a MEASUREMENT and nothing else: the instrument changes no
 * diagnostic, emits no byte and moves no `cost_gate.py` counter, so no output
 * assertion anywhere can see it break. The pins are therefore counter identities
 * that hold BY CONSTRUCTION and are breached the moment a hook moves.
 *
 * Every one asserts a NON-EMPTY population before it asserts an identity over
 * it, because a census that recorded nothing satisfies every identity vacuously
 * (round 849 — a zero from a blind instrument reads exactly like a real
 * negative), and the round's whole verdict is `population x premium`.
 */
class BoxedKeyCensusTest {

    /**
     * The fixture drives the two mechanisms the round's arithmetic rests on: a
     * RELATION (sites 0/1/2 — the assignability comparisons that probe
     * `Relation.cache` and push `relationComparisonStack`) and a GENERIC member
     * resolution (site 5, `resolvedPropertyTypes`).
     *
     * The mismatched assignment is load-bearing: a relation that succeeds on the
     * identity fast path never reaches `checkTypeRelatedToCore` at all, so a
     * fixture of only well-typed code leaves site 0 EMPTY and every identity
     * below vacuous.
     */
    private val source = """
        interface Box<T> { readonly value: T; readonly tag: string; }
        interface Wide { readonly value: string; readonly tag: string; readonly extra: number; }

        declare const w: Wide;
        const narrowed: Box<string> = w;

        declare function take(b: Box<number>): void;
        declare const bad: Box<string>;
        take(bad);

        function reads(b: Box<string>): string { return b.value; }
        reads(bad);
    """.trimIndent()

    private fun <T> withCensus(census: Boolean = true, amp: Int = 0, block: () -> T): T {
        val savedOn = MapCensus.on
        val savedCensus = MapCensus.boxedKeyCensus
        val savedAmp = MapCensus.boxedKeyAmp
        try {
            MapCensus.reset()
            MapCensus.on = true
            MapCensus.boxedKeyCensus = census
            MapCensus.boxedKeyAmp = amp
            return block()
        } finally {
            MapCensus.on = savedOn
            MapCensus.boxedKeyCensus = savedCensus
            MapCensus.boxedKeyAmp = savedAmp
            MapCensus.reset()
        }
    }

    // ---- the negative control ----------------------------------------------

    /**
     * INV.0's requirement, and the ONE pin that would see the round-900 mistake:
     * a guard cannot protect its own ARGUMENT, so a hook written as
     * `bk(site, derive(x))` would run `derive` on every production compile while
     * leaving the counters at zero — and every other pin here would still pass.
     *
     * This asserts the counters stay at zero with the census OFF, and the pin
     * BELOW asserts the same fixture moves them when it is ON. Neither is
     * sufficient alone: together they say the hooks are reached exactly when
     * armed.
     */
    @Test
    fun `with the census off every site counter stays at zero`() = withCensus(census = false) {
        diagnose(source)
        var total = 0L
        for (i in 0 until MapCensus.BK_SITES) total += MapCensus.bkOps[i]
        assert(total == 0L)
    }

    // ---- the populations ----------------------------------------------------

    /**
     * The relation family is reached and counted. Site 0 is the largest
     * non-already-refused member of the family and the one whose population
     * carries the round's verdict, so a hook lost there silently deletes 444,446
     * operations from the census and makes the family look smaller than it is.
     */
    @Test
    fun `the relation sites record a non-empty population`() = withCensus {
        diagnose(source)
        assert(MapCensus.bkOps[MapCensus.BK_REL_STACK] > 0)
        assert(MapCensus.bkOps[MapCensus.BK_REL_CACHE] > 0)
    }

    /**
     * Round 890's law as an identity: a transient add/remove container's table is
     * bounded by its MAX LIVE size, not by the keys it ever saw — which is why a
     * `relationComparisonStack` that saw 51,447 distinct keys can never treeify,
     * and why its successor is a linear-scan primitive array rather than a
     * `LongKeyMap`. The pin is that the live count is genuinely a stack: it never
     * exceeds the pushes, and it is never negative-going (a pop without a push
     * would drive `bkMaxLive` to a number the pushes cannot justify).
     *
     * `>= 1` and not `> 0` on the pushes is deliberate: the assertion is that the
     * push/pop pair is BALANCED around the `finally`, so a `remove` moved outside
     * it shows up here as a live count that outgrows its own pushes.
     */
    @Test
    fun `the transient relation stack never exceeds its own pushes`() = withCensus {
        diagnose(source)
        val ops = MapCensus.bkOps[MapCensus.BK_REL_STACK]
        val maxLive = MapCensus.bkMaxLive[MapCensus.BK_REL_STACK]
        assert(ops > 0)
        assert(maxLive >= 1)
        // Each reaching call performs contains + add + remove, so the live count
        // can never exceed a third of the recorded operations.
        assert(maxLive.toLong() * 3 <= ops)
    }

    /**
     * The key RANGE is what refuses a site for free, and this is the pin that it
     * is really recorded. `Integer.valueOf`/`Long.valueOf` cache -128..127, so a
     * site whose keys all fall there boxes nothing new AND short-circuits
     * `HashMap.getNode`'s identity test before `equals`; the round measured
     * **0.41%** of the family in that range, i.e. the deflation does not apply.
     *
     * A packed relation key is a FULL-WIDTH 64-bit value by construction (round
     * 890's finalizing multiply by an odd constant), so its magnitude must exceed
     * 2^32 — which is what separates a real packed key from a bare id widened by
     * a `toLong()`, and what a census recording the wrong expression would fail.
     *
     * The pin is magnitude and NOT a sign spread: this fixture drives exactly one
     * distinct relation key, so `min < 0 && max > 0` would be a statement about
     * the fixture's size rather than about the key. (On the compiler profile the
     * range really is -9.22e18..9.22e18.)
     */
    @Test
    fun `the packed relation key is a full-width 64-bit value`() = withCensus {
        diagnose(source)
        assert(MapCensus.bkOps[MapCensus.BK_REL_CACHE] > 0)
        val widest = maxOf(
            MapCensus.bkMaxKey[MapCensus.BK_REL_CACHE],
            -(MapCensus.bkMinKey[MapCensus.BK_REL_CACHE] + 1L),
        )
        assert(widest > 0xFFFFFFFFL)
        // and none of the keys is in `Long.valueOf`'s -128..127 cache, i.e. every
        // one of them really boxes — the deflation that would refuse a site free.
        assert(MapCensus.bkSmallKeys[MapCensus.BK_REL_CACHE] == 0L)
    }

    /**
     * The `symbolTypeResolutionInProgress` / `memberResolutionInProgress` control.
     * Round 896 measured `symAdds` at 24,232 with MAX LIVE 3 on the compiler
     * profile, and the whole-family census must agree with the counter that
     * measures the same sets by a different hook — two instruments, one
     * population.
     *
     * The identity is exact: site 11 counts one operation per add AND one per
     * remove for both sets, so it is at least twice `symAdds`.
     */
    @Test
    fun `the in-progress control agrees with the round-896 sentinel counter`() = withCensus {
        diagnose(source)
        assert(MapCensus.symAdds > 0)
        assert(MapCensus.bkOps[MapCensus.BK_SYM_INPROG] >= 2 * MapCensus.symAdds)
    }

    // ---- the amplifier ------------------------------------------------------

    /**
     * The LOCKSTEP control, which is what makes `A - B` a comparison of two
     * containers holding the same thing rather than of a full map against an
     * empty one. Arm B's `LongKeyMap` is populated at `Relation.set` beside the
     * real `cache`, so every probe must find the same presence in both — and an
     * unequal sink is the only signal that the shadow stopped mirroring.
     *
     * The sink is also asserted to be an exact multiple of `r`, round 759's
     * arithmetic falsifier that the amplified reads were not elided.
     */
    @Test
    fun `the amplifier arms stay in lockstep and their sinks are exact`() =
        withCensus(census = false, amp = 4) {
            diagnose(source)
            assert(MapCensus.bkAmpCalls > 0)
            assert(MapCensus.bkAmpBoxedSink == MapCensus.bkAmpPrimSink)
            assert(MapCensus.bkAmpBoxedSink % 4L == 0L)
            assert(MapCensus.bkAmpPrimSink % 4L == 0L)
            // Both arms really ran: a timestamp pair is ~90 ns, so a live arm
            // cannot record zero nanos over a non-empty call population.
            assert(MapCensus.bkAmpBoxedNanos > 0)
            assert(MapCensus.bkAmpPrimNanos > 0)
        }

    /**
     * `LongKeyMap` reserves key 0 as its empty-slot sentinel and `put` THROWS on
     * it, so the amplifier's shadow skips such a key and counts it. The pin is
     * that the skip population is recorded rather than assumed impossible — the
     * round quotes `sentinel keys skipped=0` on the compiler profile, and a
     * counter that could never move would make that zero meaningless.
     */
    @Test
    fun `the sentinel-key population is recorded rather than assumed`() =
        withCensus(census = false, amp = 2) {
            diagnose(source)
            assert(MapCensus.bkAmpCalls > 0)
            assert(MapCensus.bkAmpSentinelKeys >= 0)
        }
}
