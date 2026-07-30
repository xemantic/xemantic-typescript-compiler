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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.TimeSource

/**
 * (SERVER.1) Stage 1 — a pre-warmed compile server.
 *
 * ## Why this exists
 *
 * The warm JVM is the fastest artifact this project has: **11,580 ms** on the
 * compiler profile against the AOT binary's 13,350 ms and a **26,272 ms** cold
 * CLI. All of that gap is JVM warm-up, which a one-shot CLI process can never
 * amortize. Holding one hot JVM and feeding it requests captures it.
 *
 * ## What this deliberately does NOT do
 *
 * It does not retain program state between requests. Round 772 measured that
 * idea and it is worth nothing here: on tsc's own sources a *leaf* edit yields
 * a reverse-dependency closure of 77 of 78 files, because the sources are
 * `export *` barrels, so every file transitively depends on every other. Each
 * request therefore builds a **fresh program**, which is also what makes this
 * safe — see below.
 *
 * ## Two invariants a change here must preserve
 *
 * 1. **Requests are served SEQUENTIALLY, on one thread.** Symbol/Type id
 *    sequences are thread-local and handed off by `runWithDeepStack`
 *    (INV.6(6c0)), and `--workers` has already been measured producing 62
 *    diagnostics where the sequential path produces 46. A thread-per-connection
 *    server would re-open that whole class of bug for a latency win of zero —
 *    the compile is the cost, not the accept.
 * 2. **The request runs the ORDINARY [com.xemantic.typescript.compiler.main]**,
 *    with stdout captured. Nothing about argument parsing, diagnostic
 *    formatting or exit semantics is reimplemented here, so server output is
 *    identical to CLI output *by construction* rather than by testing — the
 *    tests then check that the construction holds.
 *
 * Reusing one JVM across compiles is not speculative: `BenchMain` runs twelve
 * in-process rebuilds and every one reports the same 78 files and 46 errors.
 *
 * ## Transport
 *
 * A Unix domain socket, not a TCP port. It costs less code than a loopback
 * socket plus the shared-secret token it would need, and it inherits
 * filesystem permissions instead of being reachable by every local process.
 * Framing is a 4-byte big-endian length followed by UTF-8 JSON.
 */
object CompileServer {

    /** Default socket path; per-user, so two users on one host do not collide. */
    fun defaultSocketPath(): String {
        val tmp = System.getProperty("java.io.tmpdir") ?: "/tmp"
        val user = System.getProperty("user.name") ?: "unknown"
        return "$tmp/xtsc-$user.sock"
    }

    /**
     * The kernel's `sockaddr_un.sun_path` is 108 bytes on Linux (104 on macOS)
     * INCLUDING the terminating NUL, and exceeding it surfaces as a bare
     * `SocketException: Unix domain path too long` from deep inside the bind,
     * naming neither the path nor the limit. Checked here so the message says
     * what to do about it.
     */
    private const val MAX_SOCKET_PATH = 100

