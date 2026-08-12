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
 * (WARM.24) round 897 — pins [NameCensus], the instrument that priced round
 * 894's candidate (1) and refused it.
 *
 * A refusal rests on its instrument exactly as a fix does, and this one has
 * two claims a reader must be able to check without re-running a benchmark:
 *
 *  1. **The replayed arms answer the SAME THING.** Interning changes `String`
 *     IDENTITY and nothing else, so the raw arm and the interned arm must
 *     agree on every hit count, every rep. If they did not, the measured delta
 *     would be the price of a different computation and the refusal's number
 *     would be meaningless. This is also the round's equivalence claim in
 *     miniature: it is the property a real intern would have to preserve.
 *  2. **The counts are an exact multiple of the reps.** Round 759's law is
 *     that an amplified instrument is falsified by ARITHMETIC and never by
 *     timing — a JIT that elided one arm's loop would show up here as a hit
 *     count that is not `reps x constant`, and nowhere else.
 *
 * Nothing here asserts a TIME. Round 868: a timed assertion over a
 * sub-millisecond region is a coin flip, and the quantities this instrument
 * reports are milliseconds of a whole-population pass.
 */
class NameCensusTest {

    private fun <T> withSavedModes(block: () -> T): T {
        val on = NameCensus.on
        val reps = NameCensus.replayReps
        try {
            return block()
        } finally {
            NameCensus.on = on
            NameCensus.replayReps = reps
            NameCensus.reset()
        }
    }

    /**
     * The population deliberately uses DISTINCT instances for equal values —
     * `StringBuilder`-built, so no compile-time constant pool can collapse them
     * — because that is the whole subject: a probe and a stored key holding the
     * same characters in two different objects.
     */
    private fun fresh(s: String): String = StringBuilder(s).toString()

    @Test
    fun `the two replay arms return identical hit counts so interning is same-answers`() =
        withSavedModes {
            NameCensus.reset()
            val members = setOf(fresh("kind"), fresh("flags"))
            NameCensus.seed(
                tokens = listOf(fresh("kind"), fresh("kind"), fresh("other")),
                probes = listOf(fresh("kind"), fresh("flags"), fresh("absent"), fresh("kind")),
                members = members,
                globalNames = setOf(fresh("kind")),
            )
            NameCensus.replayReps = 4
            NameCensus.replay()
            assert(NameCensus.repsRun == 4L)
            assert(NameCensus.setRawHits == NameCensus.setInternHits)
            assert(NameCensus.mapRawHits == NameCensus.mapInternHits)
            // three of the four probes are set members ("kind" twice, "flags")
            assert(NameCensus.setRawHits == 12L)
            // one of the four probes is a global name ("kind", twice)
            assert(NameCensus.mapRawHits == 8L)
        }

    @Test
    fun `every replayed count is an exact multiple of the reps`() = withSavedModes {
        NameCensus.reset()
        NameCensus.seed(
            tokens = listOf(fresh("a"), fresh("a"), fresh("b")),
            probes = listOf(fresh("a"), fresh("zz")),
            members = setOf(fresh("a")),
            globalNames = setOf(fresh("a")),
        )
        NameCensus.replayReps = 5
        NameCensus.replay()
        assert(NameCensus.setRawHits % 5L == 0L)
        assert(NameCensus.setInternHits % 5L == 0L)
        assert(NameCensus.mapRawHits % 5L == 0L)
        assert(NameCensus.mapInternHits % 5L == 0L)
        assert(NameCensus.internHits % 5L == 0L)
        assert(NameCensus.foldHitsSeen % 5L == 0L)
        assert(NameCensus.keywordHitsSeen % 5L == 0L)
        // The intern arm sees one repeat of "a" per rep, and nothing else repeats.
        assert(NameCensus.internHits == 5L)
    }

    /**
     * The FOLD arm models the probe `scanIdentifier` ALREADY performs, so its
     * table is seeded with the reserved words: a keyword token must be a HIT in
     * both the keyword-only arm and the folded one, and a name token a hit only
     * once it has been interned.
     */
    @Test
    fun `the fold arm seeds the reserved words and interns everything else`() =
        withSavedModes {
            NameCensus.reset()
            NameCensus.seed(
                tokens = listOf(fresh("const"), fresh("const"), fresh("myName"), fresh("myName")),
                probes = listOf(fresh("myName")),
                members = setOf(fresh("myName")),
                globalNames = emptySet(),
            )
            NameCensus.replayReps = 2
            NameCensus.replay()
            // keyword-only arm: both `const` tokens hit, neither `myName` does
            assert(NameCensus.keywordHitsSeen == 4L)
            // folded arm: both `const` hit AND the second `myName` hits the entry
            // the first one interned
            assert(NameCensus.foldHitsSeen == 6L)
        }

