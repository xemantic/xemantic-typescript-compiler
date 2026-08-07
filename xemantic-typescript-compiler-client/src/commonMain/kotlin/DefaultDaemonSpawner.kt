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

/**
 * Starts the daemon by running a launcher command with `--serve`.
 *
 * ## How the command is found
 *
 * `XTSC_DAEMON_CMD` if set (whitespace-separated), else the `xtsc-daemon`
 * launcher named by `XTSC_HOME`. There is deliberately NO search of `PATH` and
 * no guess at a JVM invocation: the client knows nothing about Java, which is
 * the entire point of the dependency edge, and a wrong guess would start
 * something other than this build's daemon — the one failure that produces
 * plausible but wrong diagnostics rather than an error.
 *
 * That is also why [describe] exists. When no command can be determined the
 * client must say what to set, because the user has no other way to find out.
 */
public class LauncherDaemonSpawner(
    private val launch: (List<String>) -> Boolean = ::spawnDetached,
    private val env: (String) -> String? = ::readEnv,
) : DaemonSpawner {

    override suspend fun spawn(socketPath: String): Boolean {
        val command = command() ?: return false
        return launch(command + listOf("--serve", "--socket", socketPath))
    }

    override fun describe(): String =
        "set XTSC_DAEMON_CMD to the daemon launcher, or XTSC_HOME to an installation"

    /** The launcher command, or null when the environment does not name one. */
    internal fun command(): List<String>? {
        env("XTSC_DAEMON_CMD")
            ?.split(' ')
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val home = env("XTSC_HOME")?.trimEnd('/', '\\') ?: return null
        return listOf("$home/bin/xtsc-daemon")
    }

}

/** The spawner [main] uses. */
public fun defaultDaemonSpawner(): DaemonSpawner = LauncherDaemonSpawner()
