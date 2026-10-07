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
import com.xemantic.typescript.tsgo.go.unicode.utf8.decodeLastRuneInString
import com.xemantic.typescript.tsgo.go.unicode.utf8.decodeRuneInString
import com.xemantic.typescript.tsgo.go.unicode.utf8.runeCountInString
import com.xemantic.typescript.tsgo.go.unicode.utf8.validString
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoPanic
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.goCopy
import com.xemantic.typescript.tsgo.runtime.goEq
import com.xemantic.typescript.tsgo.runtime.goFloat64ToInt64
import com.xemantic.typescript.tsgo.runtime.goInt8
import com.xemantic.typescript.tsgo.runtime.goPanic
import com.xemantic.typescript.tsgo.runtime.goRangeString
import com.xemantic.typescript.tsgo.runtime.goRuneToString
import com.xemantic.typescript.tsgo.runtime.goShl
import com.xemantic.typescript.tsgo.runtime.goShr
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import com.xemantic.typescript.tsgo.runtime.goStringToRunes
import com.xemantic.typescript.tsgo.runtime.goUint8
import com.xemantic.typescript.tsgo.runtime.withDefers
import com.xemantic.typescript.tsgo.runtime.withDefersNamed
import kotlin.test.Test

/** A struct stand-in: identity equality, an explicit Go value copy. */
private class Point(var x: Int = 0) {
    fun goCopy(): Point = Point(x)
}

private val POINT = GoElem({ Point() }, { it.goCopy() })

private fun panics(block: () -> Unit): String? = try {
    block()
    null
} catch (p: GoPanic) {
    p.message
}

class GoRuntimeTest {

    @Test
    fun `a sub-slice aliases its parent and append within capacity writes through`() {
        val s = GoSlice.of(GoElem.INT, 1, 2, 3, 4)
        val t = s.slice(1, 2) // len 1, cap 3
        val u = t.append(99) // fits: overwrites s[2]
        assert(s[2] == 99)
        assert(u.len == 2 && u.cap == 3)
        u[0] = 7
        assert(s[1] == 7)
    }

    @Test
    fun `append beyond capacity reallocates and stops aliasing`() {
        val s = GoSlice.of(GoElem.INT, 1, 2)
        val grown = s.append(3)
        assert(grown.cap == 4) // Go: doubling below 256
        grown[0] = 100
        assert(s[0] == 1)
    }

    @Test
    fun `a three-index slice caps capacity so the next append copies`() {
        val s = GoSlice.of(GoElem.INT, 1, 2, 3, 4)
        val t = s.slice3(0, 2, 2)
        assert(t.cap == 2)
        val u = t.append(9)
        assert(s[2] == 3)
        assert(u[2] == 9)
    }

    @Test
    fun `reslicing up to capacity exposes implicit zeros and a struct zero is one object per slot`() {
        val s = GoSlice.make(POINT, 0, 4)
        val full = s.slice(0, 4)
        full[2].x = 5 // materialized zero, stored back
        assert(full[2].x == 5)
        assert(full[1] !== full[3])
        val ints = GoSlice.make(GoElem.INT, 0, 3).slice(0, 3)
        assert(ints[2] == 0)
    }

    @Test
    fun `growth and copy duplicate struct values instead of aliasing them`() {
        val s = GoSlice.of(POINT, Point(1))
        val grown = s.append(Point(2))
        grown[0].x = 42
        assert(s[0].x == 1)
        val dst = GoSlice.make(POINT, 1)
        val n = goCopy(dst, s)
        dst[0].x = 9
        assert(n == 1 && s[0].x == 1)
    }

    @Test
    fun `copy handles overlapping windows like memmove`() {
        val s = GoSlice.of(GoElem.INT, 1, 2, 3, 4, 5)
        goCopy(s.slice(1), s)
        assert(s.toList() == listOf(1, 1, 2, 3, 4))
    }

    @Test
    fun `out of range index and slice bounds panic with Go's messages`() {
        val s = GoSlice.of(GoElem.INT, 1, 2, 3)
        assert(panics { s[3] } == "runtime error: index out of range [3] with length 3")
        assert(panics { s.slice(0, 4) } == "runtime error: slice bounds out of range [:4] with capacity 3")
        assert(panics { s.slice(2, 1) } == "runtime error: slice bounds out of range [2:1]")
    }

    @Test
    fun `a nil slice has length zero appends normally and stays nil when sliced`() {
        val n = GoElem.INT.nilSlice
        assert(n.isNil && n.len == 0)
        assert(n.slice(0, 0).isNil)
        assert(n.append().isNil)
        val a = n.append(1)
        assert(!a.isNil && a[0] == 1)
        assert(!GoSlice.make(GoElem.INT, 0).isNil)
    }

    @Test
    fun `a nil map reads zero values and panics on write`() {
        val m = GoMap.nil<String, Int>(GoElem.INT)
        assert(m["x"] == 0 && m.len == 0)
        val (v, ok) = m.lookup("x")
        assert(v == 0 && !ok)
        m.delete("x")
        assert(panics { m["x"] = 1 } == "assignment to entry in nil map")
    }

