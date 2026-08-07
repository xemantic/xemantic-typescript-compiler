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

package com.xemantic.typescript.compiler.server

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.protocol.CompileRequest
import com.xemantic.typescript.compiler.runCli
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test

/**
 * The CLI and the daemon must return the SAME exit code for the same project.
 *
 * ## Why this pin exists
 *
 * It did not hold. Measured on the real compiler profile on 2026-08-08: the
 * one-shot CLI exited **0** on a project with 46 errors while the daemon path
 * exited **1** — so `xtsc`'s answer depended on whether a daemon happened to be
 * running, which is the worst possible property for something a CI pipeline
 * branches on. The daemon was deriving its code by searching captured stdout for
 * the word "FAILED"; the CLI had no code at all and reported only in its summary
 * line.
 *
 * Both now come from one place, [runCli]'s return value, and both follow `tsc`:
 * 0 for a clean compile, 1 when errors were found.
 *
 * ## What would break it again
 *
 * Anything that reintroduces a SECOND derivation of the code — a server that
 * re-reads the output text, a client that maps codes, a CLI wrapper that
 * swallows a failure. The assertion below is deliberately on the two paths
 * AGREEING rather than on either constant, because a change that moves both
 * together is fine and a change that moves one is the bug.
 */
class ExitCodeParityTest {

    private fun project(source: String): File {
        val dir = File.createTempFile("xtsc-exit-", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "a.ts").writeText(source)
        return dir
    }

    /** Runs the CLI with stdout captured, so the suite's output stays readable. */
    private fun cliExitCode(dir: File): Int {
        val previous = System.out
        return try {
            System.setOut(PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
            runCli(arrayOf("--noEmit", dir.absolutePath))
        } finally {
            System.setOut(previous)
        }
    }

    private fun daemonExitCode(dir: File): Int =
        CompileServer.respondTo(CompileRequest(listOf("--noEmit", dir.absolutePath))).exitCode

    @Test
    fun `a clean compile exits zero on both paths`() {
        val dir = project("export const n: number = 1\n")
        val cli = cliExitCode(dir)
        val daemon = daemonExitCode(dir)
        assert(cli == 0)
        assert(daemon == cli)
        dir.deleteRecursively()
    }

    // The case that was actually broken: the CLI used to answer 0 here.
    @Test
    fun `a compile with errors exits one on both paths`() {
        val dir = project("export const n: number = 'not a number'\n")
        val cli = cliExitCode(dir)
        val daemon = daemonExitCode(dir)
        assert(cli == 1)
        assert(daemon == cli)
        dir.deleteRecursively()
    }

    // Several errors must still be one failure, not a count: a shell reads only
    // zero-vs-non-zero, and an exit code of 46 would collide with a signal.
    @Test
    fun `many errors still exit one rather than a count`() {
        val dir = project(
            """
            export const a: number = 'x'
            export const b: number = 'y'
            export const c: number = 'z'
            """.trimIndent()
        )
        assert(cliExitCode(dir) == 1)
        dir.deleteRecursively()
    }

    // --help must not look like a failure to a shell that runs `xtsc --help`.
    @Test
    fun `asking for help exits zero`() {
        val previous = System.out
        val code = try {
            System.setOut(PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
            runCli(arrayOf("--help"))
        } finally {
            System.setOut(previous)
        }
        assert(code == 0)
    }

}
