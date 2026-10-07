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

import com.xemantic.typescript.tsgo.runtime.BigNat
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.Tuple2

// Decimal/hex -> float64, bit-exact with Go's strconv.ParseFloat (bitSize 64).
//
// The SYNTAX (special values, signs, underscores, the 10000 exponent cap, hex mantissas that
// must carry a 'p' exponent) is a port of Go's internal/strconv/atof.go `special`/`readFloat`;
// the VALUE is computed exactly over big integers and rounded half to even, which is what Go's
// fast paths and its decimal fallback are all specified (and tested) to produce.

private fun lower(c: Char): Char = (c.code or ('x'.code - 'X'.code)).toChar()

private fun commonPrefixLenIgnoreCase(s: String, prefix: String): Int {
    val n = minOf(prefix.length, s.length)
    for (i in 0 until n) {
        var c = s[i]
        if (c in 'A'..'Z') c += 'a' - 'A'
        if (c != prefix[i]) return i
    }
    return n
}

/** Go's `special`: inf/infinity/nan prefixes. Returns null when none matches. */
private fun special(s0: String): Pair<Double, Int>? {
    if (s0.isEmpty()) return null
    var s = s0
    var sign = 1
    var nsign = 0
    when (s[0]) {
        '+', '-' -> {
            if (s[0] == '-') sign = -1
            nsign = 1
            s = s.substring(1)
            return infinity(s, sign, nsign)
        }
        'i', 'I' -> return infinity(s, sign, nsign)
        'n', 'N' -> if (commonPrefixLenIgnoreCase(s, "nan") == 3) return Double.fromBits(0x7FF8000000000001L) to 3
    }
    return null
}

