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

// Go's `sync` for a SINGLE-THREADED port (docs/goport-design.md § 4: goroutines are refused by the
// lowering and arrive only through overrides). Every primitive here is correct when all calls come
// from one thread, and NOT thread-safe otherwise: Mutex/RWMutex do no locking (they only check
// Go's misuse panics), WaitGroup.Go runs its function synchronously, Pool is a plain free list.
// docs/goport-runtime.md § Concurrency.

import com.xemantic.typescript.tsgo.runtime.goPanic

/** `sync.Mutex` (single-threaded: records the lock state only). */
class Mutex {
    private var locked = false

    fun lock() {
        locked = true
    }

    fun unlock() {
        if (!locked) goPanic("sync: unlock of unlocked mutex")
        locked = false
    }

    fun tryLock(): Boolean {
        if (locked) return false
        locked = true
        return true
    }

    fun goCopy(): Mutex = Mutex()
}

/** `sync.RWMutex` (single-threaded). */
class RWMutex {
    private var writer = false
    private var readers = 0

    fun lock() {
        writer = true
    }

    fun unlock() {
        if (!writer) goPanic("sync: Unlock of unlocked RWMutex")
        writer = false
    }

    fun rLock() {
        readers++
    }

    fun rUnlock() {
        if (readers <= 0) goPanic("sync: RUnlock of unlocked RWMutex")
        readers--
    }

    fun goCopy(): RWMutex = RWMutex()
}

/** `sync.Once`. */
class Once {
    private var done = false

    fun `do`(f: () -> Unit) {
        if (done) return
        done = true
        f()
    }

    fun goCopy(): Once = Once().also { it.done = done }
}

/** `sync.OnceValue(f)`. A panic in `f` is re-thrown on every call, as in Go. */
fun <T> onceValue(f: () -> T): () -> T {
    var done = false
    var value: Any? = null
    var failure: Throwable? = null
    return {
        if (!done) {
            done = true
            try {
                value = f()
            } catch (e: Exception) {
                failure = e
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        value as T
    }
}

/** `sync.OnceFunc(f)`. */
fun onceFunc(f: () -> Unit): () -> Unit {
    val v = onceValue(f)
    return { v() }
}

/** `sync.Pool`: `Get` returns a pooled value or `New()`. */
class Pool(var new: (() -> Any?)? = null) {
    private val free = ArrayList<Any?>()

    fun get(): Any? = if (free.isNotEmpty()) free.removeAt(free.size - 1) else new?.invoke()

    fun put(x: Any?) {
        if (x != null) free.add(x)
    }

    fun goCopy(): Pool = Pool(new)
}

/** `sync.WaitGroup`: `Go` runs the function synchronously. */
class WaitGroup {
    private var count = 0

    fun add(delta: Int) {
        count += delta
        if (count < 0) goPanic("sync: negative WaitGroup counter")
    }

    fun done() {
        add(-1)
    }

    fun go(f: () -> Unit) {
        add(1)
        try {
            f()
        } finally {
            done()
        }
    }

    // `wait()V` would clash with java.lang.Object.wait on the JVM.
    @kotlin.jvm.JvmName("goWait")
    fun wait() {}

    fun goCopy(): WaitGroup = WaitGroup()
}

/** `sync.Map` (single-threaded, over a HashMap). Keys and values are `any`. */
class Map {
    private val m = HashMap<Any?, Any?>()

    fun load(key: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Any?, Boolean> =
        com.xemantic.typescript.tsgo.runtime.Tuple2(m[key], m.containsKey(key))

    fun store(key: Any?, value: Any?) {
        m[key] = value
    }

    fun loadOrStore(key: Any?, value: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Any?, Boolean> {
        if (m.containsKey(key)) return com.xemantic.typescript.tsgo.runtime.Tuple2(m[key], true)
        m[key] = value
        return com.xemantic.typescript.tsgo.runtime.Tuple2(value, false)
    }

    fun delete(key: Any?) {
        m.remove(key)
    }

    fun clear() {
        m.clear()
    }

    /** `m.Range(f)`: stops when [f] returns false; tolerates mutation (iterates a snapshot). */
    fun range(f: (Any?, Any?) -> Boolean) {
        for (k in m.keys.toList()) {
            if (!m.containsKey(k)) continue
            if (!f(k, m[k])) return
        }
    }

    fun goCopy(): Map = Map().also { it.m.putAll(m) }
}
