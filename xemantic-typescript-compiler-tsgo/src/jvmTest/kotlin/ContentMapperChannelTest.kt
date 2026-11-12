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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import kotlin.test.Test

/**
 * (TSGO.7.1) TypeScript 7.1's content mappers talk JSON-RPC to a child process; the harness runs the test mappers
 * in process over `net.Pipe` (contentmappertest.spawner). The whole channel — net.Pipe, bufio, jsonrpc framing,
 * ipc.AsyncConn on both ends, goroutines — answers an `initialize` request.
 */
class ContentMapperChannelTest {

    private val noRequests = object : com.xemantic.typescript.tsgo.ipc.Handler {
        override fun handleNotification(p0: com.xemantic.typescript.tsgo.go.context.Context?, p1: String, p2: GoSlice<Int>) = null
        override fun handleRequest(p0: com.xemantic.typescript.tsgo.go.context.Context?, p1: String, p2: GoSlice<Int>): Tuple2<Any?, com.xemantic.typescript.tsgo.runtime.GoError?> =
            Tuple2(null, com.xemantic.typescript.tsgo.runtime.GoPlainError("unexpected"))
    }

    @Test
    fun `an in-process test mapper answers initialize over the pipe`() {
        val spawner = com.xemantic.typescript.tsgo.testutil.contentmappertest.newSpawner()!!
        val (rwc, spawnErr) = spawner.spawn(GoSlice.of(GoElem.STRING, "lisp-mapper"), "/", null)
        assert(spawnErr == null)
        val client = com.xemantic.typescript.tsgo.ipc.newAsyncConn(rwc, noRequests)!!
        val ctx = com.xemantic.typescript.tsgo.go.context.background()
        com.xemantic.typescript.tsgo.go.sync.goSpawn { client.run(ctx) }
        val (timeoutCtx, cancel) = com.xemantic.typescript.tsgo.go.context.withTimeout(ctx, com.xemantic.typescript.tsgo.go.time.Duration(5_000_000_000L))
        val params = com.xemantic.typescript.tsgo.contentmapper.InitializeParams(
            positionEncodings = GoSlice.of(com.xemantic.typescript.tsgo.contentmapper.PositionEncoding.ELEM, com.xemantic.typescript.tsgo.contentmapper.PositionEncodingUTF8, com.xemantic.typescript.tsgo.contentmapper.PositionEncodingUTF16),
        )
        val (result, err) = client.call(timeoutCtx, "initialize", params)
        cancel()
        val error = err?.error()?.let { GoString.toUtf16(it) }
        assert(error == null)
        val text = GoString.toUtf16(goBytesToString(result))
        assert(text.isNotEmpty())
    }
}
