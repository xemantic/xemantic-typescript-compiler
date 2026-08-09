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
 * Each test is written so it FAILS if the install is removed, not merely if the
 * compile breaks: every one names a symbol that exists only in the intended
 * project, so "compiled the wrong tree" and "compiled nothing" are different
 * failures.
 */
class ServeParityWorkingDirectoryTest {

    private fun project(name: String, source: String): File {
        val dir = File.createTempFile("xtsc-cwd-$name-", "").let {
            it.delete(); it.mkdirs(); it
        }
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
            // The file is only in THIS project, so naming it is proof the right
            // tree was crawled — an exit code alone could not tell that from a
            // different project that also fails.
            assert("here.ts" in response.output)
            assert(response.exitCode == 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a relative path argument resolves against the client's directory`() {
        val parent = File.createTempFile("xtsc-cwd-parent-", "").let {
            it.delete(); it.mkdirs(); it
        }
        val dir = File(parent, "inner").apply { mkdirs() }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "inner.ts").writeText("export const alsoWrong: number = 'a string'\n")
        try {
            val response = CompileServer.respondTo(
                clientRequest(listOf("--noEmit", "inner"), workingDirectory = parent.absolutePath),
            )
            assert("inner.ts" in response.output)
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
                    listOf(".", "--outDir", "does-not-exist-yet"),
                    workingDirectory = dir.absolutePath,
                ),
            )
            assert(response.exitCode == 0)
            // The whole defect in one assertion: this directory did not exist,
            // so the old "rewrite an argument that names something existing
            // here" rule left it alone and the daemon emitted into its own cwd.
            assert(File(dir, "does-not-exist-yet/emitted.js").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the installed directory is restored when the request returns`() {
        val dir = project("restored", "export const n: number = 1\n")
        val before = SystemVfs.workingDirectory
        try {
            CompileServer.respondTo(
                clientRequest(listOf("--noEmit"), workingDirectory = dir.absolutePath),
            )
            // Process-global state, one long-lived JVM: a request that left this
            // set would silently redirect every LATER request on the daemon —
            // the round-848 failure mode with a directory instead of a flag.
            assert(SystemVfs.workingDirectory == before)
        } finally {
            SystemVfs.workingDirectory = before
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a request that throws still restores the directory`() {
        val before = SystemVfs.workingDirectory
        try {
            // A path that cannot be a project at all; whatever the compile does
            // with it, the finally in `compileCapturing` is what is under test.
            CompileServer.respondTo(
                clientRequest(listOf("--noEmit"), workingDirectory = "/xtsc-not-a-directory"),
            )
            assert(SystemVfs.workingDirectory == before)
        } finally {
            SystemVfs.workingDirectory = before
        }
    }

    @Test
    fun `negative control - an empty working directory falls back to the server's own`() {
        val dir = project("ignored", "export const n: number = 1\n")
        val before = SystemVfs.workingDirectory
        try {
            // Empty is what a pre-version-2 client sends, and it must keep
            // meaning "the server's own directory" rather than resolving to the
            // filesystem root or throwing. This is also the control that the
            // three tests above are measuring the INSTALL and not something the
            // compiler would have done anyway.
            val response = CompileServer.respondTo(
                clientRequest(listOf("--noEmit", dir.absolutePath), workingDirectory = ""),
            )
            assert(response.exitCode == 0)
            assert(SystemVfs.workingDirectory == null)
        } finally {
            SystemVfs.workingDirectory = before
            dir.deleteRecursively()
        }
    }
}
