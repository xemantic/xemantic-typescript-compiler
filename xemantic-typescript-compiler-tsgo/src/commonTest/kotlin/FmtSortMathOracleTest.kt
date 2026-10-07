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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.go.fmt.sprintf
import com.xemantic.typescript.tsgo.go.math.big.Float
import com.xemantic.typescript.tsgo.go.math.big.Int as BigInt
import com.xemantic.typescript.tsgo.go.math.big.newInt
import com.xemantic.typescript.tsgo.go.math.log2
import com.xemantic.typescript.tsgo.go.math.modf
import com.xemantic.typescript.tsgo.go.math.pow
import com.xemantic.typescript.tsgo.go.slices.sort
import com.xemantic.typescript.tsgo.go.slices.sortFunc
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import kotlin.test.Test

class FmtSortMathOracleTest {

    @Test
    fun `Sprintf matches Go for the formats tsgo uses`() {
        val got = listOf(
            sprintf("%016x%016x", 0xabcuL, 0xdeadbeefcafebabeuL),
            sprintf("\\x%02x", 7),
            sprintf("%q", "a\"b\n" + bs(0x01, 0xFF, 0xC3, 0xA9)),
            sprintf("%v %v %v %d %s", 1.5, 1e21, true, -42, "x"),
        )
        val expected = SPRINTF.map { fields(it)[1] }
        assert(got == expected)
    }

    @Test
    fun `SortFunc reproduces Go's unstable pdqsort order for equal keys`() {
        val mismatches = ArrayList<String>()
        for (row in SORT_FUNC) {
            val f = fields(row)
            val keys = f[0]!!.split(',').map { it.toInt() }
            val s = GoSlice.make(GoElem.ref<IntArray>(), keys.size)
            for ((i, k) in keys.withIndex()) s[i] = intArrayOf(k, i)
            sortFunc(s) { a, b -> a[0] - b[0] }
            val tags = s.toList().joinToString(",") { it[1].toString() }
            if (tags != f[1]) mismatches.add("n=${keys.size}")
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `slices Sort orders floats like Go with NaN first and signed zeros as equal`() {
        val f = fields(SORT_FLOAT[0])
        val s = GoSlice.make(GoElem.DOUBLE, 0).append(*f[0]!!.split(',').map { bitsToDouble(it) }.toTypedArray())
        sort(s)
        val got = s.toList().map { if (it.isNaN()) "NaN" else doubleBits(it) }
        val expected = f[1]!!.split(',').map { if (bitsToDouble(it).isNaN()) "NaN" else it }
        assert(got == expected)
    }

    @Test
    fun `math Pow Modf and Log2 match Go`() {
        val mismatches = ArrayList<String>()
        for (row in MATH) {
            val f = fields(row)
            val x = bitsToDouble(f[0]!!)
            val y = bitsToDouble(f[1]!!)
            if (!sameDouble(pow(x, y), f[2]!!)) mismatches.add("pow(${x}, ${y}) = ${doubleBits(pow(x, y))} != ${f[2]}")
            if (!sameDouble(log2(kotlin.math.abs(x)), f[3]!!)) mismatches.add("log2($x) = ${doubleBits(log2(kotlin.math.abs(x)))} != ${f[3]}")
            val (i, fr) = modf(x * -1.5)
            if (!sameDouble(i, f[4]!!) || !sameDouble(fr, f[5]!!)) mismatches.add("modf(${x * -1.5})")
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `big Int SetString String and Float64 match Go`() {
        val mismatches = ArrayList<String>()
        for (row in BIG) {
            val f = fields(row)
            val (z, ok) = BigInt().setString(f[0]!!, f[1]!!.toInt())
            if (ok.toString() != f[2]) {
                mismatches.add("'${f[0]}' ok=$ok")
                continue
            }
            if (!ok || z == null) continue
            val (v, acc) = z.float64()
            if (z.string() != f[3] || doubleBits(v) != f[4] || acc.value.toString() != f[5]) {
                mismatches.add("'${f[0]}': ${z.string()} ${doubleBits(v)} ${acc.value}")
            }
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `big Int Exp and a 256-bit big Float rounding match Go`() {
        val mismatches = ArrayList<String>()
        for (row in BIG_EXP) {
            val f = fields(row)
            val ri = BigInt().exp(newInt(f[0]!!.toLong()), newInt(f[1]!!.toLong()), null)
            val (v, _) = Float().setPrec(256u).setInt(ri).float64()
            if (ri.string() != f[2] || doubleBits(v) != f[3]) mismatches.add("${f[0]}^${f[1]}: ${doubleBits(v)}")
        }
        assert(mismatches.isEmpty())
    }

}
