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

package com.xemantic.typescript.tsgo.runtime

/**
 * A Go panic in flight. `panic(v)` is `throw GoPanic(v)` ([goPanic]).
 *
 * It is a [RuntimeException] (never an `Error`), so the defensive `catch (Exception)` guards
 * elsewhere in the repo behave as for any other failure, and a `StackOverflowError` is NOT turned
 * into a recoverable panic (Go's stack overflow is fatal too).
 */
class GoPanic(
    val value: Any?,
    cause: Throwable? = null,
) : RuntimeException(describePanicValue(value), cause) {

    /** Go's `panic: <value>` rendering. */
    override fun toString(): String = "panic: ${describePanicValue(value)}"

}

internal fun describePanicValue(value: Any?): String = when (value) {
    null -> "nil"
    is GoError -> value.error()
    is String -> GoString.toUtf16(value)
    else -> value.toString()
}

/**
 * The panic most recently stopped by a `recover()` (any goroutine): what `runtime/debug.Stack()` renders, since
 * Go's deferred recovering function runs ON the panicking stack. A diagnostic aid only (racy across goroutines).
 */
@kotlin.concurrent.Volatile
var lastRecoveredPanic: GoPanic? = null

/** `panic(v)`. */
fun goPanic(value: Any?): Nothing = throw GoPanic(value)

/**
 * The end of a Go function whose last statement is terminating to Go (a `for {}`, a `switch` with a
 * `default` whose every arm returns, …) but not to Kotlin's flow analysis. Never reached; it is not a
 * Go panic, so `recover` does not see it.
 */
fun goUnreachable(): Nothing = throw IllegalStateException("goport: unreachable")

/** Go's `index out of range [i] with length n` runtime panic. */
fun goPanicIndex(i: Int, len: Int): Nothing =
    throw GoPanic(GoRuntimeError("index out of range [$i] with length $len"))

/** Go's `slice bounds out of range …` runtime panic. */
fun goPanicSlice(detail: String): Nothing =
    throw GoPanic(GoRuntimeError("slice bounds out of range $detail"))

/** Go's `integer divide by zero` runtime panic. */
fun goPanicDivide(): Nothing = throw GoPanic(GoRuntimeError("integer divide by zero"))

/**
 * Maps any [Exception] a lowered Go function can raise onto the Go panic it stands for:
 * a [GoPanic] is itself; a Kotlin `NullPointerException` is Go's nil dereference, a
 * `ClassCastException` a failed type assertion, an `IndexOutOfBoundsException` an index panic and
 * an `ArithmeticException` an integer division by zero. Anything else is wrapped as a runtime error.
 */
fun toGoPanic(e: Exception): GoPanic = when (e) {
    is GoPanic -> e
    is NullPointerException -> GoPanic(GoRuntimeError("invalid memory address or nil pointer dereference"), e)
    is ClassCastException -> GoPanic(GoRuntimeError("interface conversion: ${e.message}"), e)
    is IndexOutOfBoundsException -> GoPanic(GoRuntimeError("index out of range: ${e.message}"), e)
    is ArithmeticException -> GoPanic(GoRuntimeError("integer divide by zero"), e)
    else -> GoPanic(GoRuntimeError(e.toString()), e)
}

/**
 * One activation's `defer` stack and panic state.
 *
 * Created by [withDefers]/[withDefersNamed]; the lowering registers each `defer f(args)` with
 * [defer] (arguments evaluated at the `defer` statement, as in Go) and lowers `recover()` inside
 * a deferred closure to [recover] on the frame of the function that deferred it.
 */
class GoDeferFrame @PublishedApi internal constructor() {

    /** Made on the first `defer`: most frames register none (a tracer's `defer` runs only when tracing is on). */
    private var stack: ArrayList<() -> Unit>? = null

    @PublishedApi
    internal var panic: GoPanic? = null

    @PublishedApi
    internal var recovered: Boolean = false

    /** `defer f()`. Deferred calls run last-in first-out when the function returns or panics. */
    fun defer(f: () -> Unit) {
        (stack ?: ArrayList<() -> Unit>().also { stack = it }).add(f)
    }

    /**
     * `recover()`: while this frame is panicking, stops the panic and answers its value;
     * otherwise answers `null`.
     */
    fun recover(): Any? {
        val p = panic ?: return null
        panic = null
        recovered = true
        lastRecoveredPanic = p
        return p.value
    }

    @PublishedApi
    internal fun beginPanic(e: Exception) {
        panic = toGoPanic(e)
        recovered = false
    }

    /**
     * Runs every deferred call LIFO. A deferred call that panics replaces the current panic (as in
     * Go) and the remaining deferred calls still run and may recover it.
     */
    @PublishedApi
    internal fun runDefers() {
        val stack = stack ?: return
        while (stack.isNotEmpty()) {
            val f = stack.removeAt(stack.size - 1)
            try {
                f()
            } catch (e: Exception) {
                beginPanic(e)
            }
        }
    }

}

/**
 * A function with `defer` and unnamed (or unmodified) results.
 *
 * [body] returns the function's result. On a normal return the defers run after the result was
 * evaluated (a deferred call cannot change it — use [withDefersNamed] when one does). On a panic
 * the defers run; if one of them recovers, the function returns [onRecovered]'s value (Go: the
 * current values of the result variables, i.e. the zero values for unnamed results).
 */
inline fun <R> withDefers(onRecovered: () -> R, body: (GoDeferFrame) -> R): R {
    val frame = GoDeferFrame()
    var panicked = false
    try {
        return body(frame)
    } catch (e: Exception) {
        panicked = true
        frame.beginPanic(e)
        frame.runDefers()
        val p = frame.panic
        if (p != null) throw p
        return onRecovered()
    } finally {
        if (!panicked) {
            frame.runDefers()
            val p = frame.panic
            if (p != null) throw p
        }
    }
}

/**
 * A function with `defer` and NAMED results that deferred calls may modify: [body] assigns the
 * result variables (it returns nothing), then — after the defers ran, and after a recovered panic
 * too — the function returns [results]' value read from those variables.
 */
inline fun <R> withDefersNamed(results: () -> R, body: (GoDeferFrame) -> Unit): R {
    val frame = GoDeferFrame()
    try {
        body(frame)
    } catch (e: Exception) {
        frame.beginPanic(e)
    }
    frame.runDefers()
    val p = frame.panic
    if (p != null) throw p
    return results()
}
