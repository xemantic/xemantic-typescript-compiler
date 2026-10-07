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

package com.xemantic.typescript.tsgo.go.cmp

// `cmp.Ordered` is erased to Kotlin `Comparable` (docs/goport-design.md § 3). Go's float order
// differs from Kotlin's `Double.compareTo`: Go puts NaN BELOW every number and treats -0 == +0,
// so the generic functions route floats through the Go rule.

private fun <T : Comparable<T>> goCompare(x: T, y: T): Int {
    if (x is Double && y is Double) return compareFloat(x, y)
    if (x is Float && y is Float) return compareFloat(x.toDouble(), y.toDouble())
    val c = x.compareTo(y)
    return if (c < 0) -1 else if (c > 0) 1 else 0
}

private fun compareFloat(x: Double, y: Double): Int {
    val xNaN = x.isNaN()
    val yNaN = y.isNaN()
    if (xNaN) return if (yNaN) 0 else -1
    if (yNaN) return 1
    return if (x < y) -1 else if (x > y) 1 else 0
}

/** `cmp.Compare(x, y)`: -1, 0 or +1. */
fun <T : Comparable<T>> compare(x: T, y: T): Int = goCompare(x, y)

/** `cmp.Less(x, y)`. */
fun <T : Comparable<T>> less(x: T, y: T): Boolean = goCompare(x, y) < 0

/** `cmp.Or(vals...)`: the first non-zero value; [isZero] decides Go's zero test for `T`. */
fun <T> or(isZero: (T) -> Boolean, zero: T, vararg vals: T): T {
    for (v in vals) if (!isZero(v)) return v
    return zero
}
