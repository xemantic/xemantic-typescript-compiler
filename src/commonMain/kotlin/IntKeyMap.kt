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
 * M0.3(vi): a minimal open-addressing `Int → V` map for id-keyed checker
 * caches ([Symbol.id] / [Type.id] / flow-node ids), replacing `HashMap<Int, V>`
 * whose every probe boxes the key (ids run far past the JVM Integer cache) and
 * allocates a map node per store. Linear probing over a power-of-two table, no
 * removal (the converted caches only grow), grows at 50% load.
 *
 * INVARIANT: key [EMPTY] (= [Int.MIN_VALUE]) is the empty-slot sentinel. Every
 * id space that keys instances of this map stays clear of it by construction:
 * main-space symbol/type ids count up from 1, scope-space symbol ids count
 * DOWN from −2 (see the INV.2(c) note in Types.kt), and flow-node ids are
 * small per-graph counters — none can reach `Int.MIN_VALUE`. [set] enforces
 * this loudly.
 *
 * Not thread-safe — confine instances per worker like every other checker
 * cache (Tier 2, docs/parallel-caching.md).
 */
internal class IntKeyMap<V : Any>(initialCapacity: Int = 1024) {
    private var capacity = highestOneBit(maxOf(initialCapacity, 16))
    private var keys = emptyKeys(capacity)
    private var values = arrayOfNulls<Any>(capacity)
    private var size = 0

    val entryCount: Int get() = size

    private companion object {
        const val EMPTY = Int.MIN_VALUE

        fun emptyKeys(capacity: Int): IntArray =
            IntArray(capacity).also { it.fill(EMPTY) }

        fun highestOneBit(v: Int): Int {
            var c = 16
            while (c < v) c = c shl 1
            return c
        }

        /** Finalizing mix so sequential ids spread across buckets. */
        fun bucket(key: Int, mask: Int): Int {
            var h = key * -0x61c88647 // 0x9E3779B9 (golden-ratio mix)
            h = h xor (h ushr 16)
            return h and mask
        }
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Int): V? {
        val mask = capacity - 1
        var i = bucket(key, mask)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i] as V?
            if (k == EMPTY) return null
            i = (i + 1) and mask
        }
    }

    operator fun set(key: Int, value: V) {
        require(key != EMPTY) { "IntKeyMap: Int.MIN_VALUE is the empty-slot sentinel — no id space reaches it" }
        if (size * 2 >= capacity) grow()
        val mask = capacity - 1
        var i = bucket(key, mask)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) {
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
        keys = emptyKeys(capacity)
        values = arrayOfNulls(capacity)
        val mask = capacity - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == EMPTY) continue
            var i = bucket(k, mask)
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = k
            values[i] = oldValues[j]
        }
    }
}

/**
 * M0.3(vi): the narrowing walk's per-invocation flow-node memo, specialized
 * from `MutableMap<Int, Pair<Int, Type>>` — one instance is minted per depth-0
 * [flow walk][com.xemantic.typescript.compiler] entry (~111k per self-compile),
 * so the boxed key + `Pair` allocation per store and the LinkedHashMap node
 * churn were pure overhead on the hottest checker path (narrowWalks ≈ 5 s of
 * the round-618 profile). Parallel arrays: open-addressed int keys (flow-node
 * ids — small per-graph counters, never [Int.MIN_VALUE]), int depths, Type
 * values.
 *
 * Semantics preserved byte-exactly from the map form (see the round-385/413
 * memo gotcha — unconditional serving over-narrows past the depth truncation):
 *  - [served]: a stored entry answers a probe iff `depth <= storedDepth`;
 *  - [putIfDeeper]: insert, or overwrite only when `storedDepth < depth`
 *    (the old `prev == null || prev.first < depth` rule).
 */
internal class NarrowFlowMemo(initialCapacity: Int = 32) {
    private var capacity = highestOneBit(maxOf(initialCapacity, 16))
    private var keys = emptyKeys(capacity)
    private var depths = IntArray(capacity)
    private var types = arrayOfNulls<Type>(capacity)
    private var size = 0

    private companion object {
        const val EMPTY = Int.MIN_VALUE

        fun emptyKeys(capacity: Int): IntArray =
            IntArray(capacity).also { it.fill(EMPTY) }

        fun highestOneBit(v: Int): Int {
            var c = 16
            while (c < v) c = c shl 1
            return c
        }

        fun bucket(key: Int, mask: Int): Int {
            var h = key * -0x61c88647
            h = h xor (h ushr 16)
            return h and mask
        }
    }

