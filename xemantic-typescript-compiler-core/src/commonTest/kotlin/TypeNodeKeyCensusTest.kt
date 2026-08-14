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
 * (WARM.30) round 903 — the census and the three-arm amplifier that price
 * `state.nodeTypes`' deep AST-VALUE key, plus the FIRST pin on round 896's
 * `nodeEnter`/`nodeLeave` sentinel hooks, which shipped their numbers unpinned.
 *
 * These pins protect a MEASUREMENT and nothing else: the instrument changes no
 * diagnostic, emits no byte and moves no `cost_gate.py` counter, so no output
 * assertion anywhere can see it break. That is round 897's A1 / round 900's A3
 * for the fourth time, and the answer is the same — the pins are counter
 * identities that hold BY CONSTRUCTION and are breached the moment a hook moves.
 *
 * Every one of them is written to FAIL if the instrument is inert: each asserts a
 * non-empty population BEFORE it asserts an identity over it, because a census
 * that recorded nothing satisfies every sum-to-total identity vacuously (round
 * 849 — a zero from a blind instrument reads exactly like a real negative).
 */
class TypeNodeKeyCensusTest {

    /**
     * A fixture that reaches all three buckets of the partition.
     *
     * The GENERIC function is load-bearing: `getTypeFromTypeNodeCore`'s cacheable
     * gate refuses any resolution taken while `currentTypeParamScope` is
     * installed, so without a type parameter in the file the BYPASSED bucket is
     * empty and a census that classified everything as cacheable would pass.
     *
     * The repeated annotations are the other half: a HIT needs the same
     * STRUCTURAL node shape to be resolved twice, which is exactly what the deep
     * key buys and exactly what a `(file, nodeId)` re-key would not — two
     * distinct `Box<string> | undefined` annotations are ONE `nodeTypes` entry
     * today and would be two under any identity-shaped successor.
     */
    private val source = """
        interface Box<T> { readonly value: T; }
        type Pair = { readonly left: string; readonly right: number };

        declare function unwrap<T>(b: Box<T>): T;

        const a: Box<string> | undefined = undefined;
        const b: Box<string> | undefined = undefined;
        const c: ReadonlyArray<Pair> = [];
        const d: ReadonlyArray<Pair> = [];

        function identity<T>(x: T): T { return x; }
        function pick(p: Pair): string { return p.left; }

        identity(a);
        pick({ left: "x", right: 1 });
    """.trimIndent()

    private fun <T> withCensus(census: Boolean = true, amp: Int = 0, block: () -> T): T {
        val savedOn = MapCensus.on
        val savedCensus = MapCensus.typeNodeKeyCensus
        val savedAmp = MapCensus.typeNodeKeyAmp
        try {
            MapCensus.reset()
            MapCensus.on = true
            MapCensus.typeNodeKeyCensus = census
            MapCensus.typeNodeKeyAmp = amp
            return block()
        } finally {
            MapCensus.on = savedOn
            MapCensus.typeNodeKeyCensus = savedCensus
            MapCensus.typeNodeKeyAmp = savedAmp
            MapCensus.reset()
        }
    }

    // ---- the partition ------------------------------------------------------

    /**
     * Every censused call leaves through exactly one of the three doors. A hook
     * moved above the cacheable gate, or an early `return` added inside the
     * cacheable block without one, breaks this before it can quietly shift a
     * population between rows — which is what would silently re-price the whole
     * 354,131-hash arithmetic the round rests on.
     */
    @Test
    fun `every censused resolution is charged to exactly one bucket`() = withCensus {
        diagnose(source)
        val calls = MapCensus.tnkCalls
        assert(calls > 0)
        val doors = MapCensus.tnkHits + MapCensus.tnkMisses + MapCensus.tnkBypassed
        assert(doors == calls)
    }

