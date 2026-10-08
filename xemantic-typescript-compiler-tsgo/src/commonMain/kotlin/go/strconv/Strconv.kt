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

package com.xemantic.typescript.tsgo.go.strconv

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoUnwrapper
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.appendRuneBytes
import com.xemantic.typescript.tsgo.runtime.goDecodeRune

/** `strconv.IntSize`: Go's `int` is 64-bit on tsgo's targets (the Kotlin `Int` is a lowering assumption). */
const val IntSize: Int = 64

/** `strconv.ErrRange`. */
val errRange: GoError = GoPlainError("value out of range")

/** `strconv.ErrSyntax`. */
val errSyntax: GoError = GoPlainError("invalid syntax")

/** `strconv.NumError`. */
class NumError(
    var func: String = "",
    var num: String = "",
    var err: GoError? = null,
) : GoError, GoUnwrapper {

    override fun error(): String = "strconv." + func + ": " + "parsing " + quote(num) + ": " + (err?.error() ?: "<nil>")

    override fun unwrap(): GoError? = err

    fun goCopy(): NumError = NumError(func, num, err)
}

private fun lower(c: Char): Char = (c.code or ('x'.code - 'X'.code)).toChar()

private fun baseError(fn: String, s: String, base: Int): GoError =
    NumError(fn, s, GoPlainError("invalid base $base"))

private fun bitSizeError(fn: String, s: String, bitSize: Int): GoError =
    NumError(fn, s, GoPlainError("invalid bit size $bitSize"))

/** `strconv.ParseBool(str)`. */
fun parseBool(str: String): Tuple2<Boolean, GoError?> = when (str) {
    "1", "t", "T", "TRUE", "true", "True" -> Tuple2(true, null)
    "0", "f", "F", "FALSE", "false", "False" -> Tuple2(false, null)
    else -> Tuple2(false, NumError("ParseBool", str, errSyntax))
}

/** `strconv.ParseUint(s, base, bitSize)`. */
fun parseUint(s: String, base: Int, bitSize: Int): Tuple2<ULong, GoError?> {
    val r = parseUintInternal(s, base, bitSize)
    return when (val e = r.second) {
        null -> Tuple2(r.first, null)
        "syntax" -> Tuple2(r.first, NumError("ParseUint", s, errSyntax))
        "range" -> Tuple2(r.first, NumError("ParseUint", s, errRange))
        "base" -> Tuple2(r.first, baseError("ParseUint", s, base))
        else -> Tuple2(r.first, if (e == "bitsize") bitSizeError("ParseUint", s, bitSize) else null)
    }
}

/** Go's internal ParseUint: the value and the internal error kind. */
private fun parseUintInternal(s0: String, base0In: Int, bitSizeIn: Int): Pair<ULong, String?> {
    if (s0.isEmpty()) return 0uL to "syntax"
    var s = s0
    var base = base0In
    val base0 = base == 0
    when {
        base in 2..36 -> {}
        base == 0 -> {
            base = 10
            if (s[0] == '0') {
                when {
                    s.length >= 3 && lower(s[1]) == 'b' -> { base = 2; s = s.substring(2) }
                    s.length >= 3 && lower(s[1]) == 'o' -> { base = 8; s = s.substring(2) }
                    s.length >= 3 && lower(s[1]) == 'x' -> { base = 16; s = s.substring(2) }
                    else -> { base = 8; s = s.substring(1) }
                }
            }
        }
        else -> return 0uL to "base"
    }
    var bitSize = bitSizeIn
    if (bitSize == 0) bitSize = IntSize else if (bitSize < 0 || bitSize > 64) return 0uL to "bitsize"
    val cutoff = ULong.MAX_VALUE / base.toULong() + 1uL
    val maxVal = if (bitSize == 64) ULong.MAX_VALUE else (1uL shl bitSize) - 1uL
    var underscores = false
    var n = 0uL
    for (c in s) {
        val d: Int
        when {
            c == '_' && base0 -> { underscores = true; continue }
            c in '0'..'9' -> d = c - '0'
            lower(c) in 'a'..'z' -> d = lower(c) - 'a' + 10
            else -> return 0uL to "syntax"
        }
        if (d >= base) return 0uL to "syntax"
        if (n >= cutoff) return maxVal to "range"
        n *= base.toULong()
        val n1 = n + d.toULong()
        if (n1 < n || n1 > maxVal) return maxVal to "range"
        n = n1
    }
    if (underscores && !underscoreOK(s0)) return 0uL to "syntax"
    return n to null
}

