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
        assert(m[1] == null)
        m[2] = "a"
        assert(m[3] == null)
        assert(m[-2] == null)
    }

    @Test
    fun `put then get round-trips and re-put overwrites`() {
        val m = IntKeyMap<String>()
        m[7] = "first"
        assert(m[7] == "first")
        m[7] = "second"
        assert(m[7] == "second")
        assert(m.entryCount == 1)
    }

    @Test
    fun `negative keys are first-class - the scope-symbol id space`() {
        val m = IntKeyMap<String>()
        m[-2] = "scope"
        m[2] = "main"
        m[0] = "zero"
        assert(m[-2] == "scope")
        assert(m[2] == "main")
        assert(m[0] == "zero")
        assert(m[-3] == null)
    }

    @Test
    fun `sentinel key is rejected loudly`() {
        val m = IntKeyMap<String>()
        assertFailsWith<IllegalArgumentException> { m[Int.MIN_VALUE] = "x" }
        // get harmlessly returns null (matches an empty slot).
        assert(m[Int.MIN_VALUE] == null)
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
        assert(m.entryCount == oracle.size)
        for ((k, expected) in oracle) {
            val got = m[k]
            assert(got == expected)
        }
        assert(m[5001] == null)
        assert(m[-5003] == null)
    }

    private val t1: Type = Type.Intrinsic(TypeFlags.Any, "memo-t1")
    private val t2: Type = Type.Intrinsic(TypeFlags.Any, "memo-t2")

    @Test
    fun `NarrowFlowMemo serves only at same-or-shallower probe depth`() {
        val memo = NarrowFlowMemo()
        // A zero-budget maxDepth switches the round-736 height disjunct OFF, so
        // this pins the original depth rule on its own.
        memo.putIfDeeper(id = 5, depth = 3, hi = 3, type = t1)
        // depth <= storedDepth → served
        assert(memo.served(5, 3, 0) === t1)
        assert(memo.served(5, 2, 0) === t1)
        assert(memo.served(5, 0, 0) === t1)
        // depth > storedDepth → NOT served (the over-narrowing guard)
        assert(memo.served(5, 4, 0) == null)
        // absent id → null regardless of depth
        assert(memo.served(6, 0, 0) == null)
    }

    @Test
    fun `NarrowFlowMemo putIfDeeper overwrites only at strictly deeper depth`() {
        val memo = NarrowFlowMemo()
        memo.putIfDeeper(9, 5, 5, t1)
        // shallower and equal depths do NOT overwrite (prev.first < depth rule)
        memo.putIfDeeper(9, 4, 4, t2)
        assert(memo.served(9, 5, 0) === t1)
        memo.putIfDeeper(9, 5, 5, t2)
        assert(memo.served(9, 5, 0) === t1)
        // strictly deeper overwrites value AND raises the serve ceiling
        memo.putIfDeeper(9, 6, 6, t2)
        assert(memo.served(9, 6, 0) === t2)
        assert(memo.served(9, 5, 0) === t2)
    }

    @Test
    fun `NarrowFlowMemo survives growth with many flow-node ids`() {
        val memo = NarrowFlowMemo(16)
        for (id in 0 until 4000) {
            memo.putIfDeeper(id, id % 7, id % 7, if (id % 2 == 0) t1 else t2)
        }
        for (id in 0 until 4000) {
            val expected = if (id % 2 == 0) t1 else t2
            assert(memo.served(id, id % 7, 0) === expected)
            assert(memo.served(id, id % 7 + 1, 0) == null)
        }
        assert(memo.served(4000, 0, 0) == null)
    }

    // -- (CALL.3) round 736: the HEIGHT disjunct -----------------------------

    @Test
    fun `NarrowFlowMemo serves a deeper probe when the stored height still fits`() {
        val memo = NarrowFlowMemo()
        // stored at depth 3, subtree reached depth 10 → height 7
        memo.putIfDeeper(id = 5, depth = 3, hi = 10, type = t1)
        // a DEEPER probe at 4: a fresh walk would reach 4 + 7 = 11 < 2000, so
        // it provably cannot hit the depth cap and provably reproduces t1
        assert(memo.served(5, 4, 2000) === t1)
        assert(memo.served(5, 500, 2000) === t1)
        // the served entry reports the height the caller must fold upward
        assert(memo.lastHitHeight == 7)
    }

    @Test
    fun `NarrowFlowMemo refuses a deeper probe whose recomputation would hit the cap`() {
        val memo = NarrowFlowMemo()
        memo.putIfDeeper(id = 5, depth = 3, hi = 10, type = t1)
        // maxDepth 20, height 7: depth 12 would reach 19 (< 20) → served
        assert(memo.served(5, 12, 20) === t1)
        // depth 13 would reach exactly 20 = the cap → truncation possible → refuse
        assert(memo.served(5, 13, 20) == null)
        assert(memo.served(5, 19, 20) == null)
        // the shallow disjunct is unaffected by maxDepth
        assert(memo.served(5, 3, 20) === t1)
    }

    @Test
    fun `NarrowFlowMemo height travels with the depth it was stored at`() {
        val memo = NarrowFlowMemo()
        memo.putIfDeeper(7, 2, 4, t1)     // height 2
        memo.putIfDeeper(7, 6, 40, t2)    // deeper → overwrites BOTH, height 34
        assert(memo.served(7, 6, 2000) === t2)
        assert(memo.lastHitHeight == 34)
        // and a non-overwriting shallower put leaves the pair intact
        memo.putIfDeeper(7, 1, 1, t1)
        assert(memo.served(7, 7, 2000) === t2)
        assert(memo.lastHitHeight == 34)
    }

    @Test
    fun `negative control - a zero-height entry is still refused past the cap`() {
        val memo = NarrowFlowMemo()
        memo.putIfDeeper(3, 0, 0, t1)
        assert(memo.served(3, 9, 10) === t1)
        assert(memo.served(3, 10, 10) == null)
        assert(memo.served(3, 11, 10) == null)
    }

    @Test
    fun `NarrowSeen add dedups and popToMark restores exact membership`() {
        val seen = NarrowSeen()
        assert(seen.add(1))
        assert(seen.add(2))
        assert(!seen.add(1))
        val m = seen.mark()
        assert(seen.add(3))
        assert(seen.add(4))
        assert(!seen.add(2))
        seen.popToMark(m)
        // 3 and 4 removed; 1 and 2 kept
        assert(seen.add(3))
        seen.popToMark(m)
        assert(!seen.add(1))
        assert(!seen.add(2))
        assert(seen.add(4))
    }

    @Test
    fun `NarrowSeen randomized oracle across growth - tombstone churn and nested marks`() {
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
                    assert(actual == expected)
                }
                7 -> marks.addLast(seen.mark().also { assert(it == log.size) })
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
            assert(actual != member)
        }
    }
}
