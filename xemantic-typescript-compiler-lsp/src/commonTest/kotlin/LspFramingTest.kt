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

import com.xemantic.kotlin.test.assert
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test

/**
 * The LSP base-protocol framing, driven through in-memory [Buffer]s — the same
 * calls production stdio goes through.
 */
class LspFramingTest {

    @Test
    fun `a written frame reads back byte-identically`() {
        val buffer = Buffer()
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""
        writeFrame(buffer, body)
        val read = readFrame(buffer)
        assert(read == body)
        assert(buffer.exhausted())
    }

    @Test
    fun `Content-Length counts bytes and not chars`() {
        // "π" is 2 UTF-8 bytes for 1 UTF-16 unit; the astral "𝕏" is 4 bytes
        // for 2 units — a frame counting chars desynchronises on either.
        val body = """{"value":"π 𝕏"}"""
        val byteCount = body.encodeToByteArray().size
        assert(byteCount != body.length)
        val buffer = Buffer()
        writeFrame(buffer, body)
        val raw = buffer.copy().readString()
        assert(raw.startsWith("Content-Length: $byteCount\r\n"))
        val read = readFrame(buffer)
        assert(read == body)
        assert(buffer.exhausted())
    }

    @Test
    fun `two frames back-to-back in one buffer both read in order`() {
        val buffer = Buffer()
        writeFrame(buffer, """{"id":1}""")
        writeFrame(buffer, """{"id":2}""")
        val first = readFrame(buffer)
        val second = readFrame(buffer)
        assert(first == """{"id":1}""")
        assert(second == """{"id":2}""")
        assert(readFrame(buffer) == null)
    }

    @Test
    fun `the exact Content-Length spelling is accepted`() {
        val buffer = Buffer()
        buffer.writeString("Content-Length: 2\r\n\r\n{}")
        assert(readFrame(buffer) == "{}")
    }

    @Test
    fun `header names match case-insensitively`() {
        val buffer = Buffer()
        buffer.writeString("CONTENT-LENGTH: 2\r\n\r\n{}")
        assert(readFrame(buffer) == "{}")
    }

    @Test
    fun `a Content-Type header beside the length is tolerated`() {
        val buffer = Buffer()
        buffer.writeString(
            "Content-Length: 2\r\n" +
                "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n" +
                "\r\n{}",
        )
        assert(readFrame(buffer) == "{}")
    }

    @Test
    fun `end of input at a frame boundary answers null`() {
        assert(readFrame(Buffer()) == null)
    }

    @Test
    fun `a header block without Content-Length is refused`() {
        val buffer = Buffer()
        buffer.writeString("Content-Type: application/vscode-jsonrpc\r\n\r\n{}")
        val refused = try {
            readFrame(buffer)
            false
        } catch (_: LspFramingException) {
            true
        }
        assert(refused)
    }

    @Test
    fun `a stream ending inside the body throws rather than truncating`() {
        val buffer = Buffer()
        buffer.writeString("Content-Length: 10\r\n\r\n{}")
        val truncated = try {
            readFrame(buffer)
            false
        } catch (_: EOFException) {
            true
        }
        assert(truncated)
    }
}
