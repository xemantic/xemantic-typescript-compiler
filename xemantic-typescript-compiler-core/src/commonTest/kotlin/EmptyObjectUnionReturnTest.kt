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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 438: `return <non-nullish>` against a target that STRUCTURALLY carries an
 * empty-object `{}` member (`{} | undefined`) is legal — every non-nullish value
 * relates to `{}` (round 430's empty-object relation rule). The engine confirms it,
 * but because the relation PASSES for a non-nullish source there was no early return,
 * so control fell to the STRING fallback which re-widens / mis-handles `{}` and FP'd:
 * tsc commandLineParser.ts `getOptionValueWithEmptyStrings(...): {} | undefined`
 * returning `""` (a string) ×2.
 *
 * Same return-path string-fallback trap as [LiteralReturnVsLiteralUnionTest]; the
 * precise-verdict early return is gated to the `{}`-member target shape.
 */
class EmptyObjectUnionReturnTest {

    @Test
    fun `string return against empty-object-or-undefined is legal`() {
        diagnose(
            """
            function getOptionValueWithEmptyStrings(value: any): {} | undefined {
                if (value === undefined) return value;
                return "";
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `number and boolean returns against empty-object-or-undefined are legal`() {
        diagnose(
            """
            function f(kind: number): {} | undefined {
                if (kind === 0) return 1;
                if (kind === 1) return true;
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `bare empty-object return is legal - positive control`() {
        diagnose("""function f(): {} { return "x"; }""") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `null return against empty-object-or-undefined still fires - negative control`() {
        // null is NOT assignable to `{}` (nullish) nor to `undefined` — the engine
        // relation is false, so the empty-object early return does not fire and the
        // genuine TS2322 stands.
        diagnose(
            """
            function f(c: boolean): {} | undefined {
                return null;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
