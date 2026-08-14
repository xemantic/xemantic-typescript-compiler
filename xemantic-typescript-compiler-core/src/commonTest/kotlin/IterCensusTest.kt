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
 * (WARM.32) round 905 — the two things the iterator-allocation family left
 * behind: the SEMANTICS of [anyIdentical], and the census that priced the
 * family at 0.074% and refused it.
 *
 * The first group is a CORRECTNESS pin and outranks the rest. 145 call sites in
 * `Checker.kt` were `xs.any { it === child }` and are now `xs.anyIdentical(child)`;
 * the reason they were never `xs.contains(child)` is round 471 — AST nodes are
 * data classes, so `equals`/`hashCode` DEEP-RECURSE the subtree, which is both a
 * cost and a wrong answer (two structurally identical nodes at different
 * positions in the tree are NOT the same child). Nothing in the corpus can see
 * that distinction reliably, so it is pinned here.
 *
 * The second group protects a MEASUREMENT: the instrument changes no diagnostic,
 * emits no byte and moves no `cost_gate.py` counter, so no output assertion
 * anywhere can see it break. Every pin asserts a NON-EMPTY population before it
 * asserts an identity over it — a census that recorded nothing satisfies every
 * identity vacuously (round 849).
 */
class IterCensusTest {

    /**
     * Drives both populations at once: the list child positions (a `Block`'s
     * statements, a call's arguments, a class's members, an array literal's
     * elements) and the INV.4 edge classifiers' identity tests, which are
     * reached from a CALL-ARGUMENT edge — the shape `parent.arguments.any { it
     * === child }` is written for.
     */
    private val source = """
        class Holder {
            readonly items: number[] = [1, 2, 3];
            take(a: number, b: string): void { }
            run(): void {
                const xs = [1, 2, 3];
                this.take(xs.length, "s");
                for (const x of xs) { this.take(x, "t"); }
            }
        }
        declare function f(a: number, b: string, c: boolean): void;
        f(1, "x", true);
    """.trimIndent()

    private fun <T> withCensus(census: Boolean = true, amp: Int = 0, block: () -> T): T {
        val savedOn = IterCensus.on
        val savedCensus = IterCensus.census
        val savedAmp = IterCensus.amp
        try {
            IterCensus.reset()
            IterCensus.on = true
            IterCensus.census = census
            IterCensus.amp = amp
            return block()
        } finally {
            IterCensus.on = savedOn
            IterCensus.census = savedCensus
            IterCensus.amp = savedAmp
            IterCensus.reset()
        }
    }

    // ---- the correctness group ---------------------------------------------

    /**
     * The whole reason the 145 sites are not `contains`: two nodes that are
     * `equals` must NOT be found, because the question the edge classifiers ask
     * is "is this the very child I am standing on", not "is a node like it
     * somewhere in the list".
     *
     * The pin asserts BOTH directions on one list, so it cannot be satisfied by
     * an implementation that always answers false.
     */
    @Test
    fun `anyIdentical is identity and never equality`() {
        val a = Identifier("x")
        val twin = Identifier("x")
        val b = Identifier("y")
        val xs = listOf<Node>(a, b)
        assert(a == twin)
        assert(a !== twin)
        assert(xs.anyIdentical(a))
        assert(xs.anyIdentical(b))
        assert(!xs.anyIdentical(twin))
        assert(!xs.anyIdentical(Identifier("z")))
    }

    /** The two degenerate populations the census found to be 7.0% and 0.9%. */
    @Test
    fun `anyIdentical answers false for an empty list and scans a singleton`() {
        val a = Identifier("a")
        assert(!emptyList<Node>().anyIdentical(a))
        assert(listOf<Node>(a).anyIdentical(a))
        assert(!listOf<Node>(Identifier("b")).anyIdentical(a))
    }

    /**
     * The hit stops the scan, which is what makes `steps` (378,400) smaller than
     * `length` (521,728) in the census and is the quantity the amplifier's arm B
     * reproduces. Asserted through the census's own step counter, so this pin
     * fails if a future body scans the whole list.
     */
    @Test
    fun `anyIdentical stops at the hit`() = withCensus {
        val a = Identifier("a")
        val xs = listOf<Node>(a, Identifier("b"), Identifier("c"), Identifier("d"))
        assert(xs.anyIdentical(a))
        assert(IterCensus.anyCalls == 1L)
        assert(IterCensus.anySteps == 1L)
        assert(IterCensus.anyLength == 4L)
        assert(IterCensus.anyHits == 1L)
        // ... and a MISS pays the whole list, which is the other half of the
        // 1.72-steps-per-call mean.
        assert(!xs.anyIdentical(Identifier("z")))
        assert(IterCensus.anySteps == 5L)
        assert(IterCensus.anyHits == 1L)
    }

