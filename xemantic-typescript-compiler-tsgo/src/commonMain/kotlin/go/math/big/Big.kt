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

package com.xemantic.typescript.tsgo.go.math.big

import com.xemantic.typescript.tsgo.runtime.BigNat
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goPanic

// The `math/big` subset tsgo's `jsnum` reaches (big-int literals and exact exponentiation), over
// the runtime's exact BigNat. Go-exact for everything implemented; the rest is absent.

/** `big.Accuracy`: the sign of the rounding error (`Below` -1, `Exact` 0, `Above` +1). */
@kotlin.jvm.JvmInline
value class Accuracy(val value: kotlin.Int) {
    companion object {
        val Below: Accuracy = Accuracy(-1)
        val Exact: Accuracy = Accuracy(0)
        val Above: Accuracy = Accuracy(1)
    }
}

/** `big.Int` (mutable, like Go's: methods set and return the receiver). */
class Int {

    internal var neg: Boolean = false
    internal var abs: BigNat = BigNat.ZERO

    /** `z.Set(x)`. */
    fun set(x: Int): Int {
        neg = x.neg
        abs = x.abs
        return this
    }

    /** `z.SetInt64(x)`. */
    fun setInt64(x: Long): Int {
        neg = x < 0
        abs = if (x == Long.MIN_VALUE) BigNat.ofULong(1uL shl 63) else BigNat.of(kotlin.math.abs(x))
        return this
    }

    /** `x.Sign()`. */
    fun sign(): kotlin.Int = if (abs.isZero) 0 else if (neg) -1 else 1

    /**
     * `z.SetString(s, base)` → `(z, true)`, or `(null, false)` when [s] is not entirely a number:
     * Go's scanner (sign, base-0 prefixes `0b`/`0o`/`0x`/`0`, `_` separators only for base 0).
     */
    fun setString(s: String, base: kotlin.Int): Tuple2<Int?, Boolean> {
        if (base != 0 && (base < 2 || base > 62)) goPanic("invalid number base $base")
        var i = 0
        var negative = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            negative = s[i] == '-'
            i++
        }
        var prev = '.'
        var invalSep = false
        var b = base
        var prefix = 0.toChar()
        var count = 0
        if (base == 0) {
            b = 10
            if (i < s.length && s[i] == '0') {
                prev = '0'
                count = 1
                i++
                if (i < s.length) {
                    when (s[i]) {
                        'b', 'B' -> { b = 2; prefix = 'b' }
                        'o', 'O' -> { b = 8; prefix = 'o' }
                        'x', 'X' -> { b = 16; prefix = 'x' }
                        else -> { b = 8; prefix = '0' }
                    }
                    if (prefix != 0.toChar()) {
                        count = 0
                        if (prefix != '0') i++
                    }
                }
            }
        }
        val digits = StringBuilder()
        while (i < s.length) {
            val ch = s[i]
            if (ch == '_' && base == 0) {
                if (prev != '0') invalSep = true
                prev = '_'
                i++
                continue
            }
            val d1 = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> if (b <= 36) ch - 'A' + 10 else ch - 'A' + 36
                else -> 63
            }
            if (d1 >= b) break
            prev = '0'
            count++
            digits.append(ch)
            i++
        }
        if (invalSep || prev == '_') return Tuple2(null, false)
        if (count == 0 && prefix != '0') return Tuple2(null, false)
        if (i != s.length) return Tuple2(null, false)
        // Digit values above 'z' only exist for base > 36, which is not reachable from tsgo.
        abs = if (count == 0) BigNat.ZERO else BigNat.parse(digits.toString(), b)
        neg = negative && !abs.isZero
        return Tuple2(this, true)
    }

    /** `z.Exp(x, y, m)` with `m == nil`: `x**y` (`1` for `y <= 0`). */
    fun exp(x: Int, y: Int, m: Int?): Int {
        if (m != null) TODO("shim: big.Int.Exp with a modulus")
        if (y.neg || y.abs.isZero) {
            neg = false
            abs = BigNat.ONE
            return this
        }
        val e = y.abs.toULong()
        if (y.abs.bitLength > 31) TODO("shim: big.Int.Exp with a huge exponent")
        val r = x.abs.pow(e.toInt())
        neg = x.neg && (e and 1uL) == 1uL && !r.isZero
        abs = r
        return this
    }

    /** `x.Float64()` → the nearest float64 (ties to even) and its [Accuracy]. */
    fun float64(): Tuple2<Double, Accuracy> {
        if (abs.isZero) return Tuple2(0.0, Accuracy.Exact)
        val (f, _) = abs.toDouble(0, false)
        val acc = accuracyOf(f, abs)
        return if (neg) Tuple2(-f, Accuracy(-acc.value)) else Tuple2(f, acc)
    }

    /** `x.String()`. */
    fun string(): String = if (neg) "-" + abs.toString(10) else abs.toString(10)

    /** `x.Text(base)`. */
    fun text(base: kotlin.Int): String = if (neg) "-" + abs.toString(base) else abs.toString(base)

    /** `x.Int64()` (low 64 bits, two's complement). */
    fun int64(): Long {
        val v = abs.toULong().toLong()
        return if (neg) -v else v
    }

    /** `x.Cmp(y)`. */
    fun cmp(y: Int): kotlin.Int {
        val sx = sign()
        val sy = y.sign()
        if (sx != sy) return sx.compareTo(sy)
        val c = abs.compareTo(y.abs)
        return if (sx < 0) -c else c
    }

    fun goCopy(): Int = Int().set(this)

    override fun toString(): String = string()
}

