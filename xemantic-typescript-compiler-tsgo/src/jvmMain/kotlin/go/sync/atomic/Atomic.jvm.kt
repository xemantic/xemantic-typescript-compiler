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

import java.util.concurrent.atomic.AtomicLongFieldUpdater

/** `atomic.Uint64` on the JVM: one object, a volatile `long` updated through [AtomicLongFieldUpdater] (sequentially consistent, as Go's). */
actual class Uint64 actual constructor() {
    @Volatile
    private var v: Long = 0L

    actual fun load(): ULong = v.toULong()
    actual fun store(x: ULong) {
        v = x.toLong()
    }
    actual fun add(d: ULong): ULong = V.addAndGet(this, d.toLong()).toULong()
    actual fun swap(x: ULong): ULong = V.getAndSet(this, x.toLong()).toULong()
    actual fun compareAndSwap(old: ULong, new: ULong): Boolean = V.compareAndSet(this, old.toLong(), new.toLong())
    actual fun goCopy(): Uint64 = Uint64().also { it.v = v }

    private companion object {
        private val V: AtomicLongFieldUpdater<Uint64> = AtomicLongFieldUpdater.newUpdater(Uint64::class.java, "v")
    }
}