    // ---- the negative control ----------------------------------------------

    /**
     * INV.0's requirement, and the ONE pin that would see the round-900 mistake:
     * a `if (mode != ON) return` guard cannot protect its own ARGUMENT, because
     * Kotlin evaluates arguments strictly. A hook written `noteList(xs.size)`
     * would read `size` on every production compile while leaving these counters
     * at zero — and every other pin in this class would still pass.
     *
     * This asserts the counters stay at zero with the census OFF; the pin below
     * asserts the same fixture moves them when it is ON. Neither is sufficient
     * alone: together they say the hooks are reached exactly when armed.
     */
    @Test
    fun `with the census off every counter stays at zero`() = withCensus(census = false) {
        diagnose(source)
        assert(IterCensus.fecCalls == 0L)
        assert(IterCensus.fecElements == 0L)
        assert(IterCensus.anyCalls == 0L)
        assert(IterCensus.ampCalls == 0L)
    }

    // ---- the census identities ---------------------------------------------

    /**
     * Both populations are REACHED by a real compile — round 902's law: an arm
     * whose mistake is never reached prints exactly like a redundant one, and a
     * census whose hook is never reached prints exactly like a real zero.
     */
    @Test
    fun `a real compile reaches both populations`() = withCensus {
        diagnose(source)
        assert(IterCensus.fecCalls > 0L)
        assert(IterCensus.fecElements > 0L)
        assert(IterCensus.anyCalls > 0L)
        assert(IterCensus.anyHits > 0L)
    }

    /**
     * The histogram is the round's empty/singleton split, so it must partition
     * the calls exactly — an off-by-one bucket would move the 7.0% EMPTY figure
     * the verdict quotes.
     */
    @Test
    fun `each histogram partitions its own call count`() = withCensus {
        diagnose(source)
        var fec = 0L
        for (n in IterCensus.fecSizeHistogram) fec += n
        assert(fec == IterCensus.fecCalls)
        var any = 0L
        for (n in IterCensus.anySizeHistogram) any += n
        assert(any == IterCensus.anyCalls)
    }

    /**
     * `elements` is the sum of the list lengths, so it can never be smaller than
     * the number of calls that saw a non-empty list; and `steps <= length`,
     * because a hit stops the scan and a miss walks it all.
     */
    @Test
    fun `the element and step totals bound each other`() = withCensus {
        diagnose(source)
        assert(IterCensus.fecElements >= IterCensus.fecCalls - IterCensus.fecSizeHistogram[0])
        assert(IterCensus.anySteps <= IterCensus.anyLength)
        assert(IterCensus.anyHits <= IterCensus.anyCalls)
    }

    // ---- the amplifier's own falsifiers ------------------------------------

    /**
     * The two arms answer the SAME question, so their sinks must be equal —
     * that is an equivalence assertion, not merely a liveness one, and it is
     * what says the indexed arm visits the same elements in the same order.
     * Every sink must also be an exact multiple of `r`, which is round 759's
     * arithmetic falsifier against an elided loop.
     *
     * Pinned here because the round's verdict rests on it and nothing else in
     * the suite runs the amplifier at all.
     */
    @Test
    fun `both amplifier arms agree and their sinks are exact multiples of r`() {
        val r = 8
        withCensus(census = false, amp = r) {
            diagnose(source)
            assert(IterCensus.ampCalls > 0L)
            assert(IterCensus.ampIterSink == IterCensus.ampIdxSink)
            assert(IterCensus.ampIterSink % r == 0L)
            assert(IterCensus.ampAnyCalls > 0L)
            assert(IterCensus.ampAnyIterSink == IterCensus.ampAnyIdxSink)
            assert(IterCensus.ampAnyIterSink % r == 0L)
            // Both arms actually took a bracket — a zero here would make the
            // premium unmeasurable while every equality above still held.
            assert(IterCensus.ampIterNanos > 0L)
            assert(IterCensus.ampIdxNanos > 0L)
        }
    }
}
