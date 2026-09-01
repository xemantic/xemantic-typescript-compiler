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

/**
 * (INV.0) The relater's CACHE seam — [Ternary] and the per-relation [Relation]
 * table, extracted from `Checker.kt` as Stage 0's second collaborator
 * (`docs/inversion-ambient-ledger.md` row 2). A pure RELOCATION: the four
 * relation instances stay in `CheckerState`, every call site is unchanged, and
 * the class's ambient surface is empty (its probes reach the process-wide
 * [MapCensus]/[PassTiming] instruments, which are measurement machinery, not
 * checker state). This file is where the relater's algorithm lands when its
 * own extraction round comes.
 */

/** Ternary logic for type relations: True, False, or Maybe (for recursive checks). */
internal enum class Ternary { True, False, Maybe }

/** Relation cache — maps (sourceId, targetId) packed into Long → result. */
internal class Relation {
    private val cache = HashMap<Long, Ternary>()

    /**
     * (WARM.31) round 904 — arm B of the boxed-key amplifier, populated in
     * LOCKSTEP with [cache] and read only under `--boxedKeyAmp`. It is the
     * primitive-keyed successor this whole candidate is about, and putting it
     * HERE rather than in a synthetic loop is what keeps the measurement
     * honest: the same key stream, the same table occupancy, the same cache
     * residency (round 897 — a leaf profile cannot see a working-set effect,
     * so a bench-rig key sequence would price a different machine state).
     *
     * `LongKeyMap` reserves key 0 as its empty-slot sentinel, so a key of
     * exactly 0 is skipped and COUNTED rather than assumed impossible.
     */
    private val shadow = LongKeyMap<Ternary>(1024)

    fun get(sourceId: Int, targetId: Int): Ternary? {
        val k = packKey(sourceId, targetId)
        if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_REL_CACHE, k)
        if (MapCensus.boxedKeyAmp > 0) amp(k)
        return cache[k]
    }

    /**
     * Two arms under one timestamp pair each, order alternating per call so a
     * drift inside the rebuild lands on both. Neither arm's key is computed
     * inside its bracket, so what is measured is the PROBE and nothing else.
     *
     * Two falsifiers ride along. (1) Each sink is 0 or `r` per call, hence an
     * exact multiple of `r` — and the two sinks must be EQUAL, which is the
     * lockstep control: they diverge the moment the shadow stops mirroring.
     * (2) A `HashMap` probe on an unchanging key is loop-invariant-looking, so
     * C2 may hoist it; the falsifier for that is the SLOPE between two `r`,
     * never the sink (round 903).
     */
    private fun amp(k: Long) {
        val r = MapCensus.boxedKeyAmp
        val boxedFirst = (MapCensus.bkAmpCalls and 1L) == 0L
        if (boxedFirst) { ampBoxed(k, r); ampPrimitive(k, r) }
        else { ampPrimitive(k, r); ampBoxed(k, r) }
        MapCensus.bkAmpCalls++
    }

    private fun ampBoxed(k: Long, r: Int) {
        val t0 = PassTiming.nowNanos()
        var s = 0L
        var i = 0
        while (i < r) { if (cache[k] != null) s++; i++ }
        MapCensus.bkAmpBoxedNanos += PassTiming.nowNanos() - t0
        MapCensus.bkAmpBoxedSink += s
    }

    private fun ampPrimitive(k: Long, r: Int) {
        val t0 = PassTiming.nowNanos()
        var s = 0L
        var i = 0
        while (i < r) { if (shadow.get(k) != null) s++; i++ }
        MapCensus.bkAmpPrimNanos += PassTiming.nowNanos() - t0
        MapCensus.bkAmpPrimSink += s
    }

    fun set(sourceId: Int, targetId: Int, result: Ternary) {
        val k = packKey(sourceId, targetId)
        if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_REL_CACHE, k)
        cache[k] = result
        if (MapCensus.boxedKeyAmp > 0) {
            if (k != 0L) shadow.put(k, result) else MapCensus.bkAmpSentinelKeys++
        }
    }

    /** (HASH.1)(b) round 890: see [packIdPair] — this map's 43,080 real keys
     *  collapsed onto 18,201 hashes, 1,140 of them in a single bucket. */
    private fun packKey(a: Int, b: Int): Long = packIdPair(a, b)
}
