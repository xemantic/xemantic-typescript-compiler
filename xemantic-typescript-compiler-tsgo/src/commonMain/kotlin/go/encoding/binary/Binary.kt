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

package com.xemantic.typescript.tsgo.go.encoding.binary

import com.xemantic.typescript.tsgo.runtime.GoSlice

/** `binary.ByteOrder` (the 16-bit half the port reaches). */
interface ByteOrder {
    fun uint16(b: GoSlice<Int>): Int
}

/** The type of `binary.LittleEndian` (Go's unexported `littleEndian`). */
class LittleEndianOrder : ByteOrder {

    /** `binary.LittleEndian.Uint32(b)`. */
    fun uint32(b: GoSlice<Int>): UInt {
        if (b.len < 4) b[3] // Go's bounds-check panic
        return (b[0].toUInt() and 0xFFu) or
            ((b[1].toUInt() and 0xFFu) shl 8) or
            ((b[2].toUInt() and 0xFFu) shl 16) or
            ((b[3].toUInt() and 0xFFu) shl 24)
    }

    /** `binary.LittleEndian.PutUint32(b, v)`. */
    fun putUint32(b: GoSlice<Int>, v: UInt) {
        b[3] = ((v shr 24) and 0xFFu).toInt()
        b[0] = (v and 0xFFu).toInt()
        b[1] = ((v shr 8) and 0xFFu).toInt()
        b[2] = ((v shr 16) and 0xFFu).toInt()
    }

    /** `binary.LittleEndian.AppendUint32(b, v)`. */
    fun appendUint32(b: GoSlice<Int>, v: UInt): GoSlice<Int> =
        b.append(
            (v and 0xFFu).toInt(),
            ((v shr 8) and 0xFFu).toInt(),
            ((v shr 16) and 0xFFu).toInt(),
            ((v shr 24) and 0xFFu).toInt(),
        )

    /** `binary.LittleEndian.Uint64(b)`. */
    fun uint64(b: GoSlice<Int>): ULong {
        if (b.len < 8) b[7]
        var r = 0uL
        for (k in 7 downTo 0) r = (r shl 8) or (b[k].toULong() and 0xFFuL)
        return r
    }

    /** `binary.LittleEndian.PutUint64(b, v)`. */
    fun putUint64(b: GoSlice<Int>, v: ULong) {
        b[7] = ((v shr 56) and 0xFFuL).toInt()
        for (k in 0 until 7) b[k] = ((v shr (8 * k)) and 0xFFuL).toInt()
    }

    /** `binary.LittleEndian.Uint16(b)`. */
    override fun uint16(b: GoSlice<Int>): Int {
        if (b.len < 2) b[1]
        return (b[0] and 0xFF) or ((b[1] and 0xFF) shl 8)
    }

    /** `binary.LittleEndian.PutUint16(b, v)` (`uint16` held in Int). */
    fun putUint16(b: GoSlice<Int>, v: Int) {
        b[1] = (v shr 8) and 0xFF
        b[0] = v and 0xFF
    }

    override fun toString(): String = "LittleEndian"
}

/** `binary.LittleEndian`. */
val littleEndian: LittleEndianOrder = LittleEndianOrder()

/** The type of `binary.BigEndian`. */
class BigEndianOrder : ByteOrder {
    override fun uint16(b: GoSlice<Int>): Int {
        if (b.len < 2) b[1]
        return (b[1] and 0xFF) or ((b[0] and 0xFF) shl 8)
    }

    override fun toString(): String = "BigEndian"
}

/** `binary.BigEndian`. */
val bigEndian: BigEndianOrder = BigEndianOrder()

/**
 * `binary.Read(r, order, data)` for the one shape the port reaches: `data` a pointer to a `[]uint16`
 * (`vfs/internal.decodeUtf16`). Fills the slice from [r]; `io.ErrUnexpectedEOF` on a short read
 * after some bytes, `io.EOF` when nothing was read, as Go's `io.ReadFull`.
 */
fun read(r: com.xemantic.typescript.tsgo.go.io.Reader?, order: ByteOrder?, data: Any?): com.xemantic.typescript.tsgo.runtime.GoError? {
    @Suppress("UNCHECKED_CAST")
    val ptr = data as? com.xemantic.typescript.tsgo.runtime.GoPtr<GoSlice<Int>>
        ?: com.xemantic.typescript.tsgo.runtime.goPanic("binary.Read: unsupported data type (the port carries *[]uint16 only)")
    val out = ptr.value
    val buf = GoSlice.make(com.xemantic.typescript.tsgo.runtime.GoElem.INT, 2 * out.len)
    var n = 0
    while (n < buf.len) {
        val (k, err) = r!!.read(buf.slice(n))
        n += k
        if (err != null) {
            if (n >= buf.len) break
            return if (n == 0) err else com.xemantic.typescript.tsgo.go.io.errUnexpectedEOF
        }
    }
    for (i in 0 until out.len) out[i] = order!!.uint16(buf.slice(2 * i, 2 * i + 2))
    return null
}
