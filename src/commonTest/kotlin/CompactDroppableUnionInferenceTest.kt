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
 * Round 466 (M3.1): inference gate (l) — an ARRAY param whose element is a
 * union of one bare TP plus DROPPABLE members (nullish / falsy literals), tsc
 * core.ts's `compact<T>(array: (T | undefined | null | false | 0 | "")[]): T[]`.
 * The candidate is the arg's element union MINUS the droppables, so
 * `compact([a, b, undefined-able])` binds T without undefined. Without the
 * gate, the bare-`T[]` overload won and bound T WITH undefined
 * (smartSelection.ts:336 ×2).
 */
class CompactDroppableUnionInferenceTest {

    private val prelude = """
        interface Node2 { kind: number; }
        interface SyntaxList2 extends Node2 { children: Node2[]; }
        declare function compact<T>(array: (T | undefined | null | false | 0 | "")[]): T[];
        declare function compact<T>(array: readonly (T | undefined | null | false | 0 | "")[]): readonly T[];
        declare function compact<T>(array: T[]): T[];
        declare function compact<T>(array: readonly T[]): readonly T[];
    """.trimIndent()

    @Test
    fun `compact strips the droppable union members from the inferred element`() {
        diagnose(
            prelude + """

            export function f(a: SyntaxList2 | undefined, b: Node2): readonly Node2[] {
                return compact([a, b]);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the stripped element type still checks precisely`() {
        diagnose(
            prelude + """

            export function g(a: SyntaxList2 | undefined): number[] {
                const r = compact([a]);
                return r;
            }
            """
        ) should {
            // T := SyntaxList2 (undefined stripped) — SyntaxList2[] is NOT number[].
            have(any { it.code == 2322 })
        }
    }
}
