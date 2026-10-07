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

package com.xemantic.typescript.tsgo.runtime

/**
 * A minimal immutable arbitrary-precision natural number (little-endian unsigned 32-bit limbs,
 * no leading zero limbs), for the exact conversions of `strconv` and `math/big`. Kotlin common
 * has no `BigInteger`, and every float conversion here must be correctly rounded to agree with Go
 * bit for bit.
 */
internal class BigNat private constructor(private val mag: IntArray) : Comparable<BigNat> {

    val isZero: Boolean get() = mag.isEmpty()

    val bitLength: Int
        get() = if (mag.isEmpty()) 0 else (mag.size - 1) * 32 + (32 - mag[mag.size - 1].countLeadingZeroBits())

    fun testBit(n: Int): Boolean {
        val limb = n ushr 5
        if (limb >= mag.size) return false
        return (mag[limb] ushr (n and 31)) and 1 != 0
    }

    /** Whether any of the bits `0 until n` is set. */
    fun anyBitBelow(n: Int): Boolean {
        val full = minOf(n ushr 5, mag.size)
        for (i in 0 until full) if (mag[i] != 0) return true
        if (full < mag.size && (n and 31) != 0) {
            val mask = (1 shl (n and 31)) - 1
            if (mag[full] and mask != 0) return true
        }
        return false
    }

