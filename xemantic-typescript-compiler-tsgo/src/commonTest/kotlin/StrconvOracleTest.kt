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
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.marshal
import com.xemantic.typescript.tsgo.go.strconv.atoi
import com.xemantic.typescript.tsgo.go.strconv.formatFloat
import com.xemantic.typescript.tsgo.go.strconv.parseFloat
import com.xemantic.typescript.tsgo.go.strconv.parseInt
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import kotlin.test.Test

class StrconvOracleTest {

    @Test
    fun `shortest FormatFloat e f g and json Marshal match Go`() {
        val mismatches = ArrayList<String>()
        for (row in SHORTEST) {
            val f = fields(row)
            val v = bitsToDouble(f[0]!!)
            val e = formatFloat(v, 'e'.code, -1, 64)
            if (e != f[1]) mismatches.add("${f[0]} e: $e != ${f[1]}")
            if (f[2] != "SKIP") {
                val ff = formatFloat(v, 'f'.code, -1, 64)
                if (ff != f[2]) mismatches.add("${f[0]} f: $ff != ${f[2]}")
            }
            val g = formatFloat(v, 'g'.code, -1, 64)
            if (g != f[3]) mismatches.add("${f[0]} g: $g != ${f[3]}")
            if (f[4] != null) {
                val (bytes, err) = marshal(v)
                val j = goBytesToString(bytes)
                if (err != null || j != f[4]) mismatches.add("${f[0]} json: $j != ${f[4]}")
            }
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `fixed precision FormatFloat matches Go including half-even ties`() {
        val mismatches = ArrayList<String>()
        for (row in FIXED) {
            val f = fields(row)
            val v = bitsToDouble(f[0]!!)
            val out = formatFloat(v, f[1]!![0].code, f[2]!!.toInt(), 64)
            if (out != f[3]) mismatches.add("${f[0]} %${f[1]}.${f[2]}: $out != ${f[3]}")
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `ParseFloat matches Go bits and errors`() {
        val mismatches = ArrayList<String>()
        for (row in PARSE) {
            val f = fields(row)
            val (v, err) = parseFloat(f[0]!!, 64)
            val errText = err?.error()
            if (!sameDouble(v, f[1]!!) || errText != f[2]) {
                mismatches.add("'${f[0]}': ${doubleBits(v)} / $errText != ${f[1]} / ${f[2]}")
            }
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `ParseInt matches Go values and errors`() {
        val mismatches = ArrayList<String>()
        for (row in PARSE_INT) {
            val f = fields(row)
            val (v, err) = parseInt(f[0]!!, f[1]!!.toInt(), f[2]!!.toInt())
            if (v.toString() != f[3] || err?.error() != f[4]) mismatches.add("${f[0]}: $v / ${err?.error()} != ${f[3]} / ${f[4]}")
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `Atoi matches Go truncated to the 32-bit int representation`() {
        val mismatches = ArrayList<String>()
        for (row in ATOI) {
            val f = fields(row)
            val (v, err) = atoi(f[0]!!)
            if (v.toString() != f[1] || err?.error() != f[2]) mismatches.add("${f[0]}: $v / ${err?.error()} != ${f[1]} / ${f[2]}")
        }
        assert(mismatches.isEmpty())
    }

}
