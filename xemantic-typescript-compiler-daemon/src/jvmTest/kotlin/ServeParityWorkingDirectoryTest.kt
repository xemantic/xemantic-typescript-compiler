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
 * (SERVE.2) round 873 — a request means what it means in the CLIENT's directory.
 *
 * These are the pins for the defect the parity matrix found: a
 * [com.xemantic.typescript.compiler.protocol.CompileRequest] used to carry the
 * argument vector and nothing else, so every
 * relative path in it — above all the project path a user did not type, which
 * the CLI defaults to `"."` — was resolved against the DAEMON's directory.
 * Measured before the fix: `xtsc --daemon --noEmit` in a project full of errors
 * compiled the daemon's own directory and exited **0**, and `--outDir out`
 * emitted the user's JavaScript into it.
 *
 * Each test is written so it FAILS if the request's directory is ignored, not
 * merely if the compile breaks: every one names a file that exists only in the
 * intended project, so "compiled the wrong tree" and "compiled nothing" are
 * different failures. Since (TSGO.7) the directory is the ported tsc's current
 * directory for the request (`TsgoCli.run`): nothing process-global is
 * installed, so there is nothing to restore either.
 */
class ServeParityWorkingDirectoryTest {

    private val ts2322 = "error TS2322: Type 'string' is not assignable to type 'number'."

    private fun tempDir(name: String): File =
        File.createTempFile("xtsc-cwd-$name-", "").let { it.delete(); it.mkdirs(); it }

    private fun project(name: String, source: String): File {
        val dir = tempDir(name)
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "$name.ts").writeText(source)
        return dir
    }

    @Test
    fun `a request with no path argument compiles the client's directory`() {
        val dir = project("here", "export const wrongOnPurpose: number = 'a string'\n")
        try {
            val response = CompileServer.respondTo(
                clientRequest(listOf("--noEmit"), workingDirectory = dir.absolutePath),
            )
            assert(response.output == "here.ts(1,14): $ts2322\n")
            assert(response.exitCode == 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a relative project path resolves against the client's directory`() {
        val parent = tempDir("parent")
        val dir = File(parent, "inner").apply { mkdirs() }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "inner.ts").writeText("export const alsoWrong: number = 'a string'\n")
        try {
            val response = CompileServer.respondTo(
                clientRequest(listOf("--noEmit", "-p", "inner"), workingDirectory = parent.absolutePath),
            )
            assert(response.output == "inner/inner.ts(1,14): $ts2322\n")
            assert(response.exitCode == 1)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `a relative outDir writes into the client's directory, not the daemon's`() {
        val dir = project("emitted", "export const n: number = 1\n")
        try {
            val response = CompileServer.respondTo(
                clientRequest(
                    listOf("--outDir", "does-not-exist-yet"),
                    workingDirectory = dir.absolutePath,
                ),
            )
            assert(response.output == "")
            assert(response.exitCode == 0)
            assert(File(dir, "does-not-exist-yet/emitted.js").readText() == "export const n = 1;\n")
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A directory that does not exist is tsc's business, not the server's: the
     * answer is tsc's for a directory with no `tsconfig.json` (its help text,
     * status 1), never a server failure.
     */
    @Test
    fun `a missing working directory is answered as tsc answers an empty one`() {
        val empty = tempDir("empty")
        try {
            val missing = CompileServer.respondTo(
                clientRequest(listOf("--noEmit"), workingDirectory = "/xtsc-not-a-directory"),
            )
            val none = CompileServer.respondTo(
                clientRequest(listOf("--noEmit"), workingDirectory = empty.absolutePath),
            )
            assert(missing.output == none.output)
            assert(missing.exitCode == 1)
            assert(none.exitCode == 1)
        } finally {
            empty.deleteRecursively()
        }
    }

    @Test
    fun `negative control - an empty working directory falls back to the server's own`() {
        val dir = project("ignored", "export const n: number = 1\n")
        try {
            val response = CompileServer.respondTo(
                clientRequest(listOf("--noEmit", "-p", dir.absolutePath), workingDirectory = ""),
            )
            assert(response.output == "")
            assert(response.exitCode == 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
