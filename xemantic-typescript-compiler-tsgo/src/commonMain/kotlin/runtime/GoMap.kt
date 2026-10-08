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

package com.xemantic.typescript.tsgo.runtime

/**
 * A Go map `map[K]V`: an open-addressing hash table (linear probing, backward-shift deletion).
 *
 * Keys use Kotlin `equals`/`hashCode`: identity for generated struct and pointer classes, value
 * equality for `String` (byte strings), boxed numbers and value classes. A struct-VALUE key must be
 * wrapped by the lowering in a structural key (docs/goport-design.md § 3). A `nil` key (a nil
 * pointer or interface) is a key like any other.
 *
 * Why not `HashMap` (docs/goport-perf.md § 6): the checker's link stores are maps keyed by node and
 * symbol identity, read on almost every check step; a chained `HashMap` costs two dependent memory
 * reads per hit (bucket, then node) and one allocation per entry. Here a hit on an identity key is
 * one read of [slots] (key and value side by side) and a compare by reference; [hashes] is read only
 * when the reference differs (a value-equal key, or a collision), so `equals` runs only on a full
 * hash match.
 *
 * A nil map ([isNil]) answers every read with the zero value and panics on every write, as in Go.
 * A read of an absent key answers [elem]'s zero value.
 *
 * Iteration order is unspecified, as in Go (correct tsgo never depends on it). [range] tolerates
 * mutation during iteration with Go's guarantees: an entry deleted before it is reached is not
 * produced; an entry added during iteration may or may not be.
 */
