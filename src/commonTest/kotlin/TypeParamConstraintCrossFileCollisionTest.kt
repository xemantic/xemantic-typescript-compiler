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
 * `checkConstraintsForTypeArgs` interns each generic's type-parameter as a shared
 * `Type.TypeParam` via `typeParamInternCache`, keyed by the parameter's absolute AST
 * `pos`. In a MULTI-FILE program those positions COLLIDE across files (each file's
 * positions start at 0), so an UNCONSTRAINED parameter can get back the very instance
 * a CONSTRAINED, pos-colliding parameter in another file left with a stale `.constraint`
 * (e.g. `{}` from `<X extends {}>`). The check then compares the type argument against
 * that foreign constraint and emits a spurious TS2344 — exactly the FP that showed up in
 * tsc's own `src/compiler` (`LexicalEnvironment<in out TEnvData, TPrivateEnvData,
 * TPrivateEntry>`, whose unconstrained 3rd param was reported as not satisfying `{}`).
 *
 * The fix ALWAYS (re)sets `.constraint`/`.default` from the current node — clearing to
 * null for an unconstrained param — so a foreign constraint can never leak in. Single-file
 * compiles never collide, so nothing changes there (the corpus stays byte-identical).
 */
class TypeParamConstraintCrossFileCollisionTest {

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
    fun `unconstrained type param does not inherit a colliding constrained param's constraint`() {
        // g.ts declares a genuinely-constrained generic `<X extends {}>` (used validly), whose
        // `X` decl sits at the same byte offset as e.ts's unconstrained `A`/`C`. Before the fix,
        // e.ts's `E<A, B, C>` use reported `Type 'A' does not satisfy the constraint '{}'`.
        val result = build(
            mapOf(
                "/proj/src/g.ts" to """
                    interface G<X extends {}> { v: X; }
                    let g: G<{ a: number }> = null as any;
                """.trimIndent(),
                "/proj/src/e.ts" to """
                    interface E<A, B, C> { d: A; p: E<A, B, C> | undefined; }
                    function f<A, B, C>(e: E<A, B, C> | undefined): void {}
                """.trimIndent(),
            )
        )
        assert(ts2344(result).isEmpty())
    }

    @Test
    fun `variance-annotated interface with a trailing unconstrained param - no TS2344`() {
        // The exact tsc shape: `in out` variance on the first param, two trailing unconstrained
        // params, plus a sibling file with an `extends {}` generic to seed the collision.
        val result = build(
            mapOf(
                "/proj/src/lib.ts" to """
                    interface Bounded<T extends {}> { value: T; }
                    export let b: Bounded<{ x: number }> = null as any;
                """.trimIndent(),
                "/proj/src/env.ts" to """
                    export interface PrivEnv<TData, TEntry> { readonly data: TData; ids?: Map<string, TEntry>; }
                    export interface LexEnv<in out TEnvData, TPrivateEnvData, TPrivateEntry> {
                        data: TEnvData;
                        privateEnv?: PrivEnv<TPrivateEnvData, TPrivateEntry>;
                        readonly previous: LexEnv<TEnvData, TPrivateEnvData, TPrivateEntry> | undefined;
                    }
                    export function walk<TEnvData, TPrivateEnvData, TPrivateEntry, U>(
                        env: LexEnv<TEnvData, TPrivateEnvData, TPrivateEntry> | undefined,
                        cb: (env: LexEnv<TEnvData, TPrivateEnvData, TPrivateEntry>) => U,
                    ): U | undefined { return undefined; }
                """.trimIndent(),
            )
        )
        assert(ts2344(result).isEmpty())
    }

    @Test
    fun `a genuine constraint violation still fires TS2344 in a multi-file program`() {
        // Negative control: the fix must not over-suppress — a real `string` ≁ `number` violation
        // still errors, even alongside the collision-prone constrained generic.
        val result = build(
            mapOf(
                "/proj/src/g.ts" to """
                    interface G<X extends {}> { v: X; }
                    let g: G<{ a: number }> = null as any;
                """.trimIndent(),
                "/proj/src/h.ts" to """
                    interface H<Y extends number> { w: Y; }
                    let h: H<string> = null as any;
                """.trimIndent(),
            )
        )
        assert(ts2344(result).any { it.contains("'string'") && it.contains("'number'") })
    }
}
