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
 * M1.12 (self-compile burn-down, round 415): a `x!` (NonNullExpression) arithmetic operand
 * whose base type is `T | undefined` must use the NON-NULL type `T` — tsc types `x!` as
 * `NonNullable<typeof x>` and does arithmetic on THAT.
 *
 * `getTypeOfExpression(NonNullExpression)` deliberately keeps the union for `(T | undefined)!`
 * (round 407: a GLOBAL nullish-strip in that case unmasks M3 object-literal/generic gaps →
 * reverted). But the arithmetic pass classifies operands, and a `Type.Union`'s own `.flags`
 * carry neither the Undefined bit nor the numeric-enum bit, so `TokenFlags | undefined` fails
 * every operand test → spurious TS2362/TS2363. `arithOperandType` strips nullish LOCALLY (only
 * for a syntactic `!`), which reproduced tsc's own source: `templateFlags! & TokenFlags.X`
 * (nodeFactory), `contextFlags! & ContextFlags.X` (checker), `state.affectedFilesIndex! - 1`
 * (builder).
 *
 * Negative control: a `x!` whose non-null type is genuinely non-numeric (`string | undefined`)
 * must STILL fire TS2362 — the strip only removes nullish members, it does not make a
 * non-numeric operand valid.
 */
class NonNullArithmeticOperandTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `nonnull numeric enum operand bitwise - no TS2362`() {
        // Mirrors nodeFactory.ts `templateFlags! & TokenFlags.TemplateLiteralLikeFlags`.
        val d = diags(
            """
            enum TokenFlags { None = 0, A = 1, B = 2, Mask = 3 }
            export function f(flags: TokenFlags | undefined): TokenFlags {
                return flags! & TokenFlags.Mask;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2362 || it.code == 2363 },
            "`flags! & TokenFlags.Mask` on a `TokenFlags | undefined` operand must not fire " +
                "TS2362/TS2363; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `nonnull number operand subtraction - no TS2362`() {
        // Mirrors builder.ts `state.affectedFilesIndex! - 1`.
        val d = diags(
            """
            interface S { idx: number | undefined; }
            export function f(s: S): number {
                return s.idx! - 1;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2362 || it.code == 2363 },
            "`s.idx! - 1` on a `number | undefined` member must not fire TS2362/TS2363; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `nonnull operand on the right side - no TS2363`() {
        val d = diags(
            """
            enum F { None = 0, A = 1 }
            export function f(mask: F, flags: F | undefined): F {
                return mask & flags!;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2362 || it.code == 2363 },
            "`mask & flags!` (nonnull on the RHS) must not fire TS2362/TS2363; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `nonnull non-numeric operand STILL fires TS2362 - negative control`() {
        val d = diags(
            """
            export function bad(s: string | undefined): number {
                return s! - 1;
            }
            """,
        )
        // `s! - 1` — after the strip the operand is `string`, still non-numeric → TS2362 stands.
        assertTrue(
            d.any { it.code == 2362 },
            "a nonnull operand whose non-null type is `string` MUST still fire TS2362; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `plain undefined-union operand without nonnull STILL fires TS2362 - control`() {
        // Without the `!`, the arithmetic pass does no flow narrowing → the union is invalid.
        // (tsc narrows via `&&`, but that is M3.4; here there is no guard, so TS2362 is correct.)
        val d = diags(
            """
            enum TokenFlags { None = 0, A = 1, Mask = 3 }
            export function f(flags: TokenFlags | undefined): TokenFlags {
                return flags & TokenFlags.Mask;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2362 },
            "a bare `flags & X` on a `TokenFlags | undefined` (no `!`, no guard) MUST still fire " +
                "TS2362; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
