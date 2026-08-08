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
 * (WARM.1)(c) round 846 — the probe's TIERS.
 *
 * `--passTiming` costs ~50% of a WARM rebuild, and essentially none of that is
 * the ~513 `pass()` boundaries: it is the per-CALL bookkeeping. The tiers
 * ([PassTiming.detail] / [PassTiming.spineDetail]) drop that bookkeeping while
 * KEEPING the pass rows, which is what makes a warm per-pass table's absolutes
 * usable at all.
 *
 * These pin the three properties the tiers live or die by, each written to FAIL
 * if the tier gates were inert:
 *
 *  1. **Behaviour-free** — every tier answers the IDENTICAL diagnostics, so a
 *     cheaper tier is never a different compile (the same contract INV.0 pins
 *     for on-vs-off).
 *  2. **Equivalent on the rows it keeps** — the `rows` tier records the SAME
 *     pass names, with the same call counts, as `full`, and a non-zero
 *     checker-init total. A tier that quietly stopped dispatching passes, or
 *     recorded a different set, would be a different table.
 *  3. **It actually drops the work** — at the `rows` tier every per-call
 *     counter and every SPINE sub-row is ZERO. This is the direction that
 *     fails if the gate swap never happened: with the old `PassTiming.enabled`
 *     gates in place these counters are all non-zero at every tier.
 *
 * Any counter reading 0 under a reduced tier is an ABSENT measurement, never a
 * measured zero — [PassTiming.dump] prints that warning in its own header, and
 * the last test here pins that it does.
 */
class PassTimingTierTest {

    /** Exercises expression typing, flow narrowing, annotation resolution and a
     *  genuine TS2322 — the same shape Inv0PassTimingTest uses, so the parity
     *  assertion below is non-vacuous. */
    private val probeSource = """
        interface Box { value: number | undefined; }
        function pick(b: Box): number {
            if (b.value !== undefined) {
                return b.value;
            }
            return 0;
        }
        const wrong: string = 42;
        export const keep = pick({ value: 1 });
    """

    /** Runs [probeSource] at the given tier and returns its diagnostics, leaving
     *  the counters in place for inspection. Modes are restored by the callers'
     *  `finally`; this helper never leaves [PassTiming.enabled] set. */
    private fun compileAtTier(detail: Boolean, spineDetail: Boolean): List<Diagnostic> {
        PassTiming.enabled = false
        PassTiming.reset()
        PassTiming.detail = detail
        PassTiming.spineDetail = spineDetail
        PassTiming.enabled = true
        return try {
            diagnose(probeSource)
        } finally {
            PassTiming.enabled = false
        }
    }

    private fun restore() {
        PassTiming.enabled = false
        PassTiming.detail = true
        PassTiming.spineDetail = true
        PassTiming.reset()
    }

    /** The per-call counters the `rows`/`spine` tiers must not collect. */
    private fun detailCounterSum(): Long =
        PassTiming.getTypeOfExpressionCalls +
            PassTiming.getTypeOfExpressionDistinct.size.toLong() +
            PassTiming.typeNodeCacheable + PassTiming.typeNodeBypassed +
            PassTiming.typeNodeCacheHits +
            PassTiming.symbolTypeCached + PassTiming.symbolTypeContextBypassed +
            PassTiming.narrowWalks + PassTiming.walkMiss + PassTiming.walkMemoServed +
            PassTiming.relationNanos + PassTiming.typeNodeNanos +
            PassTiming.memberResolveNanos + PassTiming.typeOfExprNanos +
            PassTiming.narrowWalkNanos +
            PassTiming.globalsLookups +
            PassTiming.nodeKindHistogram.values.sum() +
            PassTiming.epochNoops.values.sum() + PassTiming.epochBumps.values.sum()

    /** The per-node SPINE sub-rows the `rows` tier must not collect. */
    private fun spineCounterSum(): Long =
        PassTiming.spineNodes + PassTiming.spineEnterNanos + PassTiming.spineLeaveNanos +
            PassTiming.spineScopeNanos + PassTiming.spineUResNanos +
            PassTiming.spineChildrenNanos +
            PassTiming.spineKindCount.values.sum() + PassTiming.spineKindNanos.values.sum()

