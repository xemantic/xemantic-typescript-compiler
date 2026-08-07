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
 * INV.3(d) retire pin (originally round 460's `moduleFileLocalVarNames` bail,
 * deleted in INV.3(d)(v)): the arithmetic pass's operand typing must not
 * resolve a bare Identifier through a foreign module file's top-level variable
 * — tsc program.ts's module-level `const indent = "    "` used to leak into
 * `globals` and poison parser.ts's body-local `let indent` (`margin - indent`
 * read the leaked `string` → FP TS2363). Post-retire the merge never publishes
 * module-only names, so the foreign const is simply invisible; the fixture
 * keeps it as leak bait.
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
