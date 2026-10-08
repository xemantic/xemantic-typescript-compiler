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

@file:OptIn(ExperimentalForeignApi::class)

package com.xemantic.typescript.tsgo.go.compress.gzip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/** zlib's inflate with gzip framing (windowBits 15 + 16). */
internal actual fun platformGunzip(gz: ByteArray): ByteArray? {
    if (gz.isEmpty()) return null
    val parts = ArrayList<ByteArray>()
    val chunk = ByteArray(1 shl 16)
    gz.usePinned { input ->
        chunk.usePinned { output ->
            memScoped {
                val strm = alloc<z_stream>()
                platform.posix.memset(strm.ptr, 0, sizeOf<z_stream>().convert())
                if (inflateInit2_(strm.ptr, 15 + 16, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) return null
                try {
                    strm.next_in = input.addressOf(0).reinterpret()
                    strm.avail_in = gz.size.convert()
                    while (true) {
                        strm.next_out = output.addressOf(0).reinterpret()
                        strm.avail_out = chunk.size.convert()
                        val rc = inflate(strm.ptr, Z_NO_FLUSH)
                        if (rc != Z_OK && rc != Z_STREAM_END) return null
                        val n = chunk.size - strm.avail_out.toInt()
                        if (n > 0) parts += chunk.copyOf(n)
                        if (rc == Z_STREAM_END) break
                        if (n == 0 && strm.avail_in.toInt() == 0) return null
                    }
                } finally {
                    inflateEnd(strm.ptr)
                }
            }
        }
    }
    val out = ByteArray(parts.sumOf { it.size })
    var off = 0
    for (p in parts) {
        p.copyInto(out, off)
        off += p.size
    }
    return out
}
