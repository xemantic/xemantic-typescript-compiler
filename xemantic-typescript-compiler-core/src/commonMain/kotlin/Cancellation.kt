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
 */

package com.xemantic.typescript.compiler

/**
 * (INC.55) A host's answer to "should this build stop?", polled BY THE COMPILE
 * THREAD.
 *
 * The compiler runs its pipeline on its own deep-stack thread and JOINS it before
 * returning, so the calling thread is blocked for the whole build and cannot
 * abandon it from outside. Cancellation therefore has to be COOPERATIVE: the host
 * supplies this, and the compiler polls it.
 *
 * Implementations must be safe to call from a thread other than the one that
 * created them, and must be cheap — [Cancellation] polls at pass boundaries and
 * every [Cancellation.SPINE_POLL_INTERVAL] spine nodes.
 *
 * On the IntelliJ platform this is `ProgressManager.getInstance().progressIndicator`,
 * i.e. `{ indicator.isCanceled }` — the platform restarts analysis on every write
 * action, so a build whose answer is already stale should stop rather than finish.
 */
fun interface CancellationSignal {
    /** True once the host no longer wants this build's answer. */
    fun isCancelled(): Boolean
}

/**
 * (INC.55) Thrown on the compile thread when the installed [CancellationSignal]
 * answers true, and rethrown on the caller's thread by `runWithDeepStack`.
 *
 * ## Why this is an `Error` and not an `Exception`, which is deliberate
 *
 * The checker carries defensive `catch (Exception)` guards — narrowed from
 * `Throwable` on 2026-07-04 for exactly this class of reason — and the crawl and
 * `Vfs` carry more. A cancellation modelled as a `RuntimeException` would be
 * SWALLOWED by whichever guard it happened to be thrown inside, and the build
 * would carry on with a missing file or a wrong default: a silently wrong answer,
 * which is worse than not cancelling at all.
 *
 * `Error` is safe because the sweep left NO `catch (Throwable)` and NO
 * `catch (Error)` anywhere in `commonMain`, and the one `StackOverflowError`
 * boundary guard in `Checker`'s `init` catches that type alone. `runWithDeepStack`
 * transfers it faithfully because it uses `runCatching`, which captures
 * `Throwable`.
 *
 * That reasoning is pinned by `CancellationTest`, not left as a comment: an added
 * `catch (Throwable)` anywhere on the build path would otherwise re-open it
 * silently.
 */
class CompilationCancelledError : Error("compilation cancelled by the host")

/**
 * (INC.55) The [CancellationSignal] of the build currently running in this process,
 * installed and restored around one build.
 *
 * ## Why a process-global with install-and-restore
 *
 * It is the house pattern for exactly this shape — `SystemVfs.workingDirectory`
 * (round 873) is a per-request global installed and restored on the one compile
 * thread, and `CompileServer` serialises requests onto that thread by design. It
 * matches `Project`'s own documented contract ("one Project belongs to one thread
 * at a time"), and the alternative — threading a parameter through every checker
 * helper — is a change to hundreds of signatures for no added safety.
 *
 * The COST of that choice, stated rather than discovered: two builds running
 * CONCURRENTLY in one process share this field, so the second install wins and the
 * first build would poll the wrong signal. That is already outside `Project`'s
 * contract, and `--workers` does not install one at all.
 *
 * ## Why polling is essentially free
 *
 * [check] is called at every `pass("…")` boundary (~480 per compile) and, in the
 * spine walk, once per [SPINE_POLL_INTERVAL] nodes — 837 polls for the compiler
 * profile's 856,962 nodes. The hot loop's own comment refuses interleaved work, so
 * the poll is behind a counter rather than per node: an int increment, a mask and a
 * predictable branch, against a volatile read every 1024th time.
 */
object Cancellation {

    /**
     * Spine nodes between polls. A power of two minus one, used as a mask.
     *
     * 1024 nodes is tens of microseconds of checking, i.e. far below the latency at
     * which a host would notice, while keeping the volatile reads to ~837 per full
     * compile of tsc's own sources.
     */
    const val SPINE_POLL_INTERVAL: Int = 1024

    @Volatile
    private var signal: CancellationSignal? = null

    /** Installs [s] for the current build and answers what was there. */
    fun install(s: CancellationSignal?): CancellationSignal? {
        val previous = signal
        signal = s
        return previous
    }

    /** Restores what [install] answered. Always call this in a `finally`. */
    fun restore(previous: CancellationSignal?) {
        signal = previous
    }

    /** Whether a host has asked to be able to cancel this build. */
    val armed: Boolean get() = signal != null

    /** Throws [CompilationCancelledError] if the host has cancelled. */
    fun check() {
        val s = signal ?: return
        if (s.isCancelled()) throw CompilationCancelledError()
    }
}