/** The accuracy of [f] as an approximation of the non-negative integer [x]. */
private fun accuracyOf(f: Double, x: BigNat): Accuracy {
    if (f.isInfinite()) return Accuracy.Above
    val bits = f.toRawBits()
    val exp = ((bits ushr 52) and 0x7FF).toInt()
    val mant = (bits and ((1L shl 52) - 1)) or (1L shl 52)
    val e2 = exp - 1075
    val fv = if (e2 >= 0) BigNat.of(mant).shl(e2) else BigNat.ZERO
    if (e2 < 0) {
        // f has a fractional ulp; x is an integer: compare x * 2^-e2 with mant.
        val c = x.shl(-e2).compareTo(BigNat.of(mant))
        return if (c == 0) Accuracy.Exact else if (c < 0) Accuracy.Above else Accuracy.Below
    }
    val c = fv.compareTo(x)
    return if (c == 0) Accuracy.Exact else if (c > 0) Accuracy.Above else Accuracy.Below
}

/** `big.NewInt(x)`. */
fun newInt(x: Long): Int = Int().setInt64(x)

/**
 * `big.Float` — only `SetPrec`, `SetInt` and `Float64` (round to nearest even at each step, as
 * Go's default mode), which is what `jsnum` uses to round an exact integer power.
 */
class Float {

    private var prec: UInt = 0u
    private var neg: Boolean = false
    private var mant: BigNat = BigNat.ZERO
    private var exp2: kotlin.Int = 0 // value = mant * 2^exp2

    /** `z.SetPrec(prec)` (Go `uint` = `ULong`, design § 3; above `MaxPrec` = `MaxUint32` it is clamped, as in Go). */
    fun setPrec(prec: ULong): Float {
        this.prec = if (prec > UInt.MAX_VALUE.toULong()) UInt.MAX_VALUE else prec.toUInt()
        if (!mant.isZero) roundToPrec()
        return this
    }

    /** `z.SetInt(x)`: rounds to the precision (a zero precision becomes `max(x.BitLen(), 64)`). */
    fun setInt(x: Int): Float {
        val bitLen = x.abs.bitLength
        if (prec == 0u) prec = maxOf(bitLen, 64).toUInt()
        neg = x.neg
        mant = x.abs
        exp2 = 0
        roundToPrec()
        return this
    }

    private fun roundToPrec() {
        val len = mant.bitLength
        val p = if (prec > kotlin.Int.MAX_VALUE.toUInt()) kotlin.Int.MAX_VALUE else prec.toInt()
        if (len <= p) return
        val drop = len - p
        var m = mant.shr(drop)
        val roundBit = mant.testBit(drop - 1)
        val rest = mant.anyBitBelow(drop - 1)
        if (roundBit && (rest || m.testBit(0))) m = m + BigNat.ONE
        mant = m
        exp2 += drop
    }

    /** `x.Float64()` → nearest float64 (ties to even) and its [Accuracy]. */
    fun float64(): Tuple2<Double, Accuracy> {
        if (mant.isZero) return Tuple2(if (neg) -0.0 else 0.0, Accuracy.Exact)
        val (f, _) = mant.toDouble(exp2, false)
        val acc = accuracyOf(f, mant.shl(exp2)) // exp2 >= 0: rounding only ever drops low bits
        return if (neg) Tuple2(-f, Accuracy(-acc.value)) else Tuple2(f, acc)
    }

    fun goCopy(): Float {
        val c = Float()
        c.prec = prec
        c.neg = neg
        c.mant = mant
        c.exp2 = exp2
        return c
    }
}
