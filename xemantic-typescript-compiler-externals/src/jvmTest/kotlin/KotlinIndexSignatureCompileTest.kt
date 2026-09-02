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

package com.xemantic.typescript.compiler.externals

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (EXT.15) The MEASUREMENT behind the index-signature rule: the Kotlin shape
 * an index signature renders as — a pair of `operator fun get`/`operator fun
 * set` keyed by the signature's key type — compiled through the same
 * [compileCheck] the gates use, in every position the generator emits it:
 *
 *  - an INTERFACE with both a `String` pair and a `Double` pair (a string and
 *    a number index signature side by side — two `get` overloads and two
 *    `set` overloads distinguished by the key type alone);
 *  - a SUBINTERFACE redeclaring the pair, rendered `override operator`
 *    (Kotlin lets `operator` be repeated on an override);
 *  - the gate variant's CLASS shape: an abstract class with `= null!!`
 *    bodies on both operators and a companion object holding a static pair.
 *
 * Each is asserted to compile, and a deliberately malformed operator (a
 * `get` with no parameter — Kotlin refuses an `operator fun get` without a
 * key) is the negative control that the instrument sees an operator error
 * at all.
 */
class KotlinIndexSignatureCompileTest {

    @Test
    fun `an interface holds a string pair and a number pair of index operators`() {
        val check = compileCheck(
            """
            public interface Dictionary<T> {
                public operator fun get(key: String): T?
                public operator fun set(key: String, value: T): Unit
                public operator fun get(index: Double): T?
                public operator fun set(index: Double, value: T): Unit
                public val size: Double
            }
            """.trimIndent()
        )
        val compileErrors = check.errors
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `a subinterface redeclaring the pair renders override operator`() {
        val check = compileCheck(
            """
            public interface Base {
                public operator fun get(key: String): String?
                public operator fun set(key: String, value: String): Unit
            }
            public interface Sub : Base {
                public override operator fun get(key: String): String?
                public override operator fun set(key: String, value: String): Unit
            }
            """.trimIndent()
        )
        val compileErrors = check.errors
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `the class gate shape compiles with nothing bodies and a companion pair`() {
        val check = compileCheck(
            """
            public abstract class Table(name: String) {
                public var name: String = null!!
                public operator fun get(key: String): Double? = null!!
                public operator fun set(key: String, value: Double): Unit = null!!
                public companion object {
                    public operator fun get(key: String): Boolean? = null!!
                }
            }
            """.trimIndent()
        )
        val compileErrors = check.errors
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `negative control - an operator get without a key is refused`() {
        val check = compileCheck(
            """
            public interface Broken {
                public operator fun get(): String?
            }
            """.trimIndent()
        )
        val mentionsTheOperator = check.errors.any { "operator" in it || "get" in it }
        assert(!check.successful)
        assert(mentionsTheOperator)
    }

}
