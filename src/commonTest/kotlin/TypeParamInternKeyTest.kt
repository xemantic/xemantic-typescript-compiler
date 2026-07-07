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
import kotlin.test.Test

/**
 * M1.13: `Checker.typeParamInternCache` interns each generic's type parameter as a shared
 * `Type.TypeParam`. It USED to be keyed by the absolute AST `pos` alone, which COLLIDES
 * across files in a multi-file program (every file's positions start at 0), so two unrelated
 * type parameters in different files shared ONE instance and stomped its mutable
 * `.constraint`/`.default`. The key is now `(TypeParameter.internSalt, pos)` where `internSalt
 * = fileName.hashCode()` (stamped by the parser), so distinct files get distinct cache
 * entries. A single-file compile stamps every param with the SAME salt, so the key reduces to
 * a bijection with `pos` and interning is byte-identical (the full corpus is the regression net
 * for that). These tests pin the multi-file property that the bare-`pos` key could not.
 */
class TypeParamInternKeyTest {

    private fun build(files: Map<String, String>): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            files + ("/proj/tsconfig.json" to
                """{ "compilerOptions": { "outDir": "./dist", "strict": true }, "include": ["src/**/*.ts"] }""")
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    private fun ts2344(result: ProjectCompiler.Result): List<String> =
        result.diagnostics.filter { it.code == 2344 }.map { it.message }

    @Test
    fun `reverse compilation order - constrained generic declared in the second file - no TS2344`() {
        // The constrained `<X extends {}>` seed is in the file that sorts AFTER the victim, to
        // prove the fix is order-independent (a bare-pos cache could stomp in either direction
        // depending on which file's param is interned first).
        val result = build(
            mapOf(
                "/proj/src/a_victim.ts" to """
                    interface Holder<A, B, C> { d: A; self: Holder<A, B, C> | undefined; }
                    export function useHolder<A, B, C>(h: Holder<A, B, C> | undefined): void {}
                """.trimIndent(),
                "/proj/src/z_seed.ts" to """
                    interface Constrained<X extends {}> { v: X; }
                    export let c: Constrained<{ a: number }> = null as any;
                """.trimIndent(),
            )
        )
        have(ts2344(result).isEmpty())
    }

    @Test
    fun `generic FUNCTIONS across files with colliding param offsets and an explicit type arg - no TS2344`() {
        // Routes the collision through the function-declaration intern site, then checks the
        // explicit type arguments on the calls. `wrap<number>(1)` and `wrap<string>("s")` are
        // valid ONLY if the (unconstrained) param did not steal the sibling file's `extends {}`.
        val result = build(
            mapOf(
                "/proj/src/seed.ts" to """
                    export function bound<X extends {}>(x: X): X { return x; }
                    export const sb = bound<{ n: number }>({ n: 1 });
                """.trimIndent(),
                "/proj/src/use.ts" to """
                    export function wrap<T>(v: T): T { return v; }
                    export const a: number = wrap<number>(1);
                    export const b: string = wrap<string>("s");
                """.trimIndent(),
            )
        )
        have(ts2344(result).isEmpty())
    }

    @Test
    fun `three files each with a same-shaped generic - no spurious constraint diagnostics`() {
        // Three files whose generic `<T ...>` declarations land at overlapping byte offsets. A
        // bare-pos cache would smear one file's constraint across the others; the file-aware key
        // keeps them separate.
        val result = build(
            mapOf(
                "/proj/src/f1.ts" to """
                    interface One<T extends string> { a: T; }
                    export let x1: One<"lit"> = null as any;
                """.trimIndent(),
                "/proj/src/f2.ts" to """
                    interface Two<T> { b: T; }
                    export function mk2<T>(v: T): Two<T> { return { b: v }; }
                    export const y2 = mk2<number>(2);
                """.trimIndent(),
                "/proj/src/f3.ts" to """
                    interface Three<T extends number> { c: T; }
                    export let x3: Three<42> = null as any;
                """.trimIndent(),
            )
        )
        have(ts2344(result).isEmpty())
    }

    @Test
    fun `single-file generics with constraints are unaffected - zero errors`() {
        // Corpus-safety proxy: with one file every param carries the same salt, so the key is a
        // bijection with pos and interning is identical to the pre-fix behavior. Valid generics
        // must stay clean.
        val result = build(
            mapOf(
                "/proj/src/only.ts" to """
                    interface Pair<A, B extends { id: number }> { first: A; second: B; }
                    function pick<K extends string, V>(k: K, v: V): V { return v; }
                    const p: Pair<string, { id: number }> = { first: "x", second: { id: 1 } };
                    const v: number = pick<"n", number>("n", 3);
                """.trimIndent(),
            )
        )
        have(result.diagnostics.filter { it.code == 2344 || it.code == 2339 }.isEmpty())
    }

    @Test
    fun `a genuine cross-file constraint violation still fires TS2344 - negative control`() {
        // The fix must not over-suppress: a real `string` ≁ `number` violation still errors even
        // alongside the collision-prone constrained generic in a sibling file.
        val result = build(
            mapOf(
                "/proj/src/seed.ts" to """
                    interface Seed<X extends {}> { v: X; }
                    let s: Seed<{ a: number }> = null as any;
                """.trimIndent(),
                "/proj/src/bad.ts" to """
                    interface Bad<Y extends number> { w: Y; }
                    let bad: Bad<string> = null as any;
                """.trimIndent(),
            )
        )
        have(ts2344(result).any { it.contains("'string'") && it.contains("'number'") })
    }
}
