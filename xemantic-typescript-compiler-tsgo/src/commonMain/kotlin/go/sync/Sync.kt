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

package com.xemantic.typescript.tsgo.go.sync

// Go's `sync`, THREAD-SAFE, in common Kotlin: every primitive is built on
// `kotlin.concurrent.atomics` (volatile semantics, so a successful acquire happens-after the
// matching release, as Go's memory model requires). The uncontended path is one CAS; a contended
// caller spins briefly and then PARKS in a [WaitQueue] (an `expect`/`actual` park: LockSupport on
// the JVM), and the releasing side wakes it. `WaitGroup.Go` runs its function synchronously.
// docs/goport-runtime.md § 9a.

import com.xemantic.typescript.tsgo.runtime.goPanic
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The contended-acquire back-off: a bounded, exponentially growing run of volatile reads of
 * [probe] (each one is a memory fence the JIT cannot remove), then the caller re-tries its CAS.
 */
internal fun spinWait(round: Int, probe: AtomicInt) {
    val n = 1 shl (if (round < 10) round else 10)
    var i = 0
    while (i < n) {
        probe.load()
        i++
    }
}

/** `sync.Locker`. */
interface Locker {
    fun lock()
    fun unlock()
}

/** `sync.Mutex`: not reentrant (a second `Lock` from the same thread never returns, as in Go). */
class Mutex : Locker {
    // 0 = unlocked, 1 = locked
    private val state = AtomicInt(0)
    private val waiters = WaitQueue()

    override fun lock() {
        if (state.compareAndSet(0, 1)) return
        waiters.await(state) { state.load() == 0 && state.compareAndSet(0, 1) }
    }

    /** Go's `fatal error: sync: unlock of unlocked mutex` — a [goPanic] here (Go cannot recover it). */
    override fun unlock() {
        if (!state.compareAndSet(1, 0)) goPanic("sync: unlock of unlocked mutex")
        if (waiters.waiting.load() != 0) waiters.signalOne()
    }

    fun tryLock(): Boolean = state.compareAndSet(0, 1)

    /** A Go value copy copies the lock state (`go vet` flags it; Go allows it). */
    fun goCopy(): Mutex = Mutex().also { it.state.store(state.load()) }
}

/**
 * `sync.RWMutex`: any number of readers or one writer. As in Go, a writer waiting in [lock] blocks
 * NEW readers (so a recursive `RLock` can deadlock, and writers do not starve).
 */
class RWMutex : Locker {
    // > 0 = that many readers, -1 = a writer, 0 = free
    private val state = AtomicInt(0)
    private val writersWaiting = AtomicInt(0)
    private val waiters = WaitQueue()

    override fun lock() {
        if (state.compareAndSet(0, -1)) return
        writersWaiting.incrementAndFetch()
        waiters.await(state) { state.load() == 0 && state.compareAndSet(0, -1) }
        writersWaiting.decrementAndFetch()
    }

    fun tryLock(): Boolean = state.compareAndSet(0, -1)

    override fun unlock() {
        if (!state.compareAndSet(-1, 0)) goPanic("sync: Unlock of unlocked RWMutex")
        if (waiters.waiting.load() != 0) waiters.signalAll()
    }

    fun rLock() {
        if (tryRLock()) return
        waiters.await(state) { tryRLock() }
    }

    fun tryRLock(): Boolean {
        while (true) {
            if (writersWaiting.load() != 0) return false
            val s = state.load()
            if (s < 0) return false
            if (state.compareAndSet(s, s + 1)) return true
        }
    }

    fun rUnlock() {
        while (true) {
            val s = state.load()
            if (s <= 0) goPanic("sync: RUnlock of unlocked RWMutex")
            if (state.compareAndSet(s, s - 1)) {
                if (s == 1 && waiters.waiting.load() != 0) waiters.signalAll()
                return
            }
        }
    }

    /** `rw.RLocker()`: a [Locker] whose lock/unlock are [rLock]/[rUnlock]. */
    fun rLocker(): Locker = object : Locker {
        override fun lock() = rLock()
        override fun unlock() = rUnlock()
    }

    fun goCopy(): RWMutex = RWMutex().also { it.state.store(state.load()) }
}

/**
 * `sync.Once`: [do] runs `f` once; a concurrent caller waits until it has returned. A panic in `f`
 * still marks the `Once` done (Go's `defer o.done.Store(1)`). Calling `Do` from inside `f`
 * deadlocks, as in Go.
 */
class Once {
    private val done = AtomicInt(0)
    private val m = Mutex()

    fun `do`(f: () -> Unit) {
        if (done.load() == 1) return
        doSlow(f)
    }

    private fun doSlow(f: () -> Unit) {
        m.lock()
        try {
            if (done.load() == 0) {
                try {
                    f()
                } finally {
                    done.store(1)
                }
            }
        } finally {
            m.unlock()
        }
    }

