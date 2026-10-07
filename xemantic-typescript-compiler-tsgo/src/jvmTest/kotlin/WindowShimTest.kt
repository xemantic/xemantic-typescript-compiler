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
import com.xemantic.typescript.tsgo.go.strings.containsAnyAt
import com.xemantic.typescript.tsgo.go.strings.containsIn
import com.xemantic.typescript.tsgo.go.strings.hasPrefixAt
import com.xemantic.typescript.tsgo.go.strings.hasPrefixIn
import com.xemantic.typescript.tsgo.go.strings.hasSuffixAt
import com.xemantic.typescript.tsgo.go.strings.indexAnyAt
import com.xemantic.typescript.tsgo.go.strings.indexAt
import com.xemantic.typescript.tsgo.go.strings.indexByteAt
import com.xemantic.typescript.tsgo.go.strings.indexByteIn
import com.xemantic.typescript.tsgo.go.strings.indexIn
import com.xemantic.typescript.tsgo.go.strings.indexRuneAt
import com.xemantic.typescript.tsgo.runtime.GoPanic
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.goStrEqAt
import com.xemantic.typescript.tsgo.runtime.goStrEqIn
import com.xemantic.typescript.tsgo.runtime.goStrView
import com.xemantic.typescript.tsgo.runtime.goViewByte
import kotlin.test.Test

// Pins the substring-elimination rule's runtime (docs/goport-lowering.md § 3): every window
// variant answers what Go answers on the slice. The expected values are what go1.27.1 prints for
// the same expressions (written above each test, over the string `s` below).
class WindowShimTest {

    private val s = GoString.fromUtf16("abc/**/x*/yé*/z")

    private fun panics(f: () -> Unit): Boolean = try {
        f()
        false
    } catch (_: GoPanic) {
        true
    }

    // Go: `HasPrefix(s[3:], "/**")`, `HasPrefix(s[3:5], "/**")`, `HasPrefix(s[3:6], "/**")` → true false true.
    @Test
    fun `hasPrefix windows answer as Go does on the slice`() {
        assert(hasPrefixAt(s, 3, "/**"))
        assert(!hasPrefixIn(s, 3, 5, "/**"))
        assert(hasPrefixIn(s, 3, 6, "/**"))
    }

    // Go: `Index(s[2:], "*/")`, `Index(s[2:7], "*/")`, `Index(s[2:6], "*/")`, `Index(s[7:], "")` → 3 3 -1 0.
    @Test
    fun `index windows answer an index relative to the window and never look past its end`() {
        assert(indexAt(s, 2, "*/") == 3)
        assert(indexIn(s, 2, 7, "*/") == 3)
        assert(indexIn(s, 2, 6, "*/") == -1)
        assert(indexAt(s, 7, "") == 0)
    }

    // Go: `IndexByte(s[1:], 'x')`, `IndexByte(s[1:7], 'x')`, `IndexByte(s[1:8], 'x')` → 6 -1 6.
    @Test
    fun `indexByte windows`() {
        assert(indexByteAt(s, 1, 'x'.code) == 6)
        assert(indexByteIn(s, 1, 7, 'x'.code) == -1)
        assert(indexByteIn(s, 1, 8, 'x'.code) == 6)
    }

    // Go: `IndexAny(s[4:], "yz")`, `IndexAny(s[12:], "z")`, `IndexRune(s[4:], 'é')`, `ContainsAny(s[13:], "é")` → 6 3 7 false.
    @Test
    fun `rune-decoding windows decode from the window start`() {
        assert(indexAnyAt(s, 4, "yz") == 6)
        assert(indexAnyAt(s, 12, "z") == 3)
        assert(indexRuneAt(s, 4, 0xE9) == 7)
        assert(!containsAnyAt(s, 13, GoString.fromUtf16("é")))
    }

    // Go: `HasSuffix(s[14:], "z")`, `HasSuffix(s[15:], "/z")`, `Contains(s[0:3], "c")`, `Contains(s[0:2], "c")` → true false true false.
    @Test
    fun `suffix and contains windows`() {
        assert(hasSuffixAt(s, 14, "z"))
        assert(!hasSuffixAt(s, 15, "/z"))
        assert(containsIn(s, 0, 3, "c"))
        assert(!containsIn(s, 0, 2, "c"))
    }

    // Go: `s[3:5] == "/*"`, `s[3:] == "/**/x*/yé*/z"`, `s[16:] == ""` → true true true.
    @Test
    fun `equality against a window compares in place`() {
        assert(goStrEqIn(s, 3, 5, "/*"))
        assert(goStrEqAt(s, 3, GoString.fromUtf16("/**/x*/yé*/z")))
        assert(goStrEqAt(s, 16, ""))
        assert(!goStrEqIn(s, 3, 5, "/**"))
    }

    // Go: A view local `t := text[pos:end]` read through len/index: `scanWhile(s, 0, 10, b != '*')`, `(s, 8, 16, true)` → 4 3.
    @Test
    fun `a view local reads like the slice it replaces`() {
        fun scanWhile(text: String, pos: Int, end: Int, pred: (Int) -> Boolean): Int {
            val n = goStrView(text, pos, end)
            var i = 0
            while (i < n) {
                val b = goViewByte(text, pos, n, i)
                if (b >= 0x80 || !pred(b)) break
                i++
            }
            return i
        }
        assert(scanWhile(s, 0, 10) { it != '*'.code } == 4)
        assert(scanWhile(s, 8, 16) { true } == 3)
        assert(panics { goViewByte(s, 8, 8, 8) })
    }

    // Go: `Index(s[17:], "x")`, `HasPrefix(s[4:3], "x")`, `s[5:20] == ""` all panic in Go.
    @Test
    fun `out-of-range windows panic where Go panics`() {
        assert(panics { indexAt(s, 17, "x") })
        assert(panics { hasPrefixIn(s, 4, 3, "x") })
        assert(panics { goStrEqIn(s, 5, 20, "") })
        assert(panics { goStrView(s, 5, 20) })
    }
}
