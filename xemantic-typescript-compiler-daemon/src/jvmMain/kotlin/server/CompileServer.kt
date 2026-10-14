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

import com.xemantic.typescript.compiler.protocol.CompileRequest
import com.xemantic.typescript.compiler.protocol.CompileResponse
import com.xemantic.typescript.compiler.protocol.XTSC_PROTOCOL_VERSION
import com.xemantic.typescript.compiler.protocol.XTSC_REFUSED
import com.xemantic.typescript.compiler.protocol.protocolProblem
import com.xemantic.typescript.compiler.protocol.readFrame
import com.xemantic.typescript.compiler.protocol.socketPathProblem
import com.xemantic.typescript.compiler.protocol.writeFrame
import com.xemantic.typescript.compiler.protocol.xtscProtocolJson
import com.xemantic.typescript.compiler.protocol.xtscSocketPath
import com.xemantic.typescript.tsgo.TsgoCli
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.UnixSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.time.TimeSource

/**
 * (SERVER.1) Stage 1 — a pre-warmed compile server, serving the ported tsc ((TSGO.7), `-tsgo`'s [TsgoCli]).
 *
 * ## Why this exists
 *
 * The warm JVM is the fastest JVM arm this project has, and all of the gap to a one-shot CLI is JVM warm-up,
 * which a one-shot process can never amortize. On tsc's compiler profile (published bench, series V3,
 * 2026-10-14, GitHub runner): the port warm 2.09 s, the same port as a fresh JVM 14.56 s, tsgo 1.76 s.
 * Holding one hot JVM and feeding it requests captures that difference. (The figures before (TSGO.7) were
 * the frozen `-core` checker's; `bench-history/README.md` keeps them as the V2 archive.)
 *
 * ## What this deliberately does NOT do
 *
 * It does not retain program state between requests: each request runs the ordinary command line, which
 * builds a fresh program — what makes serving safe. (Round 772 measured retained state worth nothing on
 * tsc's sources: a leaf edit's reverse-dependency closure is 77 of 78 files, through `export *` barrels.)
 *
 * ## Three invariants a change here must preserve
 *
 * 1. **Requests are served SEQUENTIALLY, on ONE thread.** One compile at a time keeps the server's peak
 *    memory that of one compile, and the port's goroutine run tokens (`GoProcs`, `TSGO_GOMAXPROCS`) are
 *    process-wide: two concurrent compiles would share them. (Under `-core` the same thread was
 *    load-bearing for its thread-local id counters; the port has none, but [compileDispatcher] keeps the
 *    stronger property and `CompileThreadInvariantTest` still pins it.)
 * 2. **The request runs the ORDINARY command line** ([TsgoCli.run], what `TsgoMainKt` and its native
 *    image run), with its output captured. Nothing about argument parsing, diagnostic formatting or exit status
 *    is reimplemented here, so server output is identical to CLI output *by construction* — the tests
 *    then check that the construction holds.
 * 3. **The wire format lives in the `-api` module**, not here. The client is a
 *    separately-built binary, so a framing or schema change that reaches only
 *    one side presents as a HANG rather than as a type error.
 *
 * ## Transport
 *
 * A Unix domain socket over ktor-network's CIO engine, not a TCP port. It costs
 * less code than a loopback socket plus the shared-secret token it would need,
 * and it inherits filesystem permissions instead of being reachable by every
 * local process. Ktor rather than the JDK's own `UnixDomainSocketAddress`
 * because the thin client is Kotlin/Native, where the JDK does not exist and
 * ktor already carries an AF_UNIX implementation for linux, macOS and mingw —
 * so both peers share one transport as well as one codec.
 */
object CompileServer {

    /** Default socket path; per-user, so two users on one host do not collide. */
    fun defaultSocketPath(): String = xtscSocketPath(
        tempDir = System.getProperty("java.io.tmpdir") ?: "/tmp",
        user = System.getProperty("user.name") ?: "unknown",
    )

