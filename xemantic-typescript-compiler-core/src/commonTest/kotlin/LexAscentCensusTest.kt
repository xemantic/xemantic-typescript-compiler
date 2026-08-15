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
 * (WARM.34) round 907 — the census that priced the COUNT question rounds 901/902
 * left open for `lexLevelHasName`, and refused it.
 * `docs/perf/lex-ascent-count-price.md`.
 *
 * What these pins protect is a MEASUREMENT, not an answer: the census changes no
 * diagnostic, so no output assertion anywhere can see one of them break. They are
 * therefore counter identities that hold BY CONSTRUCTION and are breached the
 * moment a hook moves — the same shape rounds 901, 902 and 906 used.
 *
 * The identity that carries the round is the PARTITION. The bracketing is
 * close-on-next-entry, which is exact only while the five ascent functions are the
 * only callers of the four real probe sites and never nest; if either stops being
 * true, the per-ascent probe counts stop summing to the real-probe total and
 * `the per-ascent probe counts partition the real probes` reddens. Everything the
 * round concludes rests on the mean **1.544 probes per ascent**, i.e. on that sum
 * being the whole population and the ascent count being top-level queries only.
 */
class LexAscentCensusTest {

    /**
     * Reaches every arm: a namespace (an UNTRUSTED level), generic functions
     * (levels whose own `symbols` carry type parameters), nested functions and
     * blocks (levels binding nothing), a name asked repeatedly at ONE walk point
     * (the ascent memo's population), and a name bound NOWHERE, which is what
     * makes the no-hit population non-empty at all.
     *
     * Two devices are load-bearing and were added because the pins read zero
     * without them. The file-level bare block is round 902's: its `var` is
     * B83.5-hoisted into the SOURCE FILE root's own `symbols`, and the root is the
     * only level carrying an `existing` table past the untrusted-owner rule — with
     * an empty root every ascent that walks out to it probes a null table and is
     * charged as a no-PROBE rather than a no-HIT. And `nowhereBound` is what
     * supplies an ascent that probes a real level and finds the name in none:
     * every other name in the fixture is bound somewhere, so without it the
     * no-hit population is EMPTY and the pin would be vacuous (round 849).
     */
    private val source = """
        {
            var hoistedAtFileLevel = 0;
            hoistedAtFileLevel = hoistedAtFileLevel + 1;
        }
        const valueOnly = 1;
        const top = 2;
        namespace N {
            export const inside = 1;
            export function useOuter(): number {
                return inside + top + top + top;
            }
        }
        function outer<T>(p: T): number {
            let seen = 0;
            {
                let inner = p;
                seen = seen + seen + seen;
            }
            function nested(q: T): T {
                return q;
            }
            nested(p);
            nowhereBound(seen);
            return seen;
        }
        function sibling<T>(p: T): number {
            let seen = 0;
            {
                let inner = p;
                seen = seen + seen + seen;
            }
            return seen;
        }
        outer(N.inside);
        sibling(top);
    """.trimIndent()

    private fun <T> withCensus(block: () -> T): T {
        val savedOn = MapCensus.on
        try {
            MapCensus.reset()
            MapCensus.on = true
            return block()
        } finally {
            MapCensus.on = savedOn
            MapCensus.reset()
        }
    }

    // ---- the bracketing -----------------------------------------------------

    /**
     * An ascent is opened by a TOP-LEVEL query, never by the chain step that
     * continues one — which is the whole reason the five functions are split into
     * a public entry and a `…From` recursion. The fixture recurses (nested
     * functions inside blocks inside a generic function), so the chain steps
     * strictly exceed the ascents; wire the recursion back to the public entry and
     * the two become equal and the round's 1.544 becomes 0.42.
     */
    @Test
    fun `an ascent is opened once per top-level query, never per chain step`() = withCensus {
        diagnose(source)
        MapCensus.lexAscentFinish()
        val calls = MapCensus.lexAscentCalls.sum()
        val steps = MapCensus.lexAscentScopeSteps
        assert(calls > 0)
        assert(steps > calls)
    }

    /**
     * The partition. Every real probe any of the three families performs is
     * charged to exactly one ascent — which is what makes "1.544 probes per
     * ascent" a measurement rather than a ratio of two independently drawn
     * populations (round 796).
     */
    @Test
    fun `the per-ascent probe counts partition the real probes`() = withCensus {
        diagnose(source)
        MapCensus.lexAscentFinish()
        val probes = MapCensus.lexRealProbes()
        assert(probes > 0)
        assert(MapCensus.lexAscentProbesFirst + MapCensus.lexAscentProbesRepeat == probes)
        assert(MapCensus.lexAscentProbesByFamily.sum() == probes)
    }

    /**
     * …and every ascent is bucketed exactly once by the probes it performed, with
     * the no-probe and no-hit populations both non-empty — a zero from either
     * would be a blind instrument reading like a real negative (round 849).
     */
    @Test
    fun `the probe histogram partitions the ascents`() = withCensus {
        diagnose(source)
        MapCensus.lexAscentFinish()
        val calls = MapCensus.lexAscentCalls.sum()
        assert(MapCensus.lexAscentProbeHistogram.sum() == calls)
        assert(MapCensus.lexAscentProbeHistogram[0] == MapCensus.lexAscentNoProbe)
        assert(MapCensus.lexAscentNoProbe > 0)
        assert(MapCensus.lexAscentNoHit > 0)
        assert(MapCensus.lexAscentNoHitProbes > 0)
        assert(MapCensus.lexAscentNoHit + MapCensus.lexAscentNoProbe <= calls)
    }

