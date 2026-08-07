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
 * M0.3(iii): a minimal open-addressing `Long → V` map for EXACT packed-id keys
 * (two 32-bit ids packed into one Long — a bijection, never a hash of the ids),
 * replacing string-keyed intern caches whose `buildString`/`joinToString` key
 * construction showed in the round-618 JFR. Linear probing over a power-of-two
 * table, no removal (intern caches only grow), grows at 50% load.
 *
 * INVARIANT: key `0L` is the empty-slot sentinel. Every packer used with this
 * map must place an id `>= 1` in the HIGH 32 bits (Type/Symbol ids start at 1
 * and stay positive — see the INV.6 thread-local id-sequence gotcha), which
 * makes every real key `>= 2^32 != 0`. [put] enforces this loudly; [get] with
 * `0L` naturally returns null (matches an empty slot's null value).
 *
 * Not thread-safe — confine instances per worker like every other checker
 * cache (Tier 2, docs/parallel-caching.md).
 */
internal class LongKeyMap<V : Any>(initialCapacity: Int = 1024) {
    private var capacity = highestOneBit(maxOf(initialCapacity, 16))
    private var keys = LongArray(capacity)
    private var values = arrayOfNulls<Any>(capacity)
    private var size = 0

    val entryCount: Int get() = size

    private fun highestOneBit(v: Int): Int {
        var c = 16
        while (c < v) c = c shl 1
        return c
    }

    /** Finalizing mix so sequential packed ids spread across buckets. */
    private fun bucket(key: Long, mask: Int): Int {
        var h = key * -0x61c8864680b583ebL // 0x9E3779B97F4A7C15 (golden-ratio mix)
        h = h xor (h ushr 32)
        return (h.toInt()) and mask
    }

    @Suppress("UNCHECKED_CAST")
    fun get(key: Long): V? {
        val mask = capacity - 1
        var i = bucket(key, mask)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i] as V?
            if (k == 0L) return null
            i = (i + 1) and mask
        }
    }

    fun put(key: Long, value: V) {
        require(key != 0L) { "LongKeyMap: key 0 is the empty-slot sentinel — packers must keep an id >= 1 in the high 32 bits" }
        if (size * 2 >= capacity) grow()
        val mask = capacity - 1
        var i = bucket(key, mask)
        while (true) {
            val k = keys[i]
            if (k == 0L) {
                keys[i] = key
                values[i] = value
                size++
                return
            }
            if (k == key) {
                values[i] = value
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        capacity = capacity shl 1
        keys = LongArray(capacity)
        values = arrayOfNulls(capacity)
        val mask = capacity - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == 0L) continue
            var i = bucket(k, mask)
            while (keys[i] != 0L) i = (i + 1) and mask
            keys[i] = k
            values[i] = oldValues[j]
        }
    }
}
