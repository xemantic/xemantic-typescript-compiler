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

package com.xemantic.typescript.tsgo.go.sync.atomic

// `sync/atomic`'s types over `kotlin.concurrent.atomics` (sequentially consistent, like Go's).
// Unsigned kinds wrap a signed atomic of the same width: two's-complement addition is the same
// operation, so `add` of a `UInt`/`ULong` delta (Go's `^uint32(0)` to subtract one) is exact.

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** `atomic.Bool`. */
class Bool {
    private val v = AtomicBoolean(false)
    fun load(): Boolean = v.load()
    fun store(x: Boolean) = v.store(x)
    fun swap(x: Boolean): Boolean = v.exchange(x)
    fun compareAndSwap(old: Boolean, new: Boolean): Boolean = v.compareAndSet(old, new)
    fun goCopy(): Bool = Bool().also { it.v.store(v.load()) }
}

/** `atomic.Int32`. */
class Int32 {
    private val v = AtomicInt(0)
    fun load(): Int = v.load()
    fun store(x: Int) = v.store(x)
    fun add(d: Int): Int = v.addAndFetch(d)
    fun swap(x: Int): Int = v.exchange(x)
    fun compareAndSwap(old: Int, new: Int): Boolean = v.compareAndSet(old, new)
    fun goCopy(): Int32 = Int32().also { it.v.store(v.load()) }
}

/** `atomic.Int64`. */
class Int64 {
    private val v = AtomicLong(0L)
    fun load(): Long = v.load()
    fun store(x: Long) = v.store(x)
    fun add(d: Long): Long = v.addAndFetch(d)
    fun swap(x: Long): Long = v.exchange(x)
    fun compareAndSwap(old: Long, new: Long): Boolean = v.compareAndSet(old, new)
    fun goCopy(): Int64 = Int64().also { it.v.store(v.load()) }
}

/** `atomic.Uint32`. */
class Uint32 {
    private val v = AtomicInt(0)
    fun load(): UInt = v.load().toUInt()
    fun store(x: UInt) = v.store(x.toInt())
    fun add(d: UInt): UInt = v.addAndFetch(d.toInt()).toUInt()
    fun swap(x: UInt): UInt = v.exchange(x.toInt()).toUInt()
    fun compareAndSwap(old: UInt, new: UInt): Boolean = v.compareAndSet(old.toInt(), new.toInt())
    fun goCopy(): Uint32 = Uint32().also { it.v.store(v.load()) }
}

/**
 * `atomic.Uint64`. A platform class (docs/goport-perf.md § 7): every `ast.Node` and `ast.Symbol` embeds
 * one, so it is ONE object on the JVM — a `@Volatile` field driven by a field updater — where the common
 * form was this class plus the `AtomicLong` it wrapped.
 */
expect class Uint64() {
    fun load(): ULong
    fun store(x: ULong)
    fun add(d: ULong): ULong
    fun swap(x: ULong): ULong
    fun compareAndSwap(old: ULong, new: ULong): Boolean
    fun goCopy(): Uint64
}

/** `atomic.Pointer[T]`: `CompareAndSwap` compares by identity (Go pointer equality). */
class Pointer<T> {
    private val v = AtomicReference<T?>(null)
    fun load(): T? = v.load()
    fun store(x: T?) = v.store(x)
    fun swap(x: T?): T? = v.exchange(x)
    fun compareAndSwap(old: T?, new: T?): Boolean = v.compareAndSet(old, new)
    fun goCopy(): Pointer<T> = Pointer<T>().also { it.v.store(v.load()) }
}
