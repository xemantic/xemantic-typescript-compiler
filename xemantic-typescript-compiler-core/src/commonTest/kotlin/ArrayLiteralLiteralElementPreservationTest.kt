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
 * Round 471: a fresh ARRAY LITERAL keeps its elements' literal types when the
 * contextual target's element type contains literals (tsc contextual typing).
 * `const ops: readonly Op[] = ["a", "b"]` previously widened to `string[]` and
 * FP'd TS2322 (tsc services.ts invalidOperationsInPartialSemanticMode); the
 * ternary form (`pref.typeOrder ? [pref.typeOrder] : ["last", "inline",
 * "first"]`) is tsc organizeImports.ts getDetectionLists. Three coupled pieces:
 * literalTypeOfExpression's ArrayLiteralExpression arm, the one-literal-arm
 * ConditionalExpression relaxation, and propTypeContainsLiteral's
 * Array/ReadonlyArray Reference arm.
 */
class ArrayLiteralLiteralElementPreservationTest {

    private val prelude = """
        type Op = "a" | "b" | "c";
    """.trimIndent()

    @Test
    fun `array literal of member literals satisfies a readonly literal-union array`() {
        diagnose(
            prelude + """
            const ops: readonly Op[] = ["a", "b"];
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `array literal of member literals satisfies a mutable literal-union array`() {
        diagnose(
            prelude + """
            const ops: Op[] = ["c", "a"];
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `returned objlit member ternary with array-literal arm satisfies the declared member`() {
        diagnose(
            prelude + """
            interface Prefs { typeOrder?: Op; }
            function getLists(preferences: Prefs): { orders: Op[]; } {
                return {
                    orders: preferences.typeOrder ? [preferences.typeOrder] : ["a", "b", "c"],
                };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a non-member literal element still fires`() {
        diagnose(
            prelude + """
            const bad: readonly Op[] = ["z", "a"];
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a widened string variable element still fires`() {
        diagnose(
            prelude + """
            declare const s: string;
            const bad: readonly Op[] = [s];
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a ternary with a wrong literal arm still fires`() {
        diagnose(
            prelude + """
            declare const cond: boolean;
            const bad: 0 | 1 = cond ? 0 : 2;
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
