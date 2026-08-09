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

import com.xemantic.typescript.compiler.protocol.CompileResponse
import java.io.File

/**
 * (SERVER.1) The dispatching entry point: compile server, thin client, or the
 * ordinary one-shot compiler.
 *
 * ```
 *   xtsc --serve [--socket PATH]     run the compile server (foreground)
 *   xtsc --daemon [--socket PATH] …  send the remaining args to a running server
 *   xtsc …                           compile in this process, exactly as before
 * ```
 *
 * **`--daemon` falls back to compiling in-process when no server answers**, so
 * a script using it keeps working whether or not a server happens to be up.
 *
 * The three modes are one binary so that the AOT image built by
 * `./gradlew nativeImage` *is* the thin client: a native start costs
 * milliseconds, which is the point of pairing it with a warm JVM server that
 * holds C2-compiled code (~~11,580 ms~~ warm against 13,350 ms for the AOT
 * binary doing the whole compile itself; **round 843, 2026-08-07: the warm
 * figure is now ~7,030 ms** — see `docs/perf/warm-jvm-attribution.md`, and note
 * the two absolutes come from different rounds and boxes).
 *
 * **Exit codes match the CLI, and since 2026-08-08 both follow `tsc`**: 0 when
 * the compile found no errors, 1 when it found some. They used to return 0
 * unconditionally and report the outcome only in the summary line, which made
 * `xtsc` unusable as a build-failure signal in CI or a shell `&&` chain — and,
 * once the daemon existed, made the answer depend on whether one happened to be
 * running, since the server derived its own code by searching stdout for
 * "FAILED". The single source of truth is now
 * [com.xemantic.typescript.compiler.runCli]'s return value.
 *
 * The scripts that USED to read a non-zero exit as infrastructure failure were
 * updated with this change: `bench-compile-tsc.sh` now detects a failed run by
 * the absence of the compiler's summary line, which is the property that
 * actually distinguishes "did not run" from "ran and found errors".
 */
fun main(args: Array<String>) {
    val socket = optionValue(args, "--socket") ?: CompileServer.defaultSocketPath()
    when {
        args.contains("--serve") -> CompileServer.serve(socket)
        // Exits only on failure, exactly as `com.xemantic.typescript.compiler.main`
        // does: an explicit `exitProcess(0)` would cut short whatever the runtime
        // still wants to do on the way out.
        args.contains("--daemon") -> runAsClient(args, socket).let {
            if (it != 0) kotlin.system.exitProcess(it)
        }
        else -> com.xemantic.typescript.compiler.main(args)
    }
}

private fun optionValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

/**
 * Sends the request, or compiles here when nothing answers; returns the code.
 *
 * **It RETURNS the code rather than exiting**, for the same reason
 * [com.xemantic.typescript.compiler.runCli] does: a pin can then observe the
 * value, and `ExitCodeParityTest` proved that a pin one layer lower is not
 * enough. That test asserted the code the server puts IN the response, which
 * was always right; what this function used to do with it was propagate
 * `XTSC_REFUSED` and **drop every other non-zero code on the floor** — so a
 * daemon-served compile with errors exited **0** while the one-shot CLI and this
 * function's own in-process fallback both exited **1**. `xtsc`'s answer again
 * depended on whether a daemon happened to be running, which is precisely what
 * the 2026-08-08 change set out to end, and CI would have read the daemon path
 * as a pass. Measured round 872; pinned by `XtscClientExitCodeTest`.
 */
internal fun runAsClient(
    args: Array<String>,
    socket: String,
    // The seam exists so the pin needs no socket. This module's suite
    // deliberately never binds one (parking a thread in `accept()` inside a
    // 14,000-test run is a flakiness source), which is exactly why the defect
    // above survived: every existing pin stopped at the response object.
    request: (List<String>, String) -> CompileResponse? = CompileServer::request,
): Int {
    val forwarded = stripClientOptions(args).map(::absolutize)
    val response = request(forwarded, socket)
    if (response == null) {
        // No server: do the work here rather than failing. The point of the
        // server is latency, not capability. `runCli`, not `main`: the latter
        // would exitProcess and there would be nothing left to return.
        System.err.println("xtsc: no compile server on $socket — compiling in-process")
        return com.xemantic.typescript.compiler.runCli(forwarded.toTypedArray())
    }
    print(response.output)
    System.out.flush()
    return response.exitCode
}

/** Drops the options that steer the client itself; everything else is the compiler's. */
private fun stripClientOptions(args: Array<String>): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--daemon" -> i++
            "--socket" -> i += 2
            else -> { out.add(args[i]); i++ }
        }
    }
    return out
}

/**
 * Resolves path-like arguments against the CLIENT's working directory.
 *
 * The server runs somewhere else entirely, so a relative `.` or `./project`
 * would resolve against ITS cwd and silently compile the wrong tree — or
 * nothing. Only arguments that are not flags and that actually exist as a file
 * or directory here are rewritten, which leaves option values such as
 * `--socket /tmp/x.sock` (already absolute) and bare numbers untouched.
 */
private fun absolutize(arg: String): String {
    if (arg.startsWith("-")) return arg
    val file = File(arg)
    return if (file.exists()) file.absolutePath else arg
}
