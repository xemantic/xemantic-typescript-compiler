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

import com.xemantic.kotlin.test.have
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M0.3(vi): pins the [IntKeyMap] invariants the symbol-id caches rely on —
 * NEGATIVE keys are first-class (the INV.2(c) scope-symbol id space counts
 * DOWN from −2, so symbolTypes/declaredTypes keys span both signs), survival
 * across growth/rehash, last-write-wins on re-put, the loud sentinel-key
 * guard, and correctness under forced bucket collisions — plus the
 * [NarrowFlowMemo] serve/overwrite depth rules preserved byte-exactly from
 * the `MutableMap<Int, Pair<Int, Type>>` form (the round-385/413 memo
 * gotcha: serving past the stored entry depth would over-narrow past the
 * NARROW_MAX_DEPTH truncation).
 */
class IntKeyMapTest {

    @Test
    fun `get on empty and missing keys returns null`() {
        val m = IntKeyMap<String>()
        have(m[1] == null)
        m[2] = "a"
        have(m[3] == null)
        have(m[-2] == null)
    }

    @Test
    fun `put then get round-trips and re-put overwrites`() {
        val m = IntKeyMap<String>()
        m[7] = "first"
        assertEquals("first", m[7])
        m[7] = "second"
        assertEquals("second", m[7])
        assertEquals(1, m.entryCount)
    }

    @Test
    fun `negative keys are first-class - the scope-symbol id space`() {
        val m = IntKeyMap<String>()
        m[-2] = "scope"
        m[2] = "main"
        m[0] = "zero"
        assertEquals("scope", m[-2])
        assertEquals("main", m[2])
        assertEquals("zero", m[0])
        have(m[-3] == null)
    }

    @Test
    fun `sentinel key is rejected loudly`() {
        val m = IntKeyMap<String>()
        assertFailsWith<IllegalArgumentException> { m[Int.MIN_VALUE] = "x" }
        // get harmlessly returns null (matches an empty slot).
        have(m[Int.MIN_VALUE] == null)
    }

    @Test
    fun `10k mixed-sign keys against a HashMap oracle across growth`() {
        val m = IntKeyMap<Int>(16)
        val oracle = HashMap<Int, Int>()
        // Deterministic id-shaped keys: sequential positive runs (the main
        // symbol-id space) interleaved with a descending negative run (the
        // scope-symbol space) — the caches' actual shape.
        var v = 0
        for (i in 1..5000) {
            m[i] = v; oracle[i] = v; v++
            val neg = -2 - i
            m[neg] = v; oracle[neg] = v; v++
        }
        assertEquals(oracle.size, m.entryCount)
        for ((k, expected) in oracle) {
            val got = m[k]
            have(got == expected)
        }
        have(m[5001] == null)
        have(m[-5003] == null)
    }

    private val t1: Type = Type.Intrinsic(TypeFlags.Any, "memo-t1")
    private val t2: Type = Type.Intrinsic(TypeFlags.Any, "memo-t2")

    @Test
    fun `NarrowFlowMemo serves only at same-or-shallower probe depth`() {
        val memo = NarrowFlowMemo()
        memo.putIfDeeper(id = 5, depth = 3, type = t1)
        // depth <= storedDepth → served
        have(memo.served(5, 3) === t1)
        have(memo.served(5, 2) === t1)
        have(memo.served(5, 0) === t1)
        // depth > storedDepth → NOT served (the over-narrowing guard)
        have(memo.served(5, 4) == null)
        // absent id → null regardless of depth
        have(memo.served(6, 0) == null)
    }

    @Test
    fun `NarrowFlowMemo putIfDeeper overwrites only at strictly deeper depth`() {
        val memo = NarrowFlowMemo()
        memo.putIfDeeper(9, 5, t1)
        // shallower and equal depths do NOT overwrite (prev.first < depth rule)
        memo.putIfDeeper(9, 4, t2)
        have(memo.served(9, 5) === t1)
        memo.putIfDeeper(9, 5, t2)
        have(memo.served(9, 5) === t1)
        // strictly deeper overwrites value AND raises the serve ceiling
        memo.putIfDeeper(9, 6, t2)
        have(memo.served(9, 6) === t2)
        have(memo.served(9, 5) === t2)
    }

    @Test
    fun `NarrowFlowMemo survives growth with many flow-node ids`() {
        val memo = NarrowFlowMemo(16)
        for (id in 0 until 4000) {
            memo.putIfDeeper(id, id % 7, if (id % 2 == 0) t1 else t2)
        }
        for (id in 0 until 4000) {
            val expected = if (id % 2 == 0) t1 else t2
            have(memo.served(id, id % 7) === expected)
            have(memo.served(id, id % 7 + 1) == null)
        }
        have(memo.served(4000, 0) == null)
    }

    @Test
    fun `NarrowSeen add dedups and popToMark restores exact membership`() {
        val seen = NarrowSeen()
        have(seen.add(1))
        have(seen.add(2))
        have(!seen.add(1))
        val m = seen.mark()
        have(seen.add(3))
        have(seen.add(4))
        have(!seen.add(2))
        seen.popToMark(m)
        // 3 and 4 removed; 1 and 2 kept
        have(seen.add(3))
        seen.popToMark(m)
        have(!seen.add(1))
        have(!seen.add(2))
        have(seen.add(4))
    }

    @Test
    fun `NarrowSeen randomized oracle across growth, tombstone churn and nested marks`() {
        // Reference implementation = the pre-M0.3(vii) HashSet + ArrayList form.
        val seen = NarrowSeen()
        val ids = HashSet<Int>()
        val log = ArrayList<Int>()
        var state = 12345
        fun rnd(bound: Int): Int {
            state = state * 1103515245 + 12345
            return (state ushr 16) % bound
        }
        val marks = ArrayDeque<Int>()
        repeat(60_000) {
            when (rnd(10)) {
                in 0..6 -> {
                    val id = rnd(3000)
                    val expected = ids.add(id)
                    if (expected) log.add(id)
                    val actual = seen.add(id)
                    have(actual == expected)
                }
                7 -> marks.addLast(seen.mark().also { have(it == log.size) })
                else -> if (marks.isNotEmpty()) {
                    val m = marks.removeLast()
                    seen.popToMark(m)
                    while (log.size > m) ids.remove(log.removeAt(log.size - 1))
                }
            }
        }
        // Final membership must agree exactly: an oracle-member id must dedup,
        // a non-member must insert.
        for (id in 0 until 3000) {
            val member = id in ids
            val actual = seen.add(id)
            have(actual != member)
        }
    }
}
