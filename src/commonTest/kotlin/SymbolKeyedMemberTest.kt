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
 * A computed well-known-symbol key in an object literal — `[Symbol.iterator]: …` — is a
 * MEMBER, and must satisfy a target that requires it.
 *
 * Round 722, continuing (LIB.1)'s real-lib false-positive burn-down. tsc's `core.ts`
 * builds a `Set<TElement>` as an object literal that ends with
 *
 *     [Symbol.iterator]: () => { … },
 *     [Symbol.toStringTag]: multiMap[Symbol.toStringTag],
 *
 * and we report TS2739 "missing the following properties from type 'Set<TElement>':
 * [Symbol.iterator], [Symbol.toStringTag]" — i.e. we do not see the members the
 * literal plainly declares.
 *
 * These run under `@useRealLibs` because the curated embedded lib has no
 * `Symbol.iterator` and no iterable `Set`, so the shape does not exist on the default
 * path (the same reason the round-720 `Required<T>` family was invisible there).
 *
 * Each case carries its CONTROL, so a probe that has gone blind fails loudly rather
 * than reading as a fix — rounds 718 and 721 both had a first cut whose controls came
 * back silent.
 */
class SymbolKeyedMemberTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @target: es2015"

    @Test
    fun `a computed Symbol-iterator key in an object literal satisfies an iterable target`() {
        val diagnostics = diagnose(
            """
            interface Bag<T> {
                size: number
                [Symbol.iterator](): Iterator<T>
            }
            declare const inner: Iterator<string>
            const bag: Bag<string> = {
                size: 0,
                [Symbol.iterator]: () => inner,
            }
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2739 || it.code == 2741 })
    }

    @Test
    fun `control - omitting the Symbol-iterator key is still reported as missing`() {
        val diagnostics = diagnose(
            """
            interface Bag<T> {
                size: number
                [Symbol.iterator](): Iterator<T>
            }
            const bag: Bag<string> = {
                size: 0,
            }
            """,
            directives = realLibs,
        )
        // A silent control means the probe cannot see the missing-member diagnostic at
        // all, in which case the case above proves nothing.
        assert(diagnostics.any { it.code == 2739 || it.code == 2741 })
    }

    @Test
    fun `the method form of a computed Symbol key is also a member`() {
        val diagnostics = diagnose(
            """
            interface Bag<T> {
                size: number
                [Symbol.iterator](): Iterator<T>
            }
            declare const inner: Iterator<string>
            const bag: Bag<string> = {
                size: 0,
                [Symbol.iterator]() { return inner },
            }
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2739 || it.code == 2741 })
    }

    @Test
    fun `a genuinely dynamic computed key is still not treated as a named member`() {
        val diagnostics = diagnose(
            """
            interface Bag<T> {
                size: number
                [Symbol.iterator](): Iterator<T>
            }
            declare const dynamicKey: string
            const bag: Bag<string> = {
                size: 0,
                [dynamicKey]: 1,
            }
            """,
            directives = realLibs,
        )
        // `[dynamicKey]` must NOT be mistaken for the required symbol member: naming a
        // dynamic key would let any literal satisfy any symbol-keyed target.
        assert(diagnostics.any { it.code == 2739 || it.code == 2741 })
    }

    @Test
    fun `an interface extending ReadonlyArray is assignable to itself`() {
        val diagnostics = diagnose(
            """
            interface Positioned { pos: number }
            interface Chunk<T> extends ReadonlyArray<T> {
                pos: number
            }
            declare function make<T extends Positioned>(): Chunk<T>
            function f<T extends Positioned>(): Chunk<T> {
                const result = make<T>()
                return result
            }
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `control - an unrelated type is still rejected against that interface`() {
        val diagnostics = diagnose(
            """
            interface Positioned { pos: number }
            interface Chunk<T> extends ReadonlyArray<T> {
                pos: number
            }
            interface NotAChunk<T> { other: T }
            declare function makeOther<T extends Positioned>(): NotAChunk<T>
            function f<T extends Positioned>(): Chunk<T> {
                const result = makeOther<T>()
                return result
            }
            """,
            directives = realLibs,
        )
        assert(diagnostics.any { it.code == 2322 })
    }
}
