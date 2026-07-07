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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 439 (M3.2): findAncestor-style predicate-overload return inference. A
 * generic overload whose callback parameter is a type-guard position
 * `(x) => x is T` and whose return is built from T infers T from the actual
 * type-guard ARGUMENT's predicate target — `findAncestor(node, isFoo)` →
 * `Foo | undefined`, not the B136 concrete-overload `Node | undefined`. The
 * companion NonNull strip makes `<call>()!` usable on the resulting union.
 */
class PredicateOverloadReturnInferenceTest {

    private val prelude = """
        interface Node4 { kind: number }
        interface FuncDecl extends Node4 { params: number }
        interface ClassDecl extends Node4 { members: number }
        declare function isFuncDecl(n: Node4): n is FuncDecl;
        declare function findAncestor4<T extends Node4>(node: Node4 | undefined, cb: (n: Node4) => n is T): T | undefined;
        declare function findAncestor4(node: Node4 | undefined, cb: (n: Node4) => boolean | "quit"): Node4 | undefined;
    """.trimIndent()

    @Test
    fun `findAncestor with a named guard returns the narrowed T-or-undefined`() {
        diagnose(
            prelude + """
            function getFunc(n: Node4): FuncDecl | undefined {
                return findAncestor4(n, isFuncDecl);
            }
            """.trimIndent(),
        ) should {
            // Was TS2322 'Node4 | undefined' not assignable to 'FuncDecl | undefined'.
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `NonNull strips undefined from a concrete-union call return`() {
        // `isBoth` targets the union alias; `pick(x, isBoth)!` is `FuncDecl | ClassDecl`.
        diagnose(
            prelude + """
            type Either = FuncDecl | ClassDecl;
            declare function isBoth(n: Node4): n is Either;
            declare function pick<T extends Node4>(node: Node4, cb: (n: Node4) => n is T): T | undefined;
            declare function pick(node: Node4): Node4 | undefined;
            function useIt(x: Node4): Either {
                return pick(x, isBoth)!;
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `array-return guard overload infers the element type`() {
        diagnose(
            prelude + """
            interface Arr {
                filterG<S extends Node4>(cb: (n: Node4) => n is S): S[];
                filterG(cb: (n: Node4) => boolean): Node4[];
            }
            function onlyFuncs(a: Arr): FuncDecl[] {
                return a.filterG(isFuncDecl);
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a non-guard callback keeps the wide Node return`() {
        // An inline boolean-returning callback selects the concrete overload; the
        // result is `Node4 | undefined`, so assigning it to `FuncDecl | undefined`
        // must STILL error (the inference must not fire without a real guard arg).
        diagnose(
            prelude + """
            function getWide(n: Node4): FuncDecl | undefined {
                return findAncestor4(n, x => x.kind === 5);
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
