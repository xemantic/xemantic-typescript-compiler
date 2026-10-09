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

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** `atomic.Uint64` on Kotlin/Native: wraps a signed `AtomicLong` (two's-complement addition is the same operation). */
actual class Uint64 actual constructor() {
    private val v = AtomicLong(0L)
    actual fun load(): ULong = v.load().toULong()
    actual fun store(x: ULong) = v.store(x.toLong())
    actual fun add(d: ULong): ULong = v.addAndFetch(d.toLong()).toULong()
    actual fun swap(x: ULong): ULong = v.exchange(x.toLong()).toULong()
    actual fun compareAndSwap(old: ULong, new: ULong): Boolean = v.compareAndSet(old.toLong(), new.toLong())
    actual fun goCopy(): Uint64 = Uint64().also { it.v.store(v.load()) }
}
