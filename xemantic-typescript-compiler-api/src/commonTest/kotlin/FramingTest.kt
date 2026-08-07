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

import com.xemantic.kotlin.test.assert
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class FramingTest {

    @Test
    fun `should round-trip one frame`() = runBlocking {
        val channel = ByteChannel()
        channel.writeFrame("hello")
        assert(channel.readFrame() == "hello")
    }

    // The length prefix is what lets two messages share one connection without
    // a delimiter; without it the second read would consume the first frame's
    // tail and the stream would desynchronise silently.
    @Test
    fun `should keep consecutive frames separate`() = runBlocking {
        val channel = ByteChannel()
        channel.writeFrame("first")
        channel.writeFrame("second")
        assert(channel.readFrame() == "first")
        assert(channel.readFrame() == "second")
    }

    // The prefix counts BYTES, so a payload whose character count differs from
    // its byte count is the case that catches a length computed on the string.
    @Test
    fun `should frame a multi-byte payload by its byte length`() = runBlocking {
        val payload = "zażółć gęślą jaźń - 日本語"
        val channel = ByteChannel()
        channel.writeFrame(payload)
        assert(channel.readFrame() == payload)
    }

    @Test
    fun `should round-trip an empty frame`() = runBlocking {
        val channel = ByteChannel()
        channel.writeFrame("")
        assert(channel.readFrame() == "")
    }

    // A whole project's diagnostics is the real payload, so the codec has to
    // survive a frame far larger than any socket buffer.
    @Test
    fun `should round-trip a payload larger than a socket buffer`() = runBlocking {
        val payload = "error TS2339: Property 'x' does not exist.\n".repeat(20_000)
        val channel = ByteChannel()
        channel.writeFrame(payload)
        assert(channel.readFrame() == payload)
    }

    @Test
    fun `should round-trip a serialized compile response`() = runBlocking {
        val response = CompileResponse(
            output = "OK - 0 errors\n",
            exitCode = 0,
            elapsedMs = 11580,
            protocolVersion = XTSC_PROTOCOL_VERSION
        )
        val channel = ByteChannel()
        channel.writeFrame(xtscProtocolJson.encodeToString(response))
        val decoded = xtscProtocolJson.decodeFromString<CompileResponse>(channel.readFrame())
        assert(decoded == response)
    }

}
