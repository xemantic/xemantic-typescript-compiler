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

// Parts of this file are translated from the Go standard library (go1.27.1), Copyright The Go
// Authors, used under Go's BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.go.unicode

import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice

/** `unicode.MaxRune`. */
const val MaxRune: Int = 0x10FFFF

/** `unicode.ReplacementChar`. */
const val ReplacementChar: Int = 0xFFFD

/** `unicode.MaxASCII`. */
const val MaxASCII: Int = 0x7F

/** `unicode.MaxLatin1`. */
const val MaxLatin1: Int = 0xFF

/** `unicode.Version` (of the Go toolchain these tables were taken from). */
val version: String get() = UNICODE_VERSION

/** `unicode.Range16` (`uint16` fields held in `Int`). */
class Range16(var lo: Int = 0, var hi: Int = 0, var stride: Int = 0) {
    fun goCopy(): Range16 = Range16(lo, hi, stride)
}

/** `unicode.Range32` (`uint32` fields). */
class Range32(var lo: UInt = 0u, var hi: UInt = 0u, var stride: UInt = 0u) {
    fun goCopy(): Range32 = Range32(lo, hi, stride)
}

/** `unicode.RangeTable`. */
class RangeTable(
    var r16: GoSlice<Range16> = R16_ELEM.nilSlice,
    var r32: GoSlice<Range32> = R32_ELEM.nilSlice,
    var latinOffset: Int = 0,
) {
    fun goCopy(): RangeTable = RangeTable(r16, r32, latinOffset)

    companion object {
        /** The element kind of `[]Range16`. */
        val R16_ELEM: GoElem<Range16> = GoElem({ Range16() }, { it.goCopy() })

        /** The element kind of `[]Range32`. */
        val R32_ELEM: GoElem<Range32> = GoElem({ Range32() }, { it.goCopy() })
    }
}

private const val LINEAR_MAX = 18

private fun is16(ranges: GoSlice<Range16>, r: Int): Boolean {
    if (ranges.len <= LINEAR_MAX || r <= MaxLatin1) {
        for (i in 0 until ranges.len) {
            val range = ranges[i]
            if (r < range.lo) return false
            if (r <= range.hi) return range.stride == 1 || (r - range.lo) % range.stride == 0
        }
        return false
    }
    var lo = 0
    var hi = ranges.len
    while (lo < hi) {
        val m = lo + (hi - lo) / 2
        val range = ranges[m]
        if (range.lo <= r && r <= range.hi) return range.stride == 1 || (r - range.lo) % range.stride == 0
        if (r < range.lo) hi = m else lo = m + 1
    }
    return false
}

private fun is32(ranges: GoSlice<Range32>, r: UInt): Boolean {
    if (ranges.len <= LINEAR_MAX) {
        for (i in 0 until ranges.len) {
            val range = ranges[i]
            if (r < range.lo) return false
            if (r <= range.hi) return range.stride == 1u || (r - range.lo) % range.stride == 0u
        }
        return false
    }
    var lo = 0
    var hi = ranges.len
    while (lo < hi) {
        val m = lo + (hi - lo) / 2
        val range = ranges[m]
        if (range.lo <= r && r <= range.hi) return range.stride == 1u || (r - range.lo) % range.stride == 0u
        if (r < range.lo) hi = m else lo = m + 1
    }
    return false
}

/** `unicode.Is(rangeTab, r)` — Go's algorithm, so exact for any table tsgo builds. */
fun `is`(rangeTab: RangeTable?, r: Int): Boolean {
    val t = rangeTab ?: throw NullPointerException("unicode.Is: nil table")
    val r16 = t.r16
    if (r16.len > 0 && r.toUInt() <= r16[r16.len - 1].hi.toUInt()) return is16(r16, r and 0xFFFF)
    val r32 = t.r32
    if (r32.len > 0 && r >= r32[0].lo.toInt()) return is32(r32, r.toUInt())
    return false
}

private fun tableFromTriples(triples: IntArray, latinOffset: Int): RangeTable {
    var r16 = RangeTable.R16_ELEM.nilSlice
    var r32 = RangeTable.R32_ELEM.nilSlice
    var i = 0
    while (i < triples.size) {
        val lo = triples[i]
        val hi = triples[i + 1]
        val stride = triples[i + 2]
        if (hi <= 0xFFFF) r16 = r16.append1(Range16(lo, hi, stride))
        else r32 = r32.append1(Range32(lo.toUInt(), hi.toUInt(), stride.toUInt()))
        i += 3
    }
    return RangeTable(r16, r32, latinOffset)
}

/** `unicode.Zs` (space separators). */
val zs: RangeTable by lazy { tableFromTriples(ZS_RANGES, ZS_LATIN_OFFSET) }

/** `unicode.White_Space`. */
val whiteSpace: RangeTable by lazy { tableFromTriples(WHITE_SPACE_RANGES, WHITE_SPACE_LATIN_OFFSET) }

/** `unicode.IsSpace(r)`: Go's Latin-1 list, then the White_Space property. */
fun isSpace(r: Int): Boolean {
    if (r.toUInt() <= MaxLatin1.toUInt()) {
        return when (r) {
            '\t'.code, '\n'.code, 0x0B, 0x0C, '\r'.code, ' '.code, 0x85, 0xA0 -> true
            else -> false
        }
    }
    return `is`(whiteSpace, r)
}

private fun lookup(keys: IntArray, values: IntArray, r: Int): Int {
    var lo = 0
    var hi = keys.size - 1
    while (lo <= hi) {
        val m = (lo + hi) ushr 1
        val k = keys[m]
        when {
            k < r -> lo = m + 1
            k > r -> hi = m - 1
            else -> return values[m]
        }
    }
    return r
}

/** `unicode.ToLower(r)`. */
fun toLower(r: Int): Int {
    if (r <= MaxASCII) {
        return if (r in 'A'.code..'Z'.code) r + ('a' - 'A') else r
    }
    return lookup(TO_LOWER_KEYS, TO_LOWER_VALUES, r)
}

/** `unicode.ToUpper(r)`. */
fun toUpper(r: Int): Int {
    if (r <= MaxASCII) {
        return if (r in 'a'.code..'z'.code) r - ('a' - 'A') else r
    }
    return lookup(TO_UPPER_KEYS, TO_UPPER_VALUES, r)
}

/** `unicode.SimpleFold(r)`. */
fun simpleFold(r: Int): Int {
    if (r < 0 || r > MaxRune) return r
    return lookup(SIMPLE_FOLD_KEYS, SIMPLE_FOLD_VALUES, r)
}

/** `unicode.Mn` (nonspacing marks). */
val mn: RangeTable by lazy { tableFromTriples(MN_RANGES, MN_LATIN_OFFSET) }

private fun inPairs(pairs: IntArray, r: Int): Boolean {
    var lo = 0
    var hi = pairs.size / 2
    while (lo < hi) {
        val m = (lo + hi) ushr 1
        when {
            r < pairs[2 * m] -> hi = m
            r > pairs[2 * m + 1] -> lo = m + 1
            else -> return true
        }
    }
    return false
}

/** `unicode.IsUpper(r)` (Go 1.27.1's answer for every rune, [IS_UPPER_RANGES]). */
fun isUpper(r: Int): Boolean = inPairs(IS_UPPER_RANGES, r)

/** `unicode.IsLower(r)`. */
fun isLower(r: Int): Boolean = inPairs(IS_LOWER_RANGES, r)

/** `unicode.IsDigit(r)`. */
fun isDigit(r: Int): Boolean = inPairs(IS_DIGIT_RANGES, r)

