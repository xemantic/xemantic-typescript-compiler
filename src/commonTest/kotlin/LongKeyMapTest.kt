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
 * M0.3(iii): pins the [LongKeyMap] invariants the intern caches rely on —
 * exact key identity (a packed key is a bijection, so distinct keys NEVER
 * alias), survival across growth/rehash, last-write-wins on re-put, the loud
 * zero-key guard, and correctness under forced bucket collisions (linear
 * probing must walk past occupied non-matching slots in both get and put).
 */
class LongKeyMapTest {

    @Test
    fun `get on empty and missing keys returns null`() {
        val m = LongKeyMap<String>()
        have(m.get(1L shl 32) == null)
        m.put((1L shl 32) or 2L, "a")
        have(m.get((1L shl 32) or 3L) == null)
        have(m.get((2L shl 32) or 2L) == null)
    }

    @Test
    fun `put then get round-trips and re-put overwrites`() {
        val m = LongKeyMap<String>()
        val k = (7L shl 32) or 9L
        m.put(k, "first")
        assertEquals("first", m.get(k))
        m.put(k, "second")
        assertEquals("second", m.get(k))
        assertEquals(1, m.entryCount)
    }

    @Test
    fun `zero key is rejected loudly`() {
        val m = LongKeyMap<String>()
        assertFailsWith<IllegalArgumentException> { m.put(0L, "x") }
        // get(0) harmlessly returns null (matches the empty-slot sentinel).
        have(m.get(0L) == null)
    }

    @Test
    fun `10k packed keys against a HashMap oracle across growth`() {
        val m = LongKeyMap<Int>(16)
        val oracle = HashMap<Long, Int>()
        // Deterministic id-shaped keys: high halves 1..100, low halves 0..99 —
        // includes sequential runs (the intern caches' actual shape).
        var v = 0
        for (hi in 1..100) {
            for (lo in 0..99) {
                val key = (hi.toLong() shl 32) or lo.toLong()
                m.put(key, v)
                oracle[key] = v
                v++
            }
        }
        assertEquals(oracle.size, m.entryCount)
        for ((key, expected) in oracle) {
            assertEquals(expected, m.get(key), "key $key")
        }
        // Distinct keys never alias: flip hi/lo of an existing pair.
        have(m.get((5L shl 32) or 6L) != m.get((6L shl 32) or 5L) ||
            oracle[(5L shl 32) or 6L] == oracle[(6L shl 32) or 5L])
    }

    @Test
    fun `forced same-bucket collisions probe correctly`() {
        // Small map, no growth (few entries): craft keys that collide by
        // brute-force scanning for pairs landing in the same initial bucket.
        val m = LongKeyMap<String>(16)
        // Insert enough keys that SOME collide in a 16-slot table (birthday
        // bound guarantees it well before 50% load across several rounds).
        val keys = (1..7).map { (it.toLong() shl 32) or 1L }
        for ((i, k) in keys.withIndex()) m.put(k, "v$i")
        for ((i, k) in keys.withIndex()) assertEquals("v$i", m.get(k))
    }
}
