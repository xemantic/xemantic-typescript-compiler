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
 * (WARM.22) round 875 — the pins for [ReachCensus], the instrument that
 * measures the INV.4 reach machinery as ONE population.
 *
 * The machinery decides whether a migrated pass's diagnostic is even
 * CONSIDERED at a node, so the failure mode of anything built here is a MISSING
 * or DUPLICATED diagnostic rather than a crash. This round adds only counters,
 * and the two pins that matter say exactly that: the census counts a non-zero,
 * REPRODUCIBLE population, and arming it moves no diagnostic in either
 * direction.
 *
 * Three of the pins are built to REDDEN IF THE INSTRUMENT WERE INERT, which is
 * the failure a counter-only change actually has: a counter that is never
 * incremented, or a census whose fold count is unrelated to its ascent count,
 * reads exactly like a clean measurement and would have carried the round's
 * whole verdict (the family prices below 1%) on nothing at all.
 *
 * The amplifier's pins are ARITHMETIC, never timing (round 759): `ampCalls ==
 * r * ampBrackets` says the loop ran, and `ampSink % r == 0` says the JIT did
 * not hoist a pure predicate out of it — the two identities that make its slope
 * a measurement rather than a plausible number.
 */
class ReachCensusTest {

    private val source = """
        const enum E { A = 1, B = 2 }
        interface Node { kind: number }
        namespace N {
            export interface Shape { size: number }
            export const one = 1
        }
        type Alias = Node
        function outer<T>(t: T, n: Node): number {
            let total = 0
            if (n.kind === E.A) { total += 1 }
            const inner = (x: number) => x + total
            for (const q of [1, 2, 3]) { total += inner(q) }
            switch (n.kind) {
                case E.B: total += 2; break
                default: total += 3
            }
            try { total += 1 } catch (e) { total += 2 } finally { total += 3 }
            return total
        }
        class C {
            private v: number = 0
            get value(): number { return this.v }
            method(a: Node, b: N.Shape): number { return outer(a, a) + b.size }
        }
        const c = new C()
        c.method({ kind: 1 }, { size: 2 })
    """.trimIndent()

    /**
     * Runs one compile with the census armed and returns its diagnostic CODES.
     *
     * SAVE-AND-RESTORE, never "assign the default back": [ReachCensus.on] and
     * [ReachCensus.amp] are fork-global, and the round-619 `Inv0PassTimingTest`
     * lesson is that restoring a default re-arms whatever a lab file had set
     * for every alphabetically-later test class.
     */
    private fun runCensus(amp: Int = 0): List<Int> {
        val savedOn = ReachCensus.on
        val savedAmp = ReachCensus.amp
        ReachCensus.reset()
        ReachCensus.on = true
        ReachCensus.amp = amp
        try {
            return diagnose(source).map { it.code }
        } finally {
            ReachCensus.on = savedOn
            ReachCensus.amp = savedAmp
        }
    }

    private fun totals(): Triple<Long, Long, Long> {
        var c = 0L
        var m = 0L
        var f = 0L
        for (i in 0 until ReachCensus.N) {
            c += ReachCensus.calls[i]
            m += ReachCensus.misses[i]
            f += ReachCensus.folds[i]
        }
        return Triple(c, m, f)
    }

    @Test
    fun `the id table and the name table are index-aligned and complete`() {
        assert(ReachCensus.names.size == ReachCensus.N)
        assert(ReachCensus.calls.size == ReachCensus.N)
        assert(ReachCensus.misses.size == ReachCensus.N)
        assert(ReachCensus.folds.size == ReachCensus.N)
        // Spot-check the generated ids against their own names, which is what
        // catches a hand-edit that renumbers one without the other and silently
        // charges one classifier's consultations to another.
        assert(ReachCensus.names[ReachCensus.CE] == "Ce")
        assert(ReachCensus.names[ReachCensus.IANY] == "Iany")
        assert(ReachCensus.names[ReachCensus.URESEXPR] == "UResExpr")
        assert(ReachCensus.names[ReachCensus.TAV] == "Tav")
        assert(ReachCensus.names[ReachCensus.UY] == "Uy")
    }

    @Test
    fun `the census counts a non-empty population of consultations and folds`() {
        runCensus()
        val (calls, misses, folds) = totals()
        // Non-vacuous: an instrument whose counters never fire reads exactly
        // like a clean measurement, so the round's verdict rests on this.
        assert(calls > 0L)
        assert(misses > 0L)
        assert(folds > 0L)
        // More than one classifier must be consulted, or the fixture is
        // exercising a single pass and the family reading means nothing.
        var armed = 0
        for (i in 0 until ReachCensus.N) if (ReachCensus.calls[i] > 0L) armed++
        assert(armed >= 5)
    }

    @Test
    fun `every ascent folds at least one edge and no classifier ascends more than it is consulted`() {
        runCensus()
        for (i in 0 until ReachCensus.N) {
            val calls = ReachCensus.calls[i]
            val misses = ReachCensus.misses[i]
            val folds = ReachCensus.folds[i]
            // An ascent that folded nothing would mean the fold counter is
            // attached to a path the ascent does not take — the exact defect
            // that makes an edge-evaluation count too small and a "the edges
            // are cheap" verdict unearned.
            assert(folds >= misses)
            // A memo HIT cannot ascend, so ascents can never exceed calls.
            assert(misses <= calls)
        }
    }

    @Test
    fun `a classifier that folded an edge was also counted as consulted`() {
        runCensus()
        for (i in 0 until ReachCensus.N) {
            // A dropped `calls` increment leaves the classifier's folds and
            // ascents intact, so every OTHER pin still reads a healthy census
            // and only this one can see that a whole classifier's consultations
            // are being charged to nobody — which is how a family total comes
            // out low and a "the family prices below 1%" verdict comes out
            // unearned.
            assert(ReachCensus.calls[i] > 0L || ReachCensus.folds[i] == 0L)
        }
    }

    @Test
    fun `the census is reproducible across two identical compiles`() {
        runCensus()
        val first = ReachCensus.calls.copyOf()
        val firstFolds = ReachCensus.folds.copyOf()
        runCensus()
        for (i in 0 until ReachCensus.N) {
            assert(ReachCensus.calls[i] == first[i])
            assert(ReachCensus.folds[i] == firstFolds[i])
        }
    }

    @Test
    fun `equivalence - arming the census moves no diagnostic`() {
        val armed = runCensus()
        val plain = diagnose(source).map { it.code }
        assert(armed == plain)
        // …and the comparison is not vacuous: the fixture really does compile
        // to something the reach machinery had to walk.
        val (calls, _, _) = totals()
        assert(calls > 0L)
    }

    @Test
    fun `the amplifier multiplies exactly and its sink rules out a hoisted loop`() {
        val r = 8
        runCensus(amp = r)
        var brackets = 0L
        for (i in 0 until 2) {
            brackets += ReachCensus.ampBrackets[i]
            // `nanos(r) = boundary + r * c` is only an equation if the loop ran
            // r times per bracket.
            assert(ReachCensus.ampCalls[i] == r.toLong() * ReachCensus.ampBrackets[i])
            // Each bracket evaluates ONE pure predicate r times, so it
            // contributes 0 or r to the sink. A JIT that hoisted the call would
            // break this identity, and nothing else in the harness would.
            assert(ReachCensus.ampSink[i] % r == 0L)
        }
        assert(brackets > 0L)
    }

    @Test
    fun `equivalence - arming the amplifier moves no diagnostic`() {
        val amplified = runCensus(amp = 8)
        val plain = diagnose(source).map { it.code }
        assert(amplified == plain)
    }

    @Test
    fun `negative control - with the census off nothing is counted`() {
        val savedOn = ReachCensus.on
        ReachCensus.reset()
        ReachCensus.on = false
        try {
            diagnose(source)
        } finally {
            ReachCensus.on = savedOn
        }
        val (calls, misses, folds) = totals()
        assert(calls == 0L)
        assert(misses == 0L)
        assert(folds == 0L)
    }
}
