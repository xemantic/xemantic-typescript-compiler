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
 * (WARM.33) round 906 — the pins for the memo-ACCESS census and the two-layout
 * cache model that refused the transposition.
 *
 * The instrument changes no diagnostic, emits no byte and moves no
 * `cost_gate.py` counter, so nothing else in this repo can see it break. Every
 * pin therefore asserts a NON-EMPTY population before it asserts an identity
 * over it — a census that recorded nothing satisfies every identity vacuously
 * (round 849) — and the identities chosen are the ones that were used as
 * falsifiers when the round's numbers were read:
 *
 * * the 43 reach classifiers' PROBE count reproduces [ReachCensus]'s own
 *   `calls`, EXACTLY, which is what says the 139 injected hooks sit at the
 *   accesses they claim to;
 * * the gap histogram accounts for every ascent step except the two
 *   interleaved classifiers', which record no gap by construction;
 * * every simulated access is classified into exactly one cache level, so the
 *   four per-level counts sum to the accesses plus the modelled zeroing;
 * * and the whole thing is inert when disarmed.
 */
class ReachMemoCensusTest {

    /**
     * Wide enough to reach several classifiers at once — statements, a class
     * body, a call with arguments, a loop, a conditional — so the per-node
     * consultation histogram has a non-degenerate tail rather than a column at
     * one.
     */
    private val source = """
        class Holder {
            readonly items: number[] = [1, 2, 3];
            take(a: number, b: string): void { }
            run(flag: boolean): number {
                let total = 0;
                for (const x of this.items) {
                    if (flag) { total = total + x; } else { total = total - x; }
                    this.take(x, "t");
                }
                return total;
            }
        }
        declare function f(a: number, b: string, c: boolean): void;
        f(1, "x", true);
    """.trimIndent()

    private fun <T> withCensus(block: () -> T): T {
        val savedMemo = ReachMemoCensus.on
        val savedReach = ReachCensus.on
        try {
            ReachMemoCensus.reset()
            ReachCensus.reset()
            ReachMemoCensus.on = true
            ReachCensus.on = true
            return block()
        } finally {
            ReachMemoCensus.on = savedMemo
            ReachCensus.on = savedReach
            ReachMemoCensus.reset()
            ReachCensus.reset()
        }
    }

    /**
     * The instrument's own falsifier, and the one that decided the round could
     * be believed: the 43 reach classifiers' probe counts must reproduce round
     * 875's independently injected consultation counters to the digit. The two
     * extra `ShortArray` depth memos are excluded because [ReachCensus] does not
     * know about them — which is itself asserted, so the exclusion cannot
     * silently grow.
     */
    @Test
    fun `the 43 classifiers probe exactly once per consultation`() {
        withCensus {
            diagnose(source)
            var probes = 0L
            var calls = 0L
            for (c in 0 until ReachCensus.N) {
                probes += ReachMemoCensus.probes[c]
                calls += ReachCensus.calls[c]
            }
            assert(calls > 0)
            assert(probes == calls)
            // the depth memos are OUTSIDE ReachCensus and inside this census
            assert(ReachCensus.N == 43)
            assert(ReachMemoCensus.N == 45)
        }
    }

    /** A fold writes one status per chain element, so a rebuild that ascends
     *  must also write; and an ascent is only ever taken on a memo MISS, so it
     *  can never exceed the folds round 875 counts. */
    @Test
    fun `the access families are non-empty and ordered`() {
        withCensus {
            diagnose(source)
            var probes = 0L
            var ascents = 0L
            var writes = 0L
            for (c in 0 until ReachMemoCensus.N) {
                probes += ReachMemoCensus.probes[c]
                ascents += ReachMemoCensus.ascents[c]
                writes += ReachMemoCensus.writes[c]
            }
            assert(probes > 0)
            assert(ascents > 0)
            assert(writes > 0)
            assert(writes >= ascents)
        }
    }

