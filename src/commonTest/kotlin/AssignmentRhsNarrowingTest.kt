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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M3.4 (self-compile burn-down): a plain assignment `x = y` where `y` (an Identifier or property
 * path) is narrowed by a preceding user type-guard to a SUBTYPE of `x`'s declared type must not
 * fire TS2741/TS2322. tsc's own `utilities.ts` (`node = parent` inside
 * `if (isParenthesizedExpression(parent))`) and `nodeFactory.ts` (`target = callee` inside
 * `if (isSuperProperty(callee))`) rely on this.
 *
 * Flow narrowing was consulted by the var-decl assignability path / TS2339 / call-args / the
 * TS2349 callee, but NOT by `checkAssignmentExpression`, so the plain-assignment RHS resolved to
 * the DECLARED (wider) type and FP'd the missing-brand-property error.
 */
class AssignmentRhsNarrowingTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `assign a type-guard-narrowed local to a supertype-typed local - no error`() {
        // Mirrors utilities.ts expressionResultIsUnused: `node = parent`.
        val d = diags(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _expressionBrand: any; }
            interface ParenthesizedExpression extends Expression { _parenBrand: any; }
            declare function isParenthesizedExpression(n: Node): n is ParenthesizedExpression;

            declare function getParent(n: Node): Node;

            export function walk(node: Expression): void {
                let cur: Expression = node;
                const parent: Node = getParent(cur);
                if (isParenthesizedExpression(parent)) {
                    cur = parent; // parent : ParenthesizedExpression <: Expression — OK
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2741 || it.code == 2322 || it.code == 2739 },
            "assigning a guard-narrowed `parent` (ParenthesizedExpression) to an Expression local " +
                "must not fire a missing-property error; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `nodeFactory target = callee shape - no error`() {
        // Mirrors nodeFactory.ts createCallBinding: `target = callee`.
        val d = diags(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _expressionBrand: any; }
            interface UnaryExpression extends Expression { _unaryExpressionBrand: any; }
            interface UpdateExpression extends UnaryExpression { _updateExpressionBrand: any; }
            interface LeftHandSideExpression extends UpdateExpression { _leftHandSideExpressionBrand: any; }
            interface SuperProperty extends LeftHandSideExpression { _superBrand: any; }
            declare function isSuperProperty(n: Node): n is SuperProperty;

            export function bind(callee: Expression): void {
                let target: LeftHandSideExpression;
                if (isSuperProperty(callee)) {
                    target = callee; // callee : SuperProperty <: LeftHandSideExpression — OK
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2739 || it.code == 2741 || it.code == 2322 },
            "assigning a guard-narrowed `callee` (SuperProperty) to a LeftHandSideExpression local " +
                "must not fire a missing-property error; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `while-loop param reassignment with deep multi-base chain - no error`() {
        // EXACT shape of utilities.ts expressionResultIsUnused: a `while (true)` loop reassigns the
        // PARAMETER `node` to a type-guard-narrowed `const parent`, where the narrowed type is a
        // deep (6-level) multi-base subtype.
        val d = diags(
            """
            interface Node { kind: number; parent: Node; }
            interface Expression extends Node { _expressionBrand: any; }
            interface UnaryExpression extends Expression { _unaryExpressionBrand: any; }
            interface UpdateExpression extends UnaryExpression { _updateExpressionBrand: any; }
            interface LeftHandSideExpression extends UpdateExpression { _leftHandSideExpressionBrand: any; }
            interface MemberExpression extends LeftHandSideExpression { _memberExpressionBrand: any; }
            interface PrimaryExpression extends MemberExpression { _primaryExpressionBrand: any; }
            interface JSDocContainer { _jsdocContainerBrand: any; }
            interface ParenthesizedExpression extends PrimaryExpression, JSDocContainer { _parenBrand: any; }
            declare function isParenthesizedExpression(n: Node): n is ParenthesizedExpression;

            export function walk(node: Expression): boolean {
                while (true) {
                    const parent: Node = node.parent;
                    if (isParenthesizedExpression(parent)) {
                        node = parent; // parent : ParenthesizedExpression <: Expression — OK
                        continue;
                    }
                    return false;
                }
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2741 || it.code == 2322 || it.code == 2739 },
            "assigning a deep-chain guard-narrowed `parent` to an `Expression` parameter inside a " +
                "while-loop must not fire a missing-property error; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `genuine widening assignment still fires - negative control`() {
        // No guard: assigning a bare `Node` to an `Expression` local IS an error.
        val d = diags(
            """
            interface Node { kind: number; }
            interface Expression extends Node { _expressionBrand: any; }

            export function bad(node: Expression, parent: Node): void {
                node = parent; // Node is missing _expressionBrand — genuine error
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2741 || it.code == 2322 },
            "assigning a bare `Node` to an `Expression` (no narrowing) must still fire the " +
                "missing-property error; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