    // ---- the two key spaces -------------------------------------------------

    /**
     * The ascent memo's key is `(scope, name, family)`, and dropping the scope is
     * the mistake that inflates the repeat rate — 36.4% is what the round prices,
     * and a name-keyed set would read far higher and turn the refusal into a
     * build. Asserted on the primitive rather than through a compile, because the
     * property is about the KEY and a compile can only show its consequences.
     */
    @Test
    fun `the same name at two different scopes is two first sightings`() = withCensus {
        MapCensus.lexAscentTop(MapCensus.AS_HAS, 1, "x")
        MapCensus.lexAscentTop(MapCensus.AS_HAS, 2, "x")
        assert(MapCensus.lexAscentRepeat.sum() == 0L)
        MapCensus.lexAscentTop(MapCensus.AS_HAS, 1, "x")
        assert(MapCensus.lexAscentRepeat.sum() == 1L)
        // …and the family is part of the key: the same name at the same scope
        // asked as a TYPE is a different question with a different answer.
        MapCensus.lexAscentTop(MapCensus.AS_TYPE, 1, "x")
        assert(MapCensus.lexAscentRepeat.sum() == 1L)
        assert(MapCensus.lexAscentCalls.sum() == 4L)
    }

    /**
     * The per-LEVEL redundancy (80.7% of the stream) is keyed on the LEVEL, not on
     * the name — a name-keyed pair census would report a redundancy that is a
     * property of the program's vocabulary rather than of the probe stream, and
     * § 3's refusal is quoted against it.
     */
    @Test
    fun `a level-name pair is distinct per LEVEL`() = withCensus {
        val f = SourceFile(fileName = "t.ts", statements = emptyList(), text = "")
        val a = LexicalScope(owner = f, parent = null)
        val b = LexicalScope(owner = f, parent = null)
        MapCensus.lexPair(a, "x")
        MapCensus.lexPair(b, "x")
        assert(MapCensus.lexPairDistinct == 2L)
        assert(MapCensus.lexPairRepeat == 0L)
        MapCensus.lexPair(a, "x")
        assert(MapCensus.lexPairDistinct == 2L)
        assert(MapCensus.lexPairRepeat == 1L)
    }

    /**
     * The no-hit population — § 4's upper bound on a per-file proof-of-absence
     * filter — is keyed on whether the level HELD the name, not on what the
     * function answered. `lexLevelHasType` can find a symbol and answer false on
     * its flags, and a name filter cannot tell those apart, so recording the
     * verdict instead of the presence would over-count the refusable population
     * and inflate the ceiling the round refuses.
     *
     * Asserted as a DIFFERENTIAL rather than as a count, because a count would be
     * a claim about the whole fixture rather than about the one property. The two
     * programs differ only in how `annotated` is declared: as a `const` the root's
     * table HOLDS it while `lexLevelHasType` answers false on its flags, as a
     * `class` it holds it and answers true. Presence is the same in both, so the
     * no-hit populations must be equal — and under the verdict reading the `const`
     * arm gains an ascent the `class` arm does not.
     */
    @Test
    fun `a level that holds the name is a hit even when the verdict is false`() {
        val root = """
            {
                var hoistedAtFileLevel = 0;
                hoistedAtFileLevel = hoistedAtFileLevel + 1;
            }
        """.trimIndent()
        val asValue = withCensus {
            diagnose(root + "\nconst annotated = 1;\nlet bad: annotated;")
            MapCensus.lexAscentFinish()
            assert(MapCensus.lexRealProbes() > 0)
            MapCensus.lexAscentNoHit
        }
        val asType = withCensus {
            diagnose(root + "\nclass annotated { }\nlet bad: annotated;")
            MapCensus.lexAscentFinish()
            assert(MapCensus.lexRealProbes() > 0)
            MapCensus.lexAscentNoHit
        }
        assert(asValue == asType)
    }

    // ---- INV.0 --------------------------------------------------------------

    /**
     * Off, the census is inert. INV.0's rule, and the one property that is NOT a
     * counter identity: a hook hoisted out of its `MapCensus.on` guard costs every
     * production compile and nothing else in this repo would notice (round 900
     * found exactly that shape running for ninety-nine rounds).
     */
    @Test
    fun `the ascent census is inert when off`() {
        val savedOn = MapCensus.on
        try {
            MapCensus.reset()
            MapCensus.on = false
            diagnose(source)
            MapCensus.lexAscentFinish()
            assert(MapCensus.lexAscentCalls.sum() == 0L)
            assert(MapCensus.lexAscentScopeSteps == 0L)
            assert(MapCensus.lexRealProbes() == 0L)
            assert(MapCensus.lexPairDistinct == 0L)
            assert(MapCensus.lexAscentScopes() == 0)
        } finally {
            MapCensus.on = savedOn
            MapCensus.reset()
        }
    }
}
