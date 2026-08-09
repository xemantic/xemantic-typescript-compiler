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

import com.xemantic.typescript.compiler.protocol.CompileRequest
import com.xemantic.typescript.compiler.protocol.XTSC_PROTOCOL_VERSION
import com.xemantic.typescript.compiler.protocol.isDaemonReachable
import com.xemantic.typescript.compiler.protocol.protocolProblem
import com.xemantic.typescript.compiler.protocol.sendCompileRequest
import com.xemantic.typescript.compiler.protocol.socketPathProblem
import kotlinx.coroutines.delay

/** Exit code when the request never ran — never when a compile found errors. */
public const val XTSC_CLIENT_UNAVAILABLE: Int = 3

/**
 * The thin client: everything `xtsc` does that is not compiling.
 *
 * ## Why this module exists at all
 *
 * Until now the "thin client" was a GraalVM image of the whole compiler — 230k
 * lines of Kotlin compiled ahead of time in order to write a couple of hundred
 * bytes to a socket. This depends on `-api` and nothing else, so the binary is
 * the request path and that is all.
 *
 * ## What it will NOT do
 *
 * It cannot compile, so it cannot fall back to compiling in-process the way the
 * JVM dispatcher does. That is a deliberate consequence of the dependency edge,
 * not an omission: when no daemon can be started the client says so and exits
 * [XTSC_CLIENT_UNAVAILABLE], rather than pretending.
 *
 * ## The start race
 *
 * Two clients starting at once will both fail to connect and both spawn. That is
 * safe by construction rather than by locking: a daemon that finds something
 * already listening on the socket exits instead of stealing it, so the loser
 * simply dies and both clients then connect to the winner. This is why the wait
 * below polls for reachability rather than tracking the process it spawned — the
 * daemon that answers may well be someone else's.
 */
public class XtscClient(
    private val socketPath: String,
    private val spawner: DaemonSpawner,
    private val stdout: (String) -> Unit,
    private val stderr: (String) -> Unit,
    private val clock: MonotonicClock = MonotonicClock.system,
) {

    public suspend fun run(
        args: List<String>,
        allowSpawn: Boolean = true,
        // Injected so the pin does not depend on where the test process runs;
        // production always passes the real one.
        workingDirectory: String = currentWorkingDirectory(),
    ): Int {
        socketPathProblem(socketPath)?.let {
            stderr("xtsc: $it\n")
            return XTSC_CLIENT_UNAVAILABLE
        }
        // The directory is part of the request, not of the arguments: every
        // relative path on the command line — including the project path the
        // user did not type, which the compiler defaults to `"."` — means
        // something HERE, and the daemon is somewhere else (round 873).
        val request = CompileRequest(
            args = args,
            protocolVersion = XTSC_PROTOCOL_VERSION,
            workingDirectory = workingDirectory,
        )
        sendCompileRequest(socketPath, request)?.let { return report(it) }

        if (!allowSpawn) {
            stderr("xtsc: no compile daemon on $socketPath and --no-spawn was given\n")
            return XTSC_CLIENT_UNAVAILABLE
        }
        if (!spawner.spawn(socketPath)) {
            stderr(
                "xtsc: no compile daemon on $socketPath, and one could not be started — " +
                    "${spawner.describe()}\n"
            )
            return XTSC_CLIENT_UNAVAILABLE
        }
        if (!awaitDaemon()) {
            stderr(
                "xtsc: started a compile daemon but it did not answer on $socketPath " +
                    "within ${SPAWN_TIMEOUT_MS / 1000} s\n"
            )
            return XTSC_CLIENT_UNAVAILABLE
        }
        val response = sendCompileRequest(socketPath, request)
        if (response == null) {
            stderr("xtsc: the compile daemon on $socketPath stopped answering\n")
            return XTSC_CLIENT_UNAVAILABLE
        }
        return report(response)
    }

    /**
     * Polls until the daemon answers, or the budget runs out.
     *
     * Backs off rather than spinning, and polls REACHABILITY rather than watching
     * the spawned process: under the start race the daemon that ends up serving
     * may be one another client spawned, and waiting on our own child would then
     * time out while a perfectly good daemon was already listening.
     */
    private suspend fun awaitDaemon(): Boolean {
        val deadline = clock.millis() + SPAWN_TIMEOUT_MS
        var wait = FIRST_POLL_MS
        while (clock.millis() < deadline) {
            delay(wait)
            if (isDaemonReachable(socketPath)) return true
            wait = (wait * 2).coerceAtMost(MAX_POLL_MS)
        }
        return false
    }

    /**
     * Reproduces the daemon's captured output verbatim and adopts its exit code.
     *
     * Verbatim is the contract: the daemon ran the ordinary compiler `main` with
     * stdout captured, so what a user sees is identical to a local compile by
     * construction. Reformatting anything here would break that silently.
     */
    private fun report(response: com.xemantic.typescript.compiler.protocol.CompileResponse): Int {
        protocolProblem(response.protocolVersion)?.let {
            stderr("xtsc: $it\n")
            return XTSC_CLIENT_UNAVAILABLE
        }
        stdout(response.output)
        return response.exitCode
    }

    public companion object {
        internal const val SPAWN_TIMEOUT_MS: Long = 60_000
        internal const val FIRST_POLL_MS: Long = 25
        internal const val MAX_POLL_MS: Long = 500
    }

}

/** Starts a compile daemon. Platform-specific: there is no multiplatform exec. */
public interface DaemonSpawner {

    /** Attempts to start a daemon for [socketPath]; false if it could not try. */
    public suspend fun spawn(socketPath: String): Boolean

    /** How to fix it, when [spawn] returns false. */
    public fun describe(): String
}

/** A monotonic millisecond clock, injectable so the backoff is testable. */
public interface MonotonicClock {

    public fun millis(): Long

    public companion object {
        public val system: MonotonicClock = SystemMonotonicClock()
    }
}
