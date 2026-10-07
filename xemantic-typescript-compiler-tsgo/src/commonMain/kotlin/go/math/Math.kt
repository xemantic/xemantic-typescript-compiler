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

package com.xemantic.typescript.tsgo.go.math

// Go's `math` package. Everything here is bit-exact with Go except where a function is marked
// APPROXIMATION: Go's amd64 build has assembly `Exp` and `Log`, whose last-ulp behaviour this does
// not reproduce (docs/goport-runtime.md § Approximations).

const val MaxFloat64: Double = 1.79769313486231570814527423731704356798070e+308
const val SmallestNonzeroFloat64: Double = 4.9406564584124654417656879286822137236505980e-324
const val MaxInt: Long = Long.MAX_VALUE
const val MinInt: Long = Long.MIN_VALUE
const val MaxInt64: Long = Long.MAX_VALUE
const val MinInt64: Long = Long.MIN_VALUE
const val MaxInt32: Int = Int.MAX_VALUE
const val MinInt32: Int = Int.MIN_VALUE
const val MaxInt16: Int = 32767
const val MinInt16: Int = -32768
const val MaxInt8: Int = 127
const val MinInt8: Int = -128
const val MaxUint8: Int = 255
const val MaxUint16: Int = 65535
const val MaxUint32: UInt = UInt.MAX_VALUE
const val MaxUint64: ULong = ULong.MAX_VALUE
const val Ln2: Double = 0.693147180559945309417232121458176568075500134360255254120680009
const val Sqrt2: Double = 1.41421356237309504880168872420969807856967187537694807317667974
const val Pi: Double = 3.14159265358979323846264338327950288419716939937510582097494459

private const val SHIFT = 52
private const val MASK = 0x7FF
private const val BIAS = 1023

