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
 * (WARM.28) round 901 — the census that priced round 899's candidate (2),
 * `lexLevelHasName`, and then REFUSED it. `docs/perf/lex-level-probe-price.md`.
 *
 * What these pins protect is a MEASUREMENT, not an answer. The census changes no
 * diagnostic, so no output assertion anywhere can see it break — which is round
 * 897's A1 and round 900's A3 for the third time, and the answer is the same:
 * the pins are counter identities that hold BY CONSTRUCTION and are breached the
 * moment a hook moves.
 *
 * The identity that carries the round is the EMPTY/REAL split. `HashMap.getNode`
 * reads `table` before it hashes the key, so a level whose `symbols` map was
 * never written answers a probe with a null check — and a census that counted
 * probes alone would price those at the arc's 20-50 ns reference and manufacture
 * a prize out of 271,684 free operations. If [MapCensus.lexSymEmpty] ever stops
 * being able to be non-zero, the census is silently back to counting probes.
 */
class LexLevelProbeCensusTest {

    /**
     * A fixture that reaches every arm of the partition: a namespace (an UNTRUSTED
     * level), nested functions (levels whose own `symbols` carry type parameters
     * and block-hoisted names), and blocks (levels that bind nothing at all).
     *
     * The file-level bare block is load-bearing and was added by round 902's
     * ablation. Its `var` is B83.5-hoisted into the SOURCE FILE root's own
     * `symbols`, and the root is the ONLY level that carries an `existing` table
     * past the untrusted-owner rule (round 901 § 1). Without it the root's
     * `symbols` is empty, so the root is never a REAL probe, so no amplified scope
     * has an `existing` table at all — and an arm that pollutes the scanned array
     * with `existing` keys becomes a no-op that reads as a blind pin.
     */
    private val source = """
        {
            var hoistedAtFileLevel = 0;
            hoistedAtFileLevel = hoistedAtFileLevel + 1;
        }
        const top = 2;
        namespace N {
            export const inside = 1;
            export function useOuter(): number {
                return inside + top;
            }
        }
        function outer<T>(p: T): number {
            let seen = 0;
            {
                let inner = p;
                seen = seen + 1;
            }
            function nested(q: T): T {
                return q;
            }
            nested(p);
            return seen;
        }
        outer(N.inside);
    """.trimIndent()

    private fun <T> withCensus(amp: Int = 0, block: () -> T): T {
        val savedOn = MapCensus.on
        val savedAmp = MapCensus.lexLevelAmp
        try {
            MapCensus.reset()
            MapCensus.on = true
            MapCensus.lexLevelAmp = amp
            return block()
        } finally {
            MapCensus.on = savedOn
            MapCensus.lexLevelAmp = savedAmp
            MapCensus.reset()
        }
    }

    // ---- the partition ------------------------------------------------------

    /**
     * Every call leaves through exactly one of the four recorded doors. A hook
     * moved above a gate, or an early `return` added without one, breaks this
     * before it can quietly shift a population between rows.
     */
    @Test
    fun `every lexLevelHasName call is charged to exactly one door`() = withCensus {
        diagnose(source)
        val calls = MapCensus.lexCalls
        assert(calls > 0)
        val doors = MapCensus.lexUntrusted + MapCensus.lexFnSkipped +
            MapCensus.lexSymEmpty + MapCensus.lexSymProbe
        assert(doors == calls)
    }

    /**
     * …and every call that got past the two gates and missed `symbols` is charged
     * to exactly one of the three continuations. This is the identity that makes
     * the refusable population a MEASUREMENT rather than a subtraction.
     */
    @Test
    fun `every symbols miss is charged to exactly one continuation`() = withCensus {
        diagnose(source)
        val probed = MapCensus.lexSymEmpty + MapCensus.lexSymProbe
        assert(probed > 0)
        val misses = probed - MapCensus.lexSymHit
        val continuations = MapCensus.lexExProbe + MapCensus.lexNoExisting + MapCensus.lexRootExcluded
        assert(continuations == misses)
    }

