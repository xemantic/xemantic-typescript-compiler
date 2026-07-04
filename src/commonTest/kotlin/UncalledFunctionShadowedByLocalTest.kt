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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TS2774 ("this condition will always return true since this function is always defined")
 * must respect local shadowing: a body-local `const`/`let`/`var` shadows an outer
 * `function <name>`, so `if (<name>)` tests the local value, NOT the always-defined
 * function. The shadow was registered in the TS2774 typed-locals scan only when the
 * initializer's TYPE could be resolved — so `const emitComments = state.stack[i] =
 * shouldEmitComments(node)` (whose element-access assignment initializer types to `any`)
 * left the name unshadowed and FP'd against the outer `function emitComments`. Exactly
 * the shape in tsc's own emitter.ts (9 self-compile FPs). Fixed by registering the shadow
 * unconditionally.
 */
class UncalledFunctionShadowedByLocalTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(body.trimIndent(), "t.ts").diagnostics

    @Test
    fun `a local const shadowing an outer function - no TS2774 even when the initializer types to any`() {
        val d = diags(
            """
            function emitComments(): void {}
            function pipe(o: any): void {
                const emitComments = o.foo;
                if (emitComments) {}
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2774 },
            "a local const shadowing an outer function must NOT draw TS2774; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `a local const shadowing an outer function in a nested block - no TS2774`() {
        val d = diags(
            """
            function shouldEmit(): boolean { return true; }
            function pipe(o: any): void {
                if (o) {
                    const shouldEmit = o.bar;
                    if (shouldEmit) {}
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2774 },
            "a nested-block local shadowing an outer function must NOT draw TS2774; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `a local shadowing a NESTED outer function - no TS2774 even with an any-typed initializer`() {
        // The remaining tsc emitter.ts shape: the shadowed `shouldEmit` is a NESTED function
        // (collected into the enclosing function's typed-locals scope as a callable), and the
        // shadowing `const shouldEmit` in a deeper block types to `any`. Before the follow-up
        // fix, `lookupUncalledTypedLocal` fell through the inner (shadowed-but-untyped) scope to
        // the outer scope's callable entry and FP'd. `if (shouldEmit)` must resolve to the local.
        val d = diags(
            """
            declare function trampoline(cb: (n: number, s: any) => void): number;
            function outer(): number {
                function shouldEmit(n: number): boolean { return n > 0; }
                return trampoline(onExit);
                function onExit(node: number, state: any): void {
                    if (state.idx > 0) {
                        const shouldEmit = state.flags[state.idx];
                        if (shouldEmit) {}
                    }
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2774 },
            "a local shadowing a nested outer function must NOT draw TS2774; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `an unshadowed always-defined function still fires TS2774 (negative control)`() {
        val d = diags(
            """
            function isReady(): boolean { return true; }
            function run(): void {
                if (isReady) {}
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2774 },
            "a genuinely-uncalled function in a condition must still fire TS2774; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
