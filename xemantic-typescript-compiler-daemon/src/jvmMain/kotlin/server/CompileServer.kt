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

import com.xemantic.typescript.compiler.SystemVfs
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
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.time.TimeSource

/**
 * (SERVER.1) Stage 1 — a pre-warmed compile server.
 *
 * ## Why this exists
 *
 * The warm JVM is the fastest artifact this project has: ~~11,580 ms~~ on the
 * compiler profile against the AOT binary's 13,350 ms and a ~~26,272 ms~~ cold
 * CLI. All of that gap is JVM warm-up, which a one-shot CLI process can never
 * amortize. Holding one hot JVM and feeding it requests captures it.
 *
 * **Both absolutes superseded, round 843 (2026-08-07); the CLAIM is unchanged
 * and if anything stronger.** The 11,580/26,272 pair is round 771's, on the
 * retired 4-core box. Two independent later measurements, each self-consistent
 * and NOT comparable to the other (different boxes, different rounds — only
 * within-round pairs are quotable):
 *  - this box, round 843: cold **22,971 ms**, warm steady state **~7,030 ms**,
 *    and an eight-request ladder through this server reading 22,753 / 10,898 /
 *    7,754 / 7,606 / 7,447 / 7,410 / 7,447 / 7,100 ms of client wall — steady
 *    from request 3, reproducing the in-process harness to within ~3%.
 *  - the four-way run in commit `eb42b853`: warm daemon **3,322 ms** against
 *    **tsc 6.0.3 at 4,489 ms**, i.e. the first configuration on record in which
 *    this compiler BEATS the reference implementation on a real project.
 * The cause of the warm improvement over round 771 is unattributed; the leading
 * hypothesis is the (JIT.1) huge-method arc. See
 * `docs/perf/warm-jvm-attribution.md`. Note round 843's ladder was measured on
 * the PRE-MODULE-SPLIT server (commit `778faf2c`), before this file moved into
 * the daemon module and onto ktor sockets — the ladder SHAPE should survive
 * that, but it has not been re-measured here.
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
 * ## Three invariants a change here must preserve
 *
 * 1. **Requests are served SEQUENTIALLY, on ONE thread — and it is the SAME
 *    thread for every request.** This is stronger than "one at a time" and the
 *    difference is the whole reason [compileDispatcher] exists rather than a
 *    plain `Dispatchers.IO`. Symbol/Type id sequences are THREAD-LOCAL
 *    (INV.6(6c0)) and `runWithDeepStack` hands them off by capturing the
 *    caller's counters, seeding the compile thread, and writing the advanced
 *    values back on join. A coroutine that migrated to a different carrier
 *    thread between requests would hand off from a thread whose counters are
 *    still at zero, restarting ids at 1 and colliding with the singleton
 *    intrinsics — a failure that leaves **every byte of output identical** and
 *    is therefore invisible to any diff. `--workers` has separately been
 *    measured producing 62 diagnostics where the sequential path produces 46.
 * 2. **The request runs the ORDINARY [com.xemantic.typescript.compiler.main]**,
 *    with stdout captured. Nothing about argument parsing, diagnostic
 *    formatting or exit semantics is reimplemented here, so server output is
 *    identical to CLI output *by construction* rather than by testing — the
 *    tests then check that the construction holds. `System.setOut` is
 *    process-global, which is a second, independent reason serving cannot be
 *    concurrent.
 * 3. **The wire format lives in the `-api` module**, not here. The client is a
 *    separately-built binary, so a framing or schema change that reaches only
 *    one side presents as a HANG rather than as a type error.
 *
 * Reusing one JVM across compiles is not speculative: `BenchMain` runs twelve
 * in-process rebuilds and every one reports the same 78 files and 46 errors.
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

    /**
     * The one thread every compile runs on — see invariant 1. Single-threaded is
     * load-bearing, not a throughput choice: the id handoff is only coherent
     * while the same thread hands off each time.
     */
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
     * Runs one compile with stdout captured, on the calling thread.
     *
     * The exit code is derived from the compiler's own summary line rather than
     * recomputed, so it cannot drift from what the CLI reports.
     */
    private fun compileCapturing(args: List<String>, workingDirectory: String): CompileResponse {
        val buffer = ByteArrayOutputStream()
        val previous = System.out
        val previousCwd = SystemVfs.workingDirectory
        val mark = TimeSource.Monotonic.markNow()
        var failed: Throwable? = null
        var code = 0
        try {
            System.setOut(PrintStream(buffer, true, StandardCharsets.UTF_8))
            // (SERVE.2) round 873. The request's directory, not this process's:
            // every relative path on the command line means something THERE, and
            // the most important one is the path a user did not type at all —
            // the CLI defaults the project to `"."`, so before this a
            // `xtsc --daemon --noEmit` compiled the DAEMON's directory and
            // reported OK on a project full of errors. Install-and-restore, on
            // the one compile thread, exactly like the round-848 mode ledger:
            // this is process-global state and a request that left it set would
            // reconfigure every later one.
            SystemVfs.workingDirectory = workingDirectory.ifEmpty { null }
            // `runCli`, NOT `main`: main ends the process, which inside the
            // daemon would take the server down with the first project that has
            // an error. runCli returns the same code instead — so the server
            // reports the compiler's OWN verdict rather than deducing one by
            // searching the captured stdout for the word "FAILED", which is what
            // it used to do and which made the exit code depend on message
            // wording.
            code = com.xemantic.typescript.compiler.runCli(args.toTypedArray())
        } catch (e: Exception) {
            failed = e
        } finally {
            System.setOut(previous)
            SystemVfs.workingDirectory = previousCwd
        }
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        val text = buffer.toString(StandardCharsets.UTF_8) +
            (failed?.let { "\nserver: compile threw ${it::class.simpleName}: ${it.message}\n" } ?: "")
        val exit = if (failed != null) 1 else code
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
        // --watch never terminates, so it would wedge the single request thread
        // forever. Refuse it rather than hang.
        if (request.args.any { it == "--watch" || it == "-w" }) {
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
                    println(
                        "  requests are served sequentially; the first is cold (~26 s), " +
                            "later ones warm (~12 s)"
                    )
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
                                    System.err.println("xtsc: client mismatch — $it")
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
