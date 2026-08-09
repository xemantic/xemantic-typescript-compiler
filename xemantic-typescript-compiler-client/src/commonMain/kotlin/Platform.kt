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

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.TimeSource

/**
 * THREE `expect`s, and the list is deliberately this short.
 *
 * Every expect/actual pair is a place the JVM and native builds can silently
 * diverge, so anything that CAN be common is: the clock is
 * `kotlin.time.TimeSource`, the filesystem is kotlinx-io (which covers mingw as
 * well as posix), the temp dir and user name are derived from the environment
 * here rather than twice, and `exitProcess` already exists on both. What is left
 * is genuinely platform-shaped: reading the environment, writing to stderr —
 * which common Kotlin has no notion of — and detaching a child process.
 */

/** The value of an environment variable, or null. */
public expect fun readEnv(name: String): String?

/** Writes [text] to stderr. Common Kotlin has no stderr. */
public expect fun writeStderr(text: String)

/**
 * Starts [command] detached, without waiting for it. False if it could not be
 * launched at all.
 *
 * Detachment is what makes this platform-shaped rather than merely inconvenient:
 * the daemon must OUTLIVE the client that started it, so the child can neither
 * be waited on nor hold this process's streams open.
 */
public expect fun spawnDetached(command: List<String>): Boolean

/** Writes [text] to stdout, unmodified — the daemon's output is reproduced verbatim. */
public fun writeStdout(text: String) {
    print(text)
}

/** Ends the process with [code]. */
public fun exitProcessWith(code: Int): Nothing = kotlin.system.exitProcess(code)

/**
 * The process's temporary directory.
 *
 * Derived from the environment in COMMON code because the client and the daemon
 * must agree on the resulting socket path exactly — a disagreement does not
 * fail, it silently starts a second daemon — and two platform implementations
 * are two chances to disagree.
 */
public fun tempDirectory(): String =
    readEnv("TMPDIR") ?: readEnv("TMP") ?: readEnv("TEMP") ?: "/tmp"

/** The current user's name, for the per-user socket path. */
public fun currentUserName(): String =
    readEnv("USER") ?: readEnv("USERNAME") ?: readEnv("LOGNAME") ?: "unknown"

/**
 * The client's working directory, absolute.
 *
 * Path arguments must be absolutized before they are sent, and a MISSING path
 * argument means this directory: the daemon runs somewhere else entirely, and a
 * JVM cannot change its own cwd, so a request that does not say where it is gets
 * compiled against the DAEMON's directory (round 873).
 */
public fun currentWorkingDirectory(): String = try {
    SystemFileSystem.resolve(Path(".")).toString()
} catch (_: Exception) {
    "."
}


/** [MonotonicClock] over `kotlin.time`, which is multiplatform already. */
internal class SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun millis(): Long = origin.elapsedNow().inWholeMilliseconds
}
