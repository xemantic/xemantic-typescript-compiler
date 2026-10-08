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

@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

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
// SPAWN: a detached pthread with Go's 1 GB maximum goroutine stack (reserved, committed as touched, as
// the JVM's 1 GB `Thread` stack size is), 256 MB when that reservation is refused. One thread per
// goroutine — no idle-thread pool yet (the JVM actual reuses idle threads for 30 s).

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
import platform.posix.PTHREAD_CREATE_DETACHED
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

private val goroutineRoutine = staticCFunction<COpaquePointer?, COpaquePointer?> { arg ->
    val ref = arg!!.asStableRef<() -> Unit>()
    val f = ref.get()
    ref.dispose()
    // A Go program crashes on a goroutine's unrecovered panic; every caller here (WaitGroup.Go,
    // onGoStack) catches inside `f`, so this only keeps a stray throwable from killing the process
    // without a word.
    try {
        f()
    } catch (t: Throwable) {
        println("goroutine: uncaught $t")
    }
    null
}

private fun spawnWithStack(ref: StableRef<() -> Unit>, stack: ULong): Boolean = memScoped {
    val attr = alloc<pthread_attr_t>()
    if (pthread_attr_init(attr.ptr) != 0) return@memScoped false
    try {
        pthread_attr_setstacksize(attr.ptr, stack)
        pthread_attr_setdetachstate(attr.ptr, PTHREAD_CREATE_DETACHED)
        val thread = alloc<pthread_tVar>()
        pthread_create(thread.ptr, attr.ptr, goroutineRoutine, ref.asCPointer()) == 0
    } finally {
        pthread_attr_destroy(attr.ptr)
    }
}

internal actual fun goSpawn(f: () -> Unit) {
    val ref = StableRef.create(f)
    if (spawnWithStack(ref, GOROUTINE_STACK_BYTES) || spawnWithStack(ref, FALLBACK_STACK_BYTES)) return
    ref.dispose()
    error("goSpawn: pthread_create failed")
}
