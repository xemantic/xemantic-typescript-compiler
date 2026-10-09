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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.go.sync.GoProcs
import com.xemantic.typescript.tsgo.go.sync.Mutex
import com.xemantic.typescript.tsgo.go.sync.WaitGroup
import com.xemantic.typescript.tsgo.go.sync.goSpawn
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * (TSGO.6-e) The run limit (`GoProcs`, Go's scheduler Ps; docs/goport-runtime.md § 9a): at most N goroutines
 * run at once, a blocked goroutine gives its token back, a woken one takes one again. Every scenario runs on a
 * goroutine (as tsgo's do, via `onGoStack`) and is awaited with a DEADLINE, so a deadlock fails the test
 * instead of hanging the suite.
 */
class GoProcsTest {

    /** Counts goroutines executing user code; [peak] is the most seen at once. */
    private class Running {
        val now = AtomicInt(0)
        val peak = AtomicInt(0)

        fun enter() {
            val n = now.incrementAndFetch()
            while (true) {
                val p = peak.load()
                if (n <= p || peak.compareAndSet(p, n)) break
            }
        }

        fun leave() {
            now.decrementAndFetch()
        }
    }

    /** Busy work long enough for goroutines to overlap. */
    private fun work(probe: AtomicInt, n: Int = 20_000) {
        var i = 0
        while (i < n) {
            probe.load()
            i++
        }
    }

    /** Runs [body] on a goroutine under a limit of [n]; false when it has not finished within the deadline. */
    private fun completesWith(n: Int, body: () -> Unit): Boolean {
        val old = GoProcs.setLimit(n)
        try {
            val done = AtomicInt(0)
            val failure = AtomicReference<Throwable?>(null)
            goSpawn {
                try {
                    body()
                } catch (t: Throwable) {
                    failure.store(t)
                } finally {
                    done.store(1)
                }
            }
            val mark = TimeSource.Monotonic.markNow()
            while (done.load() == 0) {
                if (mark.elapsedNow() > 30.seconds) return false
            }
            failure.load()?.let { throw it }
            return true
        } finally {
            GoProcs.setLimit(old)
        }
    }

    @Test
    fun `never more than N goroutines run at once`() {
        val running = Running()
        val probe = AtomicInt(0)
        GoProcs.resetPeak()
        val finished = completesWith(3) {
            running.enter()
            val wg = WaitGroup()
            repeat(200) {
                wg.go {
                    running.enter()
                    work(probe, 300_000)
                    running.leave()
                }
            }
            running.leave()
            wg.wait()
            running.enter()
            running.leave()
        }
        val peak = running.peak.load()
        val tokenPeak = GoProcs.peakRunning()
        assert(finished)
        assert(peak in 2..3)
        assert(tokenPeak <= 3)
    }

    @Test
    fun `a fan-out of 1000 goroutines completes at N=1`() {
        val running = Running()
        var sum = 0
        val finished = completesWith(1) {
            val m = Mutex()
            val wg = WaitGroup()
            // the parser's shape: the parent queues children while holding a mutex they take
            m.lock()
            repeat(1000) {
                wg.go {
                    running.enter()
                    running.leave()
                    m.lock()
                    sum++
                    m.unlock()
                }
            }
            m.unlock()
            wg.wait()
        }
        val peak = running.peak.load()
        assert(finished)
        assert(sum == 1000)
        assert(peak == 1)
    }

    @Test
    fun `a goroutine blocked on a mutex whose holder waits for a token does not deadlock at N=1`() {
        var order = ""
        val finished = completesWith(1) {
            val m = Mutex()
            val g1Locked = WaitGroup().also { it.add(1) }
            val gate = WaitGroup().also { it.add(1) }
            val wg = WaitGroup()
            wg.go {
                m.lock()
                order += "1"
                g1Locked.done()
                gate.wait() // parks: gives the token to G2
                order += "3" // woken by G2, but runnable only once G2 gives the token back (blocking on m)
                m.unlock()
            }
            wg.go {
                g1Locked.wait()
                order += "2"
                gate.done() // G1 is now runnable, holding m, waiting for the token G2 holds
                m.lock() // blocks: must give the token to G1, or neither ever runs again
                order += "4"
                m.unlock()
            }
            wg.wait()
        }
        assert(finished)
        assert(order == "1234")
    }

    @Test
    fun `a woken goroutine takes a token again before it runs`() {
        val running = Running()
        val probe = AtomicInt(0)
        val finished = completesWith(2) {
            val m = Mutex()
            val wg = WaitGroup()
            repeat(64) {
                wg.go {
                    running.enter()
                    repeat(20) {
                        work(probe, 2_000)
                        running.leave()
                        m.lock() // contended: most callers park and are woken by unlock
                        running.enter()
                        work(probe, 500)
                        m.unlock()
                    }
                    running.leave()
                }
            }
            wg.wait()
        }
        val peak = running.peak.load()
        assert(finished)
        assert(peak in 1..2)
    }
}
