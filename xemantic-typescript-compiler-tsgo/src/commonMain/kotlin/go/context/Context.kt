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

import com.xemantic.typescript.tsgo.go.sync.Mutex
import com.xemantic.typescript.tsgo.runtime.GoChan
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.goUnitElem

/**
 * `context.Context` without deadlines: `context.Background` is never done, [withCancel] derives a context
 * whose `Done` channel closes (and whose `Err` becomes [canceled]) when its cancel function runs or its
 * parent is done. Values work as in Go.
 */
interface Context {
    /** `ctx.Done()`: `null` (Go: a nil channel, which never closes) for a context that is never done. */
    fun done(): GoChan<Unit>? = null

    fun err(): GoError? = null

    fun value(key: Any?): Any?
}

/** `context.Canceled`. */
val canceled: GoError = GoPlainError("context canceled")

private object EmptyContext : Context {
    override fun value(key: Any?): Any? = null
    override fun toString(): String = "context.Background"
}

private class ValueContext(val parent: Context, private val key: Any?, private val v: Any?) : Context {
    override fun done(): GoChan<Unit>? = parent.done()
    override fun err(): GoError? = parent.err()
    override fun value(key: Any?): Any? = if (this.key == key) v else parent.value(key)
}

private class CancelContext(private val parent: Context) : Context {
    private val done = GoChan.make(goUnitElem)
    private val mu = Mutex()
    private var failure: GoError? = null
    private val children = ArrayList<CancelContext>()

    init {
        var p: Context? = parent
        while (p is ValueContext) p = p.parent
        if (p is CancelContext) p.adopt(this)
    }

    private fun adopt(child: CancelContext) {
        mu.lock()
        val parentErr = failure
        if (parentErr == null) children += child
        mu.unlock()
        if (parentErr != null) child.cancel(parentErr)
    }

    fun cancel(err: GoError) {
        mu.lock()
        if (failure != null) {
            mu.unlock()
            return
        }
        failure = err
        val kids = children.toList()
        children.clear()
        mu.unlock()
        done.close()
        for (c in kids) c.cancel(err)
    }

    override fun done(): GoChan<Unit> = done

    override fun err(): GoError? {
        mu.lock()
        val e = failure
        mu.unlock()
        return e
    }

    override fun value(key: Any?): Any? = parent.value(key)
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

/**
 * `context.WithCancel(parent)`: the derived context and its cancel function (idempotent). A [withCancel] parent
 * cancels its children; only cancellable parents propagate (the port's other contexts are never done).
 */
fun withCancel(parent: Context?): com.xemantic.typescript.tsgo.runtime.Tuple2<Context?, () -> Unit> {
    if (parent == null) com.xemantic.typescript.tsgo.runtime.goPanic("cannot create context from nil parent")
    val ctx = CancelContext(parent)
    return com.xemantic.typescript.tsgo.runtime.Tuple2(ctx) { ctx.cancel(canceled) }
}