    /**
     * The round's deciding distinction, pinned as a POSITIVE control on BOTH
     * sides (round 849: a zero from a blind instrument reads like a real
     * negative). A census that classified every probe as real would fail the
     * first assertion; one that classified every probe as empty would fail the
     * second, and either would have priced 813,571 operations at the wrong rate.
     */
    @Test
    fun `the empty and the real probe populations are both non-empty`() = withCensus {
        diagnose(source)
        assert(MapCensus.lexSymEmpty > 0)
        assert(MapCensus.lexSymProbe > 0)
    }

    /** The untrusted-owner rule is reached — the fixture's namespace level. */
    @Test
    fun `the untrusted-owner rule is exercised by the fixture`() = withCensus {
        diagnose(source)
        assert(MapCensus.lexUntrusted > 0)
    }

    /**
     * The refusable population can never exceed the probes that exist to be
     * refused. True by construction, and breached the moment the `real` flag
     * stops tracking the map it describes.
     */
    @Test
    fun `the refusable population is a subset of the real probes`() = withCensus {
        diagnose(source)
        val refusable = MapCensus.lexNoExistingReal + MapCensus.lexAbsent + MapCensus.lexAbsentReal
        val real = MapCensus.lexSymProbe + MapCensus.lexExProbe
        assert(refusable <= real)
        assert(MapCensus.lexNoExistingReal <= MapCensus.lexNoExisting)
        assert(MapCensus.lexAbsentReal <= MapCensus.lexAbsent)
    }

    // ---- the scope populations ---------------------------------------------

    /**
     * A scope is QUERIED at most once into the census and BOUND exactly once, so
     * the queried population is a subset of the bound one — the identity that
     * prices an eagerly built, race-free filter against a lazily built one. It is
     * exactly round 900's A3 shape: dropping the de-duplication changes no answer
     * anywhere, so only a counter can see it.
     */
    @Test
    fun `queried scopes are a de-duplicated subset of the bound scopes`() = withCensus {
        diagnose(source)
        assert(MapCensus.lexScopesBound > 0)
        assert(MapCensus.lexScopesQueried > 0)
        assert(MapCensus.lexScopesQueried <= MapCensus.lexScopesBound)
        val queriedOwnKeys = MapCensus.lexScopeKeys - MapCensus.lexScopeExistingKeys
        assert(queriedOwnKeys <= MapCensus.lexScopeBoundKeys)
        // …and STRICTLY fewer scopes than probes, which is what de-duplication
        // MEANS. The subset assertions above are satisfied vacuously by a fixture
        // whose lib binding dominates the bound count (round 898's A3: the
        // fixture could not express the invariant), so this is the one that
        // discriminates: without the identity set the queried count becomes the
        // call count.
        assert(MapCensus.lexScopesQueried < MapCensus.lexSymEmpty + MapCensus.lexSymProbe)
    }

    // ---- the amplifier ------------------------------------------------------

    /**
     * Round 759's arithmetic falsifier: the sink must be an EXACT multiple of the
     * repetition count, at every `r`, or the JIT elided one of the two loops and
     * the slope it produced is a measurement of nothing. Both arms accumulate
     * into the same sink, so the multiple covers both.
     */
    @Test
    fun `the amplified arms sink an exact multiple of r`() = withCensus(amp = 7) {
        diagnose(source)
        assert(MapCensus.lexAmpCalls > 0)
        assert(MapCensus.sink > 0L)
        assert(MapCensus.sink % 7L == 0L)
    }

    /** The amplifier runs once per REAL probe and never for an empty level. */
    @Test
    fun `the amplifier is armed on exactly the real probes`() = withCensus(amp = 3) {
        diagnose(source)
        assert(MapCensus.lexAmpCalls == MapCensus.lexSymProbe)
    }

    /**
     * ALL THREE arms must run — one shared sink cannot tell a dropped arm from a
     * running one, and a slope read off a single surviving arm is a measurement
     * of the wrong thing with nothing to say so.
     */
    @Test
    fun `all four amplified arms run`() = withCensus(amp = 3) {
        diagnose(source)
        assert(MapCensus.lexAmpMapSink > 0L)
        assert(MapCensus.lexAmpFilterSink > 0L)
        assert(MapCensus.lexAmpScanSink > 0L)
        assert(MapCensus.lexAmpHybridSink > 0L)
        assert(
            MapCensus.sink == MapCensus.lexAmpMapSink + MapCensus.lexAmpFilterSink +
                MapCensus.lexAmpScanSink + MapCensus.lexAmpHybridSink
        )
    }

