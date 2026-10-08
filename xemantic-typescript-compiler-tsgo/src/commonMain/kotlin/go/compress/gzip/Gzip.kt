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

package com.xemantic.typescript.tsgo.go.compress.gzip

import com.xemantic.typescript.tsgo.go.io.Reader as IoReader
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2

/**
 * `gzip.Reader`: reached from `diagnostics` loading a localized message bundle (`tsc --locale de`, (TSGO.5)).
 * The whole compressed stream is read from the source on the first [read] and decompressed by the platform
 * (`actual`: java.util.zip on the JVM); then the decompressed bytes are served.
 */
class Reader internal constructor(private val source: IoReader?) : IoReader {
    private var data: ByteArray? = null
    private var offset = 0

    override fun read(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        val d = data ?: inflate().also { data = it }
            ?: return Tuple2(0, com.xemantic.typescript.tsgo.runtime.GoPlainError("gzip: invalid header"))
        if (offset >= d.size) return Tuple2(0, com.xemantic.typescript.tsgo.go.io.EOF)
        val n = minOf(p.len, d.size - offset)
        for (i in 0 until n) p[i] = d[offset + i].toInt() and 0xFF
        offset += n
        return Tuple2(n, null)
    }

    private fun inflate(): ByteArray? {
        val raw = ArrayList<Byte>()
        val buf = GoSlice.make(com.xemantic.typescript.tsgo.runtime.GoElem.BYTE, 1 shl 16)
        while (true) {
            val (n, err) = source!!.read(buf)
            for (i in 0 until n) raw += buf[i].toByte()
            if (err != null) break
        }
        return platformGunzip(raw.toByteArray())
    }

    fun close(): GoError? = null
}

/** `gzip.NewReader(r)`. Go reads the header here; the port reports a bad stream on the first Read. */
fun newReader(r: IoReader?): Tuple2<Reader?, GoError?> = Tuple2(Reader(r), null)

/** The decompressed content of a gzip stream; null when [gz] is not one. */
internal expect fun platformGunzip(gz: ByteArray): ByteArray?
