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
 * Round 471: the (k)/(l) inference anchors read a `&&`-guarded nullable
 * Identifier arg's FLOW-NARROWED type — `focusLocations &&
 * flatten(focusLocations)` (tsc mapCode.ts) read the raw
 * `TextSpan[][] | undefined`, soft-skipped the `tp[][]` anchor, and T stayed
 * unbound while the arg CHECK narrowed → FP TS2345. Nullish-strip-gated
 * (monotone; the same objLitValueNullishStrip gate as the round-459 element
 * narrowing).
 */
class FlowNarrowedInferenceAnchorArgTest {

    private val prelude = """
        interface TextSpan { start: number; length: number; }
        declare function flatten<T>(array: T[][] | readonly (T | readonly T[] | undefined)[]): T[];
    """.trimIndent()

    @Test
    fun `an and-guarded nullable double-array arg binds T through the tp-double-array anchor`() {
        diagnose(
            prelude + """
            function mapCode(focusLocations: TextSpan[][] | undefined) {
                const flattenedLocations = focusLocations && flatten(focusLocations);
                return flattenedLocations;
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a direct double-array arg still binds T`() {
        diagnose(
            prelude + """
            declare const direct: TextSpan[][];
            const flat = flatten(direct);
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the bound T proves itself against a wrong downstream target`() {
        // The sharp signal that inference genuinely bound T := TextSpan through the
        // narrowed anchor: the call's return relates as TextSpan[], which fails a
        // string[] annotation (an unbound T would silently pass as any).
        diagnose(
            prelude + """
            function g(focusLocations: TextSpan[][] | undefined) {
                const flat: string[] | undefined = focusLocations && flatten(focusLocations);
                return flat;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
