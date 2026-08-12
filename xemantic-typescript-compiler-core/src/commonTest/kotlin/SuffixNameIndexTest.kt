/*
 *  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 *  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 *  xemantic-typescript-compiler - a conformant TypeScript compiler and type
 *  checker that runs on JVM, native, and WebAssembly
 *  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, version 3 of the License.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public
 *  License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 *  As a special exception, this file contains Helper Code covered by the
 *  xemantic-typescript-compiler Output Exception; additional permissions
 *  are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (WARM.27) round 900 — [SuffixNameIndex], and the eager probe argument it
 * exposed.
 *
 * **The measurement.** Round 899 § 33.8(5) ranked `SuffixNameSet.materialize` at
 * 21.6 ms of 100%-insert map work and said the answer was BINARY: ~0.5-1.0 M
 * `HashSet.add`s is implausible for a set built once per file and plausible for
 * one rebuilt per query, so one counter decides it. The counter says **767,521
 * names inserted across 1,143 sets** on a compiler-profile rebuild — 28.1 ns per
 * add against the JFR row, i.e. the row is real, the only one of round 899's six
 * candidates whose profile figure survives round 898's plausibility test instead
 * of deflating ~3x under it.
 *
 * **And the sets were built once, so the fix is not a memo.** The 1,143 suffixes
 * are cut from **1,220 cached scans holding 15,331 names in total**: one scan
 * backs hundreds of closures, its suffixes are NESTED, and their union is the
 * scan. One shared last-occurrence index per scan therefore answers all of them,
 * which is 11,619 inserts where there were 767,521.
 *
 * **The defect underneath, which the counters found and no gate here could.**
 * `FlowGraphBuilder` reported the closure census as
 * `FrontEnd.addClosureCensus(reassigned.size.toLong())`. Kotlin evaluates
 * arguments strictly, so the `mode != ON` guard *inside* that function never got
 * the chance to stop `.size` — and asking a lazy view its size materialises it.
 * Round 801's whole point was that the view defers, and its own census
 * (`created 1143, materialized 1143`) was read as "every set is eventually
 * asked". It was not: **the asker was the instrument**, on every production
 * compile, and with the probe removed from the production path the same census
 * reads `created 1143, materialized 0`.
 *
 * These pins were ablated one mistake at a time (round 807); which arm each one
 * discriminates is recorded in `docs/perf/suffix-name-index.md`.
 */
class SuffixNameIndexTest {

    /**
     * The index stores the LAST occurrence, and a duplicate straddling the cut is
     * the only shape that can tell it from the first. With first-wins, a name
     * reassigned both before and after the closure answers `false` and the
     * narrowing flows into a closure it must not flow into.
     */
    @Test
    fun `a name occurring on both sides of the cut is in the suffix`() {
        val names = arrayOf("a", "b", "a", "c")
        assert(SuffixNameIndex(names).lastIndexOf("a") == 2)
        assert(SuffixNameSet(names, 2).contains("a"))
        assert(!SuffixNameSet(names, 3).contains("a"))
        assert(SuffixNameSet(names, 0).contains("a"))
    }

    /** An absent name answers -1 and is in no suffix at all - including the whole array. */
    @Test
    fun `an absent name is in no suffix`() {
        val names = arrayOf("a", "b", "a", "c")
        assert(SuffixNameIndex(names).lastIndexOf("zzz") == -1)
        for (lo in 0..names.size) assert(!SuffixNameSet(names, lo).contains("zzz"))
    }

    /**
     * The exhaustive equivalence: over every cut and every candidate name, the
     * index-answered `contains` agrees with the materialised set it replaced.
     * This is the whole soundness claim of the round, so it is checked by
     * construction rather than by example.
     */
    @Test
    fun `index membership agrees with the materialized set at every cut`() {
        val names = arrayOf("a", "b", "a", "c", "b", "b", "d")
        val candidates = listOf("a", "b", "c", "d", "e")
        for (lo in 0..names.size) {
            val view = SuffixNameSet(names, lo)
            val reference = names.drop(lo).toSet()
            for (name in candidates) assert(view.contains(name) == (name in reference))
            assert(view.containsAll(reference))
            assert(view.isEmpty() == reference.isEmpty())
        }
    }

