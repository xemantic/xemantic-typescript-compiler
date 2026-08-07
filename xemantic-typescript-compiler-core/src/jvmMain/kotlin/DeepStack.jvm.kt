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
    // M0.1 tail-triage lab hook — a no-op volatile read unless build/pass-lab.txt
    // exists (see PassLab). Placed at the one funnel every JVM compile crosses.
    PassLab.ensureLoaded()
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

/**
 * (PERF.HW.a) round 825: the width of ONE worker's id slice. Worker `i` allocates
 * from `WORKER_ID_BASE + i * WORKER_ID_STRIDE`, so no two workers can ever mint the
 * same Symbol/Type id — which is what makes a shared singleton whose class happens
 * to initialize INSIDE a worker harmless (it lands in exactly that worker's slice,
 * above that worker's own counter, and outside everybody else's).
 *
 * Headroom: the compiler profile's busiest worker mints ~40 k types and ~105 k
 * symbols, so 10 M is ~100x the observed peak; [MAX_PARALLEL_WORKERS] keeps the
 * top of the last slice inside `Int.MAX_VALUE`.
 */
private const val WORKER_ID_STRIDE = 10_000_000
private const val MAX_PARALLEL_WORKERS = 100

internal actual fun <T> runInDeepStackWorkers(tasks: List<() -> T>): List<T> {
    if (tasks.size <= 1) return tasks.map { it() }
    require(tasks.size <= MAX_PARALLEL_WORKERS) {
        "at most $MAX_PARALLEL_WORKERS parallel workers (id slices must fit in Int)"
    }
    // (PERF.HW.a) round 825 — THE PARALLEL-MODE RACE. Nothing on the sequential
    // prefix of a `--workers` compile (crawl, parse, Binder) touches an intrinsic
    // type, so `TypeKt`'s static initializer — which allocates `anyType` & co from
    // the CURRENT thread's sequence — used to run inside whichever worker reached
    // it first, i.e. AFTER that worker had rebased to WORKER_ID_BASE. Measured:
    // `anyType.id == 1_000_000_005`, sitting inside every other worker's id range,
    // so the id-keyed relation caches confused a shared singleton with a
    // worker-local type and invented diagnostics. Forcing the class here allocates
    // the singletons from the CALLER's ordinary low sequence, which is the
    // invariant WORKER_ID_BASE was chosen to express in the first place.
    forceIntrinsicTypeInit()
    val probe = System.getenv("XTSC_WORKER_PROBE") != null
    val outcomes = MutableList<Result<T>?>(tasks.size) { null }
    val threads = tasks.mapIndexed { i, task ->
        Thread(null, {
            Symbol.rebaseThreadIds(WORKER_ID_BASE + i * WORKER_ID_STRIDE,
                WORKER_SCOPE_ID_BASE - i * WORKER_ID_STRIDE)
            Type.rebaseThreadIds(WORKER_ID_BASE + i * WORKER_ID_STRIDE)
            val t0 = Type.captureThreadId()
            val s0 = Symbol.captureThreadIds().first
            outcomes[i] = runCatching(task)
            if (probe) System.err.println(
                "WORKERPROBE w$i type[$t0,${Type.captureThreadId()}) " +
                    "sym[$s0,${Symbol.captureThreadIds().first}) " +
                    "anyType=${anyType.id} stringType=${stringType.id} neverType=${neverType.id}"
            )
        }, DEEP_STACK_THREAD_NAME, DEEP_STACK_SIZE_BYTES)
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    if (probe) System.err.println("WORKERPROBE main anyType=${anyType.id} stringType=${stringType.id}")
    return outcomes.map { it!!.getOrThrow() }
}

/**
 * Reads one intrinsic so the JVM runs `TypeKt`'s static initializer — which mints
 * EVERY singleton intrinsic type — on THIS thread, before any worker rebases.
 */
private fun forceIntrinsicTypeInit() {
    if (anyType.id < 0) error("unreachable: intrinsic type ids are positive")
}
