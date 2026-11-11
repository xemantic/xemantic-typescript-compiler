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

package com.xemantic.typescript.tsgo.go.io

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2

/** `io.Reader`. */
interface Reader {
    fun read(p: GoSlice<Int>): Tuple2<Int, GoError?>
}

/** `io.Writer`. */
interface Writer {
    fun write(p: GoSlice<Int>): Tuple2<Int, GoError?>
}

/** `io.Closer`. */
interface Closer {
    fun close(): GoError?
}

/** `io.ReadCloser`, `io.WriteCloser`, `io.ReadWriter`, `io.ReadWriteCloser`. */
interface ReadCloser : Reader, Closer
interface WriteCloser : Writer, Closer
interface ReadWriter : Reader, Writer
interface ReadWriteCloser : ReadWriter, ReadCloser, WriteCloser

/** `io.ReadFull(r, buf)`: reads exactly `len(buf)` bytes; `ErrUnexpectedEOF` on a short read, `EOF` on none. */
fun readFull(r: Reader?, buf: GoSlice<Int>): Tuple2<Int, GoError?> {
    val rd = r ?: throw NullPointerException("io.ReadFull: nil reader")
    var n = 0
    var err: GoError? = null
    while (n < buf.len && err == null) {
        val (k, e) = rd.read(buf.slice(n))
        n += k
        err = e
    }
    return when {
        n >= buf.len -> Tuple2(n, null)
        n > 0 && err === EOF -> Tuple2(n, errUnexpectedEOF)
        else -> Tuple2(n, err)
    }
}

/** `io.StringWriter`. */
interface StringWriter {
    fun writeString(s: String): Tuple2<Int, GoError?>
}

/** `io.EOF`. */
val EOF: GoError = GoPlainError("EOF")

/** `io.ErrUnexpectedEOF`. */
val errUnexpectedEOF: GoError = GoPlainError("unexpected EOF")

/** `io.ErrShortWrite`. */
val errShortWrite: GoError = GoPlainError("short write")

/** `io.EOF` under its lowered Kotlin name (`EOF` → `eof`): the same error value. */
val eof: GoError get() = EOF

/** `io.Discard`: a Writer on which every write succeeds and is dropped. */
val discard: Writer = object : Writer, StringWriter {
    override fun write(p: GoSlice<Int>): Tuple2<Int, GoError?> = Tuple2(p.len, null)
    override fun writeString(s: String): Tuple2<Int, GoError?> = Tuple2(s.length, null)
}
