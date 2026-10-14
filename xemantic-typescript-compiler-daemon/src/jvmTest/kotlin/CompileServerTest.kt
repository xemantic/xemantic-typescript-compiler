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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.protocol.XTSC_REFUSED
import com.xemantic.typescript.compiler.server.CompileServer
import java.io.File
import kotlin.test.Test

/**
 * (SERVER.1) Stage 1 compile-server pins.
 *
 * These exercise [CompileServer.respondTo] — the whole server behaviour minus
 * the socket. The socket plumbing is deliberately not bound here: parking a
 * thread in `accept()` inside the suite is a flakiness source, and the framing
 * is the part least likely to break silently.
 *
 * The server runs the ported tsc ((TSGO.7)), so each response is asserted
 * whole against what `tools/tsgo-7.0.2/lib/tsc` prints for the same project
 * (non-pretty: stdout is not a terminal) and its exit status.
 */
class CompileServerTest {

    private fun tinyProject(): File {
        val dir = File.createTempFile("xtsc-server-", "").let {
            it.delete(); it.mkdirs(); it
        }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        return dir
    }

    @Test
    fun `a compile request returns the compiler's own output and a zero exit`() {
        val dir = tinyProject()
        File(dir, "a.ts").writeText("export const n: number = 1\n")
        val response = CompileServer.respondTo(
            clientRequest(listOf("--noEmit"), workingDirectory = dir.absolutePath),
        )
        assert(response.output == "")
        assert(response.exitCode == 0)
        dir.deleteRecursively()
    }

    @Test
    fun `a request whose compile reports errors exits non-zero and says so`() {
        val dir = tinyProject()
        File(dir, "a.ts").writeText("export const n: number = 'not a number'\n")
        val response = CompileServer.respondTo(
            clientRequest(listOf("--noEmit"), workingDirectory = dir.absolutePath),
        )
        assert(response.output == "a.ts(1,14): error TS2322: Type 'string' is not assignable to type 'number'.\n")
        assert(response.exitCode == 1)
        dir.deleteRecursively()
    }

    /**
     * Nothing a request does is left behind for the next one: the same
     * server answers a failing project and then a clean one as two separate
     * `tsc` runs would.
     */
    @Test
    fun `consecutive requests are independent compiles`() {
        val failing = tinyProject()
        File(failing, "a.ts").writeText("export const n: number = 'not a number'\n")
        val clean = tinyProject()
        File(clean, "a.ts").writeText("export const n: number = 1\n")
        val first = CompileServer.respondTo(clientRequest(listOf("--noEmit"), workingDirectory = failing.absolutePath))
        val second = CompileServer.respondTo(clientRequest(listOf("--noEmit"), workingDirectory = clean.absolutePath))
        val third = CompileServer.respondTo(clientRequest(listOf("--noEmit"), workingDirectory = failing.absolutePath))
        assert(first.exitCode == 1)
        assert(second.output == "")
        assert(second.exitCode == 0)
        assert(third.output == first.output)
        assert(third.exitCode == 1)
        failing.deleteRecursively()
        clean.deleteRecursively()
    }

    @Test
    fun `--watch is refused rather than wedging the single request thread`() {
        val response = CompileServer.respondTo(
            clientRequest(listOf("--noEmit", "--watch", ".")),
        )
        assert(response.exitCode == XTSC_REFUSED)
        assert("--watch is not supported" in response.output)
        // Refusal must be immediate — the point is that it never ran.
        assert(response.elapsedMs == 0L)
    }

    @Test
    fun `the short -w spelling of watch is refused too`() {
        val response = CompileServer.respondTo(
            clientRequest(listOf("-w", ".")),
        )
        assert(response.exitCode == XTSC_REFUSED)
    }

    @Test
    fun `an unreachable socket yields null so the client can fall back`() {
        val absent = "/tmp/xtsc-definitely-not-listening-${ProcessHandle.current().pid()}.sock"
        assert(CompileServer.request(listOf("--noEmit", "."), absent) == null)
    }

    @Test
    fun `an over-long socket path yields null rather than throwing`() {
        // Regression: the length guard first landed as a `require`, which
        // crashed the client instead of letting it fall back to compiling
        // in-process — the documented contract of --daemon.
        val tooLong = "/tmp/" + "x".repeat(200) + ".sock"
        assert(CompileServer.request(listOf("--noEmit", "."), tooLong) == null)
    }

    @Test
    fun `the default socket path is per-user and within the OS length limit`() {
        val path = CompileServer.defaultSocketPath()
        assert(path.endsWith(".sock"))
        assert(System.getProperty("user.name")!! in path)
        assert(path.toByteArray().size <= 100)
    }

}
