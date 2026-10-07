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

package com.xemantic.typescript.tsgo.go.sync.atomic

// `sync/atomic` for the single-threaded port: plain fields (docs/goport-runtime.md § Concurrency).

/** `atomic.Bool`. */
class Bool {
    private var v = false
    fun load(): Boolean = v
    fun store(x: Boolean) { v = x }
    fun swap(x: Boolean): Boolean { val o = v; v = x; return o }
    fun compareAndSwap(old: Boolean, new: Boolean): Boolean { if (v != old) return false; v = new; return true }
    fun goCopy(): Bool = Bool().also { it.v = v }
}

/** `atomic.Int32`. */
class Int32 {
    private var v = 0
    fun load(): Int = v
    fun store(x: Int) { v = x }
    fun add(d: Int): Int { v += d; return v }
    fun swap(x: Int): Int { val o = v; v = x; return o }
    fun compareAndSwap(old: Int, new: Int): Boolean { if (v != old) return false; v = new; return true }
    fun goCopy(): Int32 = Int32().also { it.v = v }
}

/** `atomic.Int64`. */
class Int64 {
    private var v = 0L
    fun load(): Long = v
    fun store(x: Long) { v = x }
    fun add(d: Long): Long { v += d; return v }
    fun swap(x: Long): Long { val o = v; v = x; return o }
    fun compareAndSwap(old: Long, new: Long): Boolean { if (v != old) return false; v = new; return true }
    fun goCopy(): Int64 = Int64().also { it.v = v }
}

/** `atomic.Uint32`. */
class Uint32 {
    private var v = 0u
    fun load(): UInt = v
    fun store(x: UInt) { v = x }
    fun add(d: UInt): UInt { v += d; return v }
    fun swap(x: UInt): UInt { val o = v; v = x; return o }
    fun compareAndSwap(old: UInt, new: UInt): Boolean { if (v != old) return false; v = new; return true }
    fun goCopy(): Uint32 = Uint32().also { it.v = v }
}

/** `atomic.Uint64`. */
class Uint64 {
    private var v = 0uL
    fun load(): ULong = v
    fun store(x: ULong) { v = x }
    fun add(d: ULong): ULong { v += d; return v }
    fun swap(x: ULong): ULong { val o = v; v = x; return o }
    fun compareAndSwap(old: ULong, new: ULong): Boolean { if (v != old) return false; v = new; return true }
    fun goCopy(): Uint64 = Uint64().also { it.v = v }
}

/** `atomic.Pointer[T]`. */
class Pointer<T> {
    private var v: T? = null
    fun load(): T? = v
    fun store(x: T?) { v = x }
    fun swap(x: T?): T? { val o = v; v = x; return o }
    fun compareAndSwap(old: T?, new: T?): Boolean { if (v !== old) return false; v = new; return true }
    fun goCopy(): Pointer<T> = Pointer<T>().also { it.v = v }
}
