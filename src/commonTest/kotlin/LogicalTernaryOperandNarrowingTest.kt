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
 * Round 458 (M3.4): logical-operand / ternary-branch narrowing in expression
 * typing. tsc types `A && B` with B evaluated under "A is true", `A || B` (and a
 * ternary's FALSE branch) under "A is false", and a ternary's TRUE branch under
 * "A is true" — the binder places a FlowCondition on the operand for exactly this.
 * Our `getTypeOfExpression` path was flow-UNAWARE for bare references, so
 * `insertComment === undefined || insertComment` typed `boolean | undefined` (the
 * `undefined` survived → FP TS2322 against `boolean`; services.ts commenting logic).
 *
 * The fix applies the same [applyConditionNarrowing] the flow walk uses, gated to a
 * pure Identifier / PropertyAccess operand and FP-safe by construction (the type is
 * returned unchanged when the condition does not mention the operand's path).
 */
class LogicalTernaryOperandNarrowingTest {

    @Test
    fun `logical OR right operand narrows by the negated left condition`() {
        // `insertComment === undefined || insertComment` : the right `insertComment`
        // is evaluated under "=== undefined is false" → non-undefined → boolean.
        diagnose(
            """
            function f(insertComment: boolean | undefined): boolean {
                const isCommenting: boolean = insertComment === undefined || insertComment;
                return isCommenting;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `logical AND right operand narrows by the left condition being true`() {
        // `x !== undefined && x` : the right `x` is evaluated under "!== undefined
        // true" → boolean (tsc: `false | boolean` = `boolean`).
        diagnose(
            """
            function f(x: boolean | undefined): boolean {
                const r: boolean = x !== undefined && x;
                return r;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `ternary true branch narrows by the condition`() {
        // `x !== undefined ? x : true` : the true branch narrows `x` to non-undefined,
        // so the result is `boolean | true` (not `boolean | undefined`).
        diagnose(
            """
            function g(x: boolean | undefined): boolean {
                const r: boolean = x !== undefined ? x : true;
                return r;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `ternary discriminant narrows the selected member`() {
        // `shape.kind === "square" ? shape : other` : the true branch narrows `shape`
        // to `Square`, so the object literal `{ sq }` matches `{ sq: Square }`.
        diagnose(
            """
            interface Circle { kind: "circle"; r: number; }
            interface Square { kind: "square"; s: number; }
            type Shape = Circle | Square;
            function pick(shape: Shape, other: Square): { sq: Square } {
                const sq = shape.kind === "square" ? shape : other;
                return { sq };
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - ternary false branch narrowed to the else-type, not the condition`() {
        // `typeof x === "string" ? x : x` : the FALSE branch must narrow `x` to
        // `number` (condition false), NOT to `string`. So the result is `string |
        // number` and `const r: string` fires TS2322 on the `number` — proving the
        // false branch is not wrongly narrowed by the condition-TRUE fact.
        diagnose(
            """
            function neg(x: string | number): string {
                const r: string = typeof x === "string" ? x : x;
                return r;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
