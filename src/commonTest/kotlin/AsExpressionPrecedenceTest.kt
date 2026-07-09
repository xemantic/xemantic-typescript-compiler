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
 * Round 457 (parser precedence): `a + b as T` must parse as `(a + b) as T`, NOT
 * `a + (b as T)`. `as`/`satisfies` have precedence 7 — LOWER than the binary
 * arithmetic operators (`+`/`-` = 9, `*`/`/`/`%` = 10) — so a trailing cast binds
 * the WHOLE binary result, never just the right operand.
 *
 * The bug: `parseBinaryExpression` used to call a greedy `parseExpressionSuffix(left)`
 * right after the unary operand, attaching `as`/`satisfies` to that bare operand
 * BEFORE the precedence-respecting binary loop — so a right operand `b` in `a + b as T`
 * got `as T` glued to it. Removing that greedy call leaves the loop (which attaches
 * `as` only when `7 > minPrec`) as the sole handler.
 *
 * tsc's own sources trip this: binder.ts `getDeclarationName` returns
 * `tokenToString(nameExpression.operator) + nameExpression.operand.text as __String`
 * and `"arg" + index as __String` — the whole `+` is the cast source, so the return
 * type is `__String`, assignable to the declared `__String | undefined`. The wrong
 * parse yielded `string + __String` = `string` → FP `string ⊄ __String | undefined`.
 */
class AsExpressionPrecedenceTest {

    private val brandedString = """
        type Branded = string & { __brand: void };

    """.trimIndent()

    @Test
    fun `a plus b as T casts the whole plus result - no TS2322`() {
        // The exact binder.ts getDeclarationName shape: (a + b) as Branded → Branded,
        // which is assignable to the declared Branded | undefined return type.
        diagnose(
            brandedString + """
            function f(a: string, b: string): Branded | undefined {
                return a + b as Branded;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `string plus number as T casts the whole result - no TS2322`() {
        // binder.ts's `"arg" + index as __String` (JSDoc parameter naming).
        diagnose(
            brandedString + """
            function f(index: number): Branded | undefined {
                return "arg" + index as Branded;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `multiplicative operand also casts the whole result - no TS2322`() {
        diagnose(
            """
            type NumBrand = number & { __b: void };
            function g(x: number, y: number): NumBrand | undefined {
                return x * y as NumBrand;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - explicit right-operand cast is NOT the whole result`() {
        // `a + (b as Branded)` is `string + Branded` = `string`, NOT assignable to
        // `Branded | undefined`. This pins that the fix did not blanket-suppress a
        // right-operand cast — the wrong-parse result still fires TS2322.
        diagnose(
            brandedString + """
            function f(a: string, b: string): Branded | undefined {
                return a + (b as Branded);
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `a plus b satisfies T binds the whole result - no spurious error`() {
        // `satisfies` shares the precedence-7 code path; `(a + b) satisfies string`
        // is a valid no-op assertion (the whole `+` result is a string).
        diagnose(
            """
            function f(a: string, b: string): string {
                return a + b satisfies string;
            }
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 1360 })
        }
    }
}
