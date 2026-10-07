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
 * A Go map `map[K]V`, wrapping a `HashMap`.
 *
 * Keys use Kotlin `equals`/`hashCode`: identity for generated struct and pointer classes, value
 * equality for `String` (byte strings), boxed numbers and value classes. A struct-VALUE key must be
 * wrapped by the lowering in a structural key (docs/goport-design.md § 3).
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
    private val backing: HashMap<K, V>?,
) {

    /** `m == nil`. */
    val isNil: Boolean get() = backing == null

    /** `len(m)`. */
    val len: Int get() = backing?.size ?: 0

    /** `m[k]`: the value, or the zero value when absent. */
    operator fun get(key: K): V {
        val b = backing ?: return elem.zeroValue()
        val v = b[key]
        return if (v != null) v else elem.zeroValue()
    }

    /** `v, ok := m[k]`. */
    fun lookup(key: K): Tuple2<V, Boolean> {
        val b = backing ?: return Tuple2(elem.zeroValue(), false)
        if (!b.containsKey(key)) return Tuple2(elem.zeroValue(), false)
        @Suppress("UNCHECKED_CAST")
        return Tuple2(b[key] as V, true)
    }

    /**
     * NOT Go API — `v, ok := m[k]` in ONE hash probe and no tuple: the stored value, or
     * [GoMapAbsent] when [key] is absent (the lowering reads it with [goProbeValue] and `!== GoMapAbsent`).
     * A second probe happens only when the stored value is `null` (a nil pointer/interface value).
     */
    fun probe(key: K): Any? {
        val b = backing ?: return GoMapAbsent
        val v = b[key]
        if (v != null) return v
        return if (b.containsKey(key)) null else GoMapAbsent
    }

    /** `_, ok := m[k]`. */
    fun contains(key: K): Boolean = backing?.containsKey(key) ?: false

    /** `m[k] = v`; panics on a nil map. */
    operator fun set(key: K, value: V) {
        val b = backing ?: goPanic(GoPlainError("assignment to entry in nil map"))
        b[key] = value
    }

    /** `delete(m, k)`; a no-op on a nil map. */
    fun delete(key: K) {
        backing?.remove(key)
    }

    /** `clear(m)`. */
    fun clear() {
        backing?.clear()
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

    /** The keys present now (a snapshot, so the map may be mutated while iterating it). */
    fun keysSnapshot(): List<K> = backing?.keys?.toList() ?: emptyList()

    /** The entries present now (a snapshot). */
    fun entriesSnapshot(): List<Pair<K, V>> = backing?.entries?.map { it.key to it.value } ?: emptyList()

    /** A shallow Go clone (struct values copied through [elem]); a nil map clones to nil. */
    fun goClone(): GoMap<K, V> {
        val b = backing ?: return this
        val n = HashMap<K, V>(b.size)
        for ((k, v) in b) n[k] = elem.copyValue(v)
        return GoMap(elem, n)
    }

    override fun toString(): String = backing?.toString()?.let { "map$it" } ?: "map[]"

    companion object {
        /** `make(map[K]V)` / `make(map[K]V, hint)` / `map[K]V{}`. */
        fun <K, V> make(elem: GoElem<V>, hint: Int = 0): GoMap<K, V> =
            GoMap(elem, HashMap(maxOf(hint, 0)))

        /** A nil map of value kind [elem]. */
        fun <K, V> nil(elem: GoElem<V>): GoMap<K, V> = GoMap(elem, null)
    }

}

/** NOT Go API — the "absent" answer of [GoMap.probe] (never a stored value). */
object GoMapAbsent

/** NOT Go API — the value half of `v, ok := m[k]` from a [GoMap.probe] result: the zero value when absent. */
@Suppress("UNCHECKED_CAST")
inline fun <V> goProbeValue(probed: Any?, zero: () -> V): V = if (probed === GoMapAbsent) zero() else probed as V
