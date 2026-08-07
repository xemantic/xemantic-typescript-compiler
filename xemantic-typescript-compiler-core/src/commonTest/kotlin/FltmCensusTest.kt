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
 * (SETUP.2), round 829 — the produced-vs-consumed census of
 * `buildFileLocalTypeMaps`, the 636 ms setup pass round 802 named.
 *
 * The census answered the item and CLOSED it: on the compiler profile the pass
 * performs **12,738** direct `getTypeOfSymbol` calls to produce **4,161** map
 * entries of which only **1,499** are ever read — 8.5 resolutions per entry any
 * reader wants — while the read site itself serves `calls=16,043` against
 * `distinct=1,499` and MISSES 278,355 times. The deferral the shape invites
 * recovers, at most, the **47.1%** of those resolutions that nothing else asks
 * for either (round 788's law disposes of the rest), which is ~200–250 ms =
 * **under 1% of the compile** — below the cold A/B band, behind a program-wide
 * name-resolution blast radius. Full numbers:
 * `docs/perf/setup-phase-and-huge-methods.md` § 27.
 *
 * These pins protect the two things the instrument depends on, and the one the
 * NEXT agent would break: [FltmCensus] must be behaviour-free when it is on
 * (INV.0's rule for every probe in this codebase), and its hooks must actually
 * fire — a census whose counters read zero is indistinguishable from a
 * population that does not exist (CLAUDE.md, round 753).
 */
class FltmCensusTest {

    /** A file-level interface, type alias, enum, function and annotated const —
     *  one of each branch of the pass — plus reads of them, so the read site is
     *  exercised and the counters cannot be vacuously zero. */
    private val probeSource = """
        interface Box { value: number; }
        type Alias = Box | undefined;
        enum Kind { A, B }
        function take(b: Box): number { return b.value; }
        const annotated: Alias = { value: 1 };
        export const one = take({ value: 2 });
        export const two = take({ value: 3 });
        export const three: Kind = Kind.B;
    """

    private fun withCensus(body: () -> Unit) {
        FltmCensus.reset()
        FltmCensus.on = true
        try {
            body()
        } finally {
            FltmCensus.on = false
            FltmCensus.reset()
        }
    }

    @Test
    fun `the census is behaviour-free - identical diagnostics with it on and off`() {
        val off = diagnose(probeSource).map { "${it.code}@${it.start}:${it.message}" }
        var on: List<String> = emptyList()
        withCensus {
            on = diagnose(probeSource).map { "${it.code}@${it.start}:${it.message}" }
        }
        assert(on == off)
    }

    @Test
    fun `the hooks fire - a zero census would measure nothing`() {
        withCensus {
            diagnose(probeSource)
            // Each of the three counters comes from a different hook: the pass's
            // own call sites, its stores, and the single `fileLocalTypeMaps` read
            // in getTypeOfIdentifier. A zero in any of them means that hook was
            // detached, not that the population is empty.
            val resolves = FltmCensus.directResolves
            val stored = FltmCensus.storedEntries
            val reads = FltmCensus.readHits
            assert(resolves > 0)
            assert(stored > 0)
            assert(reads > 0)
        }
    }

    @Test
    fun `distinct never exceeds calls - and read keys are a subset of stored ones`() {
        withCensus {
            diagnose(probeSource)
            val calls = FltmCensus.readHits
            val distinct = FltmCensus.distinctReads.toLong()
            val stored = FltmCensus.storedEntries
            val neverRead = FltmCensus.neverReadEntries
            // Round 800's ratio is only meaningful while these hold: a served read
            // is by construction an entry that was stored, so `distinct` partitions
            // `stored` into read and never-read.
            assert(distinct <= calls)
            assert(distinct.toInt() + neverRead == stored)
        }
    }

    @Test
    fun `negative control - the census stays empty when it is off`() {
        FltmCensus.reset()
        diagnose(probeSource)
        val resolves = FltmCensus.directResolves
        val reads = FltmCensus.readHits
        assert(resolves == 0L)
        assert(reads == 0L)
    }
}
