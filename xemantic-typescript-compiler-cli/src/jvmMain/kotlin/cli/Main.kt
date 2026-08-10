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

import com.xemantic.typescript.compiler.runCli
import kotlin.system.exitProcess

/**
 * Exit code for a daemon-only option this binary cannot honour.
 *
 * Non-zero, and deliberately NOT 1: 1 is "the compile ran and found errors"
 * (`tsc` semantics, and [runCli]'s contract since 2026-08-08), so a script that
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
 * (MOD.7) The LEAN entry point — the one the GraalVM native image is built from.
 *
 * ```
 *   xtsc [options] [project]      compile in this process, exactly as the CLI does
 *   xtsc --serve | --daemon | --socket   REFUSED, with exit XTSC_NO_DAEMON_SUPPORT
 * ```
 *
 * **WHY THIS EXISTS AT ALL, AND WHY IT IS NOT SIMPLY `…compiler.MainKt`.** The
 * image used to be built from the daemon module's mode dispatcher
 * (`…compiler.server.XtscMainKt`), which drags the whole transport — ktor-
 * network, slf4j, the socket machinery — into the closed-world analysis of a
 * one-shot binary that can never serve or contact anything. Pointing it back at
 * the bare `…compiler.MainKt` is the OTHER failure, and it is worse than a
 * missing feature: measured on the stale 2026-07-30 binary (round 840),
 * `--serve --socket /tmp/x.sock` bound no socket, took the socket path as the
 * PROJECT, emitted 173 files and exited **0**. A silent wrong success that
 * writes to disk.
 *
 * So the refusal below is not politeness — it is the third option, and the only
 * one that is both lean and honest. `LeanCliEntryPointTest` fails if it is
 * removed, by asserting that nothing was compiled rather than merely that
 * something was printed.
 *
 * The JVM launcher (`scripts/xtsc`, `scripts/xtsc-aot`) keeps running the
 * dispatcher — it HAS a daemon to reach — so `--serve`/`--daemon` are
 * unaffected there. This is the native arm only.
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
    compile: (Array<String>) -> Int = ::runCli,
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
