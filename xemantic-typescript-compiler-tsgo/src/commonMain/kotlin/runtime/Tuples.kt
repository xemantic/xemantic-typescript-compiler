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

/** A Go function's two results. Destructure at the call site: `val (a, b) = f()`. */
class Tuple2<out A, out B>(val first: A, val second: B) {
    operator fun component1(): A = first
    operator fun component2(): B = second
    override fun toString(): String = "($first, $second)"
}

/** Three results. */
class Tuple3<out A, out B, out C>(val first: A, val second: B, val third: C) {
    operator fun component1(): A = first
    operator fun component2(): B = second
    operator fun component3(): C = third
    override fun toString(): String = "($first, $second, $third)"
}

/** Four results. */
class Tuple4<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
) {
    operator fun component1(): A = first
    operator fun component2(): B = second
    operator fun component3(): C = third
    operator fun component4(): D = fourth
    override fun toString(): String = "($first, $second, $third, $fourth)"
}

/** Five results. */
class Tuple5<out A, out B, out C, out D, out E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
) {
    operator fun component1(): A = first
    operator fun component2(): B = second
    operator fun component3(): C = third
    operator fun component4(): D = fourth
    operator fun component5(): E = fifth
    override fun toString(): String = "($first, $second, $third, $fourth, $fifth)"
}