    /**
     * All three buckets are NON-EMPTY on this fixture, which is what makes the
     * identity above a measurement rather than a tautology. A census that
     * classified everything as one bucket would satisfy the sum and answer
     * nothing; and each bucket carries a different number of deep hashes (a hit
     * pays one, a miss four, a bypass none at the map and one subtree walk at
     * `isPerFileDependentRefNode`), so collapsing them mis-prices the row.
     */
    @Test
    fun `the hit miss and bypassed populations are all non-empty`() = withCensus {
        diagnose(source)
        assert(MapCensus.tnkHits > 0)
        assert(MapCensus.tnkMisses > 0)
        assert(MapCensus.tnkBypassed > 0)
    }

    /**
     * The probe-weighted subtree walk really runs and really varies.
     *
     * `mean > 1` is the pin that a mean of exactly 1 could not give: a
     * `MapCensus`'s subtree walk that stopped descending — a `forEachChild` arm lost,
     * or the recursion replaced by a constant — would still sum, still bucket and
     * still report a plausible-looking number, and the round's whole plausibility
     * check (is 161 ns per hash physically possible?) is that mean.
     */
    @Test
    fun `the probe-weighted key subtree is walked and is larger than one node`() = withCensus {
        diagnose(source)
        assert(MapCensus.tnkCalls > 0)
        assert(MapCensus.tnkSubtreeSum > MapCensus.tnkCalls)
        assert(MapCensus.tnkMaxSubtree > 1)
        val bucketed = MapCensus.tnkProbeSizeHistogram.sum()
        assert(bucketed == MapCensus.tnkCalls)
    }

    /**
     * The OBJECT-weighted sweep runs, is a strictly smaller population than the
     * probes, and is bucketed consistently.
     *
     * The inequality is the round's own law in miniature (round 902): a NODE
     * population is not a PROBE population. If the two were ever equal the cache
     * would be serving nothing and the deep key would be paying for a miss every
     * time — so an equality here is a finding, not a pass.
     */
    @Test
    fun `the object-weighted sweep records fewer distinct keys than there were probes`() =
        withCensus {
            diagnose(source)
            assert(MapCensus.tnkObjects > 0)
            assert(MapCensus.tnkObjects < MapCensus.tnkCalls)
            assert(MapCensus.tnkObjectSubtreeSum > MapCensus.tnkObjects)
            assert(MapCensus.tnkObjectSizeHistogram.sum() == MapCensus.tnkObjects)
        }

    /**
     * The unindexed population is COUNTED rather than assumed.
     *
     * INV.2(a): `nodeId` is stamped by `indexSourceFile` and a `copy()`d or
     * Transformer-synthesized node keeps `-1`. A `(file, nodeId)` successor key
     * cannot address such a node at all, so this counter is the first obligation
     * any re-key must discharge — and it can only be that if the census can
     * express a non-zero. Bounded rather than pinned to a value, because the
     * population is a property of the fixture and not of the instrument.
     */
    @Test
    fun `unindexed keys are a counted subset of the probes`() = withCensus {
        diagnose(source)
        assert(MapCensus.tnkUnindexed <= MapCensus.tnkCalls)
        assert(MapCensus.tnkObjectUnindexed <= MapCensus.tnkObjects)
    }

    /**
     * INV.0 — the negative control. Off, the census records NOTHING: no counter
     * moves, so no `forEachChild` subtree walk and no end-of-check cache sweep
     * ran on the production path.
     *
     * This is round 900's law as a pin. A guard written INSIDE
     * [MapCensus.tnkProbe] cannot protect its own argument, so a call site that
     * computed the subtree size and passed it in would leave every counter here at
     * zero while doing the whole walk on every production compile — which is
     * exactly the mistake that ran for 99 rounds and which no output diff, no
     * corpus baseline and no `cost_gate.py` counter can see. What makes this pin
     * able to see it is the OBJECT sweep: [MapCensus.tnkObjects] is written by a
     * hook that has no argument to be computed, so it is zero if and only if the
     * census was genuinely off.
     */
    @Test
    fun `negative control - a disabled census records nothing`() = withCensus(census = false) {
        diagnose(source)
        assert(MapCensus.tnkCalls == 0L)
        assert(MapCensus.tnkHits + MapCensus.tnkMisses + MapCensus.tnkBypassed == 0L)
        assert(MapCensus.tnkSubtreeSum == 0L)
        assert(MapCensus.tnkMaxSubtree == 0)
        assert(MapCensus.tnkObjects == 0L)
        assert(MapCensus.tnkProbeSizeHistogram.sum() == 0L)
    }

