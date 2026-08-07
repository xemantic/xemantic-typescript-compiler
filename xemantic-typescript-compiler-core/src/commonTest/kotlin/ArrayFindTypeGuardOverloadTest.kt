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
 * Round 456 (lib): the embedded `Array<T>`/`ReadonlyArray<T>` `find`/`findLast`
 * gained the type-predicate overload `find<S extends T>(predicate: (value: T, …)
 * => value is S, thisArg?): S | undefined` (mirrors round 455's `filter`/`every`),
 * so `arr.find(isFoo)` refines the element type via `tryInferPredicateOverloadReturn`
 * (round 439). Fixes tsc's `getClassLikeDeclarationOfSymbol` (utilities.ts:8276:
 * `symbol.declarations?.find(isClassLike)` returning `ClassLikeDeclaration | undefined`).
 */
class ArrayFindTypeGuardOverloadTest {

    private val prelude = """
        interface Node { kind: number; }
        interface ClassLikeDeclaration extends Node { members: number; }
        declare function isClassLike(d: Node): d is ClassLikeDeclaration;
    """.trimIndent() + "\n"

    @Test
    fun `Array find with a type-guard refines the result element type`() {
        diagnose(
            prelude + """
            function f(arr: Node[]): ClassLikeDeclaration | undefined {
                return arr.find(isClassLike);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `ReadonlyArray find with a type-guard refines the result element type`() {
        diagnose(
            prelude + """
            function f(arr: readonly Node[]): ClassLikeDeclaration | undefined {
                return arr.find(isClassLike);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `Array findLast with a type-guard refines the result element type`() {
        diagnose(
            prelude + """
            function f(arr: Node[]): ClassLikeDeclaration | undefined {
                return arr.findLast(isClassLike);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `find through an optional declarations array refines the result`() {
        diagnose(
            prelude + """
            interface Symbol { declarations?: Node[]; }
            function getClassLike(symbol: Symbol): ClassLikeDeclaration | undefined {
                return symbol.declarations?.find(isClassLike);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a plain find predicate keeps the base element type`() {
        // A NON-guard predicate keeps `find`'s result as `Node | undefined`, which
        // is NOT assignable to `ClassLikeDeclaration | undefined` (missing `members`).
        diagnose(
            prelude + """
            function f(arr: Node[]): ClassLikeDeclaration | undefined {
                return arr.find(d => d.kind === 1);
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
