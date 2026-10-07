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

package com.xemantic.typescript.tsgo.runtime

/**
 * Go strings are BYTE strings here: a Kotlin [String] whose every `Char` is one byte, `0..255`
 * (docs/goport-design.md § 3). `len(s)` is `s.length`, `s[i]` is `s[i].code`, `s[a:b]` is a
 * substring, `+` concatenates, and equality/hashing/ordering are byte-exact (a `Char` comparison
 * of `0..255` codes is a byte comparison).
 *
 * UTF-8 is decoded only here and in the `unicode/utf8` shim, and text crosses into or out of the
 * Kotlin world (UTF-16) only through [fromUtf16] and [toUtf16].
 */
object GoString {

    /** A UTF-16 Kotlin string as Go's UTF-8 byte string. A lone surrogate encodes as U+FFFD (EF BF BD). */
    fun fromUtf16(s: String): String {
        var ascii = true
        for (c in s) if (c.code >= 0x80) {
            ascii = false
            break
        }
        if (ascii) return s
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var cp = c.code
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((cp - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                i += 2
            } else {
                i++
            }
            appendRuneBytes(sb, cp)
        }
        return sb.toString()
    }

    /**
     * A Go byte string as a UTF-16 Kotlin string, decoding UTF-8 exactly as Go's `range` does:
     * every byte of an invalid sequence becomes one U+FFFD.
     */
    fun toUtf16(s: String): String {
        var ascii = true
        for (c in s) if (c.code >= 0x80) {
            ascii = false
            break
        }
        if (ascii) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val p = goDecodeRune(s, i)
            val r = p.toInt()
            i += (p ushr 32).toInt()
            if (r < 0x10000) {
                sb.append(r.toChar())
            } else {
                val v = r - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar())
                sb.append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
        return sb.toString()
    }

    /** Whether every char of [s] is a byte (`0..255`): the representation invariant. */
    fun isByteString(s: String): Boolean = s.all { it.code < 0x100 }

}

/** `utf8.RuneError`. */
internal const val RUNE_ERROR: Int = 0xFFFD

/** `utf8.MaxRune`. */
internal const val MAX_RUNE: Int = 0x10FFFF

private const val SURROGATE_MIN: Int = 0xD800
private const val SURROGATE_MAX: Int = 0xDFFF

/** Packs a decoded rune and its byte width: low 32 bits the rune, high 32 bits the width. */
private fun pack(rune: Int, width: Int): Long = (width.toLong() shl 32) or (rune.toLong() and 0xFFFFFFFFL)

/**
 * Decodes the rune starting at byte offset [i] of [s] with Go's `utf8.DecodeRuneInString(s[i:])`
 * rules, WITHOUT the substring: returns `(width shl 32) or rune`. Invalid or truncated input
 * answers `(RuneError, 1)`; `i == len(s)` answers `(RuneError, 0)`.
 *
 * Unpack: `val r = p.toInt(); val w = (p ushr 32).toInt()`.
 */
