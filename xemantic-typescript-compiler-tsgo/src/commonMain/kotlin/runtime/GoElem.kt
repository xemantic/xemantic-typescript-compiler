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
 * The element kind of a Go container: how to make a zero value and how to copy a value.
 *
 * Every [GoSlice], [GoArray] and [GoMap] carries one. The runtime needs it in exactly two places
 * the lowering cannot see: a slot that Go zeroes implicitly (the tail of a grown backing array,
 * a `clear`, a missed map read) and a copy the runtime performs itself (growth, `copy`,
 * `slices.Clone`, `maps.Clone`). For a struct element type both must produce DISTINCT objects,
 * because a generated struct class is a reference type standing for a Go value
 * (docs/goport-design.md § 3).
 *
 * [zero] is `null` exactly when the zero value is `null` (pointers, interfaces, `func`, maps and
 * slices are NOT null-zero — see [SLICE]/[MAP] helpers in the lowering doc). [copy] is `null` when
 * values are immutable or references (everything but structs and arrays).
 */
class GoElem<T>(
    val zero: (() -> T)?,
    val copy: ((T) -> T)? = null,
) {

    /** The zero value; `null` for a null-zero kind. */
    @Suppress("UNCHECKED_CAST")
    fun zeroValue(): T = zero?.invoke() as T

    /** A Go value copy of [v] (identity for non-struct kinds). */
    fun copyValue(v: T): T {
        val c = copy ?: return v
        return if (v == null) v else c(v)
    }

    /** The shared nil slice of this element kind (eager: a `lazy` read was ~1.6% of a check, docs/goport-perf.md § 6). */
    val nilSlice: GoSlice<T> = GoSlice(EMPTY_ARRAY, 0, 0, 0, this, true)

    companion object {
        internal val EMPTY_ARRAY: Array<Any?> = arrayOfNulls(0)

        private val INT_ZERO: () -> Int = { 0 }

        /** Pointers, interfaces, `func` values: zero is `null`, values are references. */
        val REF: GoElem<Any?> = GoElem(null)
        val BOOL: GoElem<Boolean> = GoElem({ false })
        /** `int`, `int8`, `int16`, `int32`, `rune`, `uint8`/`byte`, `uint16`. */
        val INT: GoElem<Int> = GoElem(INT_ZERO)
        val UINT: GoElem<UInt> = GoElem({ 0u })
        val LONG: GoElem<Long> = GoElem({ 0L })
        val ULONG: GoElem<ULong> = GoElem({ 0uL })
        val DOUBLE: GoElem<Double> = GoElem({ 0.0 })
        val FLOAT: GoElem<Float> = GoElem({ 0.0f })
        val STRING: GoElem<String> = GoElem({ "" })

        /** The element kind of a reference type `T`; zero is `null`. */
        @Suppress("UNCHECKED_CAST")
        fun <T> ref(): GoElem<T> = REF as GoElem<T>

        /** `[]E` as an element: zero is the nil slice of [inner]. */
        fun <E> slice(inner: GoElem<E>): GoElem<GoSlice<E>> = GoElem({ inner.nilSlice })

        /** `map[K]V` as an element: zero is a nil map. */
        fun <K, V> map(valueElem: GoElem<V>): GoElem<GoMap<K, V>> = GoElem({ GoMap.nil(valueElem) })
    }

}

/** `struct{}` as a container element (`map[K]struct{}` sets, `chan struct{}`): the zero (and only) value is `Unit`. */
val goUnitElem: GoElem<Unit> = GoElem({ })
