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
 * M1.12 (self-compile burn-down): the arithmetic/comparison pass records a bare-identifier
 * operand's type via `getTypeOfExpression`, which falls back to file/global scope for an
 * un-recorded function-body local — so a local `const length = arr.length` (a number) that
 * SHADOWS an outer same-named function resolved to the FUNCTION, and `i < length` FP'd TS2365
 * "Operator '<' cannot be applied to types 'number' and '(...) => number'." tsc's own
 * `src/compiler` trips this ~14 times (`core.ts` exports `function length/min/max(): number`,
 * shadowed by local `const length = X.length` / `const max = length(sig.tp)`).
 *
 * Fix: record an un-annotated `const` whose name shadows an outer FUNCTION (concrete primitive
 * when determinable, else `anyType`). The SHADOW gate is load-bearing — a non-shadowing local
 * (`const numStatements = source.length`) must NOT record `number`, or it would UNMASK a
 * pre-existing narrowing FP on the OTHER operand (`statementOffset < numStatements`,
 * statementOffset an un-narrowed `number | undefined` param). Negative controls pin both edges.
 */
class ArithmeticShadowedFunctionLocalTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `local const shadowing an outer function used in a comparison - no TS2365`() {
        val d = diags(
            """
            export function length(array: readonly number[]): number { return array.length; }
            export function f(items: readonly number[]): void {
                const length = items.length;
                let i = 0;
                while (i < length) { i = i + 1; }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2365 },
            "a local `const length = items.length` shadows the outer function → `i < length` is number<number, no TS2365; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `shadowing local whose initializer resolves to any still suppressed - no TS2365`() {
        // The `if (outer)`-narrowed-union receiver shape (checker.ts:7481): the arithmetic pass
        // can't type `outer.length` (outer is an un-narrowed union it doesn't resolve), so the
        // any-fallback records the shadow local as `any` → the comparison bails.
        val d = diags(
            """
            export function length(array: readonly number[]): number { return array.length; }
            export function f(type: { items?: readonly number[] }): void {
                const items = type.items;
                if (items) {
                    const length = items.length;
                    let i = 0;
                    while (i < length) { i = i + 1; }
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2365 },
            "a shadow local whose initializer types to any must still be suppressed; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `genuine number-lt-function (not shadowed) STILL fires TS2365 - negative control`() {
        // FP-safety: a bare function used as a comparison operand (not shadowed by any local) must
        // still error — the fix only records LOCAL const declarations that shadow a function.
        val d = diags(
            """
            export function g(): number { return 1; }
            export function h(): boolean {
                return 5 < g;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2365 },
            "a non-shadowed function operand `5 < g` MUST still fire TS2365; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `local LET shadowing an outer function used in a comparison - no TS2365`() {
        // Round 416: checker.ts's `let min = Number.POSITIVE_INFINITY` shadows the imported
        // `function min<T>(...)`, and `if (min < args.length && args.length < max)` FP'd TS2365
        // "Operator '<' cannot be applied to types '{ <T>(...) }' and 'number'." The round-407
        // recording was gated to `const`; extend to `let`/`var` (recording `anyType`, which is
        // reassignment-proof — the shadow is what kills the FP).
        val d = diags(
            """
            export function min(items: readonly number[]): number { return items[0]; }
            export function max(items: readonly number[]): number { return items[0]; }
            export function f(args: readonly number[]): void {
                let min = Number.POSITIVE_INFINITY;
                let max = Number.NEGATIVE_INFINITY;
                for (const n of args) { if (n < min) min = n; max = Math.max(max, n); }
                if (min < args.length && args.length < max) { return; }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2365 },
            "`let min`/`let max` shadowing the outer functions → `min < args.length` is number<number, no TS2365; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
