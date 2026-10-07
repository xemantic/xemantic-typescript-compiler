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

package com.xemantic.typescript.tsgo

/** One oracle row: fields split on \u0001, \u0002 = Go nil. */
internal fun fields(row: String): List<String?> = row.split('\u0001').map { if (it == "\u0002") null else it }

/** A hex bit pattern as a Double. */
internal fun bitsToDouble(hex: String): Double = Double.fromBits(hex.toULong(16).toLong())

/** A Double as Go's `%x` of its bits. */
internal fun doubleBits(d: Double): String = d.toRawBits().toULong().toString(16)

/** Equal bit patterns, or both NaN (NaN payloads are not portable across Kotlin targets). */
internal fun sameDouble(a: Double, expectedHex: String): Boolean {
    val e = bitsToDouble(expectedHex)
    return if (e.isNaN()) a.isNaN() else doubleBits(a) == expectedHex
}

/** A Go byte string from byte values (written this way because a `\uXXXX` in a fixture is not reliable). */
internal fun bs(vararg bytes: Int): String = bytes.map { (it and 0xFF).toChar() }.toCharArray().concatToString()