    /**
     * Every ascent step records its nodeId gap EXCEPT the two interleaved
     * classifiers (`UResExpr`/`UResType`), whose probe and edge share one loop
     * so the child is the node being probed. The identity is asserted rather
     * than the two counts separately, because it is the one that fails if a
     * future classifier is hooked with the wrong entry point.
     */
    @Test
    fun `the gap histogram accounts for every ascent step but the interleaved two`() {
        withCensus {
            diagnose(source)
            var ascents = 0L
            for (c in 0 until ReachMemoCensus.N) ascents += ReachMemoCensus.ascents[c]
            val interleaved =
                ReachMemoCensus.ascents[ReachCensus.URESEXPR] +
                    ReachMemoCensus.ascents[ReachCensus.URESTYPE]
            var gaps = 0L
            for (g in ReachMemoCensus.gapHistogram) gaps += g
            assert(ascents > 0)
            assert(gaps > 0)
            assert(gaps == ascents - interleaved)
            // a parent is never its own child, so the zero-gap bucket is empty
            assert(ReachMemoCensus.gapHistogram[0] == 0L)
        }
    }

    /** The per-node consultation histogram is a partition of the file's nodes,
     *  which is what makes "13.9% of nodes are never consulted" readable at
     *  all; bucket 0 is the never-consulted population. */
    @Test
    fun `the per-node consultation histogram partitions the nodes`() {
        withCensus {
            diagnose(source)
            // `report()` closes the last file's histogram — `beginFile` only
            // closes the PREVIOUS one, and this fixture has a single file.
            ReachMemoCensus.report()
            var sum = 0L
            for (b in ReachMemoCensus.consultsPerNode) sum += b
            assert(ReachMemoCensus.nodes > 0)
            assert(sum == ReachMemoCensus.nodes)
            assert(ReachMemoCensus.consultedNodes > 0)
            assert(ReachMemoCensus.consultedNodes <= ReachMemoCensus.nodes)
        }
    }

    /**
     * The cache model classifies every access into exactly one level, so the
     * four counts sum to the accesses plus the modelled zeroing — for BOTH
     * layouts, at every geometry. The report is the surface here because the
     * hierarchies are private; a sum that drifts shows up as a level count that
     * no longer adds.
     */
    @Test
    fun `the layout model classifies every access into exactly one level`() {
        withCensus {
            diagnose(source)
            val text = ReachMemoCensus.report()
            assert("LAYOUT SIMULATION" in text)
            var accesses = 0L
            for (c in 0 until ReachMemoCensus.N) {
                accesses += ReachMemoCensus.probes[c] +
                    ReachMemoCensus.ascents[c] + ReachMemoCensus.writes[c]
            }
            assert(accesses > 0)
            // anchored, so the `B-A` / `C-A` DELTA lines cannot be read as arms
            val rows = Regex(
                "(?m)^\\s+([ABC])\\s+l1=(\\d+) l2=(\\d+) l3=(\\d+) dram=(\\d+)${'$'}"
            ).findAll(text).toList()
            // three layouts x five geometries
            assert(rows.size == 15)
            for (r in rows) {
                val total = (2..5).sumOf { r.groupValues[it].toLong() }
                // every access is classified, plus the zeroing of a footprint
                // that is 45-64 bytes a node depending on the layout
                assert(total > accesses)
                assert(total < accesses + ReachMemoCensus.nodes * 64)
            }
        }
    }

    /**
     * The negative control, and the reason it is here: INV.0's "false must stay
     * behaviour-free" is a claim about 139 injected lines in `Checker.kt`, and
     * round 900 showed that a `if (mode != ON) return` guard cannot protect its
     * own ARGUMENT — so a hook whose argument did work would be paid for by
     * every production compile with nothing to show it.
     */
    @Test
    fun `disarmed the census records nothing and changes no diagnostic`() {
        val armed = withCensus { diagnose(source) }.map { it.code }
        ReachMemoCensus.reset()
        val plain = diagnose(source).map { it.code }
        assert(plain == armed)
        var accesses = 0L
        for (c in 0 until ReachMemoCensus.N) {
            accesses += ReachMemoCensus.probes[c] +
                ReachMemoCensus.ascents[c] + ReachMemoCensus.writes[c]
        }
        assert(accesses == 0L)
        assert(ReachMemoCensus.nodes == 0L)
    }
}