    override fun compareTo(other: BigNat): Int {
        if (mag.size != other.mag.size) return mag.size.compareTo(other.mag.size)
        for (i in mag.size - 1 downTo 0) {
            val a = mag[i].toUInt()
            val b = other.mag[i].toUInt()
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is BigNat && compareTo(other) == 0

    override fun hashCode(): Int = mag.contentHashCode()

    operator fun plus(other: BigNat): BigNat {
        val n = maxOf(mag.size, other.mag.size)
        val r = IntArray(n + 1)
        var carry = 0L
        for (i in 0 until n) {
            val s = limb(i) + other.limb(i) + carry
            r[i] = s.toInt()
            carry = s ushr 32
        }
        r[n] = carry.toInt()
        return of(r)
    }

    /** `this - other`; requires `this >= other`. */
    operator fun minus(other: BigNat): BigNat {
        val r = IntArray(mag.size)
        var borrow = 0L
        for (i in mag.indices) {
            var d = limb(i) - other.limb(i) - borrow
            if (d < 0) {
                d += 1L shl 32
                borrow = 1
            } else {
                borrow = 0
            }
            r[i] = d.toInt()
        }
        check(borrow == 0L) { "BigNat underflow" }
        return of(r)
    }

    operator fun times(other: BigNat): BigNat {
        if (isZero || other.isZero) return ZERO
        val r = IntArray(mag.size + other.mag.size)
        for (i in mag.indices) {
            var carry = 0L
            val a = limb(i)
            for (j in other.mag.indices) {
                val t = a * other.limb(j) + (r[i + j].toLong() and MASK) + carry
                r[i + j] = t.toInt()
                carry = t ushr 32
            }
            r[i + other.mag.size] = carry.toInt()
        }
        return of(r)
    }

    /** `this * m + a` for small non-negative `m`, `a` (< 2^31). */
    fun mulAdd(m: Int, a: Int): BigNat {
        val r = IntArray(mag.size + 1)
        var carry = a.toLong()
        for (i in mag.indices) {
            val t = limb(i) * m + carry
            r[i] = t.toInt()
            carry = t ushr 32
        }
        r[mag.size] = carry.toInt()
        return of(r)
    }

    fun shl(n: Int): BigNat {
        if (isZero || n == 0) return this
        val limbs = n ushr 5
        val bits = n and 31
        val r = IntArray(mag.size + limbs + 1)
        for (i in mag.indices) {
            val v = limb(i) shl bits
            r[i + limbs] = r[i + limbs] or v.toInt()
            r[i + limbs + 1] = (v ushr 32).toInt()
        }
        return of(r)
    }

    fun shr(n: Int): BigNat {
        val limbs = n ushr 5
        if (limbs >= mag.size) return ZERO
        val bits = n and 31
        val r = IntArray(mag.size - limbs)
        for (i in r.indices) {
            val lo = limb(i + limbs) ushr bits
            val hi = if (bits == 0) 0L else (limb(i + limbs + 1) shl (32 - bits)) and MASK
            r[i] = (lo or hi).toInt()
        }
        return of(r)
    }

    /** `(this / d, this % d)` for `0 < d < 2^31`. */
    fun divRemSmall(d: Int): Pair<BigNat, Int> {
        val r = IntArray(mag.size)
        var rem = 0L
        for (i in mag.size - 1 downTo 0) {
            val cur = (rem shl 32) or limb(i)
            r[i] = (cur / d).toInt()
            rem = cur % d
        }
        return of(r) to rem.toInt()
    }

    /** `(this / d, this % d)` by binary long division. */
    fun divRem(d: BigNat): Pair<BigNat, BigNat> {
        require(!d.isZero) { "division by zero" }
        if (this < d) return ZERO to this
        if (d.mag.size == 1 && d.mag[0] > 0) {
            val (q, r) = divRemSmall(d.mag[0])
            return q to of(r.toLong())
        }
        val n = bitLength
        val q = IntArray(mag.size)
        var rem = ZERO
        for (bit in n - 1 downTo 0) {
            rem = rem.shl(1)
            if (testBit(bit)) rem = rem + ONE
            if (rem >= d) {
                rem -= d
                q[bit ushr 5] = q[bit ushr 5] or (1 shl (bit and 31))
            }
        }
        return of(q) to rem
    }

    fun pow(e: Int): BigNat {
        var result = ONE
        var base = this
        var k = e
        while (k > 0) {
            if (k and 1 != 0) result *= base
            k = k ushr 1
            if (k > 0) base *= base
        }
        return result
    }

    /** The low 64 bits. */
    fun toULong(): ULong = (limb(0) or (limb(1) shl 32)).toULong()

    fun toString(radix: Int): String {
        if (isZero) return "0"
        val chunk: Int
        val chunkDigits: Int
        if (radix == 10) {
            chunk = 1_000_000_000
            chunkDigits = 9
        } else {
            chunk = radix
            chunkDigits = 1
        }
        val parts = ArrayList<String>()
        var x = this
        while (!x.isZero) {
            val (q, r) = x.divRemSmall(chunk)
            parts.add(r.toString(radix))
            x = q
        }
        val sb = StringBuilder()
        sb.append(parts[parts.size - 1])
        for (i in parts.size - 2 downTo 0) {
            val p = parts[i]
            repeat(chunkDigits - p.length) { sb.append('0') }
            sb.append(p)
        }
        return sb.toString()
    }

    override fun toString(): String = toString(10)

    /**
     * The double nearest to `this * 2^binExp` (plus a sticky bit below it when [sticky]), rounded
     * half to even, with subnormals and overflow to infinity. The second component is `true` when
     * the result overflowed to infinity. With [sticky], the caller guarantees at least 55
     * significant bits, so the sticky tail lies below the rounding bit.
     */
    fun toDouble(binExp: Int, sticky: Boolean): Pair<Double, Boolean> {
        if (isZero) return 0.0 to false
        val len = bitLength
        val e = len - 1 + binExp // value in [2^e, 2^(e+1))
        if (e > 1023) return Double.POSITIVE_INFINITY to true
        val prec = if (e >= -1022) 53 else e + 1074 + 1 // bits kept
        if (prec <= 0) {
            // Below half the smallest subnormal (prec < 0), or in [2^-1075, 2^-1074) (prec == 0):
            // the latter rounds to the smallest subnormal unless exactly half.
            if (prec < 0) return 0.0 to false
            val half = !anyBitBelow(len - 1) // value is exactly 2^e
            return (if (half && !sticky) 0.0 else Double.fromBits(1L)) to false
        }
        val drop = len - prec
        var m: ULong
        var exp2: Int // value = m * 2^exp2
        if (drop <= 0) {
            m = toULong() shl (-drop)
            exp2 = binExp + drop
        } else {
            m = shr(drop).toULong()
            exp2 = binExp + drop
            val roundBit = testBit(drop - 1)
            val rest = anyBitBelow(drop - 1) || sticky
            if (roundBit && (rest || (m and 1uL) != 0uL)) {
                m += 1uL
                // Carry out of a NORMAL mantissa; a subnormal carrying into 2^52 is
                // exactly the smallest normal and is assembled as such below.
                if (m == (1uL shl 53)) {
                    m = m shr 1
                    exp2++
                }
            }
        }
        // Normalize into a double.
        if (m >= (1uL shl 52)) {
            val biased = exp2 + 52 + 1023
            if (biased >= 2047) return Double.POSITIVE_INFINITY to true
            return Double.fromBits((biased.toLong() shl 52) or (m and ((1uL shl 52) - 1uL)).toLong()) to false
        }
        // Subnormal: exp2 is -1074 here.
        return Double.fromBits(m.toLong()) to false
    }

    private fun limb(i: Int): Long = if (i < mag.size) mag[i].toLong() and MASK else 0L

    companion object {
        private const val MASK = 0xFFFFFFFFL

        val ZERO: BigNat = BigNat(IntArray(0))
        val ONE: BigNat = of(1L)

        private fun of(r: IntArray): BigNat {
            var n = r.size
            while (n > 0 && r[n - 1] == 0) n--
            return if (n == 0) ZERO else BigNat(if (n == r.size) r else r.copyOf(n))
        }

        fun of(v: Long): BigNat = ofULong(v.toULong())

        fun ofULong(v: ULong): BigNat = of(intArrayOf(v.toInt(), (v shr 32).toInt()))

        /** Parses [digits] (no sign, no prefix, no separators; every char a valid digit) in [radix]. */
        fun parse(digits: String, radix: Int): BigNat {
            var x = ZERO
            for (c in digits) x = x.mulAdd(radix, digitValue(c))
            return x
        }

        fun digitValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'z' -> c - 'a' + 10
            in 'A'..'Z' -> c - 'A' + 10
            else -> 99
        }

        fun pow10(n: Int): BigNat = of(10L).pow(n)
    }

}
