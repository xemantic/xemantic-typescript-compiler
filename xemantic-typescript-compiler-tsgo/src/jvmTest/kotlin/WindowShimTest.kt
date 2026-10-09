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
import com.xemantic.typescript.tsgo.go.strings.lastIndexAt
import com.xemantic.typescript.tsgo.go.strings.lastIndexByteAt
import com.xemantic.typescript.tsgo.go.strings.lastIndexByteIn
import com.xemantic.typescript.tsgo.go.strings.lastIndexIn
import com.xemantic.typescript.tsgo.runtime.GoPanic
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.goStrEqAt
import com.xemantic.typescript.tsgo.runtime.goStrEqIn
import com.xemantic.typescript.tsgo.runtime.goStrView
import com.xemantic.typescript.tsgo.runtime.goStrWin
import com.xemantic.typescript.tsgo.runtime.goViewByte
import com.xemantic.typescript.tsgo.runtime.goViewSubstring
import com.xemantic.typescript.tsgo.go.unicode.utf8.decodeLastRuneInStringIn
import com.xemantic.typescript.tsgo.go.unicode.utf8.decodeRuneInStringIn
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

    // Go: `LastIndex(s[:9], "*/")`, `LastIndex(s[:8], "*/")`, `LastIndex(s[5:], "*/")`, `LastIndex(s[5:9], "")`,
    // `LastIndex(s[14:], "*/")` → 5 5 8 4 -1; `LastIndexByte(s[:9], '*')`, `LastIndexByte(s[2:5], 'a')`,
    // `LastIndexByte(s[3:], 'z')` → 8 -1 12.
    @Test
    fun `lastIndex windows never look past the window end nor before its start`() {
        assert(lastIndexIn(s, 0, 9, "*/") == 5)
        assert(lastIndexIn(s, 0, 8, "*/") == 5)
        assert(lastIndexAt(s, 5, "*/") == 8)
        assert(lastIndexIn(s, 5, 9, "") == 4)
        assert(lastIndexAt(s, 14, "*/") == -1)
        assert(lastIndexByteIn(s, 0, 9, '*'.code) == 8)
        assert(lastIndexByteIn(s, 2, 5, 'a'.code) == -1)
        assert(lastIndexByteAt(s, 3, 'z'.code) == 12)
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

    // A WINDOW FIELD (docs/goport-lowering.md § 3, "Window fields"): `p.sourceText = p.sourceText[:13]` keeps
    // (s, 0, 13). Go: `t := s[:13]`; `t`, `s[3:7]`, `t[10:13]` → "abc/**/x*/yé" "/**/" "yé"; `t[10:14]` panics.
    @Test
    fun `a window field materializes to the slice and to the base itself when it covers it`() {
        assert(goStrWin(s, 0, s.length) === s)
        assert(goStrWin(s, 0, 13) == GoString.fromUtf16("abc/**/x*/yé"))
        assert(goStrWin(s, 3, 4) == "/**/")
        assert(goViewSubstring(s, 0, 13, 10, 13) == GoString.fromUtf16("yé"))
        assert(panics { goViewSubstring(s, 0, 13, 10, 14) })
        assert(panics { goViewSubstring(s, 0, 13, 11, 10) })
    }

    // Go: `utf8.DecodeRuneInString(s[11:13])`, `(s[11:12])`, `(s[13:13])` → (233,2) (65533,1) (65533,0);
    // `utf8.DecodeLastRuneInString(s[0:13])`, `(s[12:13])`, `(s[3:3])` → (233,2) (65533,1) (65533,0).
    @Test
    fun `rune decoding inside a window never reads past its end nor before its start`() {
        val a = decodeRuneInStringIn(s, 11, 13)
        val b = decodeRuneInStringIn(s, 11, 12)
        val c = decodeRuneInStringIn(s, 13, 13)
        assert(a.first == 233 && a.second == 2)
        assert(b.first == 65533 && b.second == 1)
        assert(c.first == 65533 && c.second == 0)
        val d = decodeLastRuneInStringIn(s, 0, 13)
        val e = decodeLastRuneInStringIn(s, 12, 13)
        val f = decodeLastRuneInStringIn(s, 3, 3)
        assert(d.first == 233 && d.second == 2)
        assert(e.first == 65533 && e.second == 1)
        assert(f.first == 65533 && f.second == 0)
    }
}
