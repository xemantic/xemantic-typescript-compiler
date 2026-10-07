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

package com.xemantic.typescript.goport.lower

import com.xemantic.typescript.goport.emit.Ex
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.decodeB64
import com.xemantic.typescript.goport.ir.str
import java.math.BigDecimal
import java.math.BigInteger

/** Constant emission from the IR's exact values (`c`); the lowering never re-evaluates constants. */
object Literals {

    /** A Kotlin string literal holding [bytes] as a byte string (docs/goport-design.md § 3). */
    fun byteString(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size + 2)
        sb.append('"')
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == '"'.code -> sb.append("\\\"")
                c == '\\'.code -> sb.append("\\\\")
                c == '$'.code -> sb.append("\\$")
                c == '\n'.code -> sb.append("\\n")
                c == '\t'.code -> sb.append("\\t")
                c == '\r'.code -> sb.append("\\r")
                c in 0x20..0x7E -> sb.append(c.toChar())
                else -> sb.append("\\u00").append(Integer.toHexString(c).padStart(2, '0').uppercase())
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** The exact integer of a constant (an int constant, or a float constant with integral value). */
    fun intValue(c: Node): BigInteger = when (c.str("kind")) {
        "int" -> BigInteger(c.str("v")!!)
        "float" -> {
            val v = c.str("v")!!
            if ('/' in v) {
                val (n, d) = v.split('/')
                BigInteger(n).divide(BigInteger(d))
            } else BigDecimal(v).toBigIntegerExact()
        }
        else -> refuse("const-not-int", c.str("kind") ?: "?")
    }

    fun doubleValue(c: Node): Double = when (c.str("kind")) {
        "int" -> BigInteger(c.str("v")!!).toDouble()
        "float" -> c.str("f64")?.toDouble() ?: c.str("v")!!.let { v ->
            if ('/' in v) v.split('/').let { BigDecimal(it[0]).divide(BigDecimal(it[1]), java.math.MathContext.DECIMAL128).toDouble() } else v.toDouble()
        }
        else -> refuse("const-not-float")
    }

    private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)

    /** A raw literal of representation [rep] (not wrapped in a value class). */
    fun raw(c: Node, rep: TypeMapper.Rep): Ex = when (rep) {
        TypeMapper.Rep.BOOL -> Ex.primary(c.str("v")!!)
        TypeMapper.Rep.STRING -> Ex.primary(byteString(decodeB64(c.str("b64") ?: "")))
        TypeMapper.Rep.INT -> {
            val v = intValue(c)
            when {
                v == INT_MIN -> Ex.primary("Int.MIN_VALUE")
                v.bitLength() > 31 -> {
                    // An untyped constant reaching an `int` slot out of 32-bit range: the 32-bit
                    // `int` assumption (design § 3) does not hold for this value.
                    refuse("int-overflow", v.toString())
                }
                v.signum() < 0 -> Ex("$v", Ex.PREFIX)
                else -> Ex.primary("$v")
            }
        }
        TypeMapper.Rep.LONG -> {
            val v = intValue(c)
            when {
                v == LONG_MIN -> Ex.primary("Long.MIN_VALUE")
                v.signum() < 0 -> Ex("${v}L", Ex.PREFIX)
                else -> Ex.primary("${v}L")
            }
        }
        TypeMapper.Rep.UINT -> Ex.primary("${intValue(c).and(BigInteger.valueOf(0xFFFFFFFFL))}u")
        TypeMapper.Rep.ULONG -> Ex.primary("${intValue(c).and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))}uL")
        TypeMapper.Rep.DOUBLE -> double(doubleValue(c))
        TypeMapper.Rep.FLOAT -> {
            val d = doubleValue(c).toFloat()
            if (d.isNaN() || d.isInfinite()) refuse("float-special")
            val s = d.toString()
            if (s.startsWith("-")) Ex("${s}f", Ex.PREFIX) else Ex.primary("${s}f")
        }
        TypeMapper.Rep.UNSAFE -> refuse("unsafe-const")
    }

    fun double(d: Double): Ex {
        if (d.isNaN()) return Ex.primary("Double.NaN")
        if (d.isInfinite()) return Ex.primary(if (d > 0) "Double.POSITIVE_INFINITY" else "Double.NEGATIVE_INFINITY")
        val s = d.toString() // Java's shortest-repr-ish; always has '.' or 'E'
        return if (s.startsWith("-")) Ex(s, Ex.PREFIX) else Ex.primary(s)
    }
}
