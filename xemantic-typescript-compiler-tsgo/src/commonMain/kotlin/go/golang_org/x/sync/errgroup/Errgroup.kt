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

package com.xemantic.typescript.tsgo.go.golang_org.x.sync.errgroup

import com.xemantic.typescript.tsgo.go.context.Context
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.Tuple2

/** `errgroup.Group`, SINGLE-THREADED: `Go` runs the function immediately; `Wait` returns the first error. */
class Group {
    private var err: GoError? = null

    fun go(f: () -> GoError?) {
        val e = f()
        if (e != null && err == null) err = e
    }

    @kotlin.jvm.JvmName("goWait")
    fun wait(): GoError? = err

    fun goCopy(): Group = Group().also { it.err = err }
}

/** `errgroup.WithContext(ctx)`: the derived context is [ctx] itself (no cancellation). */
fun withContext(ctx: Context): Tuple2<Group?, Context> = Tuple2(Group(), ctx)