private fun infinity(s: String, sign: Int, nsign: Int): Pair<Double, Int>? {
    var n = commonPrefixLenIgnoreCase(s, "infinity")
    if (n in 4..7) n = 3
    if (n == 3 || n == 8) {
        return (if (sign < 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY) to nsign + n
    }
    return null
}

/** The parsed mantissa: all significant digits (leading zeros dropped), decimal point and exponent. */
private class FloatSyntax(
    val neg: Boolean,
    val hex: Boolean,
    /** Significant digits (leading zeros removed), in base 10 or 16. */
    val digits: String,
    /** Position of the point relative to [digits] (digits count before it), in digit units. */
    val dp: Int,
    /** Binary (hex, already times 4 for [dp]) or decimal exponent from the exponent part. */
    val expPart: Int,
    val consumed: Int,
)

/** Go's `readFloat` syntax, keeping every digit. Returns `consumed` = bytes read; `null` = not ok. */
private fun readFloat(s: String): Pair<FloatSyntax?, Int> {
    var i = 0
    var underscores = false
    var neg = false
    if (i >= s.length) return null to i
    when (s[i]) {
        '+' -> i++
        '-' -> { i++; neg = true }
    }
    var base = 10
    var expChar = 'e'
    var hex = false
    if (i + 2 < s.length && s[i] == '0' && lower(s[i + 1]) == 'x') {
        base = 16
        i += 2
        expChar = 'p'
        hex = true
    }
    var sawdot = false
    var sawdigits = false
    var nd = 0
    var dp = 0
    val digits = StringBuilder()
    loop@ while (i < s.length) {
        val c = s[i]
        when {
            c == '_' -> { underscores = true; i++; continue@loop }
            c == '.' -> {
                if (sawdot) break@loop
                sawdot = true
                dp = nd
                i++
                continue@loop
            }
            c in '0'..'9' -> {
                sawdigits = true
                if (c == '0' && nd == 0) { // ignore leading zeros
                    dp--
                    i++
                    continue@loop
                }
                nd++
                digits.append(c)
                i++
                continue@loop
            }
            base == 16 && lower(c) in 'a'..'f' -> {
                sawdigits = true
                nd++
                digits.append(lower(c))
                i++
                continue@loop
            }
        }
        break
    }
    if (!sawdigits) return null to i
    if (!sawdot) dp = nd
    var e = 0
    var esign = 1
    if (i < s.length && lower(s[i]) == expChar) {
        i++
        if (i >= s.length) return null to i
        when (s[i]) {
            '+' -> i++
            '-' -> { i++; esign = -1 }
        }
        if (i >= s.length || s[i] < '0' || s[i] > '9') return null to i
        while (i < s.length && (s[i] in '0'..'9' || s[i] == '_')) {
            if (s[i] == '_') {
                underscores = true
            } else if (e < 10000) {
                e = e * 10 + (s[i] - '0')
            }
            i++
        }
    } else if (base == 16) {
        return null to i // must have exponent
    }
    if (underscores && !underscoreOK(s.substring(0, i))) return null to i
    return FloatSyntax(neg, hex, digits.toString(), dp, e * esign, i) to i
}

/** Go's `underscoreOK`. */
internal fun underscoreOK(s0: String): Boolean {
    var s = s0
    var saw = '^'
    var i = 0
    if (s.isNotEmpty() && (s[0] == '-' || s[0] == '+')) s = s.substring(1)
    var hex = false
    if (s.length >= 2 && s[0] == '0' && (lower(s[1]) == 'b' || lower(s[1]) == 'o' || lower(s[1]) == 'x')) {
        i = 2
        saw = '0'
        hex = lower(s[1]) == 'x'
    }
    while (i < s.length) {
        if (s[i] in '0'..'9' || hex && lower(s[i]) in 'a'..'f') {
            saw = '0'; i++; continue
        }
        if (s[i] == '_') {
            if (saw != '0') return false
            saw = '_'; i++; continue
        }
        if (saw == '_') return false
        saw = '!'
        i++
    }
    return saw != '_'
}

/** Result of [atof64]: value, bytes consumed, and Go's internal error kind (null, "range", "syntax"). */
private class Atof(val f: Double, val n: Int, val err: String?)

private fun signed(neg: Boolean, f: Double): Double = if (neg) -f else f

private fun atof64(s: String): Atof {
    special(s)?.let { (v, n) -> return Atof(v, n, null) }
    val (syn, n) = readFloat(s)
    if (syn == null) return Atof(0.0, n, "syntax")
    if (syn.digits.isEmpty()) return Atof(signed(syn.neg, 0.0), n, null)
    if (syn.hex) {
        // value = 0x0.digits * 16^dp * 2^p = N * 2^(4*(dp - nd) + p)
        val big = BigNat.parse(syn.digits, 16)
        val binExp = 4 * (syn.dp - syn.digits.length) + syn.expPart
        val (f, ovf) = big.toDouble(binExp, false)
        return Atof(signed(syn.neg, f), n, if (ovf) "range" else null)
    }
    val dp = syn.dp + syn.expPart // value = 0.digits * 10^dp
    if (dp > 310) return Atof(signed(syn.neg, Double.POSITIVE_INFINITY), n, "range")
    if (dp < -330) return Atof(signed(syn.neg, 0.0), n, null)
    // Beyond 800 significant digits only "nonzero tail" matters (Go's decimal.trunc): a single
    // nonzero digit after the 800th preserves the rounding exactly.
    var digits = syn.digits
    if (digits.length > 801) {
        val tailNonZero = digits.substring(800).any { it != '0' }
        digits = digits.substring(0, 800) + if (tailNonZero) "1" else ""
    }
    val big = BigNat.parse(digits, 10)
    val e10 = dp - digits.length
    val (f, ovf) = if (e10 >= 0) {
        (big * BigNat.pow10(e10)).toDouble(0, false)
    } else {
        val den = BigNat.pow10(-e10)
        val k = maxOf(0, den.bitLength - big.bitLength + 56)
        val (q, r) = big.shl(k).divRem(den)
        q.toDouble(-k, !r.isZero)
    }
    return Atof(signed(syn.neg, f), n, if (ovf) "range" else null)
}

/** `strconv.ParseFloat(s, bitSize)`. */
fun parseFloat(s: String, bitSize: Int): Tuple2<Double, GoError?> {
    if (bitSize == 32) TODO("shim: strconv.ParseFloat with bitSize 32")
    val r = atof64(s)
    if (r.n != s.length) return Tuple2(0.0, NumError("ParseFloat", s, errSyntax))
    if (r.err == "syntax") return Tuple2(0.0, NumError("ParseFloat", s, errSyntax))
    if (r.err == "range") return Tuple2(r.f, NumError("ParseFloat", s, errRange))
    return Tuple2(r.f, null)
}
