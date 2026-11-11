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

package com.xemantic.typescript.tsgo.go.encoding.hex

import com.xemantic.typescript.tsgo.runtime.GoSlice

private const val HEXTABLE = "0123456789abcdef"

/** `hex.EncodeToString(src)`: lower-case hex of the bytes. */
fun encodeToString(src: GoSlice<Int>): String {
    val sb = StringBuilder(src.len * 2)
    for (i in 0 until src.len) {
        val v = src[i] and 0xFF
        sb.append(HEXTABLE[v shr 4]).append(HEXTABLE[v and 0x0F])
    }
    return sb.toString()
}

private fun fromHexChar(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** `hex.InvalidByteError`'s text: `encoding/hex: invalid byte: %#U`. */
private fun invalidByte(c: Char): com.xemantic.typescript.tsgo.runtime.GoError {
    val b = c.code and 0xFF
    val hex = b.toString(16).uppercase().padStart(4, '0')
    val shown = if (b in 0x20..0x7E) " '${b.toChar()}'" else ""
    return com.xemantic.typescript.tsgo.runtime.GoPlainError("encoding/hex: invalid byte: U+$hex$shown")
}

/**
 * `hex.DecodeString(s)` → (bytes, err): as Go, the bytes decoded before an invalid byte, and for an odd length
 * the invalid-byte error when the last char is not hex, else `ErrLength`.
 */
fun decodeString(s: String): com.xemantic.typescript.tsgo.runtime.Tuple2<GoSlice<Int>, com.xemantic.typescript.tsgo.runtime.GoError?> {
    val out = StringBuilder(s.length / 2)
    fun done(e: com.xemantic.typescript.tsgo.runtime.GoError?) = com.xemantic.typescript.tsgo.runtime.Tuple2(com.xemantic.typescript.tsgo.runtime.goStringToBytes(out.toString()), e)
    var i = 1
    while (i < s.length) {
        val p = fromHexChar(s[i - 1])
        val q = fromHexChar(s[i])
        if (p < 0) return done(invalidByte(s[i - 1]))
        if (q < 0) return done(invalidByte(s[i]))
        out.append(((p shl 4) or q).toChar())
        i += 2
    }
    if (s.length % 2 == 1) {
        if (fromHexChar(s[i - 1]) < 0) return done(invalidByte(s[i - 1]))
        return done(errLength)
    }
    return done(null)
}

/** `hex.ErrLength`. */
val errLength: com.xemantic.typescript.tsgo.runtime.GoError = com.xemantic.typescript.tsgo.runtime.GoPlainError("encoding/hex: odd length hex string")
