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

package com.xemantic.typescript.compiler.client

import com.xemantic.typescript.compiler.protocol.xtscSocketPath
import kotlinx.coroutines.runBlocking

/**
 * The `xtsc` binary.
 *
 * ```
 *   xtsc [--socket PATH] [--no-spawn] <compiler arguments...>
 * ```
 *
 * Everything not listed above is forwarded to the compiler verbatim, so this
 * stays a strict pass-through as the compiler's own options change — the client
 * has no opinion about them and must never grow one.
 */
public fun main(args: Array<String>) {
    val parsed = parseClientArguments(args.toList())
    val client = XtscClient(
        socketPath = parsed.socketPath,
        spawner = defaultDaemonSpawner(),
        stdout = ::writeStdout,
        stderr = ::writeStderr,
    )
    val code = runBlocking { client.run(parsed.forwarded, allowSpawn = parsed.allowSpawn) }
    exitProcessWith(code)
}

/** The client's own options, split from the compiler's. */
public data class ClientArguments(
    val socketPath: String,
    val allowSpawn: Boolean,
    val forwarded: List<String>,
)

/**
 * Splits the client's options from the compiler's and absolutizes path
 * arguments.
 *
 * Only arguments that are not flags AND that name something existing here are
 * rewritten, which leaves option values and bare numbers untouched. The
 * rewriting is not cosmetic: the daemon has a different working directory, so a
 * relative path would resolve against ITS cwd and compile the wrong tree.
 */
public fun parseClientArguments(args: List<String>): ClientArguments {
    var socket: String? = null
    var allowSpawn = true
    val forwarded = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket" -> {
                socket = args.getOrNull(i + 1)
                i += 2
            }
            "--no-spawn" -> {
                allowSpawn = false
                i++
            }
            else -> {
                forwarded += absolutizeArgument(args[i])
                i++
            }
        }
    }
    return ClientArguments(
        socketPath = socket ?: defaultSocketPath(),
        allowSpawn = allowSpawn,
        forwarded = forwarded,
    )
}

private fun absolutizeArgument(arg: String): String =
    if (arg.startsWith("-")) arg else absolutePathIfExists(arg) ?: arg

/**
 * The socket both peers derive independently, so they must agree exactly — a
 * disagreement does not fail, it silently starts a second daemon.
 */
public fun defaultSocketPath(): String =
    readEnv("XTSC_SOCKET") ?: xtscSocketPath(
        tempDir = tempDirectory(),
        user = currentUserName(),
    )
