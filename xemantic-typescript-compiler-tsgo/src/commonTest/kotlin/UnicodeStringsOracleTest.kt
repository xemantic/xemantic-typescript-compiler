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
import com.xemantic.typescript.tsgo.go.strings.count
import com.xemantic.typescript.tsgo.go.strings.equalFold
import com.xemantic.typescript.tsgo.go.strings.fields as stringsFields
import com.xemantic.typescript.tsgo.go.strings.indexAny
import com.xemantic.typescript.tsgo.go.strings.map as stringsMap
import com.xemantic.typescript.tsgo.go.strings.replaceAll
import com.xemantic.typescript.tsgo.go.strings.split
import com.xemantic.typescript.tsgo.go.strings.toLower
import com.xemantic.typescript.tsgo.go.strings.toUpper
import com.xemantic.typescript.tsgo.go.strings.toValidUTF8
import com.xemantic.typescript.tsgo.go.strings.trimLeft
import com.xemantic.typescript.tsgo.go.strings.trimRight
import com.xemantic.typescript.tsgo.go.strings.trimRightFunc
import com.xemantic.typescript.tsgo.go.strings.trimSpace
import com.xemantic.typescript.tsgo.go.unicode.`is`
import com.xemantic.typescript.tsgo.go.unicode.isSpace
import com.xemantic.typescript.tsgo.go.unicode.simpleFold
import com.xemantic.typescript.tsgo.go.unicode.toLower as runeToLower
import com.xemantic.typescript.tsgo.go.unicode.toUpper as runeToUpper
import com.xemantic.typescript.tsgo.go.unicode.zs
import com.xemantic.typescript.tsgo.runtime.GoSlice
import kotlin.test.Test

class UnicodeStringsOracleTest {

    private fun joined(s: GoSlice<String>): String = "${s.len}:" + s.toList().joinToString("\u0003")

    @Test
    fun `unicode IsSpace ToLower ToUpper SimpleFold and Zs match Go`() {
        val mismatches = ArrayList<String>()
        for (row in UNICODE) {
            val f = fields(row)
            val r = f[0]!!.toInt()
            val got = listOf(isSpace(r).toString(), runeToLower(r).toString(), runeToUpper(r).toString(), simpleFold(r).toString(), `is`(zs, r).toString())
            if (got != f.subList(1, 6)) mismatches.add("$r: $got != ${f.subList(1, 6)}")
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `strings ToLower and EqualFold match Go on byte strings`() {
        val mismatches = ArrayList<String>()
        for (row in TO_LOWER) {
            val f = fields(row)
            if (toLower(f[0]!!) != f[1]) mismatches.add("ToLower ${f[0]}")
        }
        for (row in EQUAL_FOLD) {
            val f = fields(row)
            if (equalFold(f[0]!!, f[1]!!).toString() != f[2]) mismatches.add("EqualFold ${f[0]} ${f[1]}")
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `strings operations match Go including invalid UTF-8`() {
        val mismatches = ArrayList<String>()
        for (row in STRINGS) {
            val f = fields(row)
            val a = f[1]!!
            val b = f[2]!!
            val got = when (f[0]) {
                "Split" -> joined(split(a, b))
                "ReplaceAll" -> b.split('\u0003').let { replaceAll(a, it[0], it[1]) }
                "TrimLeft" -> trimLeft(a, b)
                "TrimRight" -> trimRight(a, b)
                "TrimRightFuncSpace" -> trimRightFunc(a, ::isSpace)
                "TrimSpace" -> trimSpace(a)
                "Fields" -> joined(stringsFields(a))
                "Count" -> count(a, b).toString()
                "IndexAny" -> indexAny(a, b).toString()
                "ToValidUTF8" -> toValidUTF8(a, b)
                "MapIdentity" -> stringsMap({ it }, a)
                "MapUpperA" -> stringsMap({ if (it == 'a'.code) 'A'.code else it }, a)
                "ToUpper" -> toUpper(a)
                else -> "unknown op ${f[0]}"
            }
            if (got != f[3]) mismatches.add("${f[0]}('$a', '$b'): '$got' != '${f[3]}'")
        }
        assert(mismatches.isEmpty())
    }

}