    fun goCopy(): Once = Once().also { it.done.store(done.load()) }
}

/** `sync.OnceValue(f)`: `f` runs once, under a [Once]; a panic in `f` is re-thrown on every call, as in Go. */
fun <T> onceValue(f: () -> T): () -> T {
    val once = Once()
    var valid = false
    var value: Any? = null
    var failure: Exception? = null
    return {
        once.`do` {
            try {
                value = f()
                valid = true
            } catch (e: Exception) {
                failure = e
            }
        }
        // the Once's atomic `done` publishes the writes above to every caller that saw it set
        if (!valid) throw failure!!
        @Suppress("UNCHECKED_CAST")
        value as T
    }
}

/** `sync.OnceFunc(f)`. */
fun onceFunc(f: () -> Unit): () -> Unit {
    val v = onceValue(f)
    return { v() }
}

/** `sync.Pool`: `Get` returns a pooled value or `New()`; the free list is guarded by a [Mutex]. */
class Pool(var new: (() -> Any?)? = null) {
    private val m = Mutex()
    private val free = ArrayList<Any?>()

    fun get(): Any? {
        m.lock()
        val x = try {
            if (free.isNotEmpty()) free.removeAt(free.size - 1) else null
        } finally {
            m.unlock()
        }
        return x ?: new?.invoke()
    }

    fun put(x: Any?) {
        if (x == null) return
        m.lock()
        try {
            free.add(x)
        } finally {
            m.unlock()
        }
    }

    fun goCopy(): Pool = Pool(new)
}

/**
 * `sync.WaitGroup`: `Go` runs the function in a new goroutine ([goSpawn]); [wait] blocks until the
 * counter is zero. A panic in a goroutine crashes a Go program; here the first one is kept and
 * re-thrown by [wait] (whoever waits sees it, instead of a silently missing result).
 */
class WaitGroup {
    private val count = AtomicInt(0)
    private val waiters = WaitQueue()
    private val failure = kotlin.concurrent.atomics.AtomicReference<Throwable?>(null)

    fun add(delta: Int) {
        val v = count.addAndFetch(delta)
        if (v < 0) goPanic("sync: negative WaitGroup counter")
        if (v == 0 && waiters.waiting.load() != 0) waiters.signalAll()
    }

    fun done() {
        add(-1)
    }

    fun go(f: () -> Unit) {
        add(1)
        goSpawn {
            try {
                f()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done()
            }
        }
    }

    // `wait()V` would clash with java.lang.Object.wait on the JVM.
    @kotlin.jvm.JvmName("goWait")
    fun wait() {
        if (count.load() != 0) waiters.await(count) { count.load() == 0 }
        failure.load()?.let { throw it }
    }

    fun goCopy(): WaitGroup = WaitGroup().also { it.count.store(count.load()) }
}

/**
 * `sync.Map`: a `HashMap` guarded by a [Mutex]. Keys and values are `any`. [range] calls `f`
 * OUTSIDE the lock (Go lets `f` call any method of the map), over a snapshot of the keys, reading
 * each key's current value and skipping a key deleted meanwhile.
 */
class Map {
    private val mu = Mutex()
    private val m = HashMap<Any?, Any?>()

    private inline fun <R> locked(block: () -> R): R {
        mu.lock()
        try {
            return block()
        } finally {
            mu.unlock()
        }
    }

    fun load(key: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Any?, Boolean> =
        locked { com.xemantic.typescript.tsgo.runtime.Tuple2(m[key], m.containsKey(key)) }

    fun store(key: Any?, value: Any?) {
        locked { m[key] = value }
    }

    fun loadOrStore(key: Any?, value: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Any?, Boolean> = locked {
        if (m.containsKey(key)) {
            com.xemantic.typescript.tsgo.runtime.Tuple2(m[key], true)
        } else {
            m[key] = value
            com.xemantic.typescript.tsgo.runtime.Tuple2(value, false)
        }
    }

    fun loadAndDelete(key: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Any?, Boolean> = locked {
        val present = m.containsKey(key)
        com.xemantic.typescript.tsgo.runtime.Tuple2(m.remove(key), present)
    }

    fun delete(key: Any?) {
        locked { m.remove(key) }
    }

    fun clear() {
        locked { m.clear() }
    }

    /** `m.Range(f)`: stops when [f] returns false; tolerates concurrent mutation. */
    fun range(f: (Any?, Any?) -> Boolean) {
        val keys = locked { m.keys.toList() }
        for (k in keys) {
            var present = false
            val v = locked {
                present = m.containsKey(k)
                m[k]
            }
            if (!present) continue
            if (!f(k, v)) return
        }
    }

    fun goCopy(): Map = Map().also { c -> locked { c.m.putAll(m) } }
}
