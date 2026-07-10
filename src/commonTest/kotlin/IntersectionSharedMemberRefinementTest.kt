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
 * Round 465 (M3.1): a member declared by MULTIPLE intersection constituents
 * has the INTERSECTION of the declared types in tsc — so the round-351
 * merged-contradiction guard (`intersectionMergedContradictsTarget`) must
 * fail a shared member only when EVERY constituent's declaration fails the
 * target: if ANY declaration relates, the intersected member type (a subtype
 * of each declaration) relates too. The old first-decl-wins merge let the
 * interface constituent's WIDE member shadow the TypeLiteral's refinement,
 * FP'ing the tsc cast idiom against its own annotation
 * (`node as AssignmentExpression<EqualsToken> & { readonly left:
 * GeneratedIdentifier; }` — factory/utilities.ts:1688, parser.ts:9581).
 */
class IntersectionSharedMemberRefinementTest {

    private val tokenShape = """
        enum SyntaxKind { Unknown, EqualsToken, PlusEqualsToken }
        type PunctuationSyntaxKind = SyntaxKind.EqualsToken | SyntaxKind.PlusEqualsToken;
        interface Node { readonly kind: SyntaxKind; pos: number }
        interface Expression extends Node { e: string }
        interface LeftHandSideExpression extends Expression { lhs: string }
        interface Identifier extends LeftHandSideExpression { text: string }
        interface GeneratedIdentifier extends Identifier { gen: number }
        interface Token<TKind extends SyntaxKind> extends Node { readonly kind: TKind }
        interface PunctuationToken<TKind extends PunctuationSyntaxKind> extends Token<TKind> {}
        type EqualsToken = PunctuationToken<SyntaxKind.EqualsToken>;
        type AssignmentOperator = SyntaxKind.EqualsToken | SyntaxKind.PlusEqualsToken;
        type AssignmentOperatorToken = Token<AssignmentOperator>;
        interface BinaryExpression extends Expression { readonly left: Expression; readonly operatorToken: Token<SyntaxKind>; readonly right: Expression }
        interface AssignmentExpression<TOperator extends AssignmentOperatorToken> extends BinaryExpression {
            readonly left: LeftHandSideExpression;
            readonly operatorToken: TOperator;
        }
        interface ComputedPropertyName extends Node { readonly expression: Expression }
        declare function skipOuterExpressions(node: Expression): Expression;
        declare function isAssignmentExpression(node: Expression, excludeCompoundAssignment: true): node is AssignmentExpression<EqualsToken>;
        declare function isGeneratedIdentifier(node: Expression): node is GeneratedIdentifier;
    """.trimIndent()

    @Test
    fun `a cast intersection refining a shared member relates to its own annotation`() {
        diagnose(
            tokenShape + """

            function findCacheAssignment(name: ComputedPropertyName): AssignmentExpression<EqualsToken> & { readonly left: GeneratedIdentifier; } | undefined {
                let node = name.expression;
                while (true) {
                    node = skipOuterExpressions(node);
                    if (isAssignmentExpression(node, /*excludeCompoundAssignment*/ true) && isGeneratedIdentifier(node.left)) {
                        return node as AssignmentExpression<EqualsToken> & { readonly left: GeneratedIdentifier; };
                    }
                    break;
                }
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a shared member failing on EVERY constituent still contradicts`() {
        diagnose(
            """
            interface A { shared: string; a: number }
            interface B { b: number }
            declare const src: A & { shared: string };
            const dst: { shared: number; a?: number } = src;
            """
        ) should {
            // both declarations of `shared` are string — neither relates to number,
            // so the merged-contradiction verdict must survive the any-relates rule.
            have(any { it.code == 2322 })
        }
    }
}
