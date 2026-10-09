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

package com.xemantic.typescript.tsgo.go.unicode.utf8

import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.appendRuneBytes
import com.xemantic.typescript.tsgo.runtime.goAppendString
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goDecodeLastRune
import com.xemantic.typescript.tsgo.runtime.goDecodeLastRuneIn
import com.xemantic.typescript.tsgo.runtime.goDecodeRune
import com.xemantic.typescript.tsgo.runtime.goDecodeRuneIn
import com.xemantic.typescript.tsgo.runtime.goRuneCount
import com.xemantic.typescript.tsgo.runtime.runeLen as rtRuneLen

/** `utf8.RuneError`. */
const val RuneError: Int = 0xFFFD

/** `utf8.RuneSelf`. */
const val RuneSelf: Int = 0x80

/** `utf8.MaxRune`. */
const val MaxRune: Int = 0x10FFFF

/** `utf8.UTFMax`. */
const val UTFMax: Int = 4

private fun unpack(p: Long): Tuple2<Int, Int> = Tuple2(p.toInt(), (p ushr 32).toInt())

/** `utf8.DecodeRuneInString(s)` → (rune, size). */
fun decodeRuneInString(s: String): Tuple2<Int, Int> = unpack(goDecodeRune(s, 0))

/**
 * NOT Go API — the lowering's rewrite of `utf8.DecodeRuneInString(s[i:])`: the same answer
 * without materializing the substring (a JVM `substring` copies, so the literal translation is
 * quadratic in a scanner loop).
 */
fun decodeRuneInStringAt(s: String, i: Int): Tuple2<Int, Int> = unpack(goDecodeRune(s, i))

/** NOT Go API — `utf8.DecodeRuneInString(s[from:to])` without the substring (a window field's suffix). */
fun decodeRuneInStringIn(s: String, from: Int, to: Int): Tuple2<Int, Int> = unpack(goDecodeRuneIn(s, from, to))

/** NOT Go API — `utf8.DecodeLastRuneInString(s[from:end])` without the substring. */
fun decodeLastRuneInStringIn(s: String, from: Int, end: Int): Tuple2<Int, Int> = unpack(goDecodeLastRuneIn(s, from, end))

/** `utf8.DecodeLastRuneInString(s)` → (rune, size). */
fun decodeLastRuneInString(s: String): Tuple2<Int, Int> = unpack(goDecodeLastRune(s, s.length))

/** NOT Go API — `utf8.DecodeLastRuneInString(s[:end])` without the substring. */
fun decodeLastRuneInStringBefore(s: String, end: Int): Tuple2<Int, Int> = unpack(goDecodeLastRune(s, end))

/** `utf8.DecodeRune(p)` for a byte slice. */
fun decodeRune(p: GoSlice<Int>): Tuple2<Int, Int> =
    unpack(goDecodeRune(goBytesToString(p.slice(0, minOf(p.len, UTFMax))), 0))

/** `utf8.RuneLen(r)`. */
fun runeLen(r: Int): Int = rtRuneLen(r)

/** `utf8.RuneCountInString(s)`. */
fun runeCountInString(s: String): Int = goRuneCount(s)

/** `utf8.RuneCount(p)`. */
fun runeCount(p: GoSlice<Int>): Int = goRuneCount(goBytesToString(p))

/** `utf8.ValidRune(r)`. */
fun validRune(r: Int): Boolean = r in 0 until 0xD800 || r in 0xE000..MaxRune

/** `utf8.ValidString(s)`. */
fun validString(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        if (s[i].code < RuneSelf) {
            i++
            continue
        }
        val p = goDecodeRune(s, i)
        if (p.toInt() == RuneError && (p ushr 32).toInt() == 1) return false
        i += (p ushr 32).toInt()
    }
    return true
}

/** `utf8.RuneStart(b)`. */
fun runeStart(b: Int): Boolean = b and 0xC0 != 0x80

/** `utf8.FullRuneInString(s)`. */
fun fullRuneInString(s: String): Boolean {
    if (s.isEmpty()) return false
    val p = goDecodeRune(s, 0)
    if (p.toInt() != RuneError || (p ushr 32).toInt() != 1) return true
    // Invalid or truncated: full unless it is a valid prefix that is too short.
    val b0 = s[0].code
    val need = when (b0) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> return true
    }
    if (s.length >= need) return true
    for (k in 1 until s.length) {
        val lo = if (k == 1) when (b0) { 0xE0 -> 0xA0; 0xF0 -> 0x90; else -> 0x80 } else 0x80
        val hi = if (k == 1) when (b0) { 0xED -> 0x9F; 0xF4 -> 0x8F; else -> 0xBF } else 0xBF
        if (s[k].code !in lo..hi) return true
    }
    return false
}

/** `utf8.EncodeRune(p, r)`: writes into `p`, returns the byte count (panics if `p` is too short, as Go). */
fun encodeRune(p: GoSlice<Int>, r: Int): Int {
    val sb = StringBuilder(4)
    appendRuneBytes(sb, r)
    for (k in sb.indices) p[k] = sb[k].code
    return sb.length
}

/** `utf8.AppendRune(p, r)`. */
fun appendRune(p: GoSlice<Int>, r: Int): GoSlice<Int> {
    val sb = StringBuilder(4)
    appendRuneBytes(sb, r)
    return goAppendString(p, sb.toString())
}