    /** The stored type iff an entry exists for [id] with `depth <= storedDepth`. */
    fun served(id: Int, depth: Int): Type? {
        val mask = capacity - 1
        var i = bucket(id, mask)
        while (true) {
            val k = keys[i]
            if (k == id) return if (depth <= depths[i]) types[i] else null
            if (k == EMPTY) return null
            i = (i + 1) and mask
        }
    }

    /** Insert, or overwrite only when [depth] exceeds the stored depth. */
    fun putIfDeeper(id: Int, depth: Int, type: Type) {
        if (size * 2 >= capacity) grow()
        val mask = capacity - 1
        var i = bucket(id, mask)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) {
                keys[i] = id
                depths[i] = depth
                types[i] = type
                size++
                return
            }
            if (k == id) {
                if (depths[i] < depth) {
                    depths[i] = depth
                    types[i] = type
                }
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldDepths = depths
        val oldTypes = types
        capacity = capacity shl 1
        keys = emptyKeys(capacity)
        depths = IntArray(capacity)
        types = arrayOfNulls(capacity)
        val mask = capacity - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == EMPTY) continue
            var i = bucket(k, mask)
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = k
            depths[i] = oldDepths[j]
            types[i] = oldTypes[j]
        }
    }
}

/**
 * Perf (round 433): the flow walkers' cycle-detection set with an ADD-LOG, so a
 * FlowBranchLabel can walk each antecedent against the path-so-far membership and
 * restore it afterwards ([mark]/[popToMark]) instead of COPYING the whole set per
 * antecedent (the copies were ~11% of the tsc-source self-compile: `seen` holds
 * thousands of ids on a deep walk × one full copy per branch antecedent). Only
 * genuinely ADDED ids are logged, so a pop never removes a pre-existing id —
 * after popToMark the membership is exactly the pre-branch state, which is what
 * the per-antecedent fresh copy provided. Linear recursion shares the instance
 * unmarked (additions persist upward), matching the old shared-set behavior.
 * Each top-level walk constructs its own instance (re-entrant walks via callee
 * resolution get independent logs).
 */
internal class NarrowSeen {
    // M0.3(vii): int-specialized (was HashSet<Int> + ArrayList<Int> — every
    // add() boxed the id TWICE, once per structure, on every flow-node visit).
    // Open addressing with tombstone removal: popToMark removes in reverse
    // insertion order, which linear probing cannot honor by slot-shifting, so
    // a removed slot becomes DELETED (probes walk past it; inserts reuse it).
    // EMPTY slots are only ever created by [rehash] (which rebuilds all live
    // chains), so a probe for a PRESENT id never meets EMPTY early.
    private var capacity = 64
    private var slots = IntArray(capacity) { EMPTY }
    private var used = 0 // occupied incl. tombstones — the probe-length driver
    private var live = 0
    private var log = IntArray(64)
    private var logSize = 0

    private companion object {
        const val EMPTY = Int.MIN_VALUE
        const val DELETED = Int.MIN_VALUE + 1
        fun bucket(id: Int, mask: Int): Int {
            var h = id * -0x61c88647 // 0x9E3779B9 (golden-ratio mix)
            h = h xor (h ushr 16)
            return h and mask
        }
    }

    fun add(id: Int): Boolean {
        val mask = capacity - 1
        var i = bucket(id, mask)
        var free = -1
        while (true) {
            val s = slots[i]
            if (s == id) return false
            if (s == EMPTY) break
            if (s == DELETED && free < 0) free = i
            i = (i + 1) and mask
        }
        if (free >= 0) {
            slots[free] = id // reuse a tombstone: `used` already counts it
        } else {
            slots[i] = id
            used++
        }
        live++
        if (logSize == log.size) log = log.copyOf(log.size shl 1)
        log[logSize++] = id
        if (used * 2 >= capacity) rehash()
        return true
    }

    fun mark(): Int = logSize

    fun popToMark(mark: Int) {
        while (logSize > mark) {
            val id = log[--logSize]
            val mask = capacity - 1
            var i = bucket(id, mask)
            while (slots[i] != id) i = (i + 1) and mask // logged ⇒ present
            slots[i] = DELETED
            live--
        }
    }

    private fun rehash() {
        val old = slots
        // Grow only when genuinely loaded with LIVE entries; a tombstone-heavy
        // table purges at the same size instead.
        if (live * 4 >= capacity) capacity = capacity shl 1
        slots = IntArray(capacity) { EMPTY }
        val mask = capacity - 1
        for (s in old) {
            if (s == EMPTY || s == DELETED) continue
            var i = bucket(s, mask)
            while (slots[i] != EMPTY) i = (i + 1) and mask
            slots[i] = s
        }
        used = live
    }
}