    /**
     * The soundness assertion, kept in its OWN method so that it discriminates on
     * its own: the filter is a SUPERSET of the map by construction, so it can
     * never sink less. A mask built even one bit off its own probe breaks this
     * and nothing else — and a filter that refuses a name the map HOLDS is a
     * suppressed resolution, which no diagnostic assertion could catch because
     * the lever changes no answer when it is right.
     */
    @Test
    fun `the filter never refuses a name the map holds`() = withCensus(amp = 3) {
        diagnose(source)
        assert(MapCensus.lexAmpFilterSink >= MapCensus.lexAmpMapSink)
    }

    /**
     * (WARM.29) The scan arm's soundness assertion, and it is STRICTER than the
     * filter's: a filter may only be a superset, while a linear scan of the same
     * keys answers EXACTLY what the map answers. So the equality — not an
     * inequality — is what a swap of container would have to preserve, and it is
     * the one thing a container swap can silently get wrong (a stale array, a
     * length taken from the wrong side, a scan that stops one element short).
     *
     * It is also the arm's liveness check in a form a shared sink cannot fake: an
     * arm that never ran sinks zero, which is not equal to a non-zero map sink.
     */
    @Test
    fun `the parallel-array scan answers exactly what the map answers`() = withCensus(amp = 3) {
        diagnose(source)
        assert(MapCensus.lexAmpMapSink > 0L)
        assert(MapCensus.lexAmpScanSink == MapCensus.lexAmpMapSink)
    }

    /**
     * (WARM.29) …and so must the HYBRID, whose two branches answer the same
     * question by different means. A threshold off by one silently routes a level
     * to the branch that was not measured, and only the equality sees it.
     */
    @Test
    fun `the hybrid answers exactly what the map answers on both its branches`() = withCensus(amp = 3) {
        diagnose(source)
        assert(MapCensus.lexAmpMapSink > 0L)
        assert(MapCensus.lexAmpHybridSink == MapCensus.lexAmpMapSink)
        // …and both branches are actually taken on this fixture, so the equality
        // is not carried by one of them alone (round 849: a zero from a blind
        // instrument reads like a real negative).
        assert(MapCensus.lexHybridScanned > 0L)
        assert(MapCensus.lexHybridScanned + MapCensus.lexHybridFellBack == MapCensus.lexAmpCalls)
    }

    /**
     * (WARM.29) THE ROUND'S DECIDING IDENTITY, and the one round 901 could not
     * have stated: the scan's cost is weighted by PROBES, not by SCOPES.
     *
     * `lexBoundHistogram` counts each scope once, and off it round 901 read a mean
     * of 1.51 own symbols and estimated a scan at 3-6 ns. `lexProbeSizeHistogram`
     * counts each scope once PER PROBE, and the ascent reaches the outer levels on
     * every walk — so the two means differ by more than two orders of magnitude on
     * the compiler profile. Pinned as the structural fact that makes them
     * different populations at all: a probe-weighted total can EXCEED the bound
     * total, which a scope-weighted one never can.
     */
    @Test
    fun `every real probe is bucketed exactly once into the probe-weighted histogram`() =
        withCensus(amp = 3) {
            diagnose(source)
            assert(MapCensus.lexAmpCalls > 0L)
            var bucketed = 0L
            for (b in MapCensus.lexProbeSizeHistogram) bucketed += b
            // ONCE PER PROBE — the whole distinction the round turns on. A census
            // that recorded once per SCOPE instead is the population round 901 § 5
            // priced the successor from, and this is the only thing that sees it.
            assert(bucketed == MapCensus.lexAmpCalls)
            // …and never into bucket 0: the amplifier is armed only where the map
            // is non-empty, so a zero-size probe would mean the EMPTY/REAL split
            // stopped agreeing with the arm it gates.
            assert(MapCensus.lexProbeSizeHistogram[0] == 0L)
        }

