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
 * (CHK.67) A CHAINED assignment narrows its target to the value its RIGHTMOST operand
 * assigns: `x = y = z` gives `x` the value of `z`, because the type of an assignment
 * EXPRESSION is the type of its right operand.
 *
 * Every arm of [Checker.narrowByAssignmentRhs] classifies the right-hand side
 * SYNTACTICALLY, and an un-unwrapped `y = z` matches none of them — it is a
 * `BinaryExpression` whose operator IS `=`, which the (CHK.33) computed-primitive arm
 * excludes by construction — so the walk fell through to the conservative antecedent
 * pass-through. Measured as a SHIPPED false positive needing NO gate and NO loop:
 * `let i: number | undefined; i = c = o.len;` then `const p: number | string = i` was
 * ours-only against tsc 7.0.2, while the `i = c` and `i = c + 1` spellings of the same
 * idiom already worked — three spellings of one idiom disagreeing, round 833's shape.
 *
 * Every positive names its READER. P1/P2/P5/P6 are the DECLARATION reader with a UNION
 * target (live with no gate: the row simply disappears). P3/P4 are the DECLARATION
 * reader with a PRIMITIVE target, graded by a deliberate mis-assignment — the only
 * instrument that PRINTS the flow type — P4 inside a `for`, which is where tsc's own
 * `reorderCandidates` writes it.
 *
 * The controls pin the two ways an over-eager unwrap would be wrong: C1 a chain whose
 * ULTIMATE right-hand side is itself nullish must still NOT narrow (guards against a
 * blanket non-nullish reset), and C2 a chain assigning some OTHER name must leave ours
 * alone (guards `flowAssignmentTargetsName` against being widened by the unwrap).
 */
class AChainedAssignmentNarrowsToItsUltimateRhsTest {

    private val prelude =
        """
        declare const zzzO: { zzzLen: number };
        declare const zzzMaybe: number | undefined;

        """.trimIndent() + "\n"

    @Test
    fun `P1 - DECLARATION reader with a UNION target - a chained assignment narrows`() {
        diagnose(
            prelude +
                """
                export function zzzP1(): void {
                  let zzzC = 0;
                  let zzzI: number | undefined;
                  zzzI = zzzC = zzzO.zzzLen;
                  const zzzP: number | string = zzzI;
                }
                """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `P2 - DECLARATION reader with an OBJECT union target - an object-literal chain`() {
        diagnose(
            prelude +
                """
                export function zzzP2(): void {
                  let zzzC: { zzzQ: number } = { zzzQ: 0 };
                  let zzzI: { zzzQ: number } | undefined;
                  zzzI = zzzC = { zzzQ: 1 };
                  const zzzP: { zzzQ: number } | string = zzzI;
                }
                """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `P3 - DECLARATION reader with a PRIMITIVE target - the flow type is number, not number or undefined`() {
        diagnose(
            prelude +
                """
                export function zzzP3(): void {
                  let zzzC = 0;
                  let zzzI: number | undefined;
                  zzzI = zzzC = zzzO.zzzLen;
                  const zzzP: boolean = zzzI;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'number' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("number | undefined") })
        }
    }

    @Test
    fun `P4 - DECLARATION reader inside a for loop - tsc reorderCandidates' own shape`() {
        diagnose(
            prelude +
                """
                declare const zzzXs: readonly string[];
                export function zzzP4(): void {
                  let zzzC = 0;
                  let zzzI: number | undefined;
                  for (const zzzS of zzzXs) {
                    zzzI = zzzC = zzzO.zzzLen;
                    const zzzP: boolean = zzzI;
                  }
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'number' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("number | undefined") })
        }
    }

    @Test
    fun `P5 - DECLARATION reader - a PARENTHESISED chain unwraps too`() {
        diagnose(
            prelude +
                """
                export function zzzP5(): void {
                  let zzzC = 0;
                  let zzzI: number | undefined;
                  zzzI = (zzzC = zzzO.zzzLen);
                  const zzzP: number | string = zzzI;
                }
                """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `P6 - DECLARATION reader - a THREE-link chain reaches the rightmost operand`() {
        diagnose(
            prelude +
                """
                export function zzzP6(): void {
                  let zzzA2 = 0;
                  let zzzB2 = 0;
                  let zzzI: number | undefined;
                  zzzI = zzzA2 = zzzB2 = zzzO.zzzLen;
                  const zzzP: number | string = zzzI;
                }
                """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `C1 - control - a chain whose ultimate right-hand side is NULLISH must not narrow`() {
        diagnose(
            prelude +
                """
                export function zzzC1(): void {
                  let zzzC: number | undefined;
                  let zzzI: number | undefined;
                  zzzI = zzzC = zzzMaybe;
                  const zzzP: number | string = zzzI;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("number | undefined") })
        }
    }

    @Test
    fun `C2 - control - a chain assigning some OTHER name leaves ours alone`() {
        diagnose(
            prelude +
                """
                export function zzzC2(): void {
                  let zzzC = 0;
                  let zzzOther: number | undefined;
                  let zzzI: number | undefined;
                  zzzOther = zzzC = zzzO.zzzLen;
                  const zzzP: number | string = zzzI;
                }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("number | undefined") })
        }
    }
}