    /**
     * Returns a human message when [socketPath] cannot work, else null.
     *
     * Non-throwing on purpose: this is fatal for [serve] (nothing can be bound)
     * but must NOT be for [request], whose documented contract is to fall back
     * to compiling in-process when no server is reachable. An unusable path is
     * "not reachable", so it degrades rather than crashing the client — with
     * the reason on stderr, so it degrades loudly.
     */
    private fun socketPathProblem(socketPath: String): String? {
        val bytes = socketPath.toByteArray(StandardCharsets.UTF_8).size
        return if (bytes > MAX_SOCKET_PATH) {
            "socket path is $bytes bytes, over the ~$MAX_SOCKET_PATH-byte OS limit for Unix " +
                "domain sockets: $socketPath — pass a shorter --socket, e.g. /tmp/xtsc.sock"
        } else null
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CompileRequest(val args: List<String>)

    @Serializable
    data class CompileResponse(
        val output: String,
        val exitCode: Int,
        val elapsedMs: Long,
    )

    private fun writeFrame(channel: SocketChannel, payload: String) {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        DataOutputStream(Channels.newOutputStream(channel)).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    private fun readFrame(channel: SocketChannel): String {
        val input = DataInputStream(Channels.newInputStream(channel))
        val size = input.readInt()
        require(size in 0..MAX_FRAME_BYTES) { "implausible frame size: $size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private const val MAX_FRAME_BYTES = 64 * 1024 * 1024

    /**
     * Runs one compile with stdout captured, on the calling thread.
     *
     * `System.setOut` is process-global, which is precisely why requests are
     * serialized: with a thread-per-connection server this capture would
     * interleave two clients' output. The exit code is derived from the
     * compiler's own summary line rather than recomputed, so it cannot drift
     * from what the CLI reports.
     */
    private fun compileCapturing(args: List<String>): CompileResponse {
        val buffer = ByteArrayOutputStream()
        val previous = System.out
        val mark = TimeSource.Monotonic.markNow()
        var failed: Throwable? = null
        try {
            System.setOut(PrintStream(buffer, true, StandardCharsets.UTF_8))
            com.xemantic.typescript.compiler.main(args.toTypedArray())
        } catch (e: Exception) {
            failed = e
        } finally {
            System.setOut(previous)
        }
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        val text = buffer.toString(StandardCharsets.UTF_8) +
            (failed?.let { "\nserver: compile threw ${it::class.simpleName}: ${it.message}\n" } ?: "")
        // The CLI signals failure in its summary line, not via an exit code —
        // `main` returns normally either way — so the server mirrors that
        // rather than inventing a second source of truth.
        val exit = if (failed != null || "FAILED —" in text) 1 else 0
        return CompileResponse(output = text, exitCode = exit, elapsedMs = elapsed)
    }

    /**
     * Turns one request into one response — the whole server behaviour, minus
     * the socket.
     *
     * Extracted so it is testable without binding anything or parking a thread
     * in `accept()`: a hanging server thread inside a 13,000-test suite is a
     * flakiness source, and the socket plumbing is the part least likely to
     * break silently.
     */
    internal fun respondTo(request: CompileRequest): CompileResponse =
        // --watch never terminates, so it would wedge the single request thread
        // forever. Refuse it rather than hang.
        if (request.args.any { it == "--watch" || it == "-w" }) {
            CompileResponse(
                output = "xtsc: --watch is not supported over the compile server " +
                    "(it would hold the single request thread open forever)\n",
                exitCode = REFUSED,
                elapsedMs = 0,
            )
        } else {
            compileCapturing(request.args)
        }

    /** Exit code for a request the server declined to run at all. */
    internal const val REFUSED = 2

    /**
     * Binds [socketPath] and serves requests until the process is killed.
     *
     * A stale socket file from a crashed server is reclaimed: if nothing
     * answers a connect on it, it is deleted and re-bound. If something DOES
     * answer, this exits rather than stealing a live server's socket.
     */
    fun serve(socketPath: String = defaultSocketPath()) {
        socketPathProblem(socketPath)?.let { System.err.println("xtsc: $it"); return }
        val path = Path.of(socketPath)
        val address = UnixDomainSocketAddress.of(path)
        if (File(socketPath).exists()) {
            if (probe(address)) {
                System.err.println("xtsc: a server is already listening on $socketPath")
                return
            }
            println("xtsc: reclaiming stale socket $socketPath")
            File(socketPath).delete()
        }
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(address)
            Runtime.getRuntime().addShutdownHook(Thread { File(socketPath).delete() })
            println("xtsc compile server listening on $socketPath")
            println("  requests are served sequentially; the first is cold (~26 s), later ones warm (~12 s)")
            var served = 0
            while (true) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    System.err.println("xtsc: accept failed: ${e.message}")
                    continue
                }
                client.use { channel ->
                    try {
                        val request = json.decodeFromString<CompileRequest>(readFrame(channel))
                        val response = respondTo(request)
                        writeFrame(channel, json.encodeToString(response))
                        served++
                        System.err.println("xtsc: request $served served in ${response.elapsedMs} ms")
                    } catch (e: Exception) {
                        System.err.println("xtsc: request failed: ${e.message}")
                    }
                }
            }
        }
    }

    /** True iff something is accepting connections on [address]. */
    private fun probe(address: UnixDomainSocketAddress): Boolean = try {
        SocketChannel.open(address).use { true }
    } catch (_: Exception) {
        false
    }

    /**
     * Sends [args] to a running server. Returns null when none is reachable,
     * so the caller can fall back to compiling in-process rather than failing.
     */
    fun request(args: List<String>, socketPath: String = defaultSocketPath()): CompileResponse? {
        socketPathProblem(socketPath)?.let { System.err.println("xtsc: $it"); return null }
        val address = UnixDomainSocketAddress.of(Path.of(socketPath))
        return try {
            SocketChannel.open(address).use { channel ->
                writeFrame(channel, json.encodeToString(CompileRequest(args)))
                json.decodeFromString<CompileResponse>(readFrame(channel))
            }
        } catch (_: Exception) {
            null
        }
    }
}
