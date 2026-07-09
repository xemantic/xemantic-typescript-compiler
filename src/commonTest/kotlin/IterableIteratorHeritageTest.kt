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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 456 (lib): the embedded `IterableIterator<T>` was an EMPTY interface, so an
 * interface `extends IterableIterator<T>` did not inherit `next()`/`return?()/throw?()`
 * (they live on `Iterator<T>`) — an object literal supplying `next()` against such a
 * target FP-fired TS2353 "Object literal may only specify known properties, and 'next'
 * does not exist". `IterableIterator<T>` now `extends Iterator<T>` (as in the real lib).
 * Fixes tsc's sourcemap.ts `MappingsDecoder extends IterableIterator<Mapping>` decoder
 * object literal.
 */
class IterableIteratorHeritageTest {

    @Test
    fun `object literal with next satisfies an IterableIterator-extending interface`() {
        diagnose(
            """
            interface Mapping { line: number; }
            interface MappingsDecoder extends IterableIterator<Mapping> { readonly pos: number; }
            const decoder: MappingsDecoder = {
                pos: 0,
                next() { return { value: { line: 1 }, done: false }; },
                [Symbol.iterator]() { return this; },
            };
            """
        ) should {
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `next is callable on an IterableIterator value`() {
        diagnose(
            """
            declare const it: IterableIterator<number>;
            const r = it.next();
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a genuinely unknown property is still excess`() {
        diagnose(
            """
            interface Mapping { line: number; }
            interface MappingsDecoder extends IterableIterator<Mapping> { readonly pos: number; }
            const decoder: MappingsDecoder = {
                pos: 0,
                next() { return { value: { line: 1 }, done: false }; },
                [Symbol.iterator]() { return this; },
                bogusExtra: 1,
            };
            """
        ) should {
            have(any { it.code == 2353 })
        }
    }
}
