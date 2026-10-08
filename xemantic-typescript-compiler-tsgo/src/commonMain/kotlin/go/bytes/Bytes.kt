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

// Parts of this file are translated from the Go standard library (go1.27.1), Copyright The Go
// Authors, used under Go's BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.go.bytes

// Go's `bytes`, what the language service reaches ((TSGO.4-a)): `lsproto` scans a raw JSON object
// through `json.NewDecoder(bytes.NewBuffer(data))`. A `[]byte` is a `GoSlice<Int>` of byte values.

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goCopy

/** `bytes.Buffer`, read side: reads the bytes it was made from. */
class Buffer(private var buf: GoSlice<Int>) : com.xemantic.typescript.tsgo.go.io.Reader {
    private var off = 0

    override fun read(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        if (off >= buf.len) {
            if (p.len == 0) return Tuple2(0, null)
            return Tuple2(0, com.xemantic.typescript.tsgo.go.io.EOF)
        }
        val n = goCopy(p, buf.slice(off))
        off += n
        return Tuple2(n, null)
    }

    /** `Buffer.Len`: the unread byte count. */
    fun len(): Int = buf.len - off
}

/** `bytes.NewBuffer(buf)`. */
fun newBuffer(buf: GoSlice<Int>): Buffer = Buffer(buf)

/** `bytes.Equal(a, b)`. */
fun equal(a: GoSlice<Int>, b: GoSlice<Int>): Boolean {
    if (a.len != b.len) return false
    for (i in 0 until a.len) if (a[i] != b[i]) return false
    return true
}
