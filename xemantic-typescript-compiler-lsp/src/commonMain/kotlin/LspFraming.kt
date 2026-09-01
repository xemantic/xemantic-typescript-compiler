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

package com.xemantic.typescript.compiler.lsp

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * The LSP base protocol's framing over [kotlinx.io] streams.
 *
 * A message is a header block — ASCII lines terminated by `\r\n`, ended by an
 * empty line — followed by exactly `Content-Length` BYTES of UTF-8 JSON. The
 * byte/char distinction is the whole reason this file exists as its own layer:
 * `Content-Length` counts BYTES, a Kotlin `String` counts UTF-16 code units,
 * and conflating them desynchronises the stream at the first non-ASCII
 * character in a payload — after which every later frame is read from the
 * middle of the previous one.
 *
 * Reading and writing take [Source]/[Sink] rather than platform streams so the
 * whole server loop is drivable from a test through in-memory
 * [kotlinx.io.Buffer]s, byte-identically to production stdio.
 */

/**
 * A frame that cannot be read as LSP base protocol — a header block with no
 * `Content-Length`, or one whose value is not a non-negative integer.
 *
 * Unrecoverable by design: framing carries no resynchronisation point, so after
 * a malformed header the only sound move is to stop reading the stream. (An
 * unparseable BODY is different — the frame boundary is intact, so the server
 * answers `-32700` and keeps serving; see [XtscLanguageServer].)
 */
internal class LspFramingException(message: String) : Exception(message)

/**
 * Reads one framed message body from [source], or null at a clean end of
 * stream — end of input at a frame BOUNDARY, before any header byte.
 *
 * Tolerates any other header beside `Content-Length` (`Content-Type` is the one
 * the spec names) and matches header names case-insensitively; the spec spells
 * `Content-Length` exactly, and the tolerance is deliberate slack for clients
 * that do not.
 *
 * @throws LspFramingException when the header block carries no usable
 *   `Content-Length`.
 * @throws kotlinx.io.EOFException when the stream ends INSIDE a frame — mid
 *   header line or before `Content-Length` bytes of body arrived. Distinct from
 *   the null return on purpose: a truncated frame is a broken peer, not a
 *   finished conversation.
 */
internal fun readFrame(source: Source): String? {
    var contentLength = -1
    var sawAnyHeader = false
    while (true) {
        val line = readHeaderLine(source, atFrameBoundary = !sawAnyHeader) ?: return null
        sawAnyHeader = true
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val name = line.substring(0, colon).trim()
        if (name.equals("Content-Length", ignoreCase = true)) {
            val value = line.substring(colon + 1).trim()
            contentLength = value.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw LspFramingException("malformed Content-Length: $value")
        }
    }
    if (contentLength < 0) throw LspFramingException("header block without Content-Length")
    // readString decodes exactly contentLength BYTES as UTF-8 — the one place
    // the byte count and the char count meet.
    return source.readString(contentLength.toLong())
}

/**
 * One header line without its terminator, `\r` stripped; null at end of input
 * when [atFrameBoundary] — i.e. when no byte of this frame has been read yet.
 *
 * Headers are ASCII by protocol, so bytes map to chars directly; a stray
 * non-ASCII byte lands as its Latin-1 char in a header nobody parses.
 */
private fun readHeaderLine(source: Source, atFrameBoundary: Boolean): String? {
    if (source.exhausted()) {
        if (atFrameBoundary) return null
        throw kotlinx.io.EOFException("stream ended inside a header block")
    }
    val sb = StringBuilder()
    while (true) {
        if (source.exhausted()) {
            throw kotlinx.io.EOFException("stream ended inside a header line")
        }
        val b = source.readByte().toInt() and 0xFF
        if (b == '\n'.code) break
        sb.append(b.toChar())
    }
    if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
    return sb.toString()
}

/**
 * Writes [json] to [sink] as one framed message and flushes, so a response is
 * on the wire before the server blocks on the next read.
 *
 * The body is encoded FIRST and the byte array's size — never the string's
 * length — is what `Content-Length` announces.
 */
internal fun writeFrame(sink: Sink, json: String) {
    val body = json.encodeToByteArray()
    sink.writeString("Content-Length: ${body.size}\r\n\r\n")
    sink.write(body)
    sink.flush()
}
