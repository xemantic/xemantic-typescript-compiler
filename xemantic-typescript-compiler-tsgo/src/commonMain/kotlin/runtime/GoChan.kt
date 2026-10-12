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

package com.xemantic.typescript.tsgo.runtime

import com.xemantic.typescript.tsgo.go.sync.WaitQueue
import com.xemantic.typescript.tsgo.go.sync.goYield
import com.xemantic.typescript.tsgo.go.sync.spinWait
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A Go channel `chan T` (docs/goport-runtime.md § 10 #19); a nil channel is `null` (the top-level `go*` functions
 * below give it Go's meaning). tsgo uses channels sparingly — `chan struct{}` "done" signals closed once and
 * received by many (`execute/build`), a buffered channel of one with a non-blocking send (`watchmanager`) — so
 * the design favours being obviously correct over throughput: every channel's state sits behind a short spin
 * [guard], and every blocking operation waits on ONE process-wide [WaitQueue] for a change to ANY channel
 * ([changed]): a waiter registers before it re-checks, so no wake-up is lost, and a `select` over several
 * channels needs nothing more.
 *
 * Unbuffered sends are rendezvous: a sender queues its value and blocks until a receiver takes it (or the
 * channel is closed under it, which panics, as in Go). A non-blocking send ([trySend], `select` with
 * `default`) to an unbuffered channel succeeds only while a receiver is blocked in [recv].
 */
class GoChan<T> private constructor(private val elem: GoElem<T>, val cap: Int) {

    private class Sender(val value: Any?) {
        var taken = false
    }

    private val guard = AtomicInt(0)
    private val buffer = ArrayDeque<Any?>()
    private val senders = ArrayDeque<Sender>()
    private var closed = false
    /** Receivers blocked in [recv] (an unbuffered [trySend] may hand to one of them). */
    private var blockedReceivers = 0

    private inline fun <R> guarded(block: () -> R): R {
        var round = 0
        while (!guard.compareAndSet(0, 1)) if (round < 6) spinWait(round++, guard) else goYield()
        try {
            return block()
        } finally {
            guard.store(0)
        }
    }

    /** `len(ch)`: buffered values. */
    val len: Int get() = guarded { buffer.size }

    /** `ch <- v`. */
    fun send(value: T) {
        var pending: Sender? = null
        val done = guarded {
            if (closed) goPanic(GoPlainError("send on closed channel"))
            if (cap > 0) {
                if (buffer.size < cap) {
                    buffer.addLast(value)
                    true
                } else false
            } else {
                pending = Sender(value).also { senders.addLast(it) }
                false
            }
        }
        if (done) return changed()
        val sender = pending
        if (sender != null) {
            changed()
            await {
                guarded {
                    when {
                        sender.taken -> true
                        closed -> {
                            senders.remove(sender)
                            goPanic(GoPlainError("send on closed channel"))
                        }
                        else -> false
                    }
                }
            }
            return
        }
        await {
            guarded {
                if (closed) goPanic(GoPlainError("send on closed channel"))
                if (buffer.size < cap) {
                    buffer.addLast(value)
                    true
                } else false
            }
        }
        changed()
    }

    /** `select { case ch <- v: … default: … }`: true when the value was sent without blocking. */
    fun trySend(value: T): Boolean {
        val sent = guarded {
            if (closed) goPanic(GoPlainError("send on closed channel"))
            when {
                cap > 0 && buffer.size < cap -> {
                    buffer.addLast(value)
                    true
                }
                cap == 0 && blockedReceivers > senders.size -> {
                    senders.addLast(Sender(value))
                    true
                }
                else -> false
            }
        }
        if (sent) changed()
        return sent
    }

    /** `<-ch`: the next value, or the zero value once the channel is closed and drained. */
    fun recv(): T = recv2().first

    /** `v, ok := <-ch`. */
    fun recv2(): Tuple2<T, Boolean> {
        tryRecv2()?.let { return it }
        guarded { blockedReceivers++ }
        var got: Tuple2<T, Boolean>? = null
        try {
            await {
                got = tryRecvLocked()
                got != null
            }
        } finally {
            guarded { blockedReceivers-- }
        }
        return got!!
    }

    /** A receive that does not block: the result, or null when nothing is ready. */
    fun tryRecv2(): Tuple2<T, Boolean>? = tryRecvLocked()

    @Suppress("UNCHECKED_CAST")
    private fun tryRecvLocked(): Tuple2<T, Boolean>? {
        var wake = false
        val r = guarded {
            when {
                buffer.isNotEmpty() -> {
                    val v = buffer.removeFirst() as T
                    // a blocked sender of a full buffered channel can move in now (it re-checks on [changed])
                    wake = true
                    Tuple2(v, true)
                }
                senders.isNotEmpty() -> {
                    val s = senders.removeFirst()
                    s.taken = true
                    wake = true
                    Tuple2(s.value as T, true)
                }
                closed -> Tuple2(elem.zeroValue(), false)
                else -> null
            }
        }
        if (wake) changed()
        return r
    }

    /** `close(ch)`. */
    fun close() {
        guarded {
            if (closed) goPanic(GoPlainError("close of closed channel"))
            closed = true
        }
        changed()
    }

    companion object {
        /** `make(chan T, n)`. */
        fun <T> make(elem: GoElem<T>, cap: Int = 0): GoChan<T> {
            if (cap < 0) goPanic(GoRuntimeError("makechan: size out of range"))
            return GoChan(elem, cap)
        }

        private val version = AtomicInt(0)
        private val waiters = WaitQueue()

        /**
         * A goroutine of a `WaitGroup` failed (a Go panic, which in Go ends the PROCESS). Its channel operations
         * never happen — a `close(t.done)` the others wait for, say — so while this is set, every blocked or
         * blocking channel operation rethrows it instead of waiting: the goroutines unwind, the `WaitGroup`'s
         * count reaches zero, and its `Wait` rethrows the first failure to the caller (who reports it) instead
         * of the process hanging. Process-wide, as the Go panic is; cleared by the `Wait` that rethrows it.
         */
        private val failure = AtomicReference<Throwable?>(null)

        internal fun goroutineFailed(t: Throwable) {
            if (failure.compareAndSet(null, t)) changed()
        }

        internal fun goroutineFailureReported(t: Throwable) {
            failure.compareAndSet(t, null)
        }

        /** Every state change of any channel: wakes every waiter to re-check. */
        internal fun changed() {
            version.incrementAndFetch()
            if (waiters.waiting.load() != 0) waiters.signalAll()
        }

        /** Blocks until [ready] (which performs the operation when it can) answers true, or a goroutine failed. */
        internal fun await(ready: () -> Boolean) {
            failure.load()?.let { throw it }
            var done = false
            waiters.await(version) {
                if (failure.load() != null) true
                else {
                    done = ready()
                    done
                }
            }
            if (!done) throw failure.load()!!
        }
    }
}

/** `ch <- v`; a nil channel blocks forever. */
fun <T> goSend(ch: GoChan<T>?, value: T) {
    if (ch == null) goBlockForever()
    ch.send(value)
}

/** `<-ch`; a nil channel blocks forever. */
fun <T> goRecv(ch: GoChan<T>?): T {
    if (ch == null) goBlockForever()
    return ch.recv()
}

/** `v, ok := <-ch`; a nil channel blocks forever. */
fun <T> goRecv2(ch: GoChan<T>?): Tuple2<T, Boolean> {
    if (ch == null) goBlockForever()
    return ch.recv2()
}

/** `close(ch)`. */
fun goClose(ch: GoChan<*>?) {
    if (ch == null) goPanic(GoPlainError("close of nil channel"))
    ch.close()
}

/** `len(ch)` / `cap(ch)` of a possibly nil channel. */
val GoChan<*>?.goLen: Int get() = this?.len ?: 0
val GoChan<*>?.goCap: Int get() = this?.cap ?: 0

/** A goroutine blocked on a nil channel never runs again (Go reports a deadlock only when all are). */
fun goBlockForever(): Nothing {
    GoChan.await { false }
    error("unreachable")
}