fun abs(x: Double): Double = kotlin.math.abs(x)
fun ceil(x: Double): Double = kotlin.math.ceil(x)
fun floor(x: Double): Double = kotlin.math.floor(x)
fun trunc(x: Double): Double = kotlin.math.truncate(x)
fun sqrt(x: Double): Double = kotlin.math.sqrt(x)
fun copysign(f: Double, sign: Double): Double = Double.fromBits((f.toRawBits() and Long.MAX_VALUE) or (sign.toRawBits() and Long.MIN_VALUE))
fun signbit(x: Double): Boolean = x.toRawBits() < 0
fun float64bits(f: Double): ULong = f.toRawBits().toULong()
fun float64frombits(b: ULong): Double = Double.fromBits(b.toLong())
fun float32bits(f: Float): UInt = f.toRawBits().toUInt()
fun float32frombits(b: UInt): Float = Float.fromBits(b.toInt())
fun inf(sign: Int): Double = if (sign >= 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
fun isInf(f: Double, sign: Int): Boolean =
    sign >= 0 && f == Double.POSITIVE_INFINITY || sign <= 0 && f == Double.NEGATIVE_INFINITY
fun isNaN(f: Double): Boolean = f.isNaN()
/** `math.NaN()`: Go's exact NaN (payload 1), which `Float64bits` exposes. */
fun naN(): Double = Double.fromBits(0x7FF8000000000001L)

/** `math.Min`: NaN wins, and `-0 < +0` (identical to Kotlin's `minOf`). */
fun min(x: Double, y: Double): Double = minOf(x, y)

/** `math.Max`. */
fun max(x: Double, y: Double): Double = maxOf(x, y)

/** `math.Mod`: the exact remainder with the sign of `x` (IEEE `fmod`; Kotlin's `%` on doubles). */
fun mod(x: Double, y: Double): Double = x % y

/** `math.Round`: half away from zero. */
fun round(x: Double): Double {
    if (x.isNaN() || x.isInfinite()) return x
    val t = kotlin.math.truncate(x)
    return if (kotlin.math.abs(x - t) >= 0.5) t + kotlin.math.sign(x) else t
}

private fun normalize(x: Double): Pair<Double, Int> =
    if (kotlin.math.abs(x) < 2.2250738585072014e-308) (x * (1L shl 52).toDouble()) to -52 else x to 0

/** `math.Frexp` (Go's algorithm). */
fun frexp(f0: Double): Pair<Double, Int> {
    if (f0 == 0.0 || f0.isInfinite() || f0.isNaN()) return f0 to 0
    val (f, e0) = normalize(f0)
    var x = f.toRawBits()
    val exp = e0 + ((x ushr SHIFT).toInt() and MASK) - BIAS + 1
    x = x and (MASK.toLong() shl SHIFT).inv()
    x = x or ((-1L + BIAS) shl SHIFT)
    return Double.fromBits(x) to exp
}

/** `math.Ldexp` (Go's algorithm, including its denormal path). */
fun ldexp(frac0: Double, exp0: Int): Double {
    if (frac0 == 0.0 || frac0.isInfinite() || frac0.isNaN()) return frac0
    val (frac, e) = normalize(frac0)
    var exp = exp0 + e
    var x = frac.toRawBits()
    exp += ((x ushr SHIFT).toInt() and MASK) - BIAS
    if (exp < -1075) return copysign(0.0, frac)
    if (exp > 1023) return if (frac < 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    var m = 1.0
    if (exp < -1022) {
        exp += 53
        m = 1.0 / (1L shl 53).toDouble()
    }
    x = x and (MASK.toLong() shl SHIFT).inv()
    x = x or ((exp + BIAS).toLong() shl SHIFT)
    return m * Double.fromBits(x)
}

/** `math.Modf` → (int, frac), Go's algorithm (`Modf(-3.0)` is `(-3, -0)`). */
fun modf(f: Double): Pair<Double, Double> {
    if (f < 1) {
        if (f < 0) {
            val (i, fr) = modf(-f)
            return -i to -fr
        }
        if (f == 0.0) return f to f
        return 0.0 to f
    }
    var x = f.toRawBits()
    val e = ((x ushr SHIFT).toInt() and MASK) - BIAS
    if (e < 64 - 12) x = x and ((1L shl (64 - 12 - e)) - 1).inv()
    val int = Double.fromBits(x)
    return int to f - int
}

/**
 * `math.Log` — a port of Go's pure-Go algorithm. APPROXIMATION: Go on amd64 runs an assembly
 * `Log`; they may differ in the last ulp.
 */
fun log(x: Double): Double {
    val ln2Hi = 6.93147180369123816490e-01
    val ln2Lo = 1.90821492927058770002e-10
    val l1 = 6.666666666666735130e-01
    val l2 = 3.999999999940941908e-01
    val l3 = 2.857142874366239149e-01
    val l4 = 2.222219843214978396e-01
    val l5 = 1.818357216161805012e-01
    val l6 = 1.531383769920937332e-01
    val l7 = 1.479819860511658591e-01
    if (x.isNaN() || x == Double.POSITIVE_INFINITY) return x
    if (x < 0) return Double.NaN
    if (x == 0.0) return Double.NEGATIVE_INFINITY
    var (f1, ki) = frexp(x)
    if (f1 < Sqrt2 / 2) {
        f1 *= 2
        ki--
    }
    val f = f1 - 1
    val k = ki.toDouble()
    val s = f / (2 + f)
    val s2 = s * s
    val s4 = s2 * s2
    val t1 = s2 * (l1 + s4 * (l3 + s4 * (l5 + s4 * l7)))
    val t2 = s4 * (l2 + s4 * (l4 + s4 * l6))
    val r = t1 + t2
    val hfsq = 0.5 * f * f
    return k * ln2Hi - ((hfsq - (s * (hfsq + r) + k * ln2Lo)) - f)
}

/** `math.Log2` (Go's algorithm over [log]: exact for powers of two). */
fun log2(x: Double): Double {
    val (frac, exp) = frexp(x)
    if (frac == 0.5) return (exp - 1).toDouble()
    return log(frac) * (1 / Ln2) + exp.toDouble()
}

/** `math.Exp`. APPROXIMATION: Kotlin's `exp`, not Go's (amd64 assembly) `Exp`. */
fun exp(x: Double): Double = kotlin.math.exp(x)

private fun isOddInt(x: Double): Boolean {
    if (kotlin.math.abs(x) >= (1L shl 53).toDouble()) return false
    val (xi, xf) = modf(x)
    return xf == 0.0 && xi.toLong() and 1L == 1L
}

/**
 * `math.Pow` — Go's algorithm. Bit-exact for integer exponents (successive squaring with
 * Frexp/Ldexp); a fractional exponent goes through [exp]/[log] (APPROXIMATION, last ulp).
 */
fun pow(x: Double, y: Double): Double {
    when {
        y == 0.0 || x == 1.0 -> return 1.0
        y == 1.0 -> return x
        x.isNaN() || y.isNaN() -> return Double.NaN
        x == 0.0 -> when {
            y < 0 -> return if (signbit(x) && isOddInt(y)) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            y > 0 -> return if (signbit(x) && isOddInt(y)) x else 0.0
        }
        y.isInfinite() -> return when {
            x == -1.0 -> 1.0
            (kotlin.math.abs(x) < 1) == (y == Double.POSITIVE_INFINITY) -> 0.0
            else -> Double.POSITIVE_INFINITY
        }
        x.isInfinite() -> {
            if (x == Double.NEGATIVE_INFINITY) return pow(1 / x, -y)
            when {
                y < 0 -> return 0.0
                y > 0 -> return Double.POSITIVE_INFINITY
            }
        }
        y == 0.5 -> return kotlin.math.sqrt(x)
        y == -0.5 -> return 1 / kotlin.math.sqrt(x)
    }
    var (yi, yf) = modf(kotlin.math.abs(y))
    if (yf != 0.0 && x < 0) return Double.NaN
    if (yi >= 9.223372036854775807E18) {
        return when {
            x == -1.0 -> 1.0
            (kotlin.math.abs(x) < 1) == (y > 0) -> 0.0
            else -> Double.POSITIVE_INFINITY
        }
    }
    var a1 = 1.0
    var ae = 0
    if (yf != 0.0) {
        if (yf > 0.5) {
            yf--
            yi++
        }
        a1 = exp(yf * log(x))
    }
    var (x1, xe) = frexp(x)
    var i = yi.toLong()
    while (i != 0L) {
        if (xe < -(1 shl 12) || (1 shl 12) < xe) {
            ae += xe
            break
        }
        if (i and 1L == 1L) {
            a1 *= x1
            ae += xe
        }
        x1 *= x1
        xe = xe shl 1
        if (x1 < .5) {
            x1 += x1
            xe--
        }
        i = i shr 1
    }
    if (y < 0) {
        a1 = 1 / a1
        ae = -ae
    }
    return ldexp(a1, ae)
}
