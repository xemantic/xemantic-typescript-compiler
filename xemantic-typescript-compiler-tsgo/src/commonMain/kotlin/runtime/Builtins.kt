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

// Go's predeclared functions, for the shapes the lowering does not map onto a member directly.
// (`len`/`cap` are the `len`/`cap` properties and `String.length`; `append` is GoSlice.append*;
// `copy` is goCopy/goCopyString; `delete` is GoMap.delete; `make` is GoSlice.make/GoMap.make;
// `new` is goNew or a struct constructor; `panic`/`recover` are goPanic/GoDeferFrame.recover.)

/** `make([]T, n)` / `make([]T, n, c)`. */
fun <T> goMakeSlice(elem: GoElem<T>, len: Int, cap: Int = len): GoSlice<T> = GoSlice.make(elem, len, cap)

/** `make(map[K]V)` / `make(map[K]V, hint)`. */
fun <K, V> goMakeMap(elem: GoElem<V>, hint: Int = 0): GoMap<K, V> = GoMap.make(elem, hint)

/** `clear(m)`. */
fun <K, V> goClear(m: GoMap<K, V>) {
    m.clear()
}

/** `min(x, y, …)` for ordered non-float operands (strings compare bytewise). */
fun <T : Comparable<T>> goMin(first: T, vararg rest: T): T {
    var m = first
    for (x in rest) if (x < m) m = x
    return m
}

/** `max(x, y, …)` for ordered non-float operands. */
fun <T : Comparable<T>> goMax(first: T, vararg rest: T): T {
    var m = first
    for (x in rest) if (x > m) m = x
    return m
}

/** `min` for `float64`: any NaN gives NaN, and `-0.0` is smaller than `+0.0` (as in Go). */
fun goMin(first: Double, vararg rest: Double): Double {
    var m = first
    for (x in rest) m = minOf(m, x)
    return m
}

/** `max` for `float64`. */
fun goMax(first: Double, vararg rest: Double): Double {
    var m = first
    for (x in rest) m = maxOf(m, x)
    return m
}

/** Zero-value helper for a TYPE PARAMETER (the lowering emits zero values explicitly elsewhere). */
fun <T> goZero(elem: GoElem<T>): T = elem.zeroValue()

/**
 * The zero value of a type parameter `T` WITHOUT an element kind: `null`. The lowering emits it only
 * where no `goElem_T` dictionary is in scope (method values of generic functions). Correct for every
 * reference instantiation (pointers, interfaces, structs reached by pointer); an instantiation with
 * a Kotlin primitive (`int`, `bool`, …) reads it as an NPE — a known limit of erasure
 * (docs/goport-lowering.md § 5).
 */
@Suppress("UNCHECKED_CAST")
fun <T> goZeroTP(): T = null as T

/**
 * Go's `==` for values of a type parameter or interface: a float compares as IEEE (`NaN != NaN`,
 * `-0 == +0`), where Kotlin's boxed `equals` does the opposite; everything else is `==`
 * (identity for generated struct classes, value equality for strings and boxed integers).
 */
fun goEq(a: Any?, b: Any?): Boolean {
    if (a is Double && b is Double) {
        val x: Double = a
        val y: Double = b
        return x == y
    }
    if (a is Float && b is Float) {
        val x: Float = a
        val y: Float = b
        return x == y
    }
    return a == b
}