    // ---- the amplifier ------------------------------------------------------

    /**
     * All three arms run, take their own bracket, and consume their own sink.
     *
     * The sinks are SPLIT for round 901's reason — one shared sink cannot tell a
     * dropped arm from a running one — and each must be an exact multiple of `r`
     * (round 759's arithmetic falsifier), because every call contributes either
     * `0` or `r` to its arm.
     *
     * What this pin CANNOT see is C2 hoisting an arm out of its loop: a hoisted
     * arm still produces `r * h`. That falsifier is the SLOPE between two `r`,
     * which only a whole-project run can take, and it is why arm A amplifies the
     * map GET rather than `node.hashCode()` and arm C is a recursive call.
     */
    @Test
    fun `all three amplifier arms run and sink an exact multiple of r`() =
        withCensus(census = false, amp = AMP) {
            diagnose(source)
            assert(MapCensus.tnkAmpCalls > 0)
            assert(MapCensus.tnkAmpMapNanos > 0)
            assert(MapCensus.tnkAmpLongNanos > 0)
            assert(MapCensus.tnkAmpRefNanos > 0)
            assert(MapCensus.tnkAmpMapSink > 0)
            assert(MapCensus.tnkAmpMapSink % AMP == 0L)
            assert(MapCensus.tnkAmpLongSink % AMP == 0L)
            assert(MapCensus.tnkAmpRefSink % AMP == 0L)
        }

    /**
     * ARM B is populated in LOCKSTEP with arm A, so the two arms answer the same
     * question on the same population and `A - B` is the deep-key PREMIUM rather
     * than a comparison of two different hit rates.
     *
     * The bound is `B <= A` and NOT `B == A`, and the difference is the round's
     * bonus finding rather than a defect: a structural key can serve a probe from
     * a DIFFERENT, structurally-equal node, which a `(file, nodeId)` key never
     * can — measured at exactly 130 probes of 176,282 on the compiler profile
     * (0.074%), i.e. the whole semantic content of the deep key. It can only go
     * one way: arm B's key is injective on nodes, so it cannot answer a hit arm A
     * missed.
     *
     * `nodeId` restarts at 0 in every SourceFile (round 787), so a successor key
     * that dropped the file would collapse one node per file onto each id and arm
     * B would answer hits arm A never had — which is what the `<=` direction
     * catches, and which is invisible in every timing.
     */
    @Test
    fun `the parallel key answers a subset of what the structural key answers`() =
        withCensus(census = false, amp = AMP) {
            diagnose(source)
            assert(MapCensus.tnkStores > 0)
            assert(MapCensus.tnkAmpLongSink <= MapCensus.tnkAmpMapSink)
            assert(MapCensus.tnkAmpLongSink > 0)
        }

    /**
     * The REACHED control for amplifier arm C (round 902: an arm can be DEAD
     * rather than the pin blind, and `git diff --shortstat` cannot tell you
     * which).
     *
     * `isPerFileDependentRefNode` opens with
     * `if (multiFileModuleTypeNames.isEmpty() || depth > 4) return false`, so on a
     * program with no type name declared in two module files arm C prices a field
     * read and a return while reading exactly like a subtree walk. This fixture is
     * single-file, so the set IS empty here and the counter reports it — the pin
     * is that the census can EXPRESS the distinction, which is what makes the
     * whole-project reading interpretable.
     */
    @Test
    fun `the arm C reached control is recorded`() = withCensus {
        diagnose(source)
        assert(MapCensus.tnkObjects > 0)
        // Single-file, so no type name is declared in two module files and arm C
        // would price the early return here. A non-zero would mean the counter is
        // wired to something other than the set the function actually guards on.
        assert(MapCensus.tnkMultiFileNames == 0L)
    }