    /**
     * (WARM.29) The summed sizes must be consistent with the histogram of the same
     * sizes — two independent accumulations of one quantity, kept in their own
     * method so they discriminate on their own.
     *
     * A census that recorded a CONSTANT size per probe (rather than the level's
     * length) leaves the histogram intact and only the sum wrong, which the
     * bucketing identity above cannot see and this can.
     */
    @Test
    fun `the summed probe sizes agree with the histogram of those sizes`() = withCensus(amp = 3) {
        diagnose(source)
        var lower = 0L
        for (i in MapCensus.lexProbeSizeHistogram.indices) {
            lower += i.toLong() * MapCensus.lexProbeSizeHistogram[i]
        }
        // Purely INTERNAL to the two accumulations, and deliberately so: comparing
        // `lower` against the CALL count would also fail under a de-duplicated
        // census, which is a different defect with a different consequence and
        // already has its own pin. An assertion that fires for two causes
        // separates neither (round 807).
        assert(lower > 0L)
        assert(MapCensus.lexProbeSizeSum >= lower)
    }

    /**
     * (WARM.29) …and the population really is RE-WEIGHTED: the same level is probed
     * many times over, so a probe-weighted total counts its symbols once per probe
     * where a scope-weighted one counts them once.
     *
     * Stated against the QUERIED scopes' own keys and not the BOUND ones. The bound
     * count is dominated by the lib binding on any small fixture, which is round
     * 898's A3 and round 901's A2 for the third time: an inequality a fixture
     * cannot express is satisfied — or violated — for a reason that has nothing to
     * do with the thing under test.
     */
    @Test
    fun `the probe-weighted size population is not the scope-weighted one`() = withCensus(amp = 3) {
        diagnose(source)
        // a scan pays at most the level's whole length and at least one step
        assert(MapCensus.lexScanSteps <= MapCensus.lexProbeSizeSum)
        assert(MapCensus.lexScanSteps >= MapCensus.lexAmpCalls)
        val queriedOwn = MapCensus.lexScopeKeys - MapCensus.lexScopeExistingKeys
        assert(MapCensus.lexProbeSizeSum > queriedOwn)
    }

    /**
     * (WARM.29) The scan's array is a view of the level's OWN keys and of nothing
     * else — never [LexicalScope.existing], whose table aliases the main binder's
     * and would put every INV.3 name back in play (round 748). Pinned as a size
     * identity because the array is built from `symbols.keys` and a scan over a
     * wider array is exactly the mistake that cannot be seen in an answer while
     * the amplifier is a measurement.
     */
    @Test
    fun `the scanned array holds the level's own keys and only those`() = withCensus(amp = 3) {
        diagnose(source)
        var scopesWithArray = 0
        var mismatches = 0
        for (scope in MapCensus.lexScopesSeen()) {
            val names = scope.censusNames ?: continue
            scopesWithArray++
            if (names.size != scope.symbols.size) mismatches++
            for (n in names) if (!scope.symbols.containsKey(n)) mismatches++
        }
        assert(scopesWithArray > 0)
        assert(mismatches == 0)
    }

    // ---- the round-900 lesson ----------------------------------------------

    /**
     * Every hook must sit INSIDE its `MapCensus.on` guard, arguments included.
     * Round 900 found a probe whose guard was inside the function while its
     * argument materialised a lazy view at the call site, so the instrument ran
     * on every production compile for ninety-nine rounds. Nothing but a pin that
     * compiles with the census OFF can see that.
     */
    @Test
    fun `no counter moves while the census is off`() {
        val savedOn = MapCensus.on
        val savedAmp = MapCensus.lexLevelAmp
        try {
            MapCensus.reset()
            MapCensus.on = false
            MapCensus.lexLevelAmp = 0
            diagnose(source)
            assert(MapCensus.lexCalls == 0L)
            assert(MapCensus.lexSymProbe == 0L)
            assert(MapCensus.lexScopesQueried == 0L)
            assert(MapCensus.lexScopesBound == 0L)
            assert(MapCensus.lexAmpCalls == 0L)
            assert(MapCensus.lexAmpScanSink == 0L)
            assert(MapCensus.lexAmpHybridSink == 0L)
            assert(MapCensus.lexProbeSizeSum == 0L)
            assert(MapCensus.sink == 0L)
        } finally {
            MapCensus.on = savedOn
            MapCensus.lexLevelAmp = savedAmp
            MapCensus.reset()
        }
    }
}
