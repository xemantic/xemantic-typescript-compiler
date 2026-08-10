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

package com.xemantic.typescript.compiler.cli

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (MOD.7) The seam pins for the native binary's entry point.
 *
 * **WHAT THEY ARE FOR.** Round 840 measured the hazard on a real artifact: an
 * image built from the bare `…compiler.MainKt` took `--serve --socket
 * /tmp/x.sock`, bound no socket, adopted the socket path as the PROJECT, emitted
 * 173 files and exited **0**. The compiler's argument loop ends in
 * `else -> if (!a.startsWith("-")) o.project = a`, so an unknown flag is ignored
 * while its value is adopted — which is why nothing about that failure is loud.
 *
 * **WHY THEY DISCRIMINATE, WHICH IS THE WHOLE POINT.** Every refusal pin asserts
 * `compiled.isEmpty()`, i.e. that the compiler was never reached — not that a
 * message was printed. Delete the refusal branch in [runLeanCli] and each one
 * fails on that assertion (the fake compiler records a call and the function
 * returns 0), which is exactly the round-840 behaviour they exist to forbid. A
 * pin asserting only the stderr text would stay green against a binary that
 * complains and then compiles the socket path anyway.
 *
 * The negative controls below matter as much: a refusal that swallowed ORDINARY
 * invocations would be a far worse regression than the one being prevented, and
 * [anOrdinaryCompileIsDelegatedVerbatim] is what notices.
 */
class LeanCliEntryPointTest {

    /**
     * Drives [runLeanCli] with both seams faked, so a call to the compiler is
     * recorded rather than performed. `runCli` itself is never invoked — these
     * pins are about dispatch, and a real compile would put a whole project
     * crawl inside a 14,000-test suite.
     */
    private class Driver(private val exitCode: Int = 0) {
        val errors = mutableListOf<String>()
        val compiled = mutableListOf<List<String>>()
        fun run(vararg args: String): Int = runLeanCli(
            args = arrayOf(*args),
            err = { errors += it },
            compile = { compiled += it.toList(); exitCode }
        )
    }

    @Test
    fun `serve is refused and nothing is compiled`() {
        val d = Driver()
        val code = d.run("--serve", "--socket", "/tmp/x.sock")
        assert(code == XTSC_NO_DAEMON_SUPPORT)
        assert(d.compiled.isEmpty())
        assert(d.errors.size == 1)
    }

    @Test
    fun `daemon is refused and nothing is compiled`() {
        val d = Driver()
        val code = d.run("--daemon", "--noEmit", "/proj")
        assert(code == XTSC_NO_DAEMON_SUPPORT)
        assert(d.compiled.isEmpty())
        assert(d.errors.size == 1)
    }

    /**
     * `--socket` alone selects nothing, and that is precisely why it has to be
     * refused: without this arm the compiler would ignore the flag and adopt
     * `/tmp/x.sock` as the project — round 840's silent wrong success, minus the
     * flag that makes it obvious.
     */
    @Test
    fun `a bare socket option is refused and nothing is compiled`() {
        val d = Driver()
        val code = d.run("--socket", "/tmp/x.sock", "--noEmit")
        assert(code == XTSC_NO_DAEMON_SUPPORT)
        assert(d.compiled.isEmpty())
    }

    /** Position-independent: the refusal scans the whole argument vector. */
    @Test
    fun `a daemon option is refused wherever it appears`() {
        val d = Driver()
        val code = d.run("/proj", "--noEmit", "--listAll", "--daemon")
        assert(code == XTSC_NO_DAEMON_SUPPORT)
        assert(d.compiled.isEmpty())
    }

    /**
     * The message has one job beyond being non-empty: telling the reader which
     * artifact DOES have the feature. A refusal that says only "unsupported"
     * sends them to the issue tracker.
     */
    @Test
    fun `the refusal names the offending option and the artifact that has it`() {
        val d = Driver()
        d.run("--serve")
        val message = d.errors.single()
        assert("--serve" in message)
        assert("xtsc" in message)
        assert("compile server" in message)
    }

    /** Both refused options are named when both are present, not just the first. */
    @Test
    fun `the refusal names every offending option`() {
        val d = Driver()
        d.run("--daemon", "--socket", "/tmp/x.sock")
        val message = d.errors.single()
        assert("--daemon" in message)
        assert("--socket" in message)
    }

    /**
     * NEGATIVE CONTROL — the refusal must not touch an ordinary invocation, and
     * must not rewrite it either. The arguments arrive at the compiler exactly as
     * given: this module deliberately does no argument parsing of its own, which
     * is the round-873 lesson about clients that try to interpret the compiler's
     * option table without having it.
     */
    @Test
    fun `an ordinary compile is delegated verbatim`() {
        val d = Driver()
        val code = d.run("--noEmit", "--listAll", "/proj")
        assert(code == 0)
        assert(d.errors.isEmpty())
        assert(d.compiled == listOf(listOf("--noEmit", "--listAll", "/proj")))
    }

    /** No arguments at all is an ordinary invocation too — the CLI defaults the project. */
    @Test
    fun `an empty argument list is delegated`() {
        val d = Driver()
        assert(d.run() == 0)
        assert(d.compiled == listOf(emptyList<String>()))
    }

    /**
     * The compiler's own exit code reaches the process. This is round 872's
     * lesson one layer over: a value that crosses a boundary is pinned where it
     * is CONSUMED, and a lean entry point that swallowed a failing compile's 1
     * would make CI read a failure as a pass.
     */
    @Test
    fun `a failing compile propagates its exit code`() {
        val d = Driver(exitCode = 1)
        assert(d.run("/proj") == 1)
        assert(d.compiled.size == 1)
    }

    /**
     * The refusal code must be distinguishable from BOTH "all good" and "the
     * compile found errors", or a script cannot tell a missing feature from a
     * broken project.
     */
    @Test
    fun `the refusal exit code is neither success nor compile failure`() {
        assert(XTSC_NO_DAEMON_SUPPORT != 0)
        assert(XTSC_NO_DAEMON_SUPPORT != 1)
    }
}