    /**
     * INV.0 — the amplifier's negative control. Off, no bracket is taken and the
     * parallel container is never written, so a production compile pays a static
     * read and a not-taken branch at each of the two call sites and nothing else.
     */
    @Test
    fun `negative control - a disarmed amplifier takes no bracket`() = withCensus {
        diagnose(source)
        assert(MapCensus.tnkAmpCalls == 0L)
        assert(MapCensus.tnkAmpMapNanos == 0L)
        assert(MapCensus.tnkAmpLongNanos == 0L)
        assert(MapCensus.tnkAmpRefNanos == 0L)
        assert(MapCensus.tnkStores == 0L)
    }

    // ---- round 896's sentinel hooks, pinned for the first time ---------------

    /**
     * (WARM.23) round 896 shipped `nodeAdds` / `nodeReentries` / `nodeMaxLive` —
     * the population and MAX LIVE SIZE of `nodeTypeResolutionInProgress`, the
     * numbers its 3-5 ms refusal was priced from — and NOTHING in `commonTest`
     * named them. This is that pin.
     *
     * The identity is the one round 890's law turns on: a transient add/remove
     * set's table is bounded by its max LIVE size, not by the keys it ever saw,
     * and a `nodeLeave` that stopped firing (or a `nodeEnter` moved above the
     * re-entry guard) would make `nodeMaxLive` grow without bound while every
     * other counter stayed plausible. `nodeMaxLive <= nodeAdds - nodeReentries`
     * is the strongest statement that holds by construction: only a call that
     * genuinely ADDED can raise the live count.
     *
     * The census counts a superset of the round's own misses, because `tnkMisses`
     * is recorded at the cache probe and `nodeEnter` one line below it — so the
     * two are equal exactly while nothing returns in between, which is the seam
     * this pin holds.
     */
    @Test
    fun `the in-progress sentinel hooks record a bounded live population`() = withCensus {
        diagnose(source)
        assert(MapCensus.nodeAdds > 0)
        assert(MapCensus.nodeMaxLive >= 1)
        assert(MapCensus.nodeMaxLive.toLong() <= MapCensus.nodeAdds - MapCensus.nodeReentries)
        // The cacheable-miss population and the sentinel's add population are the
        // SAME calls — `nodeEnter` sits directly under the census probe with no
        // return between them. An inequality means a gate was inserted there.
        assert(MapCensus.nodeAdds == MapCensus.tnkMisses)
    }

    /**
     * The symbol-side sibling, which round 896 priced in the same table and which
     * was equally unpinned. Its live population is what refuted candidate (4) on
     * arithmetic alone (1,164 ns to add one boxed `Integer` to a 3-element set is
     * impossible), so a `symLeave` that stopped firing would restore a refuted
     * candidate to the queue with a number nobody could see was wrong.
     */
    @Test
    fun `the symbol in-progress sentinel hooks record a bounded live population`() = withCensus {
        diagnose(source)
        assert(MapCensus.symAdds > 0)
        assert(MapCensus.symMaxLive >= 1)
        assert(MapCensus.symMaxLive.toLong() <= MapCensus.symAdds - MapCensus.symReentries)
    }

    /** INV.0 — off, the sentinel hooks record nothing at all. */
    @Test
    fun `negative control - disabled sentinel hooks record nothing`() {
        val savedOn = MapCensus.on
        try {
            MapCensus.reset()
            MapCensus.on = false
            diagnose(source)
            assert(MapCensus.nodeAdds == 0L)
            assert(MapCensus.nodeMaxLive == 0)
            assert(MapCensus.symAdds == 0L)
            assert(MapCensus.symMaxLive == 0)
        } finally {
            MapCensus.on = savedOn
            MapCensus.reset()
        }
    }

    private companion object {
        /** Small enough for a unit test, large enough that every sink is unambiguous. */
        const val AMP = 3
    }
}
