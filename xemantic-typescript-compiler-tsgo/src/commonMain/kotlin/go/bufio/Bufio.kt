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

package com.xemantic.typescript.tsgo.go.bufio


import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2

// Go's `bufio` — the subset TypeScript 7.1's `jsonrpc` framing uses (content mappers talk JSON-RPC to a child
// process): a buffered `Reader` (`ReadBytes`, `Read`) and `Writer` (`Write`, `WriteString`, `Flush`).

private const val DEFAULT_SIZE = 4096

/** `bufio.Reader`. */
class Reader(private val rd: com.xemantic.typescript.tsgo.go.io.Reader?) : com.xemantic.typescript.tsgo.go.io.Reader {
    private val buf: GoSlice<Int> = GoSlice.make(GoElem.BYTE, DEFAULT_SIZE)
    private var r = 0
    private var w = 0
    private var err: GoError? = null

    private fun fill() {
        if (r > 0) {
            for (i in r until w) buf[i - r] = buf[i]
            w -= r
            r = 0
        }
        val (n, e) = (rd ?: throw NullPointerException("bufio: nil reader")).read(buf.slice(w))
        w += n
        if (e != null) err = e
    }

    private fun takeErr(): GoError? = err.also { err = null }

    /** `ReadBytes(delim)`: through the first [delim]; the bytes read and the error when none was found. */
    fun readBytes(delim: Int): Tuple2<GoSlice<Int>, GoError?> {
        var out = GoElem.BYTE.nilSlice
        while (true) {
            for (i in r until w) if (buf[i] == (delim and 0xFF)) {
                out = out.appendSlice(buf.slice(r, i + 1))
                r = i + 1
                return Tuple2(out, null)
            }
            out = out.appendSlice(buf.slice(r, w))
            r = w
            if (err != null) return Tuple2(out, takeErr())
            fill()
        }
    }

    override fun read(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        if (p.len == 0) return Tuple2(0, if (r < w) null else takeErr())
        if (r == w) {
            if (err != null) return Tuple2(0, takeErr())
            fill()
            if (r == w) return Tuple2(0, takeErr())
        }
        val n = minOf(p.len, w - r)
        for (i in 0 until n) p[i] = buf[r + i]
        r += n
        return Tuple2(n, null)
    }
}

/** `bufio.NewReader(rd)`. */
fun newReader(rd: com.xemantic.typescript.tsgo.go.io.Reader?): Reader = Reader(rd)

/** `bufio.Writer`: buffers until [flush] (or the buffer fills); the first write error sticks. */
class Writer(private val wr: com.xemantic.typescript.tsgo.go.io.Writer?) :
    com.xemantic.typescript.tsgo.go.io.Writer, com.xemantic.typescript.tsgo.go.io.StringWriter {
    private var pending: GoSlice<Int> = GoElem.BYTE.nilSlice
    private var err: GoError? = null

    override fun write(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        err?.let { return Tuple2(0, it) }
        pending = pending.appendSlice(p)
        if (pending.len >= DEFAULT_SIZE) flush()?.let { return Tuple2(0, it) }
        return Tuple2(p.len, null)
    }

    override fun writeString(s: String): Tuple2<Int, GoError?> = write(com.xemantic.typescript.tsgo.runtime.goStringToBytes(s))

    /** `Flush()`. */
    fun flush(): GoError? {
        err?.let { return it }
        if (pending.len == 0) return null
        val (n, e) = (wr ?: throw NullPointerException("bufio: nil writer")).write(pending)
        val failure = e ?: if (n < pending.len) com.xemantic.typescript.tsgo.go.io.errShortWrite else null
        if (failure != null) { err = failure; return failure }
        pending = GoElem.BYTE.nilSlice
        return null
    }
}

/** `bufio.NewWriter(w)`. */
fun newWriter(w: com.xemantic.typescript.tsgo.go.io.Writer?): Writer = Writer(w)


