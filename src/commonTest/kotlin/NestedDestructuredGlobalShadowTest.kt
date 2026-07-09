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
 * Round 460: a DESTRUCTURED const inside a nested block (`if (…) { const
 * { start, length } = getSpan(…); … }`) shadows a same-named global — tsc
 * checker.ts:37376's `const { start, length } = getDiagnosticSpanForCallNode(…)`
 * inside an if-block: the read `diagnostic.length = length` otherwise resolved
 * through globals to core.ts's `length(array)` function → FP TS2322
 * '(array: …) => number' not assignable to 'number'. Nested binding-pattern
 * names now register anyType (same discipline as the round-455 Identifier arm
 * of applyNestedGlobalShadow).
 */
class NestedDestructuredGlobalShadowTest {

    private val prelude = """
        declare function length(array: readonly any[] | undefined): number;
        interface Diag { start: number; length: number; }
        declare function getSpan(n: number): { start: number; length: number };

    """.trimIndent()

    @Test
    fun `nested destructured const shadowing a global function - no TS2322 on the read`() {
        diagnose(prelude + """
            function f(diagnostic: Diag, cond: boolean) {
                if (cond) {
                    const { start, length } = getSpan(1);
                    diagnostic.start = start;
                    diagnostic.length = length;
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `array-pattern nested destructuring shadowing a global - no TS2322 on the read`() {
        diagnose(prelude + """
            declare function pair(): [number, number];
            function f(diagnostic: Diag, cond: boolean) {
                if (cond) {
                    const [start, length] = pair();
                    diagnostic.start = start;
                    diagnostic.length = length;
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a non-shadowing global function read still fires`() {
        diagnose(prelude + """
            function f(diagnostic: Diag, cond: boolean) {
                if (cond) {
                    diagnostic.length = length;
                }
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
