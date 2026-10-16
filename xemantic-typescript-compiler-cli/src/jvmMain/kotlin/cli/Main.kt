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

import com.xemantic.typescript.tsgo.cli.runMain
import kotlin.system.exitProcess

/**
 * Exit code for a daemon-only option this binary cannot honour.
 *
 * Non-zero, and deliberately NOT 1: 1 is "the compile ran and found errors"
 * (`tsc` semantics, and [runMain]'s), so a script that
 * branches on the exit code can tell "your project has type errors" from "this
 * binary does not do that".
 *
 * The VALUE matches `XTSC_REFUSED` in `:xemantic-typescript-compiler-api` — the
 * daemon's own "declined to run the request at all" code, which is exactly what
 * happens here — but it is duplicated rather than imported ON PURPOSE: that
 * module exports ktor-network, and not carrying ktor is this module's entire
 * reason to exist.
 */
public const val XTSC_NO_DAEMON_SUPPORT: Int = 2

/**
 * The options that select a compile SERVER, and which this binary therefore has
 * no implementation of.
 *
 * `--socket` is in the list even though it selects nothing by itself, and that
 * is the round-840 hazard rather than tidiness: the compiler's argument loop
 * ends in `else -> if (!a.startsWith("-")) o.project = a`, so an unknown FLAG is
 * ignored while its VALUE is silently adopted as the project. `--socket
 * /tmp/x.sock` alone would compile `/tmp/x.sock`.
 */
private val DAEMON_ONLY_OPTIONS = listOf("--serve", "--daemon", "--socket")

/**
 * (MOD.7) The LEAN one-shot entry point: the ported tsc ((TSGO.8-b), `-tsgo`'s
 * [runMain]) with no transport on the classpath.
 *
 * ```
 *   xtsc [options] [project]      compile in this process, exactly as tsc does
 *   xtsc --serve | --daemon | --socket   REFUSED, with exit XTSC_NO_DAEMON_SUPPORT
 * ```
 *
 * The refusal predates the port: `-core`'s argument loop took an unknown flag's
 * VALUE as the project (round 840: `--serve --socket /tmp/x.sock` emitted 173
 * files and exited 0). The port rejects the flags itself (TS5023, exit 1), but
 * the refusal still says WHERE the daemon modes live and answers with a code a
 * script can tell from "the project has errors". `LeanCliEntryPointTest` pins it.
 *
 * The shipped native image is built from the port's own entry
 * (`TsgoMainKt`), not from this one; the JVM launcher (`scripts/xtsc`) runs the
 * daemon's dispatcher, which HAS a daemon to reach.
 */
public fun main(args: Array<String>) {
    val code = runLeanCli(args)
    // Only on failure: exiting explicitly with 0 is the same as returning, and
    // `exitProcess` from a NORMAL completion would cut short anything the
    // runtime still wants to do on the way out. Same shape as
    // `com.xemantic.typescript.compiler.main`.
    if (code != 0) exitProcess(code)
}

/**
 * Runs the lean CLI and RETURNS its exit code instead of ending the process.
 *
 * The two seams have one purpose: making the REFUSAL observable to a pin. A test
 * that only inspected stderr could not tell a refusal from a message printed on
 * the way into a compile, and the failure this guards against is precisely a
 * binary that prints something and compiles anyway.
 */
internal fun runLeanCli(
    args: Array<String>,
    err: (String) -> Unit = { System.err.println(it) },
    compile: (Array<String>) -> Int = ::runMain,
): Int {
    val refused = DAEMON_ONLY_OPTIONS.filter { it in args }
    if (refused.isNotEmpty()) {
        err(
            "xtsc: ${refused.joinToString(", ")} " +
                (if (refused.size == 1) "needs" else "need") +
                " a compile server, and this binary has none — it is the " +
                "standalone ahead-of-time compiler. Use the JVM distribution " +
                "(xtsc from XTSC_HOME/bin, or scripts/xtsc in the source tree) " +
                "for --serve and --daemon."
        )
        return XTSC_NO_DAEMON_SUPPORT
    }
    return compile(args)
}
