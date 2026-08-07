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
 * Round 471: a nested SAME-FILE type guard shadows a same-named cross-file
 * global function in flow-callee resolution — tsc fixMissingTypeAnnotation-
 * OnExports.ts nests `isConstAssertion(location): location is
 * AssertionExpression` while compiler/utilities.ts exports a NON-guard
 * `isConstAssertion(location): boolean`; the merged-globals hit returned the
 * boolean one and the narrowing silently never fired, FP'ing TS2741 on
 * `initializationNode = replacementTarget = replacementTarget.parent`.
 * resolveFlowCalleeDecl now prefers the unique predicate-bearing nested decl
 * of the CURRENT file over a non-predicate globals hit (flow-only).
 */
class NestedGuardShadowsGlobalFnTest {

    private val prelude = """
        // @filename: types.ts
        export interface Node { kind: number; parent: Node; }
        export interface Expression extends Node { _expressionBrand: any; }
        export interface AssertionExpression extends Expression { type: string; }
        export declare function isAssertionExpression(n: Node): n is AssertionExpression;
        export declare function walkUp(n: Node): Node;
        // @filename: utilities.ts
        import { Node } from "./types";
        export function isConstAssertion(location: Node): boolean {
            return location.kind === 1;
        }
    """.trimIndent()

    @Test
    fun `a nested same-file guard narrows despite a same-named non-guard global`() {
        diagnose(
            prelude + "\n" + """
            // @filename: fix.ts
            import { Node, Expression, AssertionExpression, isAssertionExpression, walkUp } from "./types";
            export function outer(targetNode: Expression) {
                let replacementTarget = targetNode;
                let initializationNode = targetNode;
                replacementTarget = walkUp(replacementTarget.parent) as Expression;
                if (isConstAssertion(replacementTarget.parent)) {
                    initializationNode = replacementTarget = replacementTarget.parent;
                }
                return [replacementTarget, initializationNode];
                function isConstAssertion(location: Node): location is AssertionExpression {
                    return isAssertionExpression(location);
                }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2741 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the narrowed value still fails an unrelated target`() {
        diagnose(
            prelude + "\n" + """
            // @filename: fix.ts
            import { Node, AssertionExpression, isAssertionExpression } from "./types";
            export function outer2(x: Node) {
                if (isConstAssertion(x.parent)) {
                    const s: string = x.parent;
                    return s;
                }
                return "";
                function isConstAssertion(location: Node): location is AssertionExpression {
                    return isAssertionExpression(location);
                }
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
