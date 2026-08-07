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
 * Round 460: a CAPTURED read inside an expression-bodied arrow must see the
 * ENCLOSING function's assignments — `isAssignedAtFlow` now follows
 * `FlowStart.outerFlow` (tsc assumes a captured outer-variable read
 * initialized; checker.ts:14106's `filter(inheritedIndexInfos, info =>
 * !findIndexInfo(indexInfos, info.keyType))` reads `indexInfos` assigned by
 * the preceding if/else → FP TS2454).
 */
class CapturedReadDefiniteAssignmentTest {

    private val prelude = """
        interface IndexInfo { keyType: string; }
        declare function filter<T>(a: readonly T[], f: (x: T) => boolean): readonly T[];
        declare function findIndexInfo(infos: readonly IndexInfo[], k: string): IndexInfo | undefined;
        declare function concatenate<T>(a: readonly T[], b: readonly T[]): readonly T[];

    """.trimIndent()

    @Test
    fun `captured read in an arrow sees the outer if-else assignment - no TS2454`() {
        diagnose(prelude + """
            function f(cond: boolean, declared: readonly IndexInfo[], bases: readonly (readonly IndexInfo[])[]) {
                let indexInfos: readonly IndexInfo[];
                if (cond) {
                    indexInfos = declared;
                } else {
                    indexInfos = concatenate(declared, declared);
                }
                if (bases.length) {
                    for (const b of bases) {
                        indexInfos = concatenate(indexInfos, filter(b, info => !findIndexInfo(indexInfos, info.keyType)));
                    }
                }
                return indexInfos;
            }
        """.trimIndent()) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `captured read sees a straight-line outer assignment before a loop - no TS2454`() {
        diagnose(prelude + """
            function f(declared: readonly IndexInfo[], bases: readonly (readonly IndexInfo[])[]) {
                let indexInfos: readonly IndexInfo[];
                indexInfos = declared;
                for (const b of bases) {
                    indexInfos = concatenate(indexInfos, filter(b, info => !findIndexInfo(indexInfos, info.keyType)));
                }
                return indexInfos;
            }
        """.trimIndent()) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a same-container read before any assignment still fires TS2454`() {
        diagnose(prelude + """
            function f(cond: boolean, declared: readonly IndexInfo[]) {
                let indexInfos: readonly IndexInfo[];
                if (cond) {
                    const n = findIndexInfo(indexInfos, "k");
                }
                indexInfos = declared;
                return indexInfos;
            }
        """.trimIndent()) should {
            have(any { it.code == 2454 })
        }
    }
}
