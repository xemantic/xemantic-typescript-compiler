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
import com.xemantic.typescript.compiler.protocol.CompileRequest
import com.xemantic.typescript.compiler.protocol.XTSC_PROTOCOL_UNVERSIONED
import com.xemantic.typescript.compiler.protocol.XTSC_PROTOCOL_VERSION
import com.xemantic.typescript.compiler.protocol.XTSC_REFUSED
import com.xemantic.typescript.compiler.protocol.protocolProblem
import com.xemantic.typescript.compiler.server.CompileServer
import java.io.File
import kotlin.test.Test

/**
 * A protocol-mismatched request is refused WITHOUT being compiled.
 *
 * Two independent reasons, and the pins below cover both.
 *
 * **Correctness.** A version-1 request carries no `workingDirectory`, so
 * serving one resolves every relative path — above all the project path the CLI
 * defaults to `"."` — against the DAEMON's directory. That is precisely the
 * round-873 defect the version bump exists to prevent, and the server used to
 * merely LOG the mismatch and then compile anyway.
 *
 * **Cost.** The client's answer to a mismatch is to fall back and compile
 * in-process, so a compile done here is discarded by construction. Measured on
 * the tsc-compiler profile with a stale version-1 native client: every
 * `xtsc --daemon` invocation cost TWO full compiles, 14.6 s against an honest
 * 7.5 s.
 *
 * **Why the positive control is not optional.** The refusal is invisible in an
 * exit code alone (a failing compile also exits non-zero), so each pin asserts
 * that the compiler's own summary line is ABSENT — and the control asserts the
 * very same arguments DO produce it at the current version. Without that
 * control every assertion here would pass just as well against a project that
 * silently compiled to nothing.
 */
class CompileServerProtocolRefusalTest {

    /** A project whose compile is loud: it fails, and it names its own file. */
    private fun loudProject(): File {
        val dir = File.createTempFile("xtsc-refusal-", "").let {
            it.delete(); it.mkdirs(); it
        }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "loud.ts").writeText("export const n: number = 'not a number'\n")
        return dir
    }

    /** The control: these very arguments compile, loudly, at the current version. */
    @Test
    fun `a current-protocol request with these arguments really does compile`() {
        val dir = loudProject()
        try {
            val response = CompileServer.respondTo(
                CompileRequest(
                    args = listOf("--noEmit", dir.absolutePath),
                    protocolVersion = XTSC_PROTOCOL_VERSION,
                ),
            )
            assert("FAILED —" in response.output)
            assert("loud.ts" in response.output)
            assert(response.exitCode == 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a request from an older protocol is refused and never compiled`() {
        val dir = loudProject()
        try {
            val response = CompileServer.respondTo(
                CompileRequest(
                    args = listOf("--noEmit", dir.absolutePath),
                    protocolVersion = XTSC_PROTOCOL_VERSION - 1,
                ),
            )
            assert(response.exitCode == XTSC_REFUSED)
            // The compiler never ran: no summary line, and the project's own
            // file is never named. Both are present in the control above.
            assert("FAILED —" !in response.output)
            assert("OK —" !in response.output)
            assert("loud.ts" !in response.output)
            // Instant, which is the whole cost half of the fix.
            assert(response.elapsedMs == 0L)
            // The refusal says which versions disagree, in both directions.
            assert("${XTSC_PROTOCOL_VERSION - 1}" in response.output)
            assert("$XTSC_PROTOCOL_VERSION" in response.output)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The default-constructed case, which is what every server pin in this
     * module used to be: absent-means-old, so it must be refused too.
     */
    @Test
    fun `a request that predates protocol versioning is refused and never compiled`() {
        val dir = loudProject()
        try {
            val response = CompileServer.respondTo(
                CompileRequest(args = listOf("--noEmit", dir.absolutePath)),
            )
            assert(response.exitCode == XTSC_REFUSED)
            assert("FAILED —" !in response.output)
            assert("loud.ts" !in response.output)
            assert(response.elapsedMs == 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A NEWER peer is refused as well. It cannot be served safely for the same
     * reason an older one cannot: this build does not know what its fields mean.
     */
    @Test
    fun `a request from a newer protocol is refused and never compiled`() {
        val dir = loudProject()
        try {
            val response = CompileServer.respondTo(
                CompileRequest(
                    args = listOf("--noEmit", dir.absolutePath),
                    protocolVersion = XTSC_PROTOCOL_VERSION + 1,
                    workingDirectory = dir.absolutePath,
                ),
            )
            assert(response.exitCode == XTSC_REFUSED)
            assert("FAILED —" !in response.output)
            assert("loud.ts" !in response.output)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The response a client reads back always speaks THIS build's version, so a
     * mismatched client can recognise the refusal for what it is rather than
     * reading it as a compile that found nothing.
     */
    @Test
    fun `a refusal is answered at this build's own protocol version`() {
        val response = CompileServer.respondTo(
            CompileRequest(args = listOf("--noEmit", "."), protocolVersion = 1),
        )
        assert(response.protocolVersion == XTSC_PROTOCOL_VERSION)
        assert(protocolProblem(response.protocolVersion) == null)
    }

    /**
     * The message is printed by BOTH peers — the client about a daemon, the
     * daemon about a client — so it must not name either role. It used to say
     * "the daemon speaks protocol N, this client speaks M", which read exactly
     * backwards in a daemon log and pointed a reader at the wrong binary.
     */
    @Test
    fun `the mismatch message is peer-neutral`() {
        for (version in listOf(XTSC_PROTOCOL_UNVERSIONED, 1, XTSC_PROTOCOL_VERSION + 1)) {
            val problem = protocolProblem(version)
            assert(problem != null)
            assert("this client" !in problem)
            assert("the daemon" !in problem)
            assert("peer" in problem)
        }
    }
}
