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

package com.xemantic.typescript.tsgo.go.sync

import java.util.concurrent.locks.LockSupport

internal actual fun parkToken(): Any = Thread.currentThread()

internal actual fun park(blocker: Any) {
    LockSupport.park(blocker)
}

internal actual fun unpark(token: Any) {
    LockSupport.unpark(token as Thread)
}

/**
 * Goroutines run on daemon platform threads with Go's maximum goroutine stack (reserved, committed as
 * it is touched), REUSED while idle: a program's parse queues a goroutine per file and import, most of
 * them short or parked on a per-file mutex, and creating a 1 GB-stack thread for each was a visible part
 * of a parallel build. Unbounded (a cached pool: a direct hand-off, a new thread whenever none is idle),
 * so a goroutine waiting on another goroutine can never starve it, as in Go; how many of them RUN at once
 * is bounded by [GoProcs]' run tokens instead ((TSGO.6-e)).
 */
private val goroutines: java.util.concurrent.ExecutorService by lazy {
    val n = java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.ThreadPoolExecutor(
        0, Int.MAX_VALUE, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    ) { r ->
        GoroutineThread(r, "goroutine-${n.incrementAndGet()}").also { it.isDaemon = true }
    }
}

/** A goroutine's thread; [holdsProc] is whether it currently holds a [GoProcs] run token. */
private class GoroutineThread(r: Runnable, name: String) : Thread(null, r, name, 1L shl 30) {
    @JvmField
    var holdsProc = false
}

internal actual fun goProcHeld(): Boolean = (Thread.currentThread() as? GoroutineThread)?.holdsProc ?: false

/** Only a goroutine thread holds tokens ([GoProcs]); another thread has nowhere to record one. */
internal actual fun setGoProcHeld(held: Boolean) {
    (Thread.currentThread() as? GoroutineThread)?.holdsProc = held
}

internal actual fun goProcLimitDefault(): Int =
    System.getenv("TSGO_GOMAXPROCS")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        ?: Runtime.getRuntime().availableProcessors()

/**
 * A goroutine's unrecovered panic is fatal to a Go program, and so is a throwable escaping [f] here. Every
 * caller (WaitGroup.Go, onGoStack) catches inside `f`, so one arriving here escaped THAT handler — measured:
 * an out-of-memory error raised again while the handler allocated — and the goroutine never signalled whoever
 * waits on it. Left to the pool it killed the worker thread and the waiter parked forever: the GraalVM image
 * HUNG at 0% CPU on type-fest instead of failing. Exit status 2 is Go's for an unrecovered panic; `halt`,
 * because shutdown hooks may need the memory that just ran out.
 */
internal actual fun startGoroutineThread(body: () -> Unit) {
    goroutines.execute {
        try {
            body()
        } catch (t: Throwable) {
            runCatching { System.err.println("fatal error: goroutine: $t") }
            Runtime.getRuntime().halt(2)
        }
    }
}