    @Test
    fun `every tier compiles the identical program`() {
        try {
            PassTiming.enabled = false
            PassTiming.reset()
            val off = diagnose(probeSource)
            val rows = compileAtTier(detail = false, spineDetail = false)
            val spine = compileAtTier(detail = false, spineDetail = true)
            val full = compileAtTier(detail = true, spineDetail = true)
            assert(rows == off)
            assert(spine == off)
            assert(full == off)
            // Non-vacuous: the shape really does emit.
            assert(off.any { it.code == 2322 })
        } finally {
            restore()
        }
    }

    @Test
    fun `the rows tier keeps every pass row that the full tier records`() {
        try {
            compileAtTier(detail = true, spineDetail = true)
            val fullNames = PassTiming.passNanos.keys.toList()
            val fullCalls = HashMap(PassTiming.passCalls)
            assert(fullNames.size > 100)

            compileAtTier(detail = false, spineDetail = false)
            val rowNames = PassTiming.passNanos.keys.toList()
            val rowCalls = HashMap(PassTiming.passCalls)
            // Same passes, in the same dispatch order, with the same invocation
            // counts — the rows tier changes what is MEASURED, never what runs.
            assert(rowNames == fullNames)
            assert(rowCalls == fullCalls)
            assert(PassTiming.checkerInitNanos > 0)
            assert(PassTiming.passNanos.values.sum() > 0)
        } finally {
            restore()
        }
    }

    @Test
    fun `the rows tier collects NO per-call and NO spine bookkeeping`() {
        try {
            // Positive control FIRST: at the full tier all of it is non-zero, so
            // the zeros below cannot be an artefact of the probe source.
            compileAtTier(detail = true, spineDetail = true)
            val fullDetail = detailCounterSum()
            val fullSpine = spineCounterSum()
            assert(fullDetail > 0)
            assert(fullSpine > 0)

            compileAtTier(detail = false, spineDetail = false)
            assert(detailCounterSum() == 0L)
            assert(spineCounterSum() == 0L)
            // …while the rows themselves ARE measured.
            assert(PassTiming.passNanos.isNotEmpty())
        } finally {
            restore()
        }
    }

    @Test
    fun `the spine tier keeps the spine sub-rows and still drops the per-call counters`() {
        try {
            compileAtTier(detail = false, spineDetail = true)
            assert(spineCounterSum() > 0)
            assert(PassTiming.spineNodes > 0)
            assert(detailCounterSum() == 0L)
            assert(PassTiming.passNanos.isNotEmpty())
        } finally {
            restore()
        }
    }

    @Test
    fun `the tier flags are MODES - reset must not clear them`() {
        try {
            PassTiming.detail = false
            PassTiming.spineDetail = false
            PassTiming.reset()
            assert(!PassTiming.detail)
            assert(!PassTiming.spineDetail)
        } finally {
            restore()
        }
    }

    @Test
    fun `detailed and spineProfiled are the conjunction with enabled`() {
        try {
            PassTiming.enabled = false
            PassTiming.detail = true
            PassTiming.spineDetail = true
            assert(!PassTiming.detailed)
            assert(!PassTiming.spineProfiled)
            PassTiming.enabled = true
            assert(PassTiming.detailed)
            assert(PassTiming.spineProfiled)
            PassTiming.detail = false
            assert(!PassTiming.detailed)
            assert(PassTiming.spineProfiled)
            PassTiming.spineDetail = false
            assert(!PassTiming.spineProfiled)
            assert(PassTiming.tierName() == "rows")
        } finally {
            restore()
        }
    }

    @Test
    fun `the dump names its tier and warns that a dropped counter is not a measured zero`() {
        try {
            compileAtTier(detail = false, spineDetail = false)
            val rowsDump = StringBuilder()
            PassTiming.dump { rowsDump.appendLine(it) }
            val rowsText = rowsDump.toString()
            assert("tier: rows" in rowsText)
            assert("NOT COLLECTED" in rowsText)
            assert("ABSENT measurement" in rowsText)

            compileAtTier(detail = true, spineDetail = true)
            val fullDump = StringBuilder()
            PassTiming.dump { fullDump.appendLine(it) }
            val fullText = fullDump.toString()
            assert("tier: full" in fullText)
            assert("NOT COLLECTED" !in fullText)
        } finally {
            restore()
        }
    }
}
