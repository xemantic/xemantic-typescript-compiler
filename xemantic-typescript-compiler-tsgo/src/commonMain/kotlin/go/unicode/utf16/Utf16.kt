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

package com.xemantic.typescript.tsgo.go.unicode.utf16

import com.xemantic.typescript.tsgo.runtime.Tuple2

private const val REPLACEMENT_CHAR = 0xFFFD
private const val MAX_RUNE = 0x10FFFF
private const val SURR1 = 0xD800
private const val SURR2 = 0xDC00
private const val SURR3 = 0xE000
private const val SURR_SELF = 0x10000

/** `utf16.IsSurrogate(r)`. */
fun isSurrogate(r: Int): Boolean = r in SURR1 until SURR3

/** `utf16.DecodeRune(r1, r2)`. */
fun decodeRune(r1: Int, r2: Int): Int =
    if (r1 in SURR1 until SURR2 && r2 in SURR2 until SURR3) ((r1 - SURR1) shl 10 or (r2 - SURR2)) + SURR_SELF
    else REPLACEMENT_CHAR

/** `utf16.EncodeRune(r)` → (r1, r2). */
fun encodeRune(r: Int): Tuple2<Int, Int> {
    if (r < SURR_SELF || r > MAX_RUNE) return Tuple2(REPLACEMENT_CHAR, REPLACEMENT_CHAR)
    val v = r - SURR_SELF
    return Tuple2(SURR1 + ((v shr 10) and 0x3FF), SURR2 + (v and 0x3FF))
}

/** `utf16.RuneLen(r)`. */
fun runeLen(r: Int): Int = when {
    r in 0 until SURR1 || r in SURR3 until SURR_SELF -> 1
    r in SURR_SELF..MAX_RUNE -> 2
    else -> -1
}