    /** The one thread every compile runs on — see invariant 1. */
    private val compileDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, COMPILE_THREAD_NAME).apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** The name [onCompileThread] runs under; asserted by the invariant pin. */
    internal const val COMPILE_THREAD_NAME = "xtsc-compile"

    /**
     * Runs [block] on the one compile thread — the whole of invariant 1.
     *
     * A named function rather than an inline `withContext` so the invariant is
     * testable: nothing else can observe which carrier thread a coroutine
     * happened to land on, and the failure it guards against leaves every byte
     * of output identical.
     */
    internal suspend fun <T> onCompileThread(block: () -> T): T =
        withContext(compileDispatcher) { block() }

    /**
     * Runs one compile with its output captured, on the calling thread.
     *
     * The ported tsc ((TSGO.7)): [TsgoCli.run] takes the directory and the output sink as arguments, so nothing
     * process-global is installed for a request — no `System.setOut`, no working-directory override — and the
     * exit status is tsc's own return value. An exception thrown on this thread is reported in the response
     * with Go's fatal status 2 (the CLI's `fatal error:` exit); a throwable escaping one of the compile's
     * goroutines is fatal to the whole server, as it is to `tsc` (CLAUDE.md, `goSpawn`), and the client then
     * falls back to compiling in-process.
     */
    private fun compileCapturing(args: List<String>, workingDirectory: String): CompileResponse {
        val buffer = ByteArrayOutputStream()
        val mark = TimeSource.Monotonic.markNow()
        var failed: Throwable? = null
        var code = 0
        try {
            // (SERVE.2) round 873. The request's directory, not this process's: every relative path on the
            // command line means something THERE, and the most important one is the path a user did not type
            // at all — tsc looks for `tsconfig.json` in the current directory.
            code = TsgoCli.run(
                args = args,
                currentDirectory = workingDirectory.ifEmpty { File(System.getProperty("user.dir")).absolutePath },
                out = { buffer.write(it) },
                libDirectory = System.getenv("XTSC_TSGO_LIB_DIR")?.takeIf { it.isNotEmpty() },
                environment = { System.getenv(it) },
            )
        } catch (e: Exception) {
            failed = e
        }
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        val text = buffer.toString(StandardCharsets.UTF_8) +
            (failed?.let { "\nserver: compile threw ${it::class.simpleName}: ${it.message}\n" } ?: "")
        val exit = if (failed != null) 2 else code
        return CompileResponse(
            output = text,
            exitCode = exit,
            elapsedMs = elapsed,
            protocolVersion = XTSC_PROTOCOL_VERSION,
        )
    }

    /**
     * Turns one request into one response — the whole server behaviour, minus
     * the socket.
     *
     * Extracted so it is testable without binding anything or parking a thread
     * in `accept()`: a hanging server coroutine inside a 13,000-test suite is a
     * flakiness source, and the socket plumbing is the part least likely to
     * break silently.
     */
    internal fun respondTo(request: CompileRequest): CompileResponse =
        // A PROTOCOL MISMATCH IS REFUSED WITHOUT COMPILING, and both halves of
        // that matter.
        //
        // CORRECTNESS is the first half, and it is the whole reason version 2
        // exists: a version-1 request carries NO `workingDirectory`, so serving
        // it resolves every relative path — including the project path the CLI
        // defaults to `"."` — against the DAEMON's directory. That is round
        // 873's CI-grade bug, and until this guard the server merely LOGGED the
        // mismatch and then compiled the wrong tree anyway.
        //
        // COST is the second half. The client's answer to a mismatch is to
        // fall back and compile in-process, so the daemon's compile is thrown
        // away by construction — and on the tsc-compiler profile a stale
        // version-1 native client made every `xtsc --daemon` invocation cost
        // TWO full compiles (one discarded here, one after the fallback):
        // 14.6 s where the honest figure is 7.5 s.
        //
        // XTSC_REFUSED is the documented "the request never ran" code, which is
        // exactly what the client needs in order to re-run it safely.
        protocolProblem(request.protocolVersion)?.let { problem ->
            CompileResponse(
                output = "xtsc: refusing a request that speaks protocol " +
                    "${request.protocolVersion} — $problem\n",
                exitCode = XTSC_REFUSED,
                elapsedMs = 0,
                protocolVersion = XTSC_PROTOCOL_VERSION,
            )
        }
        // --watch never terminates, so it would wedge the single request thread
        // forever. Refuse it rather than hang.
        ?: if (request.args.any { it == "--watch" || it == "-w" }) {
            CompileResponse(
                output = "xtsc: --watch is not supported over the compile server " +
                    "(it would hold the single request thread open forever)\n",
                exitCode = XTSC_REFUSED,
                elapsedMs = 0,
                protocolVersion = XTSC_PROTOCOL_VERSION,
            )
        } else {
            compileCapturing(request.args, request.workingDirectory)
        }

    /**
     * Binds [socketPath] and serves requests until the process is killed.
     *
     * A stale socket file from a crashed server is reclaimed: if nothing
     * answers a connect on it, it is deleted and re-bound. If something DOES
     * answer, this exits rather than stealing a live server's socket.
     */
    fun serve(socketPath: String = defaultSocketPath()) {
        socketPathProblem(socketPath)?.let { System.err.println("xtsc: $it"); return }
        if (!UnixSocketAddress.isSupported()) {
            System.err.println(
                "xtsc: Unix domain sockets are not available on this JVM/OS " +
                    "(they need Java 16+, and Windows 10 1803+)"
            )
            return
        }
        val address = UnixSocketAddress(socketPath)
        val socketFile = File(socketPath)
        runBlocking {
            SelectorManager(Dispatchers.IO).use { selector ->
                if (socketFile.exists()) {
                    if (probe(selector, address)) {
                        System.err.println("xtsc: a server is already listening on $socketPath")
                        return@runBlocking
                    }
                    println("xtsc: reclaiming stale socket $socketPath")
                    socketFile.delete()
                }
                val server = try {
                    aSocket(selector).tcp().bind(address)
                } catch (e: Exception) {
                    System.err.println("xtsc: cannot bind $socketPath: ${e.message}")
                    return@runBlocking
                }
                server.use {
                    Runtime.getRuntime().addShutdownHook(Thread { socketFile.delete() })
                    println("xtsc compile server listening on $socketPath")
                    println("  requests are served sequentially; the first is cold, later ones warm")
                    var served = 0
                    while (true) {
                        val client = try {
                            server.accept()
                        } catch (e: Exception) {
                            System.err.println("xtsc: accept failed: ${e.message}")
                            continue
                        }
                        // NOT `launch { }` — see invariant 1. One connection is
                        // handled to completion before the next is accepted.
                        client.use { socket ->
                            // A connection that delivers NO frame is a
                            // reachability probe, not a failed request: that is
                            // exactly what `isDaemonReachable` does, and the
                            // client polls it every 25-500 ms while waiting for a
                            // daemon it just spawned. Logging those as failures
                            // fills the daemon's log with alarming lines
                            // describing its own health check, so a probe is
                            // recognised here and passed over in silence.
                            val text = try {
                                socket.openReadChannel().readFrame()
                            } catch (_: Exception) {
                                null
                            }
                            if (text == null) return@use
                            try {
                                val request = xtscProtocolJson
                                    .decodeFromString<CompileRequest>(text)
                                protocolProblem(request.protocolVersion)?.let {
                                    System.err.println(
                                        "xtsc: refusing a client that speaks protocol " +
                                            "${request.protocolVersion} — $it"
                                    )
                                }
                                val response = onCompileThread { respondTo(request) }
                                val out = socket.openWriteChannel(autoFlush = false)
                                out.writeFrame(xtscProtocolJson.encodeToString(response))
                                out.flushAndClose()
                                served++
                                System.err.println(
                                    "xtsc: request $served served in ${response.elapsedMs} ms"
                                )
                            } catch (e: Exception) {
                                System.err.println("xtsc: request failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    /** True iff something is accepting connections on [address]. */
    private suspend fun probe(selector: SelectorManager, address: UnixSocketAddress): Boolean = try {
        aSocket(selector).tcp().connect(address).use { true }
    } catch (_: Exception) {
        false
    }

    /**
     * Sends [args] to a running server. Returns null when none is reachable,
     * so the caller can fall back to compiling in-process rather than failing.
     *
     * A PROTOCOL MISMATCH also returns null. A daemon outlives the client that
     * started it by days, so the two are routinely different builds; answering
     * from a peer that may not understand this request is worse than doing the
     * work here, and the message says which way to fix it.
     */
    fun request(
        args: List<String>,
        socketPath: String = defaultSocketPath(),
        // THIS process's directory, because `request` runs in the CLIENT — the
        // JVM dispatcher, launched by the user in the user's project. The daemon
        // cannot know it any other way and a JVM cannot change its own cwd.
        workingDirectory: String = File(".").absolutePath.removeSuffix("/.").ifEmpty { "/" },
    ): CompileResponse? {
        socketPathProblem(socketPath)?.let { System.err.println("xtsc: $it"); return null }
        if (!UnixSocketAddress.isSupported()) return null
        val address = UnixSocketAddress(socketPath)
        val response = try {
            runBlocking {
                SelectorManager(Dispatchers.IO).use { selector ->
                    aSocket(selector).tcp().connect(address).use { socket ->
                        val out: ByteWriteChannel = socket.openWriteChannel(autoFlush = false)
                        out.writeFrame(
                            xtscProtocolJson.encodeToString(
                                CompileRequest(
                                    args = args,
                                    protocolVersion = XTSC_PROTOCOL_VERSION,
                                    workingDirectory = workingDirectory,
                                )
                            )
                        )
                        out.flush()
                        val input: ByteReadChannel = socket.openReadChannel()
                        xtscProtocolJson.decodeFromString<CompileResponse>(input.readFrame())
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        protocolProblem(response.protocolVersion)?.let {
            System.err.println("xtsc: $it — compiling in-process instead")
            return null
        }
        return response
    }
}
