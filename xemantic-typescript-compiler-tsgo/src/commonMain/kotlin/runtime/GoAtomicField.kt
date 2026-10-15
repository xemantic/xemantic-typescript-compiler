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

package com.xemantic.typescript.tsgo.runtime

import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// Inlined atomic fields (docs/goport-lowering.md § 3): a `sync/atomic` field the porter proves is only ever loaded,
// stored and compared-and-swapped is a `@Volatile` primitive of its holder instead of an object. A load is a volatile
// read; a compare-and-swap, and every other write of a field some compare-and-swap writes, runs under one of 64
// stripe locks chosen by the holder, so a swap never interleaves with a write it did not see. The critical section is
// a read and a write — no allocation, no blocking — so a waiter spins.

@PublishedApi
internal val goAtomicStripes: AtomicIntArray = AtomicIntArray(64)

@PublishedApi
internal fun goAtomicLock(holder: Any): Int {
    val i = holder.hashCode() and 63
    while (!goAtomicStripes.compareAndSetAt(i, 0, 1)) {
        // spin: the holder is between a read and a write of one field
    }
    return i
}

@PublishedApi
internal fun goAtomicUnlock(i: Int) = goAtomicStripes.storeAt(i, 0)

/** A write of a locked inlined atomic field (`x.f.Store(v)` where some `x.f.CompareAndSwap` exists). */
inline fun goAtomicLocked(holder: Any, write: () -> Unit) {
    val i = goAtomicLock(holder)
    try {
        write()
    } finally {
        goAtomicUnlock(i)
    }
}

/** `x.f.CompareAndSwap(old, new)` of an inlined `atomic.Int64`/`Uint64` field. */
inline fun goAtomicCasLong(holder: Any, get: () -> Long, set: (Long) -> Unit, old: Long, new: Long): Boolean {
    val i = goAtomicLock(holder)
    try {
        if (get() != old) return false
        set(new)
        return true
    } finally {
        goAtomicUnlock(i)
    }
}

/** `x.f.CompareAndSwap(old, new)` of an inlined `atomic.Int32`/`Uint32` field. */
inline fun goAtomicCasInt(holder: Any, get: () -> Int, set: (Int) -> Unit, old: Int, new: Int): Boolean {
    val i = goAtomicLock(holder)
    try {
        if (get() != old) return false
        set(new)
        return true
    } finally {
        goAtomicUnlock(i)
    }
}

/** `x.f.CompareAndSwap(old, new)` of an inlined `atomic.Bool` field. */
inline fun goAtomicCasBoolean(holder: Any, get: () -> Boolean, set: (Boolean) -> Unit, old: Boolean, new: Boolean): Boolean {
    val i = goAtomicLock(holder)
    try {
        if (get() != old) return false
        set(new)
        return true
    } finally {
        goAtomicUnlock(i)
    }
}
