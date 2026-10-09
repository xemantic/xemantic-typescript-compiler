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

@file:OptIn(ExperimentalAtomicApi::class)

package com.xemantic.typescript.tsgo.go.sync

// Blocking for the `sync` shims. Common Kotlin cannot park a thread, so the primitive is an
// `expect` per platform ([parkToken] / [park] / [unpark], LockSupport semantics: an `unpark` that
// arrives before the `park` makes it return at once, and `park` may return spuriously). On top of
// it, [WaitQueue] gives "block until a condition holds" without lost wake-ups: a waiter REGISTERS
// before it re-checks its condition, and every state change that can make a condition true is
// followed by a signal when [WaitQueue.waiting] is non-zero (both are sequentially consistent
// atomics, so one of the two always sees the other). docs/goport-runtime.md § 9a.

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/** The current thread, as a token [unpark] accepts. */
internal expect fun parkToken(): Any

/** Blocks the current thread until [unpark]ed (a pending permit makes it return at once), or spuriously. */
internal expect fun park(blocker: Any)

/** Wakes the thread of [token] (or gives it a permit for its next [park]). */
internal expect fun unpark(token: Any)

/**
 * Runs [f] concurrently — a goroutine (`WaitGroup.Go`, `onGoStack`; docs/goport-runtime.md § 9a): on a
 * goroutine thread ([startGoroutineThread]: a large stack, as a goroutine's grows to 1 GB in Go and the
 * checker recurses deeply), holding one of [GoProcs]' run tokens — queued until one is free.
 */
internal fun goSpawn(f: () -> Unit) = GoProcs.go(f)

/**
 * A FIFO of parked threads, each waiting for its own condition. [await] spins briefly, then parks;
 * a woken waiter re-checks its condition and parks again if another thread got there first (no
 * hand-off, so a lock is not fair — as Go's normal mode is not).
 */
internal class WaitQueue {

    private class Waiter(val token: Any) {
        val signaled = AtomicInt(0)
    }

    /** Waiters registered (enqueued or about to be). A signaller skips all work while it is 0. */
    val waiting = AtomicInt(0)

    // a tiny spin lock guarding [queue]: held only for an ArrayDeque operation
    private val guard = AtomicInt(0)
    private val queue = ArrayDeque<Waiter>()

    private inline fun <R> guarded(block: () -> R): R {
        var round = 0
        while (!guard.compareAndSet(0, 1)) spinWait(round++, guard)
        try {
            return block()
        } finally {
            guard.store(0)
        }
    }

    /** Returns once [ready] (which may ACQUIRE as its side effect, e.g. a CAS) answers true. */
    fun await(probe: AtomicInt, ready: () -> Boolean) {
        for (round in 0 until SPIN_ROUNDS) {
            if (ready()) return
            spinWait(round, probe)
        }
        awaitSlow(ready)
    }

    private fun awaitSlow(ready: () -> Boolean) {
        val token = parkToken()
        while (true) {
            val w = Waiter(token)
            waiting.incrementAndFetch()
            guarded { queue.addLast(w) }
            if (ready()) {
                guarded { queue.remove(w) }
                waiting.decrementAndFetch()
                return
            }
            // a goroutine gives its run token back while it is parked, and takes one before it re-checks
            blockingWait { while (w.signaled.load() == 0) park(this) }
            waiting.decrementAndFetch()
            if (ready()) return
        }
    }

    /** Wakes the longest-waiting thread, if any. */
    fun signalOne() {
        val w = guarded { queue.removeFirstOrNull() } ?: return
        w.signaled.store(1)
        unpark(w.token)
    }

    /** Wakes every waiting thread. */
    fun signalAll() {
        val ws = guarded {
            val all = queue.toList()
            queue.clear()
            all
        }
        for (w in ws) {
            w.signaled.store(1)
            unpark(w.token)
        }
    }

    companion object {
        /** Spin rounds before parking (round r reads the probe 2^r times). */
        const val SPIN_ROUNDS = 7
    }
}
