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

package com.xemantic.typescript.tsgo.go.context

import com.xemantic.typescript.tsgo.runtime.GoError

/**
 * `context.Context`, without deadlines or cancellation (the single-threaded port has no
 * goroutines to cancel): `done()` is never closed, `err()` is nil. Values work as in Go.
 */
interface Context {
    /** `ctx.Done()`: always `null` (Go: a nil channel, which never closes). */
    fun done(): Any? = null

    fun err(): GoError? = null

    fun value(key: Any?): Any?
}

private object EmptyContext : Context {
    override fun value(key: Any?): Any? = null
    override fun toString(): String = "context.Background"
}

private class ValueContext(private val parent: Context, private val key: Any?, private val v: Any?) : Context {
    override fun value(key: Any?): Any? = if (this.key == key) v else parent.value(key)
}

/** `context.Background()`. */
fun background(): Context = EmptyContext

/** `context.TODO()`. */
fun todo(): Context = EmptyContext

/** `context.WithValue(parent, key, val)`. */
fun withValue(parent: Context?, key: Any?, v: Any?): Context {
    if (parent == null) com.xemantic.typescript.tsgo.runtime.goPanic("cannot create context from nil parent")
    if (key == null) com.xemantic.typescript.tsgo.runtime.goPanic("nil key")
    return ValueContext(parent, key, v)
}