/** `strconv.ParseInt(s, base, bitSize)`. */
fun parseInt(s: String, base: Int, bitSize: Int): Tuple2<Long, GoError?> {
    val (v, e) = parseIntInternal(s, base, bitSize)
    return when (e) {
        null -> Tuple2(v, null)
        "syntax" -> Tuple2(v, NumError("ParseInt", s, errSyntax))
        "range" -> Tuple2(v, NumError("ParseInt", s, errRange))
        "base" -> Tuple2(v, baseError("ParseInt", s, base))
        else -> Tuple2(v, bitSizeError("ParseInt", s, bitSize))
    }
}

private fun parseIntInternal(s0: String, base: Int, bitSizeIn: Int): Pair<Long, String?> {
    if (s0.isEmpty()) return 0L to "syntax"
    var s = s0
    var neg = false
    when (s[0]) {
        '+' -> s = s.substring(1)
        '-' -> { s = s.substring(1); neg = true }
    }
    val (un, err) = parseUintInternal(s, base, bitSizeIn)
    if (err != null && err != "range") return 0L to err
    val bitSize = if (bitSizeIn == 0) IntSize else bitSizeIn
    val cutoff = 1uL shl (bitSize - 1)
    if (!neg && un >= cutoff) return (cutoff - 1uL).toLong() to "range"
    if (neg && un > cutoff) return -(cutoff.toLong()) to "range"
    var n = un.toLong()
    if (neg) n = -n
    return n to null
}

/**
 * `strconv.Atoi(s)`: parsed with Go's 64-bit `int`, then truncated to the Kotlin `Int` the
 * lowering uses for `int` (docs/goport-design.md § 3 assumption; a value beyond 32 bits wraps).
 */
fun atoi(s: String): Tuple2<Int, GoError?> {
    val sLen = s.length
    if (sLen in 1..18) {
        var t = s
        if (s[0] == '-' || s[0] == '+') {
            t = s.substring(1)
            if (t.isEmpty()) return Tuple2(0, NumError("Atoi", s, errSyntax))
        }
        var n = 0L
        for (ch in t) {
            val d = ch - '0'
            if (d < 0 || d > 9) return Tuple2(0, NumError("Atoi", s, errSyntax))
            n = n * 10 + d
        }
        if (s[0] == '-') n = -n
        return Tuple2(n.toInt(), null)
    }
    val (i64, e) = parseIntInternal(s, 10, 0)
    return when (e) {
        null -> Tuple2(i64.toInt(), null)
        "syntax" -> Tuple2(i64.toInt(), NumError("Atoi", s, errSyntax))
        else -> Tuple2(i64.toInt(), NumError("Atoi", s, errRange))
    }
}

/** `strconv.FormatInt(i, base)`. */
fun formatInt(i: Long, base: Int): String {
    if (base < 2 || base > 36) com.xemantic.typescript.tsgo.runtime.goPanic("strconv: illegal AppendInt/FormatInt base")
    return i.toString(base)
}

/** `strconv.FormatUint(i, base)`. */
fun formatUint(i: ULong, base: Int): String {
    if (base < 2 || base > 36) com.xemantic.typescript.tsgo.runtime.goPanic("strconv: illegal AppendInt/FormatInt base")
    return i.toString(base)
}

/** `strconv.Itoa(i)`. */
fun itoa(i: Int): String = i.toString()

/** `strconv.FormatBool(b)`. */
fun formatBool(b: Boolean): String = if (b) "true" else "false"

/**
 * `strconv.Quote(s)`: a double-quoted Go string literal for the byte string [s].
 *
 * APPROXIMATION: Go escapes a non-ASCII rune that `unicode.IsPrint` rejects (`­`, unassigned
 * code points, …); this keeps every valid non-ASCII rune except U+FFFD-from-invalid-bytes as is.
 * ASCII, control characters and invalid UTF-8 (`\x..`) are exact.
 */
fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        if (c < 0x80) {
            when (c) {
                '"'.code -> sb.append("\\\"")
                '\\'.code -> sb.append("\\\\")
                7 -> sb.append("\\a")
                8 -> sb.append("\\b")
                12 -> sb.append("\\f")
                10 -> sb.append("\\n")
                13 -> sb.append("\\r")
                9 -> sb.append("\\t")
                11 -> sb.append("\\v")
                else -> if (c < 0x20 || c == 0x7F) {
                    sb.append("\\x").append(HEX[c shr 4]).append(HEX[c and 15])
                } else {
                    sb.append(c.toChar())
                }
            }
            i++
            continue
        }
        val p = goDecodeRune(s, i)
        val r = p.toInt()
        val w = (p ushr 32).toInt()
        if (r == 0xFFFD && w == 1) {
            sb.append("\\x").append(HEX[c shr 4]).append(HEX[c and 15])
        } else {
            appendRuneBytes(sb, r)
        }
        i += w
    }
    sb.append('"')
    return sb.toString()
}

private const val HEX = "0123456789abcdef"
