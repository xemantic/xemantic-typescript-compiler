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
 * Round 467 (M3.4): a returned ARRAY LITERAL re-types from its flow-narrowed
 * reference elements — `if (isThrowStatement(node)) return [node];` built
 * `Node[]` (getTypeOfArrayLiteral's element narrowing accepts only nullish
 * strips per round 459's shadowing hazard), FP'ing against
 * `readonly ThrowStatement[] | undefined`. The narrowed array substitutes ONLY
 * when it makes the return relation pass (monotone) — wired at both the
 * direct-return path and the ternary-arm path (tsc documentHighlights.ts
 * aggregateOwnedThrowStatements / aggregateAllBreakAndContinueStatements,
 * jsDoc.ts, extractSymbol.ts).
 */
class GuardedArrayLiteralReturnTest {

    private val prelude = """
        interface Node2 { kind: number; }
        interface ThrowStatement2 extends Node2 { expr: string; }
        declare function isThrowStatement2(n: Node2): n is ThrowStatement2;
    """.trimIndent()

    @Test
    fun `a guarded array-literal return narrows its element`() {
        diagnose(
            prelude + """

            export function agg(node: Node2): readonly ThrowStatement2[] | undefined {
                if (isThrowStatement2(node)) {
                    return [node];
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a guarded ternary-arm array literal narrows its element`() {
        diagnose(
            prelude + """

            export function agg(node: Node2): readonly ThrowStatement2[] | undefined {
                return isThrowStatement2(node) ? [node] : undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed array-literal return still fires`() {
        diagnose(
            prelude + """

            export function bad(node: Node2): readonly ThrowStatement2[] | undefined {
                return [node];
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