    /** A keyword token is counted, but is not a NAME and must not enlarge the intern table. */
    @Test
    fun `idToken splits reserved words out of the distinct-name population`() =
        withSavedModes {
            NameCensus.reset()
            NameCensus.on = true
            NameCensus.idToken("const", keyword = true)
            NameCensus.idToken("myName", keyword = false)
            NameCensus.idToken("myName", keyword = false)
            NameCensus.on = false
            assert(NameCensus.idTokens == 3L)
            assert(NameCensus.keywordTokens == 1L)
            assert(NameCensus.distinctNameCount == 1)
            // every identifier-shaped token is captured, keyword or not — the
            // fold arm prices the probe the Scanner already pays for a keyword too
            assert(NameCensus.idChars == 17L)
        }

    /**
     * The two arms must probe two DIFFERENT containers.
     *
     * This pin exists because the ablation found the mistake it names — the
     * interned arm pointed back at the raw container — leaves every hit-count
     * pin green, and correctly so: both containers hold the same values, so
     * both arms report the same hits, which IS the same-answers property the
     * other pins assert. The fault is to the measurement, not to the answer,
     * and only the container identity can see it (round 813).
     */
    @Test
    fun `the raw and interned arms probe two distinct containers`() = withSavedModes {
        NameCensus.reset()
        NameCensus.seed(
            tokens = emptyList(),
            probes = listOf(fresh("kind")),
            members = setOf(fresh("kind")),
            globalNames = setOf(fresh("kind")),
        )
        NameCensus.replayReps = 2
        NameCensus.replay()
        assert(NameCensus.rawSetArm != null)
        assert(NameCensus.internSetArm != null)
        assert(NameCensus.rawSetArm !== NameCensus.internSetArm)
        assert(NameCensus.rawMapArm !== NameCensus.internMapArm)
        // …and the sticky claim, which is the one that survives the arms
        // alternating: a fault in the even branch alone is overwritten by the
        // odd one, so the three references above read healthy at the end.
        assert(!NameCensus.armsShared)
    }

    /**
     * [NameCensus.publish] is FIRST-WINS.
     *
     * A later publish must not replace a captured population. The two
     * populations here are chosen so that last-wins gives a DIFFERENT answer —
     * the second member set does not contain the probed name and the second
     * global set does. A pin whose two populations agree cannot see the fault,
     * which is what the first version of this test did.
     */
    @Test
    fun `publish never replaces a population it has already captured`() = withSavedModes {
        NameCensus.reset()
        NameCensus.publish(setOf(fresh("kind")), emptySet())
        NameCensus.publish(setOf(fresh("other")), setOf(fresh("kind")))
        NameCensus.seedProbes(listOf(fresh("kind")))
        NameCensus.replayReps = 1
        NameCensus.replay()
        // first-wins: "kind" IS a member and is NOT a global name
        assert(NameCensus.setRawHits == 1L)
        assert(NameCensus.mapRawHits == 0L)
    }

    /**
     * [NameCensus.publish] refuses an EMPTY member set.
     *
     * `moduleOnlyGlobalNames` is empty until init step 1b2, so a snapshot taken
     * before it would make every replayed probe a miss and the whole answer
     * vacuously zero — round 849's blind-instrument failure, which reads
     * exactly like a real negative. Nothing may be seeded in between here, or
     * the seed masks the fault (which is what made the first version blind).
     */
    @Test
    fun `publish refuses an empty population so a premature snapshot cannot stick`() =
        withSavedModes {
            NameCensus.reset()
            NameCensus.publish(emptySet(), setOf(fresh("kind")))
            NameCensus.publish(setOf(fresh("kind")), emptySet())
            NameCensus.seedProbes(listOf(fresh("kind")))
            NameCensus.replayReps = 1
            NameCensus.replay()
            assert(NameCensus.setRawHits == 1L)
        }

    /** Off is OFF: with no reps armed the replay records nothing at all. */
    @Test
    fun `an unarmed replay is inert`() = withSavedModes {
        NameCensus.reset()
        NameCensus.seed(
            tokens = listOf(fresh("a")),
            probes = listOf(fresh("a")),
            members = setOf(fresh("a")),
            globalNames = setOf(fresh("a")),
        )
        NameCensus.replayReps = 0
        NameCensus.replay()
        assert(NameCensus.repsRun == 0L)
        assert(NameCensus.setRawHits == 0L)
        assert(NameCensus.sink == 0L)
    }
}
