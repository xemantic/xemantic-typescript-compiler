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
 * Round 470 (M3.4): a returned `X || undefined` / `X ?? y` whose LEFT reference
 * was guard-narrowed recombines with the narrowed left before the return check
 * (monotone — substituted only when it makes the return relate). The tsc shape is
 * sourcemaps.ts getSourceMapper: `if (isString(c)) return …; if (c !== undefined)
 * return c || undefined;` where `c: string | DocumentPositionMapper | undefined`.
 */
class NarrowedLeftOperandOrReturnTest {

    private val prelude = """
        interface Mapper { getSourcePosition(input: number): number; }
        declare function isString(text: unknown): text is string;
    """.trimIndent()

    @Test
    fun `a guard-narrowed OR-left return relates to the narrowed target`() {
        diagnose(
            prelude + """

            declare function readMapFile(f: string): string | Mapper | undefined | false;
            function getMapper(locations: string[]): Mapper | undefined {
                for (const location of locations) {
                    const contents = readMapFile(location);
                    if (isString(contents)) {
                        return undefined;
                    }
                    if (contents !== undefined) {
                        return contents || undefined;
                    }
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a nullish-coalescing left narrows the same way`() {
        diagnose(
            prelude + """

            declare const c: string | Mapper | undefined;
            function f(): Mapper | undefined {
                if (isString(c)) return undefined;
                return c ?? undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed string-carrying union still fails`() {
        diagnose(
            prelude + """

            declare const raw: string | Mapper | undefined;
            function bad(): Mapper | undefined {
                return raw || undefined;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
