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
 * Round 460 (M3.1): the `flatten<T>(array: T[][] | readonly (T | readonly T[] |
 * undefined)[])` union param — a `tp[][]` union member is an inference ANCHOR:
 * an array-of-array arg binds T from its inner element type. Without it the
 * whole signature bailed at the "mentions a TP in an unhandled shape" gate, T
 * stayed raw, and the relation FP'd TS2345 'readonly Extension[][]' vs the raw
 * union display (tsc utilities.ts supportedTSExtensionsFlat/JS).
 */
class FlattenDoubleArrayInferenceTest {

    private val prelude = """
        enum Extension { Ts = ".ts", Tsx = ".tsx", Js = ".js" }
        declare function flatten<T>(array: T[][] | readonly (T | readonly T[] | undefined)[]): T[];

    """.trimIndent()

    @Test
    fun `readonly enum double-array arg infers T and passes`() {
        diagnose(prelude + """
            const exts: readonly Extension[][] = [[Extension.Ts], [Extension.Tsx]];
            export const flat: readonly Extension[] = flatten(exts);
        """.trimIndent()) should {
            have(none { it.code == 2345 || it.code == 2322 })
        }
    }

    @Test
    fun `mutable string double-array arg infers T and passes`() {
        diagnose(prelude + """
            const rows: string[][] = [["a"], ["b", "c"]];
            export const flat2: string[] = flatten(rows);
        """.trimIndent()) should {
            have(none { it.code == 2345 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the inferred result type still checks downstream`() {
        diagnose(prelude + """
            const rows: string[][] = [["a"]];
            export const wrong: number = flatten(rows);
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
