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
 * Round 459: two coupled narrowing extensions.
 *
 * (1) An array-literal ELEMENT that is a flow-narrowed bare Identifier reads its
 * narrowed type in `getTypeOfArrayLiteral` — the array sibling of round 438's
 * object-literal property-value narrowing, same NULLISH-STRIP gate
 * (objLitValueNullishStrip). tsc's builderState.ts `if (!sourceFile) return
 * emptyArray; … return [sourceFile];` otherwise FP'd
 * `(SourceFile | undefined)[]` ⊄ `readonly SourceFile[]`.
 *
 * (2) `Array.isArray(x)` narrowing in `applyConditionNarrowing` (covers both the
 * round-458 AST ternary/logical path AND the flow-graph FlowCondition path — the
 * embedded lib deliberately has no ArrayConstructor, so the predicate cannot
 * resolve via declarations). tsc's chainDiagnosticMessages
 * `details === undefined || Array.isArray(details) ? details : [details]`
 * otherwise FP'd against `next?: DiagnosticMessageChain[]`.
 */
class ArrayElementFlowNarrowingTest {

    @Test
    fun `guarded element in a returned array literal narrows - no TS2322`() {
        diagnose("""
            interface SourceFile { path: string; }
            declare function getSF(p: string): SourceFile | undefined;
            declare const emptyArray: readonly SourceFile[];
            function f(p: string): readonly SourceFile[] {
                const sourceFile = getSF(p);
                if (!sourceFile) {
                    return emptyArray;
                }
                return [sourceFile];
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `chainDiagnosticMessages ternary with Array-isArray narrows both branches - no TS2322`() {
        diagnose("""
            interface Chain { messageText: string; next?: Chain[]; }
            function chain(details: Chain | Chain[] | undefined, text: string): Chain {
                return {
                    messageText: text,
                    next: details === undefined || Array.isArray(details) ? details : [details],
                };
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `Array-isArray true branch keeps only the array member - no TS2345`() {
        diagnose("""
            declare function takeArr(xs: string[]): void;
            function f(x: string[] | string) {
                if (Array.isArray(x)) {
                    takeArr(x);
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - unguarded possibly-undefined element still fires TS2322`() {
        diagnose("""
            interface SourceFile { path: string; }
            declare function getSF(p: string): SourceFile | undefined;
            function neg(p: string): readonly SourceFile[] {
                const sourceFile = getSF(p);
                return [sourceFile];
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
