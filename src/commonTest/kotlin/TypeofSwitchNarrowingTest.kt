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
 * Round 466: `switch (typeof <ref>)` clause narrowing — narrowBySwitchClause
 * gains a TypeOfExpression subject arm (each clause narrows by its string tag
 * via narrowByTypeOfGuard: positive for the matched range, negative for prior
 * cases, negative-by-all for a default). tsc completions.ts:1477 read
 * `type.value.negative` on `string | number | PseudoBigInt` inside
 * `case "object":` and FP'd TS2339 ×2.
 */
class TypeofSwitchNarrowingTest {

    private val prelude = """
        interface PseudoBigInt { negative: boolean; base10Value: string; }
        interface Lit { value: string | number | PseudoBigInt; }
    """.trimIndent()

    @Test
    fun `a typeof switch case narrows a property-path subject`() {
        diagnose(
            prelude + """

            export function f(type: Lit): string {
                switch (typeof type.value) {
                    case "object":
                        return type.value.negative ? "-" + type.value.base10Value : type.value.base10Value;
                    default:
                        return "";
                }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a default clause narrows negatively by every case tag`() {
        diagnose(
            prelude + """

            export function f(type: Lit): string {
                switch (typeof type.value) {
                    case "string":
                        return type.value;
                    case "number":
                        return String(type.value);
                    default:
                        // only PseudoBigInt remains
                        return type.value.base10Value;
                }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a member read outside the narrowing case still fires`() {
        diagnose(
            prelude + """

            export function f(type: Lit): string {
                switch (typeof type.value) {
                    case "number":
                        return (type.value as any).toFixed(0);
                    default:
                        // string | PseudoBigInt here — 'negative' is not on string
                        return String((type.value).negative);
                }
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }
}
