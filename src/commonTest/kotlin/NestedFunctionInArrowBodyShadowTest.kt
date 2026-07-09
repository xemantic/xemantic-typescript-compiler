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
 * Round 454 (M3.1, self-compile burn-down): a NESTED `function NAME(...)` declared inside an
 * ARROW or FUNCTION-EXPRESSION body shadows a same-named EXPORTED/global function of a different
 * signature. The binder does not bind body-nested function declarations (B83.5), so the arg-check
 * callee resolver `getCalleeType` fell through to the merged `globals` and checked the call's args
 * against the exported function's params — tsc's own program.ts declares a nested
 * `function createDiagnosticForNodeArray(nodes, message)` inside the `runWithCancellationToken(() =>
 * { … })` arrow body, shadowing utilities.ts's exported `createDiagnosticForNodeArray(sourceFile,
 * nodes, message)`; a sibling-nested `walkArray`'s call FP-checked `nodes` (a NodeArray) against
 * `sourceFile` (a SourceFile) → TS2345 ×3. `shadowNestedFunctionNames` (which anyType-bails a
 * body-nested fn colliding with an outer binding) previously ran ONLY for FunctionDeclaration
 * bodies; it now also runs for ArrowFunction / FunctionExpression bodies in the call-types walker.
 */
class NestedFunctionInArrowBodyShadowTest {

    private val prelude = """
        interface SourceFile2 { kind: number; }
        interface NodeArr { length: number; }
        export function makeDiag(sourceFile: SourceFile2, nodes: NodeArr, message: string): void {}
        declare function run(cb: () => void): void;
    """.trimIndent() + "\n"

    @Test
    fun `nested function in an arrow body shadows a same-named exported function`() {
        // The nested `makeDiag(nodes, message)` shadows the exported `makeDiag(sourceFile, nodes,
        // message)`; the sibling-nested `walkArray`'s call must resolve to the nested one, so
        // `nodes` (NodeArr) is NOT checked against `sourceFile` (SourceFile2).
        diagnose(
            prelude +
            """
            export function outer(): void {
                run(() => {
                    function walkArray(nodes: NodeArr): void {
                        makeDiag(nodes, "hello");
                    }
                    function makeDiag(nodes: NodeArr, message: string): void {}
                    walkArray({ length: 0 });
                });
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `nested function in a function-expression body shadows a same-named exported function`() {
        diagnose(
            prelude +
            """
            export function outer(): void {
                run(function () {
                    function walkArray(nodes: NodeArr): void {
                        makeDiag(nodes, "hi");
                    }
                    function makeDiag(nodes: NodeArr, message: string): void {}
                    walkArray({ length: 0 });
                });
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a call to the exported function outside the arrow still type-checks - negative control`() {
        // No shadowing local in scope here — the exported `makeDiag(sourceFile, nodes, message)`
        // must still fire TS2345 when the first arg is the wrong type.
        diagnose(
            prelude +
            """
            export function outer(nodes: NodeArr): void {
                makeDiag(nodes, nodes, "boom");
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
