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

package com.xemantic.typescript.tsgo.go.golang_org.x.sync.errgroup

import com.xemantic.typescript.tsgo.go.context.Context
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.Tuple2
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * `errgroup.Group`: `Go` runs the function IMMEDIATELY, on the calling thread (goroutines are
 * refused by the lowering, design § 4), so `Wait` has nothing to wait for and returns the first
 * error. The first error is recorded with a CAS, so a `Group` shared between threads keeps Go's
 * "first non-nil error wins". `SetLimit` is accepted and has nothing to limit.
 */
class Group {
    private val err = AtomicReference<GoError?>(null)

    fun go(f: () -> GoError?) {
        val e = f()
        if (e != null) err.compareAndSet(null, e)
    }

    /** `g.TryGo(f)`: always starts `f` (there is no limit to hit). */
    fun tryGo(f: () -> GoError?): Boolean {
        go(f)
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    fun setLimit(n: Int) {}

    @kotlin.jvm.JvmName("goWait")
    fun wait(): GoError? = err.load()

    fun goCopy(): Group = Group().also { it.err.store(err.load()) }
}

/** `errgroup.WithContext(ctx)`: the derived context is [ctx] itself (no cancellation). */
fun withContext(ctx: Context): Tuple2<Group?, Context> = Tuple2(Group(), ctx)