class GoMap<K, V> private constructor(
    val elem: GoElem<V>,
    /** `false` for a nil map. */
    private val live: Boolean,
    hint: Int,
) {

    /** Key at `2i`, value at `2i + 1`; a `null` key slot is empty ([NULL_KEY] stands for a nil key). */
    private var slots: Array<Any?> = EMPTY

    /** The mixed hash of the key in slot `i`. */
    private var hashes: IntArray = EMPTY_HASHES

    /** Capacity − 1 (capacity a power of two), or −1 before the first insertion. */
    private var mask = -1

    /** `32 − log2(capacity)`: [index] keeps the hash's top bits. */
    private var shift = 32

    private var size = 0

    /** Bumped by every write ([set], [delete], [clear]): a [GoMapIter] reads its snapshot only while it is unchanged. */
    internal var modCount = 0

    init {
        if (hint > 0) allocate(capacityFor(hint))
    }

    /** `m == nil`. */
    val isNil: Boolean get() = !live

    /** `len(m)`. */
    val len: Int get() = size

    /** The slot index of [key] (its key half at `2 * result`), or −1. */
    private fun find(key: Any?): Int {
        if (size == 0) return -1
        val k = key ?: NULL_KEY
        val s = slots
        val h = mix(k.hashCode())
        var i = h ushr shift
        while (true) {
            val sk = s[i shl 1] ?: return -1
            if (sk === k || (hashes[i] == h && sk == k)) return i
            i = (i + 1) and mask
        }
    }

    /** `m[k]`: the value, or the zero value when absent. */
    @Suppress("UNCHECKED_CAST")
    operator fun get(key: K): V {
        val i = find(key)
        if (i < 0) return elem.zeroValue()
        val v = slots[(i shl 1) + 1] ?: return elem.zeroValue()
        return v as V
    }

    /** `v, ok := m[k]`. */
    @Suppress("UNCHECKED_CAST")
    fun lookup(key: K): Tuple2<V, Boolean> {
        val i = find(key)
        if (i < 0) return Tuple2(elem.zeroValue(), false)
        return Tuple2(slots[(i shl 1) + 1] as V, true)
    }

    /**
     * NOT Go API — `v, ok := m[k]` in ONE hash probe and no tuple: the stored value, or
     * [GoMapAbsent] when [key] is absent (the lowering reads it with [goProbeValue] and `!== GoMapAbsent`).
     */
    fun probe(key: K): Any? {
        val i = find(key)
        return if (i < 0) GoMapAbsent else slots[(i shl 1) + 1]
    }

    /** `_, ok := m[k]`. */
    fun contains(key: K): Boolean = find(key) >= 0

    /** `m[k] = v`; panics on a nil map. */
    operator fun set(key: K, value: V) {
        if (!live) goPanic(GoPlainError("assignment to entry in nil map"))
        modCount++
        val k = key ?: NULL_KEY
        if (mask < 0) allocate(MIN_CAPACITY)
        val h = mix(k.hashCode())
        var i = h ushr shift
        while (true) {
            val sk = slots[i shl 1]
            if (sk == null) break
            if (sk === k || (hashes[i] == h && sk == k)) {
                slots[(i shl 1) + 1] = value
                return
            }
            i = (i + 1) and mask
        }
        // A new key: grow first when past the load limit (1/2), then insert.
        if ((size + 1) * 2 > mask + 1) {
            rehash((mask + 1) * 2)
            i = h ushr shift
            while (slots[i shl 1] != null) i = (i + 1) and mask
        }
        slots[i shl 1] = k
        slots[(i shl 1) + 1] = value
        hashes[i] = h
        size++
    }

    /** `delete(m, k)`; a no-op on a nil map. */
    fun delete(key: K) {
        var i = find(key)
        if (i < 0) return
        modCount++
        // Backward-shift deletion: pull every later member of the probe run whose home is not in
        // (i, j] into the hole, so no lookup ever stops early.
        val s = slots
        var j = i
        while (true) {
            j = (j + 1) and mask
            val sk = s[j shl 1] ?: break
            val home = hashes[j] ushr shift
            val stays = if (i <= j) home in (i + 1)..j else home > i || home <= j
            if (stays) continue
            s[i shl 1] = sk
            s[(i shl 1) + 1] = s[(j shl 1) + 1]
            hashes[i] = hashes[j]
            i = j
        }
        s[i shl 1] = null
        s[(i shl 1) + 1] = null
        size--
    }

    /** `clear(m)`. */
    fun clear() {
        if (size == 0) return
        modCount++
        slots.fill(null)
        size = 0
    }

    /** `for k, v := range m`. Return `false` from [body] to stop (Go `break`). */
    inline fun range(body: (K, V) -> Boolean) {
        for (k in keysSnapshot()) {
            val r = probe(k)
            if (r === GoMapAbsent) continue // deleted during iteration
            @Suppress("UNCHECKED_CAST")
            if (!body(k, r as V)) return
        }
    }

    /**
     * NOT Go API — `for k, v := range m` as the lowering emits it (`while (it.next()) { it.key; it.value }`):
     * one copy of the table, no per-entry probe while the map is unchanged (the old form snapshotted the
     * keys and probed each one again — a second hashing pass over every symbol table the checker merges).
     */
    fun iter(): GoMapIter<K, V> = GoMapIter(this, if (size == 0) EMPTY else slots.copyOf(), modCount)

    /** The keys present now (a snapshot, so the map may be mutated while iterating it). */
    @Suppress("UNCHECKED_CAST")
    fun keysSnapshot(): List<K> {
        if (size == 0) return emptyList()
        val out = ArrayList<K>(size)
        val s = slots
        var i = 0
        while (i < s.size) {
            val k = s[i]
            if (k != null) out.add((if (k === NULL_KEY) null else k) as K)
            i += 2
        }
        return out
    }

    /** The entries present now (a snapshot). */
    @Suppress("UNCHECKED_CAST")
    fun entriesSnapshot(): List<Pair<K, V>> {
        if (size == 0) return emptyList()
        val out = ArrayList<Pair<K, V>>(size)
        val s = slots
        var i = 0
        while (i < s.size) {
            val k = s[i]
            if (k != null) out.add(((if (k === NULL_KEY) null else k) as K) to (s[i + 1] as V))
            i += 2
        }
        return out
    }

    /** A shallow Go clone (struct values copied through [elem]); a nil map clones to nil. */
    @Suppress("UNCHECKED_CAST")
    fun goClone(): GoMap<K, V> {
        if (!live) return this
        val n = GoMap<K, V>(elem, true, 0)
        if (size == 0) return n
        n.slots = slots.copyOf()
        n.hashes = hashes.copyOf()
        n.mask = mask
        n.shift = shift
        n.size = size
        if (elem.copy != null) {
            var i = 0
            while (i < n.slots.size) {
                if (n.slots[i] != null) n.slots[i + 1] = elem.copyValue(n.slots[i + 1] as V)
                i += 2
            }
        }
        return n
    }

    override fun toString(): String =
        entriesSnapshot().joinToString(", ", "map{", "}") { "${it.first}=${it.second}" }

    private fun allocate(capacity: Int) {
        slots = arrayOfNulls(capacity * 2)
        hashes = IntArray(capacity)
        mask = capacity - 1
        shift = 32 - capacity.countTrailingZeroBits()
    }

    private fun rehash(capacity: Int) {
        val old = slots
        val oldHashes = hashes
        allocate(capacity)
        val s = slots
        var o = 0
        while (o < oldHashes.size) {
            val k = old[o shl 1]
            if (k != null) {
                val h = oldHashes[o]
                var i = h ushr shift
                while (s[i shl 1] != null) i = (i + 1) and mask
                s[i shl 1] = k
                s[(i shl 1) + 1] = old[(o shl 1) + 1]
                hashes[i] = h
            }
            o++
        }
    }

    companion object {
        private val EMPTY: Array<Any?> = arrayOfNulls(0)
        private val EMPTY_HASHES = IntArray(0)
        private const val MIN_CAPACITY = 8

        /** Stands for a `nil` key in [slots] (where `null` means an empty slot). */
        internal val NULL_KEY = Any()

        /** Fibonacci hashing: spreads `hashCode`s whose entropy is in the low bits (boxed ints, `String`). */
        private fun mix(h: Int): Int = h * -0x61c88647

        private fun capacityFor(n: Int): Int {
            var c = MIN_CAPACITY
            while (c < n * 2 && c < (1 shl 30)) c = c shl 1
            return c
        }

        /** `make(map[K]V)` / `make(map[K]V, hint)` / `map[K]V{}`. */
        fun <K, V> make(elem: GoElem<V>, hint: Int = 0): GoMap<K, V> = GoMap(elem, true, maxOf(hint, 0))

        /** A nil map of value kind [elem]. */
        fun <K, V> nil(elem: GoElem<V>): GoMap<K, V> = GoMap(elem, false, 0)
    }

}

