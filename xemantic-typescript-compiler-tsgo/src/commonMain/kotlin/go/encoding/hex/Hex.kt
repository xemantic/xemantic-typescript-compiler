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
