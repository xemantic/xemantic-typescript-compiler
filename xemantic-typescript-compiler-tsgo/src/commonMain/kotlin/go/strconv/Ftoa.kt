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
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.goAppendString

// Float -> decimal, bit-exact with Go's strconv.FormatFloat for 'e', 'E', 'f', 'g', 'G' (bitSize 64).
//
// Go's own implementation (internal/strconv/uscale.go, ftoa.go) is a fast unrounded-scaling
// algorithm; this is the classical exact one over big integers. Both are specified to produce the
// same digits: shortest = the fewest digits that round-trip, the closest such, ties to even;
// fixed precision = the exact decimal expansion rounded half to even (Go's decimal.Round). The
// formatting step (fmtEFG) is a line-by-line port.

/** A decimal digit string `0.d1d2…dn × 10^dp`, trailing zeros trimmed (Go's `decimal` after `trim`). */
private class Decimal(var d: CharArray, var nd: Int, var dp: Int) {

    fun round(n: Int) {
        if (n < 0 || n >= nd) return
        if (shouldRoundUp(n)) roundUp(n) else roundDown(n)
    }

    private fun shouldRoundUp(n: Int): Boolean {
        if (n < 0 || n >= nd) return false
        if (d[n] == '5' && n + 1 == nd) { // exactly halfway - round to even
            return n > 0 && (d[n - 1] - '0') % 2 != 0
        }
        return d[n] >= '5'
    }

    private fun roundDown(n: Int) {
        if (n < 0 || n >= nd) return
        nd = n
        trim()
    }

    private fun roundUp(n: Int) {
        if (n < 0 || n >= nd) return
        for (i in n - 1 downTo 0) {
            if (d[i] < '9') {
                d[i] = d[i] + 1
                nd = i + 1
                return
            }
        }
        d[0] = '1'
        nd = 1
        dp++
    }

    fun trim() {
        while (nd > 0 && d[nd - 1] == '0') nd--
        if (nd == 0) dp = 0
    }
}

/** The exact decimal expansion of `mant * 2^exp2` (mant > 0). */
private fun exactDecimal(mant: Long, exp2: Int): Decimal {
    val digits: String
    val dp: Int
    if (exp2 >= 0) {
        digits = BigNat.of(mant).shl(exp2).toString(10)
        dp = digits.length
    } else {
        // mant * 2^-k = mant * 5^k / 10^k
        val k = -exp2
        digits = (BigNat.of(mant) * BigNat.of(5L).pow(k)).toString(10)
        dp = digits.length - k
    }
    val dec = Decimal(digits.toCharArray(), digits.length, dp)
    dec.trim()
    return dec
}

/**
 * The shortest digits that read back as `mant * 2^exp2` (Steele & White / Burger & Dybvig, free
 * format, exact): the fewest digits inside the rounding interval (boundaries inclusive when the
 * mantissa is even), the closest such, ties to even.
 */
private fun shortestDecimal(mant: Long, exp2: Int, unequalGaps: Boolean): Decimal {
    val inclusive = mant and 1L == 0L
    var r: BigNat
    var s: BigNat
    var mPlus: BigNat
    var mMinus: BigNat
    val m = BigNat.of(mant)
    if (exp2 >= 0) {
        val be = BigNat.ONE.shl(exp2)
        if (!unequalGaps) {
            r = (m * be).shl(1); s = BigNat.of(2); mPlus = be; mMinus = be
        } else {
            r = (m * be).shl(2); s = BigNat.of(4); mPlus = be.shl(1); mMinus = be
        }
    } else {
        if (!unequalGaps) {
            r = m.shl(1); s = BigNat.ONE.shl(-exp2 + 1); mPlus = BigNat.ONE; mMinus = BigNat.ONE
        } else {
            r = m.shl(2); s = BigNat.ONE.shl(-exp2 + 2); mPlus = BigNat.of(2); mMinus = BigNat.ONE
        }
    }
    val bitLen = 64 - mant.countLeadingZeroBits()
    var k = kotlin.math.ceil((exp2 + bitLen - 1) * 0.30102999566398114 - 1e-10).toInt()
    if (k >= 0) {
        s *= BigNat.pow10(k)
    } else {
        val scale = BigNat.pow10(-k)
        r *= scale; mPlus *= scale; mMinus *= scale
    }
    fun high(rr: BigNat, mp: BigNat, ss: BigNat): Boolean {
        val c = (rr + mp).compareTo(ss)
        return if (inclusive) c >= 0 else c > 0
    }
    while (high(r, mPlus, s)) {
        s = s.mulAdd(10, 0); k++
    }
    while (!high(r.mulAdd(10, 0), mPlus.mulAdd(10, 0), s)) {
        r = r.mulAdd(10, 0); mPlus = mPlus.mulAdd(10, 0); mMinus = mMinus.mulAdd(10, 0); k--
    }
    val out = StringBuilder()
    while (true) {
        r = r.mulAdd(10, 0); mPlus = mPlus.mulAdd(10, 0); mMinus = mMinus.mulAdd(10, 0)
        val (q, rem) = r.divRem(s)
        val d = q.toULong().toInt()
        r = rem
        val c1 = r.compareTo(mMinus)
        val tc1 = if (inclusive) c1 <= 0 else c1 < 0
        val tc2 = high(r, mPlus, s)
        if (!tc1 && !tc2) {
            out.append('0' + d)
            continue
        }
        val last = when {
            tc1 && !tc2 -> d
            !tc1 -> d + 1
            else -> {
                val c = r.shl(1).compareTo(s)
                when {
                    c < 0 -> d
                    c > 0 -> d + 1
                    else -> if (d % 2 == 0) d else d + 1
                }
            }
        }
        if (last == 10) {
            // Carry (cannot happen with a correct fixup, kept for safety).
            var i = out.length - 1
            while (i >= 0 && out[i] == '9') {
                out.setLength(i); i--
            }
            if (i < 0) {
                out.append('1'); k++
            } else {
                out[i] = out[i] + 1
            }
        } else {
            out.append('0' + last)
        }
        break
    }
    val dec = Decimal(out.toString().toCharArray(), out.length, k)
    dec.trim()
    return dec
}

