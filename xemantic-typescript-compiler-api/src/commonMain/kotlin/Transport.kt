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

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.UnixSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.Dispatchers

/**
 * Sends one [request] to the daemon listening on [socketPath], or returns null
 * when none is reachable.
 *
 * Lives here, next to the codec, so that the two peers share ONE implementation
 * of the exchange rather than one each. They are separately-built binaries: a
 * client that flushed where the daemon did not, or framed in the other order,
 * would present as a HANG — the failure mode with no stack trace and no diff.
 *
 * Null rather than an exception because unreachable is the ORDINARY case, not an
 * error: it is what a first invocation sees before any daemon exists, and every
 * caller answers it by doing something else (spawning one, or compiling
 * in-process) rather than by failing.
 *
 * `Dispatchers.Default` and not `Dispatchers.IO`: this function is compiled for
 * Kotlin/Native too, and the exchange is a couple of frames, not a workload.
 */
public suspend fun sendCompileRequest(
    socketPath: String,
    request: CompileRequest,
): CompileResponse? {
    if (socketPathProblem(socketPath) != null) return null
    if (!UnixSocketAddress.isSupported()) return null
    return try {
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().connect(UnixSocketAddress(socketPath)).use { socket ->
                val out = socket.openWriteChannel(autoFlush = false)
                out.writeFrame(xtscProtocolJson.encodeToString(request))
                out.flush()
                xtscProtocolJson.decodeFromString<CompileResponse>(
                    socket.openReadChannel().readFrame()
                )
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * True iff something is accepting connections on [socketPath].
 *
 * Used by a would-be daemon to tell a stale socket file from a live server, and
 * by a client to wait for one it has just spawned.
 */
public suspend fun isDaemonReachable(socketPath: String): Boolean =
    if (!UnixSocketAddress.isSupported()) false
    else try {
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().connect(UnixSocketAddress(socketPath)).use { true }
        }
    } catch (_: Exception) {
        false
    }
