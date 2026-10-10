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

package com.xemantic.typescript.tsgo.runtime

/**
 * A per-thread LIFO pool of reusable struct values, for locals the porter PROVED never outlive their
 * function (docs/goport-lowering.md § 3, "Pooled locals"; `Program.computePooledLocals`): Go keeps such a
 * `var b keyBuilder` on the stack, the JVM would allocate it (and everything it owns) per call.
 *
 * The generated code is `val s = T.POOL.stack(); val b = s.acquire(); try { … } finally { s.release() }`:
 * an acquired value is reset to Go's zero in place ([reset] keeps owned buffers' capacity) and stays
 * the caller's until the matching [GoLocalStack.release]. Nested acquires (the checker re-enters key
 * building while a key is half written) take the next slot, so the stack is sound for any nesting;
 * the stack is per THREAD and a goroutine runs on one thread from start to finish, so parallel checkers
 * never share one.
 */
class GoLocalPool<T : Any>(private val zero: () -> T, private val reset: (T) -> Unit) {
    private val stacks = GoThreadLocal { GoLocalStack(zero, reset) }

    /** This thread's stack (look it up once per function, not per acquire). */
    fun stack(): GoLocalStack<T> = stacks.get()
}

class GoLocalStack<T : Any> internal constructor(private val zero: () -> T, private val reset: (T) -> Unit) {
    private var items = arrayOfNulls<Any>(8)
    private var depth = 0

    /** A value equal to Go's zero, owned by the caller until [release]. */
    fun acquire(): T {
        val d = depth
        if (d == items.size) items = items.copyOf(d * 2)
        @Suppress("UNCHECKED_CAST")
        var v = items[d] as T?
        if (v == null) {
            v = zero()
            items[d] = v
        } else {
            reset(v)
        }
        depth = d + 1
        return v
    }

    /** Gives back the most recently acquired value (LIFO: the generated `finally` guarantees it). */
    fun release() {
        depth--
    }

    /** How many values are held now (for the pins). */
    val held: Int get() = depth
}

/** A value per thread, created on first [get] (`ThreadLocal` on the JVM, `@ThreadLocal` storage natively). */
expect class GoThreadLocal<T>(initial: () -> T) {
    fun get(): T
}
