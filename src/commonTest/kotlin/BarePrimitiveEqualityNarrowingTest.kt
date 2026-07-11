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
 * Round 474 (the tsc server/utilities.ts getBaseConfigFileName family): a POSITIVE
 * equality against a literal narrows a BARE supertype primitive to the literal
 * (tsc narrowTypeByEquality) — [narrowUnionByLiteral]'s non-union branch
 * previously returned the primitive unchanged, so `base === "tsconfig.json" ||
 * base === "jsconfig.json" ? base : undefined` FP'd `string` against the literal
 * union return. The negative (`!==`) case stays unchanged.
 */
class BarePrimitiveEqualityNarrowingTest {

    @Test
    fun `equality-narrowed bare string relates to the literal union in a ternary return`() {
        diagnose(
            """
            declare function getBaseFileName(p: string): string;
            export function getBaseConfigFileName(configFilePath: string): "tsconfig.json" | "jsconfig.json" | undefined {
                const base = getBaseFileName(configFilePath);
                return base === "tsconfig.json" || base === "jsconfig.json" ? base : undefined;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed bare string still fails the literal union`() {
        diagnose(
            """
            declare function getBaseFileName(p: string): string;
            export function bad(configFilePath: string): "tsconfig.json" | "jsconfig.json" {
                const base = getBaseFileName(configFilePath);
                return base;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
