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

// (TSGO.6-e) Go's scheduler Ps: at most [GoProcs.limit] goroutines RUN at once. docs/goport-runtime.md § 9a.
//
// A goroutine HOLDS one of N run tokens while it executes, gives it back whenever it blocks ([blockingWait],
// around every park of the `sync` shims and of `onGoStack`) and takes one again when it wakes, before it
// re-checks its condition. A goroutine that holds a token is therefore never blocked indefinitely (it runs, or
// spins a bounded number of rounds), so every goroutine some holder waits for eventually gets a token: the
// limit cannot deadlock, at any N >= 1. A goroutine started while every token is held is QUEUED as a closure
// (no thread) — Go's run queue — and gets a thread together with a token. Threads themselves stay unbounded:
// one per running or BLOCKED goroutine (a bounded pool deadlocks tsgo's parse, which queues a goroutine per
// file while holding mutexes those goroutines take). Tokens are handed off FIFO: nobody waiting is starved.

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The default run limit: `TSGO_GOMAXPROCS` when set to a positive integer, else the number of available
 * processors (Go's `GOMAXPROCS` default is `runtime.NumCPU()`). Read once.
 */
internal expect fun goProcLimitDefault(): Int

/** Whether the current thread holds a run token (only a goroutine thread ever does). */
internal expect fun goProcHeld(): Boolean

internal expect fun setGoProcHeld(held: Boolean)

/**
 * Runs [body] on a pooled goroutine thread (Go's 1 GB maximum goroutine stack, reserved and committed as
 * touched). A throwable escaping [body] is FATAL: `fatal error: goroutine: …` on stderr, exit status 2.
 */
internal expect fun startGoroutineThread(body: () -> Unit)

/**
 * The run tokens and Go's run queue. One FIFO holds everything waiting for a token: a PARKED goroutine thread
 * (woken from a block, it needs a token to continue) or a goroutine NOT YET STARTED ([go] found every token
 * held) — the latter has no thread at all, as a queued Go goroutine has no M, so a fan-out of thousands of
 * goroutines costs closures, not threads. A released token goes to the head of the queue (a thread is unparked,
 * or a thread is started for the closure, the token handed over), else back to the free count.
 */
internal object GoProcs {

    private sealed class Entry

    /** A parked thread waiting for a token; [granted] is set before it is unparked. */
    private class Waiter(val token: Any) : Entry() {
        val granted = AtomicInt(0)
    }

    /** A goroutine that has not started: it gets a thread together with its token. */
    private class Pending(val f: () -> Unit) : Entry()

    /** The run limit (Go's `GOMAXPROCS`); written only under [guard]. */
    var limit: Int = goProcLimitDefault().coerceAtLeast(1)
        private set

    /** Tokens not held by anyone. `free > 0` implies an empty queue (a release hands off to the queue first). */
    private val free = AtomicInt(limit)

    private val guard = AtomicInt(0)
    private val queue = ArrayDeque<Entry>()

    // statistics (TSGO_GOROUTINE_STATS)
    private val held = AtomicInt(0)
    private val peakHeld = AtomicInt(0)
    private val queuedAcquires = AtomicInt(0)
    private val queuedStarts = AtomicInt(0)

    private inline fun <R> guarded(block: () -> R): R {
        var round = 0
        while (!guard.compareAndSet(0, 1)) spinWait(round++, guard)
        try {
            return block()
        } finally {
            guard.store(0)
        }
    }

    private fun tryTake(): Boolean {
        while (true) {
            val f = free.load()
            if (f <= 0) return false
            if (free.compareAndSet(f, f - 1)) return true
        }
    }

    /** Takes a token now, or queues [e] — under the guard, as `free` is only ever INCREASED under it. */
    private fun takeOrEnqueue(e: Entry): Boolean {
        if (tryTake()) return true
        return guarded {
            if (tryTake()) {
                true
            } else {
                queue.addLast(e)
                false
            }
        }
    }

    private fun noteHeld() {
        val h = held.incrementAndFetch()
        while (true) {
            val p = peakHeld.load()
            if (h <= p || peakHeld.compareAndSet(p, h)) break
        }
    }

    /** Starts [f] on a goroutine thread that already holds a token (counted by the caller). */
    private fun start(f: () -> Unit) {
        noteHeld()
        startGoroutineThread {
            setGoProcHeld(true)
            try {
                f()
            } finally {
                release()
            }
        }
    }

    private fun grant(e: Entry) {
        when (e) {
            is Waiter -> {
                e.granted.store(1)
                unpark(e.token)
            }
            is Pending -> start(e.f)
        }
    }

    /** `go f()`: runs [f] on a goroutine thread as soon as a token is free (now, or queued FIFO). */
    fun go(f: () -> Unit) {
        if (takeOrEnqueue(Pending(f))) start(f) else queuedStarts.incrementAndFetch()
    }

    /**
     * Takes a run token for the current thread, waiting (parked, FIFO) while all [limit] are held. The
     * caller must not hold one already.
     */
    fun acquire() {
        val w = Waiter(parkToken())
        if (!takeOrEnqueue(w)) {
            queuedAcquires.incrementAndFetch()
            while (w.granted.load() == 0) park(this)
        }
        noteHeld()
        setGoProcHeld(true)
    }

    /** Gives the current thread's token back: to the head of the queue, or to the free count. */
    fun release() {
        setGoProcHeld(false)
        held.decrementAndFetch()
        val e = guarded {
            val head = if (free.load() >= 0) queue.removeFirstOrNull() else null
            if (head == null) free.incrementAndFetch()
            head
        } ?: return
        grant(e) // the new holder counts itself (start / acquire)
    }

    /**
     * Sets the limit (tests; Go's `runtime.GOMAXPROCS(n)`). Tokens already held stay held; a lowered limit
     * takes effect as they are released (the free count goes negative until then).
     */
    fun setLimit(n: Int): Int {
        require(n >= 1)
        val granted = ArrayList<Entry>()
        val old = guarded {
            val old = limit
            limit = n
            free.addAndFetch(n - old)
            while (free.load() > 0) {
                val head = queue.removeFirstOrNull() ?: break
                free.decrementAndFetch()
                granted += head
            }
            old
        }
        for (e in granted) grant(e)
        return old
    }

    /** `procs=… peakRunning=… queuedStarts=… queuedAcquires=…`. */
    fun stats(): String =
        "procs=$limit peakRunning=${peakHeld.load()} queuedStarts=${queuedStarts.load()} queuedAcquires=${queuedAcquires.load()}"

    /** Peak tokens held at once since the last [resetPeak] (tests). */
    fun peakRunning(): Int = peakHeld.load()

    fun resetPeak() {
        peakHeld.store(held.load())
    }
}

/**
 * Runs [wait] — an indefinite block — without the current thread's run token, if it holds one, and takes
 * a token again before returning (Go: a blocked goroutine gives up its P; a runnable one needs a P to run).
 */
internal inline fun <R> blockingWait(wait: () -> R): R {
    if (!goProcHeld()) return wait()
    GoProcs.release()
    try {
        return wait()
    } finally {
        GoProcs.acquire()
    }
}
