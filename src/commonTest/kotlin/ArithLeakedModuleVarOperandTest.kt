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
 * Round 460: the arithmetic pass's operand typing must not resolve a bare
 * Identifier through a LEAKED cross-file module variable (the round-442
 * `moduleFileLocalVarNames` family) — tsc program.ts's module-level
 * `const indent = "    "` leaked into globals and parser.ts's body-local
 * `let indent` (a number the pass leaves unrecorded) read `margin - indent`
 * as the leaked `string` → FP TS2363 (parser.ts:8974). `arithOperandType`
 * now bails such names to anyType UNLESS they are the current file's own
 * binding.
 */
class ArithLeakedModuleVarOperandTest {

    @Test
    fun `cross-file leaked module const does not poison a same-named body local operand`() {
        diagnose("""
            // @filename: program.ts
            export const indent = "    ";
            // @filename: parser.ts
            export function parse(margin: number | undefined, start: number) {
                let indent = start + 4;
                if (margin !== undefined) {
                    const d = margin - indent;
                    return d;
                }
                return 0;
            }
        """.trimIndent()) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `negative control - the module var's OWN file still fires on a string operand`() {
        diagnose("""
            // @filename: program.ts
            export const indent = "    ";
            export const bad = indent - 5;
        """.trimIndent()) should {
            have(any { it.code == 2362 || it.code == 2363 })
        }
    }
}
