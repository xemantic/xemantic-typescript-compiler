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
import com.xemantic.typescript.tsgo.go.golang_org.x.sync.errgroup.Group
import com.xemantic.typescript.tsgo.go.sync.Map
import com.xemantic.typescript.tsgo.go.sync.Mutex
import com.xemantic.typescript.tsgo.go.sync.Once
import com.xemantic.typescript.tsgo.go.sync.RWMutex
import com.xemantic.typescript.tsgo.go.sync.WaitGroup
import com.xemantic.typescript.tsgo.go.sync.atomic.Int64
import com.xemantic.typescript.tsgo.go.sync.atomic.Uint32
import com.xemantic.typescript.tsgo.go.sync.onceValue
import com.xemantic.typescript.tsgo.runtime.GoPanic
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.goPanic
import kotlin.test.Test

/** Single-threaded semantics of the `sync` / `sync/atomic` / `errgroup` shims (Go's misuse panics included). */
class SyncSemanticsTest {

    private fun panics(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (_: GoPanic) {
        true
    }

    @Test
    fun `unlocking an unlocked mutex panics and tryLock reports the state`() {
        val m = Mutex()
        assert(panics { m.unlock() })
        assert(m.tryLock())
        assert(!m.tryLock())
        m.unlock()
        assert(m.tryLock())
    }

    @Test
    fun `RWMutex admits many readers or one writer`() {
        val m = RWMutex()
        m.rLock()
        m.rLock()
        assert(!m.tryLock())
        m.rUnlock()
        m.rUnlock()
        assert(panics { m.rUnlock() })
        assert(m.tryLock())
        assert(!m.tryRLock())
        m.unlock()
        assert(panics { m.unlock() })
    }

    @Test
    fun `a panic inside Once still marks it done`() {
        val o = Once()
        var calls = 0
        assert(panics { o.`do` { calls++; goPanic("boom") } })
        o.`do` { calls++ }
        assert(calls == 1)
    }

    @Test
    fun `OnceValue runs f once and re-raises its panic on every call`() {
        var calls = 0
        val v = onceValue { calls++; 42 }
        assert(v() == 42 && v() == 42 && calls == 1)
        val bad = onceValue<Int> { calls++; goPanic("bad") }
        assert(panics { bad() } && panics { bad() } && calls == 2)
    }

    @Test
    fun `a WaitGroup going negative panics`() {
        val wg = WaitGroup()
        wg.go { }
        wg.wait()
        assert(panics { wg.done() })
    }

    @Test
    fun `unsigned atomics wrap like Go`() {
        val u = Uint32()
        u.add(UInt.MAX_VALUE) // Go's `^uint32(0)`: subtract one
        assert(u.load() == UInt.MAX_VALUE)
        assert(u.add(2u) == 1u)
        val i = Int64()
        assert(i.add(-5L) == -5L && i.compareAndSwap(-5L, 7L) && i.load() == 7L)
    }

    @Test
    fun `errgroup keeps the first error`() {
        val g = Group()
        val first = GoPlainError("first")
        g.go { null }
        g.go { first }
        g.go { GoPlainError("second") }
        assert(g.wait() === first)
    }

    @Test
    fun `sync Map range tolerates deletion and stops on false`() {
        val m = Map()
        for (i in 0 until 5) m.store(i, i * 10)
        val seen = ArrayList<Any?>()
        m.range { k, _ ->
            m.delete(4)
            seen += k
            seen.size < 3
        }
        assert(seen.size == 3 && 4 !in seen)
        assert(m.loadOrStore(1, 99).first == 10)
        assert(m.loadAndDelete(1).second && !m.load(1).second)
    }
}
