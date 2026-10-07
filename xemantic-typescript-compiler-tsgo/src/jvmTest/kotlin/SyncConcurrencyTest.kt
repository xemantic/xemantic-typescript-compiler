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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.go.sync.Map
import com.xemantic.typescript.tsgo.go.sync.Mutex
import com.xemantic.typescript.tsgo.go.sync.Once
import com.xemantic.typescript.tsgo.go.sync.Pool
import com.xemantic.typescript.tsgo.go.sync.RWMutex
import com.xemantic.typescript.tsgo.go.sync.WaitGroup
import com.xemantic.typescript.tsgo.go.sync.atomic.Int64
import com.xemantic.typescript.tsgo.go.sync.atomic.Pointer
import com.xemantic.typescript.tsgo.go.sync.onceValue
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * The `sync` shims under REAL concurrency (the IntelliJ host runs one compiler thread per project
 * in one JVM, and they share process-global `sync.Pool`s and `sync.Map`s). Each case would lose
 * updates, run a `Once` twice or corrupt a `HashMap` with the former single-threaded shims.
 */
class SyncConcurrencyTest {

    private val threads = 8

    /** Runs [body] on [threads] threads released together; returns any thrown exceptions. */
    private fun race(body: (Int) -> Unit): List<Throwable> {
        val start = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val ts = (0 until threads).map { t ->
            Thread {
                start.await()
                try {
                    body(t)
                } catch (e: Throwable) {
                    failures += e
                }
            }.also { it.start() }
        }
        start.countDown()
        ts.forEach { it.join() }
        return failures
    }

    @Test
    fun `Mutex excludes concurrent increments of a plain counter`() {
        val m = Mutex()
        var counter = 0
        val failures = race {
            repeat(20_000) {
                m.lock()
                counter++
                m.unlock()
            }
        }
        assert(failures.isEmpty() && counter == threads * 20_000)
    }

    @Test
    fun `RWMutex never lets a writer overlap a reader`() {
        val m = RWMutex()
        val readers = AtomicInteger(0)
        val writers = AtomicInteger(0)
        val violations = AtomicInteger(0)
        val failures = race { t ->
            repeat(5_000) {
                if (t % 4 == 0) {
                    m.lock()
                    if (writers.incrementAndGet() != 1 || readers.get() != 0) violations.incrementAndGet()
                    writers.decrementAndGet()
                    m.unlock()
                } else {
                    m.rLock()
                    readers.incrementAndGet()
                    if (writers.get() != 0) violations.incrementAndGet()
                    readers.decrementAndGet()
                    m.rUnlock()
                }
            }
        }
        assert(failures.isEmpty() && violations.get() == 0)
    }

    @Test
    fun `Once and OnceValue run exactly once and every caller sees the result`() {
        repeat(50) {
            val once = Once()
            val runs = AtomicInteger(0)
            val calls = AtomicInteger(0)
            val v = onceValue { calls.incrementAndGet(); Any() }
            val seen = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()))
            val failures = race {
                once.`do` { runs.incrementAndGet() }
                seen += v()
            }
            assert(failures.isEmpty() && runs.get() == 1 && calls.get() == 1 && seen.size == 1)
        }
    }

    @Test
    fun `sync Map and Pool survive concurrent use`() {
        val m = Map()
        val pool = Pool { Any() }
        val failures = race { t ->
            repeat(5_000) { i ->
                m.store(t * 100_000 + i, i)
                m.loadOrStore(i, t)
                val x = pool.get()
                pool.put(x)
            }
        }
        var count = 0
        m.range { _, _ -> count++; true }
        // the shared `loadOrStore` keys `i` coincide with thread 0's keys `0 * 100_000 + i`
        assert(failures.isEmpty() && count == threads * 5_000)
    }

    @Test
    fun `atomic Int64 and Pointer are linearizable`() {
        val n = Int64()
        val p = Pointer<Any>()
        val wins = AtomicInteger(0)
        val failures = race {
            repeat(20_000) { n.add(1L) }
            if (p.compareAndSwap(null, Any())) wins.incrementAndGet()
        }
        assert(failures.isEmpty() && n.load() == threads * 20_000L && wins.get() == 1)
    }
    private val cpu = ManagementFactory.getThreadMXBean()

    /** CPU nanoseconds the current thread spends in [block] (a parked thread spends ~none). */
    private fun cpuOf(block: () -> Unit): Long {
        val t0 = cpu.currentThreadCpuTime
        block()
        return cpu.currentThreadCpuTime - t0
    }

    @Test
    fun `contended Mutex waiters park instead of spinning`() {
        val m = Mutex()
        m.lock()
        val cpuNanos = java.util.Collections.synchronizedList(ArrayList<Long>())
        val acquired = AtomicInteger(0)
        val waiters = (0 until 4).map {
            Thread {
                cpuNanos += cpuOf { m.lock() }
                acquired.incrementAndGet()
                m.unlock()
            }.also { it.start() }
        }
        Thread.sleep(400)
        val before = acquired.get()
        m.unlock()
        waiters.forEach { it.join() }
        // a spinning waiter would burn ~400 ms of CPU while the lock was held
        assert(before == 0 && acquired.get() == 4 && cpuNanos.all { it < 150_000_000L })
    }

    @Test
    fun `WaitGroup Wait blocks without spinning until another thread is Done`() {
        val wg = WaitGroup()
        wg.add(2)
        var waited = -1L
        val waiter = Thread { waited = cpuOf { wg.wait() } }.also { it.start() }
        Thread.sleep(200)
        wg.done()
        Thread.sleep(200)
        val stillWaiting = waiter.isAlive
        wg.done()
        waiter.join(5_000)
        assert(stillWaiting && !waiter.isAlive && waited in 0L until 150_000_000L)
    }

    @Test
    fun `a waiting RWMutex writer blocks new readers and goes first`() {
        val m = RWMutex()
        m.rLock()
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val writer = Thread {
            m.lock()
            order += "writer"
            m.unlock()
        }.also { it.start() }
        Thread.sleep(150) // the writer is now waiting
        val lateReader = Thread {
            m.rLock()
            order += "reader"
            m.rUnlock()
        }.also { it.start() }
        Thread.sleep(150)
        val blocked = order.isEmpty()
        m.rUnlock()
        writer.join(5_000)
        lateReader.join(5_000)
        assert(blocked && order == listOf("writer", "reader"))
    }

    @Test
    fun `a Mutex handed between many threads under long critical sections loses no wake-up`() {
        val m = Mutex()
        var counter = 0
        val failures = race {
            repeat(200) {
                m.lock()
                val c = counter
                Thread.yield()
                counter = c + 1
                m.unlock()
            }
        }
        assert(failures.isEmpty() && counter == threads * 200)
    }
}
