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
import com.xemantic.typescript.tsgo.go.sync.WaitGroup
import com.xemantic.typescript.tsgo.go.sync.atomic.Int64
import com.xemantic.typescript.tsgo.runtime.GoChan
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoPanic
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.goClose
import com.xemantic.typescript.tsgo.runtime.goRecv
import com.xemantic.typescript.tsgo.runtime.goRecv2
import com.xemantic.typescript.tsgo.runtime.goSend
import com.xemantic.typescript.tsgo.runtime.goUnitElem
import kotlin.test.Test

/** Go channel semantics of the runtime's [GoChan] (docs/goport-runtime.md § 10 #19). */
class GoChanTest {

    /** The message of the Go panic [block] raises, or null when it returns. */
    private fun panicMessage(block: () -> Unit): String? = try {
        block()
        null
    } catch (p: GoPanic) {
        (p.value as? GoError)?.error() ?: p.value.toString()
    }

    @Test
    fun `a buffered channel keeps order, reports len and cap, and drains after close`() {
        val ch = GoChan.make(GoElem.INT, 3)
        goSend(ch, 1)
        goSend(ch, 2)
        val len = ch.len
        val cap = ch.cap
        assert(len == 2 && cap == 3)
        goClose(ch)
        val a = goRecv2(ch)
        val b = goRecv2(ch)
        val c = goRecv2(ch)
        assert(a.first == 1 && a.second)
        assert(b.first == 2 && b.second)
        assert(c.first == 0 && !c.second)
    }

    @Test
    fun `misuse panics with Go's messages`() {
        val ch = GoChan.make(GoElem.INT, 1)
        goClose(ch)
        val sendOnClosed = panicMessage { goSend(ch, 1) }
        val closeClosed = panicMessage { goClose(ch) }
        val closeNil = panicMessage { goClose(null) }
        assert(sendOnClosed == "send on closed channel")
        assert(closeClosed == "close of closed channel")
        assert(closeNil == "close of nil channel")
    }

    @Test
    fun `an unbuffered channel hands every value from a goroutine to the receiver`() {
        val ch = GoChan.make(GoElem.INT)
        val wg = WaitGroup()
        wg.go {
            for (i in 1..200) goSend(ch, i)
            goClose(ch)
        }
        var sum = 0
        var count = 0
        while (true) {
            val r = goRecv2(ch)
            if (!r.second) break
            sum += r.first
            count++
        }
        wg.wait()
        assert(count == 200 && sum == 200 * 201 / 2)
    }

    @Test
    fun `closing a done channel releases every goroutine blocked on it`() {
        // execute/build's pattern: tasks block on `<-upstream.done` until the upstream task closes it.
        val done = GoChan.make(goUnitElem)
        val released = Int64()
        val wg = WaitGroup()
        repeat(8) {
            wg.go {
                goRecv(done)
                released.add(1L)
            }
        }
        val before = released.load()
        goClose(done)
        wg.wait()
        val after = released.load()
        assert(before == 0L && after == 8L)
    }

    @Test
    fun `a non-blocking send succeeds only while there is room`() {
        // watchmanager's pattern: `select { case ch <- struct{}{}: default: }` on a channel of one.
        val ch = GoChan.make(goUnitElem, 1)
        val first = ch.trySend(Unit)
        val second = ch.trySend(Unit)
        assert(first && !second)
        val r = ch.tryRecv2()
        val empty = ch.tryRecv2()
        assert(r != null && r.second && empty == null)
        val unbuffered = GoChan.make(GoElem.INT)
        assert(!unbuffered.trySend(1))
    }

    @Test
    fun `a failing goroutine releases the goroutines blocked on a channel it would have closed`() {
        // execute/build: a task that throws never reaches `close(t.done)`; Go's panic would end the process, the
        // port must not hang in its downstream tasks' `<-upstream.done` — Wait rethrows the failure instead.
        val done = GoChan.make(goUnitElem)
        val wg = WaitGroup()
        wg.go { goRecv(done) }
        wg.go { error("boom") }
        val thrown = try {
            wg.wait()
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        assert(thrown == "boom")
        // the failure was reported: an unrelated channel works again
        val ch = GoChan.make(GoElem.INT, 1)
        goSend(ch, 7)
        assert(goRecv(ch) == 7)
    }
}
