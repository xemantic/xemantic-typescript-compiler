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

@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalAtomicApi::class)

package com.xemantic.typescript.tsgo.go.sync

// (TSGO.6) The Kotlin/Native `actual`s of the park and the goroutine spawn (docs/goport-runtime.md § 9a).
//
// PARK: a per-thread token holding a `pthread_mutex` + `pthread_cond` and a PERMIT flag, i.e.
// `LockSupport` semantics — an `unpark` before the `park` leaves the permit set and the `park` returns
// at once; a `park` may return spuriously (the `WaitQueue` protocol re-checks its condition). The token
// is created lazily per thread (`@ThreadLocal`); its pthread objects are freed by a cleaner when the
// token is collected — a token can outlive its thread while an unparker still holds it, so it must not
// be freed at thread exit.
//
// SPAWN: an unbounded cached pool of detached worker pthreads, the JVM actual's `ThreadPoolExecutor(0,
// MAX, 30 s, SynchronousQueue)` rebuilt on one pthread mutex: a spawn hands its closure DIRECTLY to an
// idle worker, and creates a new worker (Go's 1 GB maximum goroutine stack, reserved and committed as
// touched; 256 MB when that reservation is refused) only when none is idle — never a queue, so a
// goroutine waiting on another can never starve it (a bounded pool deadlocks tsgo's parse). An idle
// worker exits after [KEEP_ALIVE_SECONDS] without work.

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import platform.posix.CLOCK_REALTIME
import platform.posix.ETIMEDOUT
import platform.posix.PTHREAD_CREATE_DETACHED
import platform.posix.clock_gettime
import platform.posix.pthread_cond_timedwait
import platform.posix.timespec
import platform.posix.pthread_attr_destroy
import platform.posix.pthread_attr_init
import platform.posix.pthread_attr_setdetachstate
import platform.posix.pthread_attr_setstacksize
import platform.posix.pthread_attr_t
import platform.posix.pthread_cond_destroy
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_signal
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_wait
import platform.posix.pthread_create
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_tVar

/** The pthread objects of one [ParkToken]; a separate object so the cleaner does not capture the token. */
private class ParkCell {
    val mutex = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }
    val cond = nativeHeap.alloc<pthread_cond_t>().also { pthread_cond_init(it.ptr, null) }

    /** 1 when a permit is available; read and written under [mutex]. */
    var permit = 0

    fun dispose() {
        pthread_cond_destroy(cond.ptr)
        pthread_mutex_destroy(mutex.ptr)
        nativeHeap.free(cond)
        nativeHeap.free(mutex)
    }
}

private class ParkToken {
    val cell = ParkCell()

    @Suppress("unused")
    private val cleaner: Cleaner = createCleaner(cell) { it.dispose() }
}

@ThreadLocal
private var currentToken: ParkToken? = null

private fun tokenOfThisThread(): ParkToken = currentToken ?: ParkToken().also { currentToken = it }

internal actual fun parkToken(): Any = tokenOfThisThread()

internal actual fun park(blocker: Any) {
    val c = tokenOfThisThread().cell
    pthread_mutex_lock(c.mutex.ptr)
    while (c.permit == 0) pthread_cond_wait(c.cond.ptr, c.mutex.ptr)
    c.permit = 0
    pthread_mutex_unlock(c.mutex.ptr)
}

internal actual fun unpark(token: Any) {
    val c = (token as ParkToken).cell
    pthread_mutex_lock(c.mutex.ptr)
    c.permit = 1
    pthread_cond_signal(c.cond.ptr)
    pthread_mutex_unlock(c.mutex.ptr)
}

/** Go's maximum goroutine stack on 64-bit platforms (`runtime/debug.SetMaxStack`'s default). */
private const val GOROUTINE_STACK_BYTES: ULong = 1_073_741_824uL

/** The fallback when the 1 GB reservation is refused (a tight `ulimit -v`): -core's deep-stack size. */
private const val FALLBACK_STACK_BYTES: ULong = 268_435_456uL

/** How long an idle worker waits for a closure before its thread exits (the JVM pool's keep-alive). */
private const val KEEP_ALIVE_SECONDS = 30L

/**
 * One pooled pthread. [task] and [idle] membership are read and written under [poolMutex] only; [cond]
 * is waited on with [poolMutex], so a hand-off and the worker's time-out are decided atomically.
 */
private class Worker(var task: (() -> Unit)?) {
    val cond = nativeHeap.alloc<pthread_cond_t>().also { pthread_cond_init(it.ptr, null) }
}

/** The pool's one lock (never freed: the pool lives as long as the process). */
private val poolMutex = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }

/** The idle workers, most recently idle LAST (a LIFO hand-off keeps the warmest stack in use). */
private val idle = ArrayList<Worker>()

private val spawns = AtomicInt(0)
private val threadsCreated = AtomicInt(0)
private val threadsLive = AtomicInt(0)
private val threadsPeak = AtomicInt(0)

/** `spawns=… threads=… peak=…`: how many goroutines ran on how many pthreads (`NativeCheckMain`). */
internal fun goroutinePoolStats(): String =
    "spawns=${spawns.load()} threads=${threadsCreated.load()} peakLive=${threadsPeak.load()} live=${threadsLive.load()}"

/**
 * A goroutine's unrecovered panic crashes a Go program, and so does this one. Every caller here
 * (WaitGroup.Go, onGoStack) catches inside `f`, so a throwable arriving here escaped THAT handler — an
 * out-of-memory error raised again while the handler allocated — and the goroutine never signalled
 * whoever waits on it. Carrying on would park that waiter forever: a hang, not an error.
 */
private fun runGoroutine(f: () -> Unit) {
    try {
        f()
    } catch (t: Throwable) {
        runCatching { platform.posix.fputs("fatal error: goroutine: $t\n", platform.posix.stderr) }
        platform.posix._exit(2)
    }
}

/**
 * Parks [w] in the idle list until a spawn hands it a closure (returned) or [KEEP_ALIVE_SECONDS] pass
 * without one (null: the worker has left the list and its thread exits). Holds [poolMutex] throughout
 * except while waiting, so a spawner that popped [w] always finds it waiting or about to re-check [Worker.task].
 */
private fun awaitTask(w: Worker): (() -> Unit)? = memScoped {
    val deadline = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, deadline.ptr)
    deadline.tv_sec += KEEP_ALIVE_SECONDS
    pthread_mutex_lock(poolMutex.ptr)
    try {
        idle.add(w)
        while (w.task == null) {
            val rc = pthread_cond_timedwait(w.cond.ptr, poolMutex.ptr, deadline.ptr)
            // A time-out races a hand-off only under the mutex: the task wins if it was set first.
            if (rc == ETIMEDOUT && w.task == null) {
                idle.remove(w)
                return@memScoped null
            }
        }
        w.task.also { w.task = null }
    } finally {
        pthread_mutex_unlock(poolMutex.ptr)
    }
}

private val workerRoutine = staticCFunction<COpaquePointer?, COpaquePointer?> { arg ->
    val ref = arg!!.asStableRef<Worker>()
    val w = ref.get()
    ref.dispose()
    var f = w.task.also { w.task = null }
    while (f != null) {
        runGoroutine(f)
        f = awaitTask(w)
    }
    threadsLive.decrementAndFetch()
    // Not reachable by any spawner any more (it left the idle list under the mutex): free its cond.
    pthread_cond_destroy(w.cond.ptr)
    nativeHeap.free(w.cond)
    null
}

private fun spawnWithStack(ref: StableRef<Worker>, stack: ULong): Boolean = memScoped {
    val attr = alloc<pthread_attr_t>()
    if (pthread_attr_init(attr.ptr) != 0) return@memScoped false
    try {
        pthread_attr_setstacksize(attr.ptr, stack)
        pthread_attr_setdetachstate(attr.ptr, PTHREAD_CREATE_DETACHED)
        val thread = alloc<pthread_tVar>()
        pthread_create(thread.ptr, attr.ptr, workerRoutine, ref.asCPointer()) == 0
    } finally {
        pthread_attr_destroy(attr.ptr)
    }
}

internal actual fun goSpawn(f: () -> Unit) {
    spawns.incrementAndFetch()
    pthread_mutex_lock(poolMutex.ptr)
    val w = idle.removeLastOrNull()
    if (w != null) {
        w.task = f
        pthread_cond_signal(w.cond.ptr)
    }
    pthread_mutex_unlock(poolMutex.ptr)
    if (w != null) return
    val worker = Worker(f)
    val ref = StableRef.create(worker)
    val live = threadsLive.incrementAndFetch()
    if (spawnWithStack(ref, GOROUTINE_STACK_BYTES) || spawnWithStack(ref, FALLBACK_STACK_BYTES)) {
        threadsCreated.incrementAndFetch()
        while (true) {
            val peak = threadsPeak.load()
            if (live <= peak || threadsPeak.compareAndSet(peak, live)) break
        }
        return
    }
    threadsLive.decrementAndFetch()
    ref.dispose()
    pthread_cond_destroy(worker.cond.ptr)
    nativeHeap.free(worker.cond)
    error("goSpawn: pthread_create failed")
}
