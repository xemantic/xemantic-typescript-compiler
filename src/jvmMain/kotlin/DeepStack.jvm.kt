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

package com.xemantic.typescript.compiler

private const val DEEP_STACK_THREAD_NAME = "xtsc-deep-stack"

/**
 * 256 MB of stack for the compile thread. The size is RESERVED virtual memory,
 * committed page-by-page on first touch (Linux/glibc pthread stacks), so an
 * ordinary compile costs the same physical memory as before — only genuinely
 * deep recursion pays for the pages it actually uses.
 */
private const val DEEP_STACK_SIZE_BYTES = 256L * 1024 * 1024

actual fun <T> runWithDeepStack(block: () -> T): T {
    if (Thread.currentThread().name == DEEP_STACK_THREAD_NAME) return block()
    var outcome: Result<T>? = null
    // INV.6(6c0): the Symbol/Type id sequences are thread-local — the compile
    // thread INHERITS the caller's counters and writes the advanced values
    // BACK, so a chain of sequential compiles on one caller thread allocates
    // one continuous sequence exactly as the old process-global counters did
    // (singleton intrinsics allocated on the class-load thread stay below every
    // later compile's ids). Parallel workers (INV.6(6c)) override this with
    // explicit per-worker rebases instead.
    val symbolIds = Symbol.captureThreadIds()
    val typeId = Type.captureThreadId()
    var symbolIdsAfter = symbolIds
    var typeIdAfter = typeId
    val thread = Thread(null, {
        Symbol.restoreThreadIds(symbolIds)
        Type.restoreThreadId(typeId)
        outcome = runCatching(block)
        symbolIdsAfter = Symbol.captureThreadIds()
        typeIdAfter = Type.captureThreadId()
    }, DEEP_STACK_THREAD_NAME, DEEP_STACK_SIZE_BYTES)
    thread.start()
    thread.join()
    Symbol.restoreThreadIds(symbolIdsAfter)
    Type.restoreThreadId(typeIdAfter)
    return outcome!!.getOrThrow()
}

/** INV.6(6c1): worker id-space bases — far above any singleton/static allocation. */
private const val WORKER_ID_BASE = 1_000_000_000
private const val WORKER_SCOPE_ID_BASE = -1_000_000_000

internal actual fun <T> runInDeepStackWorkers(tasks: List<() -> T>): List<T> {
    if (tasks.size <= 1) return tasks.map { it() }
    val outcomes = MutableList<Result<T>?>(tasks.size) { null }
    val threads = tasks.mapIndexed { i, task ->
        Thread(null, {
            Symbol.rebaseThreadIds(WORKER_ID_BASE, WORKER_SCOPE_ID_BASE)
            Type.rebaseThreadIds(WORKER_ID_BASE)
            outcomes[i] = runCatching(task)
        }, DEEP_STACK_THREAD_NAME, DEEP_STACK_SIZE_BYTES)
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    return outcomes.map { it!!.getOrThrow() }
}
