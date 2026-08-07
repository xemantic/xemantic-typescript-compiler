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
 * Round 465: a property read on `Union & Interface` reduces the UNION
 * constituent's members by `.kind` discriminant disjointness against the
 * sibling interface before resolving the property — tsc reduces
 * `(A | B) & C` members whose literal `kind` contradicts C's, so the
 * surviving members' (narrower) property type wins over the sibling's
 * wide declaration. The tsc-source shape is namedEvaluation.ts:
 * `node: NamedEvaluation & BinaryExpression` where `node.left` must
 * resolve to `Identifier` (from the surviving `AssignmentExpression &
 * { left: Identifier }` union members), not BinaryExpression's
 * `left: Expression`.
 */
class IntersectionUnionKindReductionTest {

    private val astShape = """
        enum SyntaxKind { PropertyAssignment, BinaryExpression, Identifier }
        interface Node { readonly kind: SyntaxKind; }
        interface Expression extends Node {}
        interface Identifier extends Expression { readonly kind: SyntaxKind.Identifier; text: string; }
        interface BinaryExpression extends Expression { readonly kind: SyntaxKind.BinaryExpression; left: Expression; right: Expression; }
        interface PropertyAssignment extends Node { readonly kind: SyntaxKind.PropertyAssignment; name: Identifier; }
        type NamedEvaluation =
            | PropertyAssignment & { readonly name: Identifier; }
            | BinaryExpression & { readonly left: Identifier; };
        declare function take(id: Identifier): void;
    """.trimIndent()

    @Test
    fun `kind-reduced union member's narrowing property type wins over the sibling interface's wide one`() {
        diagnose(
            astShape + """

            function f(node: NamedEvaluation & BinaryExpression) {
                take(node.left);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a genuinely wide property on the surviving member still fires`() {
        diagnose(
            astShape + """

            function g(node: (PropertyAssignment & { readonly name: Identifier; } | BinaryExpression) & BinaryExpression) {
                take(node.left);
            }
            """
        ) should {
            // the surviving BinaryExpression member's `left` is the wide Expression —
            // the reduction must NOT manufacture a narrowing that isn't declared.
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - kind-less union members are not reduced and prior behavior stands`() {
        diagnose(
            """
            interface A { a: string; }
            interface B { b: number; left: string; }
            interface C { left: string | number; }
            declare function takeString(s: string): void;
            function h(node: (A | B) & C) {
                takeString(node.left);
            }
            """
        ) should {
            // no readable kind on either side → no reduction → `left` resolves through
            // C's `string | number` → the arg check still fires.
            have(any { it.code == 2345 })
        }
    }
}
