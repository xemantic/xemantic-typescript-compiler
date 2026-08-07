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

package com.xemantic.typescript.compiler.protocol

/**
 * The kernel's `sockaddr_un.sun_path` holds 108 bytes on Linux and 104 on macOS,
 * including the terminating NUL. Exceeding it surfaces as a bare
 * "Unix domain path too long" from deep inside the bind, naming neither the path
 * nor the limit — so the check lives here, where the message can say what to do.
 *
 * Deliberately below the smallest real limit: the value has to hold for whichever
 * platform the *daemon* runs on, which the client cannot know.
 */
public const val MAX_SOCKET_PATH_BYTES: Int = 100

/**
 * The default socket path, per user so that two users on one host do not collide.
 *
 * A pure function of its inputs rather than a reader of ambient state, because
 * the JVM daemon and the native client discover [tempDir] and [user] through
 * entirely different APIs while having to agree on the answer exactly — a
 * disagreement here does not fail, it silently starts a second daemon.
 */
public fun xtscSocketPath(tempDir: String, user: String): String {
    // java.io.tmpdir carries a trailing separator on some platforms and not on
    // others; both spellings name the same socket, but only one of them matches
    // when a script compares the path as a string.
    val base = tempDir.trimEnd('/', '\\').ifEmpty { "/tmp" }
    return "$base/xtsc-${sanitizeUserName(user)}.sock"
}

/**
 * Reduces a user name to characters that are safe in a path segment.
 *
 * Domain-joined Windows accounts routinely contain a backslash, and a separator
 * appearing here would silently redirect the socket into another directory.
 */
private fun sanitizeUserName(user: String): String {
    val sanitized = user.map { c ->
        if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
    }.joinToString("")
    return sanitized.ifEmpty { "unknown" }
}

/**
 * A human-readable reason [socketPath] cannot work, or null when it can.
 *
 * Non-throwing on purpose. This is fatal for the daemon, which has nothing to
 * bind, but it must not be for the client, whose contract is to fall back to
 * compiling in-process whenever the daemon is unreachable — an unusable path is
 * a species of unreachable, so it degrades, and the returned message is how it
 * degrades loudly rather than silently.
 */
public fun socketPathProblem(socketPath: String): String? {
    if (socketPath.isEmpty()) return "socket path is empty"
    val bytes = socketPath.encodeToByteArray().size
    return if (bytes > MAX_SOCKET_PATH_BYTES) {
        "socket path is $bytes bytes, over the ~$MAX_SOCKET_PATH_BYTES-byte OS limit " +
            "for Unix domain sockets: $socketPath — pass a shorter --socket, " +
            "e.g. /tmp/xtsc.sock"
    } else null
}