fun goDecodeRune(s: String, i: Int): Long {
    val n = s.length - i
    if (n < 1) return pack(RUNE_ERROR, 0)
    val b0 = s[i].code
    if (b0 < 0x80) return pack(b0, 1)
    val lo: Int
    val hi: Int
    val size: Int
    when (b0) {
        in 0xC2..0xDF -> { size = 2; lo = 0x80; hi = 0xBF }
        0xE0 -> { size = 3; lo = 0xA0; hi = 0xBF }
        in 0xE1..0xEC -> { size = 3; lo = 0x80; hi = 0xBF }
        0xED -> { size = 3; lo = 0x80; hi = 0x9F }
        in 0xEE..0xEF -> { size = 3; lo = 0x80; hi = 0xBF }
        0xF0 -> { size = 4; lo = 0x90; hi = 0xBF }
        in 0xF1..0xF3 -> { size = 4; lo = 0x80; hi = 0xBF }
        0xF4 -> { size = 4; lo = 0x80; hi = 0x8F }
        else -> return pack(RUNE_ERROR, 1)
    }
    if (n < size) {
        // Go still reports width 1 for a truncated sequence.
        return pack(RUNE_ERROR, 1)
    }
    val b1 = s[i + 1].code
    if (b1 < lo || b1 > hi) return pack(RUNE_ERROR, 1)
    if (size == 2) return pack(((b0 and 0x1F) shl 6) or (b1 and 0x3F), 2)
    val b2 = s[i + 2].code
    if (b2 < 0x80 || b2 > 0xBF) return pack(RUNE_ERROR, 1)
    if (size == 3) return pack(((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F), 3)
    val b3 = s[i + 3].code
    if (b3 < 0x80 || b3 > 0xBF) return pack(RUNE_ERROR, 1)
    return pack(((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F), 4)
}

/**
 * Decodes the last rune of `s[:end]` with Go's `utf8.DecodeLastRuneInString` rules, packed as in
 * [goDecodeRune].
 */
fun goDecodeLastRune(s: String, end: Int = s.length): Long {
    if (end == 0) return pack(RUNE_ERROR, 0)
    var start = end - 1
    val r = s[start].code
    if (r < 0x80) return pack(r, 1)
    val lim = maxOf(end - 4, 0)
    start--
    while (start >= lim) {
        if (s[start].code and 0xC0 != 0x80) break
        start--
    }
    if (start < 0) start = 0
    // Decode within s[start:end] only.
    val p = goDecodeRune(if (end == s.length) s else s.substring(0, end), start)
    val size = (p ushr 32).toInt()
    if (start + size != end) return pack(RUNE_ERROR, 1)
    return p
}

/** The UTF-8 byte width of [r] (`utf8.RuneLen`): `-1` for a surrogate or an out-of-range rune. */
internal fun runeLen(r: Int): Int = when {
    r < 0 -> -1
    r < 0x80 -> 1
    r < 0x800 -> 2
    r in SURROGATE_MIN..SURROGATE_MAX -> -1
    r < 0x10000 -> 3
    r <= MAX_RUNE -> 4
    else -> -1
}

/** Appends the UTF-8 bytes of [r] (RuneError for an invalid rune) to [sb], one char per byte. */
internal fun appendRuneBytes(sb: StringBuilder, r: Int) {
    val v = if (r < 0 || r > MAX_RUNE || r in SURROGATE_MIN..SURROGATE_MAX) RUNE_ERROR else r
    when {
        v < 0x80 -> sb.append(v.toChar())
        v < 0x800 -> {
            sb.append((0xC0 or (v shr 6)).toChar())
            sb.append((0x80 or (v and 0x3F)).toChar())
        }
        v < 0x10000 -> {
            sb.append((0xE0 or (v shr 12)).toChar())
            sb.append((0x80 or ((v shr 6) and 0x3F)).toChar())
            sb.append((0x80 or (v and 0x3F)).toChar())
        }
        else -> {
            sb.append((0xF0 or (v shr 18)).toChar())
            sb.append((0x80 or ((v shr 12) and 0x3F)).toChar())
            sb.append((0x80 or ((v shr 6) and 0x3F)).toChar())
            sb.append((0x80 or (v and 0x3F)).toChar())
        }
    }
}

/** `string(r)` for a rune (or any integer): its UTF-8 bytes; an invalid rune gives "�". */
fun goRuneToString(r: Int): String {
    if (r in 0 until 0x80) return r.toChar().toString()
    val sb = StringBuilder(4)
    appendRuneBytes(sb, r)
    return sb.toString()
}

/** `string(x)` for an `int64` `x`: out-of-`int32` values are invalid runes. */
fun goRuneToString(r: Long): String =
    if (r < Int.MIN_VALUE || r > Int.MAX_VALUE) goRuneToString(-1) else goRuneToString(r.toInt())

/** `s[i]`: the byte at [i]; panics with Go's index error. */
fun goByteAt(s: String, i: Int): Int {
    if (i < 0 || i >= s.length) goPanicIndex(i, s.length)
    return s[i].code
}

/** `s[low:high]` with Go's bounds panics. */
fun goSubstring(s: String, low: Int = 0, high: Int = s.length): String {
    if (high < 0 || high > s.length) goPanicSlice("[:$high] with length ${s.length}")
    if (low < 0 || low > high) goPanicSlice("[$low:$high]")
    return s.substring(low, high)
}

/** `[]byte(s)`: a fresh, non-nil byte slice. */
fun goStringToBytes(s: String): GoSlice<Int> {
    val a = arrayOfNulls<Any?>(s.length)
    for (k in s.indices) a[k] = s[k].code
    return GoSlice.wrap(GoElem.INT, a)
}

/** `string(b)` for a byte slice. */
fun goBytesToString(b: GoSlice<Int>): String {
    val sb = StringBuilder(b.len)
    for (k in 0 until b.len) sb.append((b[k] and 0xFF).toChar())
    return sb.toString()
}

/** `[]rune(s)`. */
fun goStringToRunes(s: String): GoSlice<Int> {
    val out = ArrayList<Any?>(s.length)
    var i = 0
    while (i < s.length) {
        val p = goDecodeRune(s, i)
        out.add(p.toInt())
        i += (p ushr 32).toInt()
    }
    return GoSlice.wrap(GoElem.INT, out.toTypedArray())
}

/** `string(rs)` for a rune slice. */
fun goRunesToString(rs: GoSlice<Int>): String {
    val sb = StringBuilder(rs.len)
    for (k in 0 until rs.len) appendRuneBytes(sb, rs[k])
    return sb.toString()
}

/**
 * `for i, r := range s`: [body] receives each rune's byte offset and the rune (RuneError, width 1,
 * for every invalid byte); return `false` to `break`.
 */
inline fun goRangeString(s: String, body: (Int, Int) -> Boolean) {
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        if (c < 0x80) {
            if (!body(i, c)) return
            i++
        } else {
            val p = goDecodeRune(s, i)
            if (!body(i, p.toInt())) return
            i += (p ushr 32).toInt()
        }
    }
}

/** The number of runes in [s] (`utf8.RuneCountInString`). */
fun goRuneCount(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        i += if (c < 0x80) 1 else (goDecodeRune(s, i) ushr 32).toInt()
        n++
    }
    return n
}

