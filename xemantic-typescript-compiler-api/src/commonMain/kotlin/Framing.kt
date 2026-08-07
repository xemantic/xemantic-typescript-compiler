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

package com.xemantic.typescript.compiler.protocol

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt

/**
 * A response carrying a whole project's diagnostics is large but bounded; this
 * cap exists so that a desynchronised stream — or anything that is not a peer —
 * fails immediately instead of trying to allocate whatever the first four bytes
 * happened to say.
 */
public const val MAX_FRAME_BYTES: Int = 64 * 1024 * 1024

/**
 * Writes one frame: a 4-byte big-endian length followed by that many UTF-8 bytes.
 *
 * Both peers share this codec rather than each spelling out the same four lines,
 * because the two halves are separately-built binaries and a framing change that
 * reaches only one of them presents as a hang, not as a mismatch.
 */
public suspend fun ByteWriteChannel.writeFrame(payload: String) {
    val bytes = payload.encodeToByteArray()
    require(bytes.size <= MAX_FRAME_BYTES) {
        "frame is ${bytes.size} bytes, over the $MAX_FRAME_BYTES-byte limit"
    }
    writeInt(bytes.size)
    writeFully(bytes)
    flush()
}

/** Reads one frame written by [writeFrame]. */
public suspend fun ByteReadChannel.readFrame(): String {
    val size = readInt()
    require(size in 0..MAX_FRAME_BYTES) { "implausible frame size: $size" }
    return readByteArray(size).decodeToString()
}
