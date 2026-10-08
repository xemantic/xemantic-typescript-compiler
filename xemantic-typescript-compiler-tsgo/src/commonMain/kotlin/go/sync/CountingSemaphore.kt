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

import com.xemantic.typescript.tsgo.runtime.goPanic
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A buffered `chan struct{}` of capacity [permits] used as a counting semaphore — `s.ch <- struct{}{}`
 * is [acquire], `<-s.ch` is [release] — for the hand port of `core.LimitedSemaphore` ((TSGO.5): the
 * port has no channels; `vfs/osvfs` bounds its concurrent syscalls with three of them). Built like
 * [Mutex]: one CAS uncontended, a parked [WaitQueue] wait otherwise.
 */
class CountingSemaphore(private val permits: Int) {
    private val used = AtomicInt(0)
    private val waiters = WaitQueue()

    /** Takes a permit if one is free now. */
    fun tryAcquire(): Boolean {
        while (true) {
            val u = used.load()
            if (u >= permits) return false
            if (used.compareAndSet(u, u + 1)) return true
        }
    }

    /** Takes a permit, waiting for one to be released. */
    fun acquire() {
        if (tryAcquire()) return
        waiters.await(used) { tryAcquire() }
    }

    /** Returns a permit (Go: a receive from the channel; an empty channel would block forever — here a panic). */
    fun release() {
        while (true) {
            val u = used.load()
            if (u <= 0) goPanic("semaphore: release without acquire")
            if (used.compareAndSet(u, u - 1)) break
        }
        if (waiters.waiting.load() != 0) waiters.signalOne()
    }
}