// ---------------------------------------------------------------------------------------------
// String windows (docs/goport-lowering.md § 3, "substring elimination"): Go's `s[a:b]` is an O(1)
// view, Kotlin's `substring` a copy. A slice that only feeds a length, an index, an equality or a
// fused `strings` call is lowered to a (base, offset, length) window over the base string instead.
// Every helper keeps Go's bounds panic at the point Go would panic.

/** NOT Go API — a window `s[from:to]`: checks Go's bounds and answers its length `to - from`. */
fun goStrView(s: String, from: Int, to: Int): Int {
    if (to < 0 || to > s.length) goPanicSlice("[:$to] with length ${s.length}")
    if (from < 0 || from > to) goPanicSlice("[$from:$to]")
    return to - from
}

/** NOT Go API — `w[k]` of a window of length [n] at [off] in [s]. */
@Suppress("NOTHING_TO_INLINE")
inline fun goViewByte(s: String, off: Int, n: Int, k: Int): Int {
    if (k < 0 || k >= n) goPanicIndex(k, n)
    return s[off + k].code
}

/** NOT Go API — a sub-slice bound [b] of a window of length [n] (`0 <= b <= n`); answers [b]. */
fun goViewBound(n: Int, b: Int): Int {
    if (b < 0 || b > n) goPanicSlice("[$b] with length $n")
    return b
}

/** NOT Go API — `s[from:] == t` without the copy. */
fun goStrEqAt(s: String, from: Int, t: String): Boolean {
    if (from < 0 || from > s.length) goPanicSlice("[$from:] with length ${s.length}")
    return s.length - from == t.length && s.regionMatches(from, t, 0, t.length)
}

/** NOT Go API — `s[from:to] == t` without the copy. */
fun goStrEqIn(s: String, from: Int, to: Int, t: String): Boolean {
    goStrView(s, from, to)
    return to - from == t.length && s.regionMatches(from, t, 0, t.length)
}
