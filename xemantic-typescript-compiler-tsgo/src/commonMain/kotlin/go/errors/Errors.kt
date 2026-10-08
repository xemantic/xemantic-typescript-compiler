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

// Parts of this file are translated from the Go standard library (go1.27.1), Copyright The Go
// Authors, used under Go's BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.go.errors

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoIser
import com.xemantic.typescript.tsgo.runtime.GoMultiUnwrapper
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoUnwrapper

/** `errors.New(text)`: a distinct error value (pointer identity, as Go's `*errorString`). */
fun new(text: String): GoError = GoPlainError(text)

/** `errors.Unwrap(err)`. */
fun unwrap(err: GoError?): GoError? = (err as? GoUnwrapper)?.unwrap()

/**
 * `errors.Is(err, target)`: identity/equality, then `Is(error) bool`, then the `Unwrap` chain
 * (single and multi). The structural interface checks are the nominal [GoIser], [GoUnwrapper]
 * and [GoMultiUnwrapper] (the lowering declares them on generated types with those methods).
 */
fun `is`(err: GoError?, target: GoError?): Boolean {
    if (err == null || target == null) return err == target
    return isImpl(err, target)
}

private fun isImpl(start: GoError, target: GoError): Boolean {
    var err: GoError = start
    while (true) {
        if (err == target) return true
        if (err is GoIser && err.isError(target)) return true
        when (err) {
            is GoUnwrapper -> err = err.unwrap() ?: return false
            is GoMultiUnwrapper -> {
                val errs = err.unwrapAll()
                for (i in 0 until errs.len) {
                    val e = errs[i] ?: continue
                    if (isImpl(e, target)) return true
                }
                return false
            }
            else -> return false
        }
    }
}

private class JoinError(private val errs: List<GoError>) : GoError, GoMultiUnwrapper {
    override fun error(): String = errs.joinToString("\n") { it.error() }
    override fun unwrapAll(): com.xemantic.typescript.tsgo.runtime.GoSlice<GoError?> =
        com.xemantic.typescript.tsgo.runtime.GoSlice.of(com.xemantic.typescript.tsgo.runtime.GoElem.ref(), *errs.toTypedArray())
}

/** `errors.Join(errs...)`: nil when every argument is nil. */
fun join(vararg errs: GoError?): GoError? {
    val nonNil = errs.filterNotNull()
    return if (nonNil.isEmpty()) null else JoinError(nonNil)
}

/**
 * `errors.AsType[E](err)`: the first error in [err]'s `Unwrap` chain (single and multi, depth-first)
 * that is an [E]. Go also consults an `As(any) bool` method; no ported or shimmed error has one.
 */
inline fun <reified E> asType(err: GoError?): com.xemantic.typescript.tsgo.runtime.Tuple2<E?, Boolean> {
    if (err == null) return com.xemantic.typescript.tsgo.runtime.Tuple2(null, false)
    val stack = ArrayDeque<GoError>()
    stack.addLast(err)
    while (stack.isNotEmpty()) {
        var e: GoError? = stack.removeLast()
        while (e != null) {
            if (e is E) return com.xemantic.typescript.tsgo.runtime.Tuple2(e, true)
            e = when (e) {
                is GoUnwrapper -> e.unwrap()
                is GoMultiUnwrapper -> {
                    val errs = e.unwrapAll()
                    for (i in errs.len - 1 downTo 0) errs[i]?.let { stack.addLast(it) }
                    null
                }
                else -> null
            }
        }
    }
    return com.xemantic.typescript.tsgo.runtime.Tuple2(null, false)
}