    @Test
    fun `map reads of missing keys give zero values and range survives deletion`() {
        val m = GoMap.make<String, Point>(POINT)
        assert(m["missing"].x == 0)
        m["a"] = Point(1)
        m["b"] = Point(2)
        m["c"] = Point(3)
        val seen = ArrayList<String>()
        m.range { k, _ ->
            seen.add(k)
            // deleting every other key during iteration: a deleted, unreached key is not produced
            if (k == seen.first()) for (other in listOf("a", "b", "c")) if (other != k) m.delete(other)
            true
        }
        assert(seen.size == 1)
        val c = m.goClone()
        c[seen[0]].x = 77
        assert(m[seen[0]].x != 77)
    }

    @Test
    fun `utf8 decoding matches Go including invalid sequences and surrogates`() {
        val mismatches = ArrayList<String>()
        for (row in DECODE) {
            val f = fields(row)
            val s = f[0]!!
            val (r1, s1) = decodeRuneInString(s)
            val (r2, s2) = decodeLastRuneInString(s)
            val got = listOf(r1.toString(), s1.toString(), r2.toString(), s2.toString(), validString(s).toString(), runeCountInString(s).toString())
            if (got != f.subList(1, 7)) mismatches.add("${s.map { it.code }}: $got != ${f.subList(1, 7)}")
        }
        assert(mismatches.isEmpty())
    }

    @Test
    fun `byte strings convert to and from UTF-16 with Go's replacement rules`() {
        val e9 = bs(0xC3, 0xA9) // "é" as UTF-8 bytes
        val euro = bs(0xE2, 0x82, 0xAC)
        val grin = bs(0xF0, 0x9F, 0x98, 0x80)
        val replacement = bs(0xEF, 0xBF, 0xBD)
        val utf16 = charArrayOf(0xE9.toChar(), 0x20AC.toChar(), 0xD83D.toChar(), 0xDE00.toChar()).concatToString()
        assert(GoString.fromUtf16(utf16) == e9 + euro + grin)
        val loneSurrogate = charArrayOf('a', 0xD800.toChar(), 'b').concatToString()
        assert(GoString.fromUtf16(loneSurrogate) == "a" + replacement + "b")
        assert(GoString.toUtf16(e9 + bs(0xFF)) == charArrayOf(0xE9.toChar(), 0xFFFD.toChar()).concatToString())
        assert(goRuneToString(0xD800) == replacement)
        assert(goRuneToString(-1) == replacement)
        assert(goStringToRunes("a" + bs(0xFF) + "b").toList() == listOf('a'.code, 0xFFFD, 'b'.code))
        assert(goStringToBytes(bs(0xFF)).toList() == listOf(255))
        val offsets = ArrayList<Int>()
        goRangeString(e9 + bs(0xFF) + "x") { i, _ -> offsets.add(i); true }
        assert(offsets == listOf(0, 2, 3))
    }

    @Test
    fun `integer helpers follow Go's shift and conversion rules`() {
        assert(goShl(1, 32) == 0)
        assert(goShr(-8, 40) == -1)
        assert(goShl(1L, 63) == Long.MIN_VALUE)
        assert(goUint8(300) == 44)
        assert(goInt8(200) == -56)
        assert(goFloat64ToInt64(Double.NaN) == Long.MIN_VALUE)
        assert(goFloat64ToInt64(1e300) == Long.MIN_VALUE)
        assert(goFloat64ToInt64(-3.9) == -3L)
    }

    @Test
    fun `goEq compares floats like Go`() {
        val nan: Any = Double.NaN
        assert(!goEq(nan, nan))
        assert(goEq(-0.0, 0.0))
        assert(goEq("a", "a"))
        assert(!goEq(Point(), Point()))
    }

    @Test
    fun `defers run last in first out and a recovered panic returns onRecovered`() {
        val log = ArrayList<String>()
        val r = withDefers({ "recovered" }) { frame ->
            frame.defer { log.add("first") }
            frame.defer {
                log.add("recover=${frame.recover()}")
            }
            goPanic("boom")
        }
        assert(r == "recovered")
        assert(log == listOf("recover=boom", "first"))
    }

    @Test
    fun `an unrecovered panic propagates after the defers ran`() {
        val log = ArrayList<String>()
        val p = panics {
            withDefers<Unit>({}) { frame ->
                frame.defer { log.add("ran") }
                goPanic("bad")
            }
        }
        assert(p == "bad")
        assert(log == listOf("ran"))
    }

    @Test
    fun `named results modified by a deferred call are returned`() {
        var result = 0
        val r = withDefersNamed({ result }) { frame ->
            frame.defer { result *= 2 }
            result = 21
        }
        assert(r == 42)
    }

    @Test
    fun `a Kotlin runtime failure is recovered as a Go runtime error`() {
        val r = withDefers({ "nil deref" }) { frame ->
            frame.defer { check(frame.recover() != null) }
            val p: String? = null
            p!!.length.toString()
        }
        assert(r == "nil deref")
    }

}
