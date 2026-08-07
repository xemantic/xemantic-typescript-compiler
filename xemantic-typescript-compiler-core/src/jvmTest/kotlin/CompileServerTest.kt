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
import com.xemantic.typescript.compiler.server.CompileServer
import java.io.File
import kotlin.test.Test

/**
 * (SERVER.1) Stage 1 compile-server pins.
 *
 * These exercise [CompileServer.respondTo] — the whole server behaviour minus
 * the socket. The socket plumbing is deliberately not bound here: parking a
 * thread in `accept()` inside a 13,000-test suite is a flakiness source, and
 * the framing is the part least likely to break silently. End-to-end transport
 * (four requests over a real Unix socket, warm-up curve, output identical to
 * the direct CLI, both fallback paths) was verified out of band.
 *
 * NOT pinned here, deliberately: "server output is character-identical to the
 * direct CLI". It needs TWO full compiles in one method and the suite runs at
 * Gradle's default 512 MB test heap, where that OOMs — and raising the
 * suite-wide heap for one test is the wrong trade on a memory-tight box. The
 * property is instead guaranteed structurally ([CompileServer.respondTo] calls
 * the ordinary `main` and captures its stdout; nothing re-formats anything) and
 * was verified out of band on the REAL compiler profile, where server output is
 * byte-identical to the CLI's across all 46 diagnostics — stronger evidence than
 * a toy project could give.
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
            CompileServer.CompileRequest(listOf("--noEmit", dir.absolutePath)),
        )
        assert("OK — 0 errors" in response.output)
        assert(response.exitCode == 0)
        dir.deleteRecursively()
    }

    @Test
    fun `a request whose compile reports errors exits non-zero and says so`() {
        val dir = tinyProject()
        File(dir, "a.ts").writeText("export const n: number = 'not a number'\n")
        val response = CompileServer.respondTo(
            CompileServer.CompileRequest(listOf("--noEmit", dir.absolutePath)),
        )
        assert("FAILED —" in response.output)
        assert(response.exitCode == 1)
        dir.deleteRecursively()
    }

    @Test
    fun `--watch is refused rather than wedging the single request thread`() {
        val response = CompileServer.respondTo(
            CompileServer.CompileRequest(listOf("--noEmit", "--watch", ".")),
        )
        assert(response.exitCode == CompileServer.REFUSED)
        assert("--watch is not supported" in response.output)
        // Refusal must be immediate — the point is that it never ran.
        assert(response.elapsedMs == 0L)
    }

    @Test
    fun `the short -w spelling of watch is refused too`() {
        val response = CompileServer.respondTo(
            CompileServer.CompileRequest(listOf("-w", ".")),
        )
        assert(response.exitCode == CompileServer.REFUSED)
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