/** NOT Go API — the "absent" answer of [GoMap.probe] (never a stored value). */
object GoMapAbsent

/** NOT Go API — the value half of `v, ok := m[k]` from a [GoMap.probe] result: the zero value when absent. */
@Suppress("UNCHECKED_CAST")
inline fun <V> goProbeValue(probed: Any?, zero: () -> V): V = if (probed === GoMapAbsent) zero() else probed as V

/**
 * NOT Go API — the iteration of [GoMap.iter], with Go's guarantees: every entry present when the range
 * began is produced at most once, an entry deleted before it is reached is not produced, and a value
 * is read as it is when its entry is reached. While the map is unchanged ([GoMap.modCount]) the
 * snapshot answers; after any write each remaining key is looked up again.
 */
class GoMapIter<K, V> internal constructor(private val m: GoMap<K, V>, private val snap: Array<Any?>, private val mod: Int) {
    private var i = -2
    private var k: Any? = null
    private var v: Any? = null

    /** The current entry's key (valid after [next] answered true). */
    @Suppress("UNCHECKED_CAST")
    val key: K get() = k as K

    /** The current entry's value. */
    @Suppress("UNCHECKED_CAST")
    val value: V get() = v as V

    /** Advances to the next entry still present; false at the end. */
    @Suppress("UNCHECKED_CAST")
    fun next(): Boolean {
        while (true) {
            i += 2
            if (i >= snap.size) return false
            val sk = snap[i] ?: continue
            val kk = if (sk === GoMap.NULL_KEY) null else sk
            if (m.modCount == mod) {
                k = kk
                v = snap[i + 1]
                return true
            }
            val r = m.probe(kk as K)
            if (r === GoMapAbsent) continue
            k = kk
            v = r
            return true
        }
    }
}