/** `strconv.FormatFloat(f, fmt, prec, bitSize)`; `fmt` is the Go byte (`'e'.code`, …). */
fun formatFloat(f: Double, fmt: Int, prec: Int, bitSize: Int): String {
    if (bitSize != 64) {
        if (bitSize == 32) TODO("shim: strconv.FormatFloat with bitSize 32")
        throw com.xemantic.typescript.tsgo.runtime.GoPanic("strconv: illegal FormatFloat bitSize")
    }
    return ftoa64(f, fmt.toChar(), prec)
}

/** `strconv.AppendFloat(dst, f, fmt, prec, bitSize)`. */
fun appendFloat(dst: GoSlice<Int>, f: Double, fmt: Int, prec: Int, bitSize: Int): GoSlice<Int> =
    goAppendString(dst, formatFloat(f, fmt, prec, bitSize))

private fun ftoa64(v: Double, fmt: Char, precIn: Int): String {
    val bits = v.toRawBits()
    val neg = bits < 0
    var exp = ((bits ushr 52) and 0x7FF).toInt()
    var mant = bits and ((1L shl 52) - 1)
    if (exp == 0x7FF) {
        if (mant != 0L) return "NaN"
        return if (neg) "-Inf" else "+Inf"
    }
    val storedMantZero = mant == 0L
    val biased = exp
    if (exp == 0) exp++ else mant = mant or (1L shl 52)
    exp += -1023
    if (fmt == 'b' || fmt == 'x' || fmt == 'X') TODO("shim: strconv.FormatFloat fmt '$fmt'")
    var prec = precIn
    if (mant == 0L) return fmtEFG(neg, CharArray(0), 0, 0, prec, fmt, prec < 0)
    val exp2 = exp - 52 // v = mant * 2^exp2
    if (prec < 0) {
        val d = shortestDecimal(mant, exp2, storedMantZero && biased > 1)
        when (fmt) {
            'e', 'E' -> prec = maxOf(d.nd - 1, 0)
            'f' -> prec = maxOf(d.nd - d.dp, 0)
            'g', 'G' -> prec = d.nd
        }
        return fmtEFG(neg, d.d, d.dp, d.nd, prec, fmt, true)
    }
    val d = exactDecimal(mant, exp2)
    when (fmt) {
        'e', 'E' -> d.round(prec + 1)
        'f' -> d.round(d.dp + prec)
        'g', 'G' -> {
            if (prec == 0) prec = 1
            d.round(prec)
        }
    }
    return fmtEFG(neg, d.d, d.dp, d.nd, prec, fmt, false)
}

private fun fmtEFG(neg: Boolean, s: CharArray, dp: Int, nd: Int, precIn: Int, fmtIn: Char, shortest: Boolean): String {
    var prec = precIn
    var fmt = fmtIn
    if (fmt == 'g' || fmt == 'G') {
        var eprec = prec
        if (eprec > nd && nd >= dp) eprec = nd
        if (shortest) eprec = 6
        val exp = dp - 1
        if (exp < -4 || exp >= eprec) {
            if (prec > nd) prec = nd
            prec--
            fmt = fmt + ('e' - 'g')
        } else {
            if (prec > dp) prec = nd
            prec = maxOf(prec - dp, 0)
            fmt = 'f'
        }
    }
    val dst = StringBuilder()
    when (fmt) {
        'e', 'E' -> {
            if (neg) dst.append('-')
            dst.append(if (nd != 0) s[0] else '0')
            if (prec > 0) {
                dst.append('.')
                var i = 1
                val m = minOf(nd, prec + 1)
                if (i < m) {
                    dst.appendRange(s, i, m)
                    i = m
                }
                repeat(prec + 1 - i) { dst.append('0') }
            }
            dst.append(fmt)
            var exp = dp - 1
            if (nd == 0) exp = 0
            if (exp < 0) {
                dst.append('-'); exp = -exp
            } else {
                dst.append('+')
            }
            when {
                exp < 10 -> dst.append('0').append('0' + exp)
                exp < 100 -> dst.append('0' + exp / 10).append('0' + exp % 10)
                else -> dst.append('0' + exp / 100).append('0' + (exp / 10) % 10).append('0' + exp % 10)
            }
            return dst.toString()
        }
        'f' -> {
            if (neg) dst.append('-')
            if (dp > 0) {
                val m = minOf(nd, dp)
                dst.appendRange(s, 0, m)
                repeat(dp - m) { dst.append('0') }
            } else {
                dst.append('0')
            }
            if (prec > 0) {
                dst.append('.')
                val lz = minOf(prec, maxOf(0, -dp))
                val off = dp + lz
                val m = minOf(prec - lz, maxOf(0, nd - off))
                val tz = maxOf(0, prec - lz - m)
                repeat(lz) { dst.append('0') }
                for (i in 0 until m) dst.append(s[off + i])
                repeat(tz) { dst.append('0') }
            }
            return dst.toString()
        }
    }
    return "%$fmt"
}
