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
package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.173) G1 arc B5e (N16) — the flow graph of a `try` statement follows tsgo's
 * `bindTryStatement`: the catch entry and the finally entry see every MUTATION bound in
 * the try (the exception label), a `return` in the try/catch of a try with a finally
 * reaches the finally (the return label), the catch acts as a second try block, and an
 * enclosing try's return/exception target is reached through the inner finally.
 *
 * Before this, the catch entry was the PRE-TRY flow alone and the finally entry was the
 * pre-try flow plus the normal completion, so a read in either saw the variable's
 * pre-try type (`null`) — the N16 false TS18047 of the census (corpus
 * `tryCatchFinallyControlFlow` 13:26 under the forced G1 arm) and ten wrong types.
 *
 * Every expectation is tsgo 7.0.2's output for the same text with a leading
 * `// @strict: true` line, which this harness consumes as a directive — so each line number
 * here is tsgo's minus one. Known residues NOT pinned here:
 * the statement after a finally does not see the finally's own assignments (tsgo's
 * ReduceLabel), and a loop label read through a finally answers the declared type.
 */
class TryExceptionReturnFlowTest {

    private val prelude = "declare const c: boolean\ndeclare function g(): void\ndeclare function tb(x: boolean): void\n"

    private fun rows(source: String): List<String> =
        diagnose(prelude + source.trimIndent()).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `n16 return in try reaches the finally so the and-and right operand is narrowed`() {
        val rows = rows("""
            function f(a: number | null) {
                a = null
                try {
                    a = 123
                    return a
                } catch (e) {
                    throw e
                } finally {
                    if (a != null && a.toFixed(0) == "123") {}
                }
            }
            export {}
            """)
        val expected = emptyList<String>()
        assert(rows == expected)
    }

    @Test
    fun `a return path assignment is seen in the finally`() {
        val rows = rows("""
            function f(a: number | null) {
                a = null
                try {
                    a = 123
                    return a
                } catch (e) {
                    throw e
                } finally {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "12:12 TS2345 Argument of type 'number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a throw path assignment is seen in the finally`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    a = 123
                    throw 1
                } finally {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "10:12 TS2345 Argument of type 'number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a try assignment is seen in the catch`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    a = 123
                    g()
                } catch {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "10:12 TS2345 Argument of type 'number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `every try assignment is seen in the catch not only the last`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    a = 1
                    g()
                    a = "s"
                } catch {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "11:12 TS2345 Argument of type 'string | number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a catch assignment is seen in the finally`() {
        val rows = rows("""
            function f(a: string | null) {
                a = "x"
                try {
                    g()
                } catch {
                    a = null
                } finally {
                    a.length
                }
            }
            export {}
            """)
        val expected = listOf(
            "11:9 TS18047 'a' is possibly 'null'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a return in a catch is seen in the finally`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    g()
                } catch {
                    a = 1
                    return
                } finally {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "12:12 TS2345 Argument of type 'number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a nulling assignment before a return reports in the finally`() {
        val rows = rows("""
            function f(a: string | null) {
                a = "x"
                try {
                    a = null
                    return 1
                } finally {
                    a.length
                }
            }
            export {}
            """)
        val expected = listOf(
            "10:9 TS18047 'a' is possibly 'null'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an inner return reaches the outer finally`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    try {
                        a = 1
                        return
                    } finally {
                        g()
                    }
                } finally {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "14:12 TS2345 Argument of type 'number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an inner exception reaches the outer catch through the inner finally`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    try {
                        a = 1
                        g()
                    } finally {
                        a = "s"
                    }
                } catch {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "14:12 TS2345 Argument of type 'string | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `an assignment inside a closure does not reach the enclosing catch`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    a = 1
                    const h = () => { a = "s" }
                    g()
                } catch {
                    tb(a)
                }
            }
            export {}
            """)
        val expected = listOf(
            "11:12 TS2345 Argument of type 'number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `a break in a loop body try reaches its finally`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                while (c) {
                    try {
                        a = 1
                        break
                    } finally {
                        tb(a)
                    }
                }
            }
            export {}
            """)
        val expected = listOf(
            "11:16 TS2345 Argument of type 'number | null' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }

    @Test
    fun `TS2454 still reports in a finally reached from a return`() {
        val rows = rows("""
            function f() {
                let x: number
                try {
                    x = 1
                    return
                } finally {
                    tb(x)
                }
            }
            export {}
            """)
        val expected = listOf(
            "10:12 TS2345 Argument of type 'number' is not assignable to parameter of type 'boolean'.",
            "10:12 TS2454 Variable 'x' is used before being assigned.",
        )
        assert(rows == expected)
    }

    @Test
    fun `control - the statement after a try catch keeps the normal completion`() {
        val rows = rows("""
            function f(a: string | number | null) {
                a = null
                try {
                    a = 1
                } catch {
                    a = "s"
                    return
                } finally {
                    g()
                }
                tb(a)
            }
            export {}
            """)
        val expected = listOf(
            "14:8 TS2345 Argument of type 'number' is not assignable to parameter of type 'boolean'.",
        )
        assert(rows == expected)
    }
}