    /**
     * One index serves EVERY suffix of its scan — that sharing is the entire
     * prize, and a per-set index would leave the 767,521 inserts as 1,143
     * smaller builds instead of removing them.
     */
    @Test
    fun `one shared index answers every suffix of its scan`() {
        val index = SuffixNameIndex(arrayOf("a", "b", "c", "b"))
        FlowScan.reset()
        val views = (0..4).map { SuffixNameSet(index, it) }
        for (view in views) view.contains("b")
        assert(FlowScan.indexesBuilt == 1L)
        assert(FlowScan.indexEntries == 4L)
        assert(FlowScan.setsMaterialized == 0L)
        assert(views.map { it.contains("b") } == listOf(true, true, true, true, false))
        FlowScan.reset()
    }

    /** An index nobody questions is never built - the laziness the view exists for. */
    @Test
    fun `an unquestioned index is never built`() {
        FlowScan.reset()
        val view = SuffixNameSet(arrayOf("a", "b"), 0)
        assert(!view.isEmpty())
        assert(FlowScan.indexesBuilt == 0L)
        assert(FlowScan.setsMaterialized == 0L)
        FlowScan.reset()
    }

    /**
     * THE SHARING PIN, and it has to be a COUNTER one. Making every suffix build
     * its own index (the production call site back on `SuffixNameSet(scan.names,
     * lo)`) changes no answer whatsoever — it only does the work N times — so
     * every membership pin above stays green against it and the ablation's A3 arm
     * reads as a clean sweep. That is round 897's A1 verbatim: only the
     * container's IDENTITY can see it, and here the counters are that identity.
     *
     * Both bounds hold BY CONSTRUCTION when the index belongs to the scan — a
     * scan object contributes its names to `scanNames` once, at creation, and to
     * `indexEntries` at most once, on first question — and both are breached as
     * soon as one scan's suffixes start minting an index each.
     */
    @Test
    fun `one index per scan and not one per suffix`() {
        val source = """
            function make(n: number | undefined, m: number | undefined): unknown {
                n ??= 1;
                const a = { get: () => n };
                const b = { get: () => n };
                m ??= 2;
                const c = { get: () => m, also: () => n };
                const d = { get: () => m };
                n %= 3;
                m %= 4;
                return [a, b, c, d];
            }
        """
        FlowScan.reset()
        diagnose(source)
        val created = FlowScan.setsCreated
        val indexesBuilt = FlowScan.indexesBuilt
        val indexEntries = FlowScan.indexEntries
        val scansBuilt = FlowScan.scansBuilt
        val scanNames = FlowScan.scanNames
        FlowScan.reset()
        // round 753: an assertion over a population of zero tests nothing.
        assert(created > 1)
        assert(indexesBuilt > 0)
        assert(indexesBuilt <= scansBuilt)
        assert(indexEntries <= scanNames)
    }

    /**
     * THE PIN FOR THE ROUND'S ACTUAL DEFECT. Driven through a real compile with
     * the `--frontEnd` probe OFF (its default), so it asserts what a PRODUCTION
     * run does: closure suffix sets are created and **none** is ever
     * materialised. It fails the moment any consumer — a probe argument above
     * all — asks one of them its size or iterates it.
     *
     * `setsCreated > 0` is the positive control (round 753: an assertion over a
     * population of zero is vacuous), and it is why the fixture reassigns each
     * captured name AFTER the closure that captures it.
     */
    @Test
    fun `a production compile materializes no suffix set`() {
        val source = """
            function make(n: number | undefined, m: number | undefined): unknown {
                n ??= 1;
                const a = { get: () => n };
                n %= 2;
                m ??= 1;
                const b = { get: () => m, other: () => n };
                m %= 2;
                return [a, b];
            }
        """
        FlowScan.reset()
        diagnose(source)
        val created = FlowScan.setsCreated
        val materialized = FlowScan.setsMaterialized
        val inserted = FlowScan.setEntries
        FlowScan.reset()
        assert(created > 0)
        assert(materialized == 0L)
        assert(inserted == 0L)
    }
}
