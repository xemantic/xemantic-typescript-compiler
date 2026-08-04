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

@file:OptIn(ExperimentalForeignApi::class)

package com.xemantic.typescript.compiler

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asCPointer
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.posix.pthread_attr_destroy
import platform.posix.pthread_attr_init
import platform.posix.pthread_attr_setstacksize
import platform.posix.pthread_attr_t
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar

/**
 * (NATIVE.1) 256 MB of stack for the compile thread — the same size the JVM actual
 * asks `Thread(group, target, name, stackSize)` for, and for the same reason.
 *
 * On Linux a glibc pthread stack is a plain anonymous `mmap` without `MAP_POPULATE`,
 * so — exactly like the JVM's — the size is RESERVED VIRTUAL memory committed
 * page-by-page on first touch: an ordinary compile costs the same physical memory as
 * the old pass-through, and only genuinely deep recursion pays for the pages it
 * actually walks. That matters on a zero-swap host, where an eagerly-committed
 * quarter-gigabyte per compile would be a real cost.
 */
private const val DEEP_STACK_SIZE_BYTES = 256uL * 1024uL * 1024uL

/**
 * Re-entrancy flag, mirroring the JVM actual's thread-name test: a nested
 * `runWithDeepStack` on the already-deep thread runs its block inline.
 *
 * Correct under BOTH Kotlin/Native global-state regimes. If globals are shared
 * (the new memory model), the caller thread is parked in `pthread_join` for the
 * whole window, so no other thread can observe the flag; if they were per-thread,
 * the flag would be set on precisely the thread whose nested calls must see it.
 * It is cleared before the routine returns either way.
 */
private var onDeepStack = false

/**
 * Everything the deep-stack routine needs, reachable through one [StableRef] —
 * `staticCFunction` cannot capture, so the pthread argument is the only channel.
 *
 * Deliberately NOT generic: a `StableRef` of a generic holder buys nothing that the
 * one unchecked cast in [runWithDeepStack] does not already give, and keeps the
 * `staticCFunction`'s type free of a type parameter it could not name.
 */
private class DeepStackCall(
    val block: () -> Any?,
    val symbolIds: Pair<Int, Int>,
    val typeId: Int,
) {
    var outcome: Result<Any?>? = null
    var symbolIdsAfter: Pair<Int, Int> = symbolIds
    var typeIdAfter: Int = typeId
}

/**
 * The pthread start routine. A Kotlin exception must never unwind out of a
 * `staticCFunction` into C, so the block is run under `runCatching` and the failure
 * is re-thrown on the caller thread after the join.
 */
private val deepStackRoutine = staticCFunction<COpaquePointer?, COpaquePointer?> { arg ->
    val call = arg!!.asStableRef<DeepStackCall>().get()
    onDeepStack = true
    // INV.6(6c0): the Symbol/Type id sequences are (on the JVM) thread-local, so the
    // compile thread INHERITS the caller's counters and writes the advanced values
    // BACK below — one continuous sequence across a chain of compiles, exactly as the
    // old process-global counters gave. Under Kotlin/Native's new memory model the
    // cells are SHARED, which makes both halves no-ops rather than wrong; doing them
    // anyway is what keeps this actual correct without depending on which regime is
    // in force. Omitting the write-back is round 607's 51-corpus-failure bug, whose
    // `--listAll` output stays byte-identical — i.e. it is silent.
    Symbol.restoreThreadIds(call.symbolIds)
    Type.restoreThreadId(call.typeId)
    call.outcome = runCatching(call.block)
    call.symbolIdsAfter = Symbol.captureThreadIds()
    call.typeIdAfter = Type.captureThreadId()
    onDeepStack = false
    null
}

/**
 * Runs [block] on a pthread created with an explicit 256 MB stack.
 *
 * Before round 827 this was a pass-through, so the native binary got the main
 * thread's `ulimit -s` (8 MB) and a deeply-nested source shape KILLED THE PROCESS —
 * `StackOverflowError` is a never-thrown stub on native, which makes the checker's
 * `init` boundary guard (the thing that turns an overflow into TS2589 on the JVM)
 * inert. This does not make an overflow CATCHABLE — it makes it ~32x rarer, which is
 * the same guarantee the JVM has.
 */
actual fun <T> runWithDeepStack(block: () -> T): T {
    if (onDeepStack) return block()
    // Round 825's cheap half: mint the singleton intrinsic types from THIS thread's
    // ordinary low id sequence, so they can never be minted from inside the compile
    // thread's. Free here, and it removes a whole class of id-space collision from
    // ever depending on which thread first touches `TypeKt`.
    forceIntrinsicTypeInit()
    val call = DeepStackCall(block, Symbol.captureThreadIds(), Type.captureThreadId())
    val ref = StableRef.create(call)
    var joined = false
    memScoped {
        val attr = alloc<pthread_attr_t>()
        if (pthread_attr_init(attr.ptr) == 0) {
            pthread_attr_setstacksize(attr.ptr, DEEP_STACK_SIZE_BYTES)
            val thread = alloc<pthread_tVar>()
            if (pthread_create(thread.ptr, attr.ptr, deepStackRoutine, ref.asCPointer()) == 0) {
                pthread_join(thread.value, null)
                joined = true
            }
            pthread_attr_destroy(attr.ptr)
        }
    }
    ref.dispose()
    // The thread could not be created (EAGAIN / ENOMEM). Degrading to the old
    // pass-through is strictly better than failing the compile: it is exactly the
    // behaviour every native build had before this round.
    if (!joined) return block()
    Symbol.restoreThreadIds(call.symbolIdsAfter)
    Type.restoreThreadId(call.typeIdAfter)
    @Suppress("UNCHECKED_CAST")
    return call.outcome!!.getOrThrow() as T
}

/**
 * Reads one intrinsic so `TypeKt`'s initializer — which mints EVERY singleton
 * intrinsic type — runs on THIS thread. Mirrors the JVM helper of the same name
 * (round 825), where a lazily-initialized singleton minted from a worker's own
 * sequence poisoned every id-keyed cache.
 */
private fun forceIntrinsicTypeInit() {
    if (anyType.id < 0) error("unreachable: intrinsic type ids are positive")
}
