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

package com.xemantic.typescript.tsgo.go.strings

import com.xemantic.typescript.tsgo.go.io.EOF
import com.xemantic.typescript.tsgo.go.io.Reader as IoReader
import com.xemantic.typescript.tsgo.go.io.Writer
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.appendRuneBytes
import com.xemantic.typescript.tsgo.runtime.goPanic

/**
 * `strings.Builder`, accumulating a byte string.
 *
 * Every write returns Go's results (`(n, nil)` / `nil`); the lowering discards them when the Go
 * call is an expression statement.
 */
class Builder : Writer {

    private var buf: StringBuilder = StringBuilder()

    /** `b.String()`. */
    fun string(): String = buf.toString()

    /** `b.Len()`. */
    fun len(): Int = buf.length

    /** `b.Cap()` (an approximation: the backing `StringBuilder`'s capacity is not observable in common code). */
    fun cap(): Int = buf.length

    /** `b.Reset()`. */
    fun reset() {
        buf = StringBuilder()
    }

    /** `b.Grow(n)`. */
    fun grow(n: Int) {
        if (n < 0) goPanic("strings.Builder.Grow: negative count")
        buf.ensureCapacity(buf.length + n)
    }

    /** `b.Write(p)`. */
    override fun write(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        for (k in 0 until p.len) buf.append((p[k] and 0xFF).toChar())
        return Tuple2(p.len, null)
    }

    /** `b.WriteByte(c)`. */
    fun writeByte(c: Int): GoError? {
        buf.append((c and 0xFF).toChar())
        return null
    }

    /** `b.WriteRune(r)`. */
    fun writeRune(r: Int): Tuple2<Int, GoError?> {
        val before = buf.length
        appendRuneBytes(buf, r)
        return Tuple2(buf.length - before, null)
    }

    /** `b.WriteString(s)`. */
    fun writeString(s: String): Tuple2<Int, GoError?> {
        buf.append(s)
        return Tuple2(s.length, null)
    }

    /** A Go value copy (Go forbids writing to a copied non-zero Builder; reading it is fine). */
    fun goCopy(): Builder {
        val b = Builder()
        b.buf.append(buf)
        return b
    }

    override fun toString(): String = buf.toString()
}

/** `strings.Reader` (the subset tsgo reaches: `io.Reader`). */
class Reader(private val s: String) : IoReader {

    private var i: Int = 0

    /** `r.Len()`: unread bytes. */
    fun len(): Int = s.length - i

    override fun read(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        if (i >= s.length) return Tuple2(0, EOF)
        val n = minOf(p.len, s.length - i)
        for (k in 0 until n) p[k] = s[i + k].code
        i += n
        return Tuple2(n, null)
    }
}

/** `strings.NewReader(s)`. */
fun newReader(s: String): Reader = Reader(s)
