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
 * Round 465 (M3.4): `asserts node is Exclude<T, U>` filtering must let
 * `.kind`-key DISJOINTNESS beat the assignability relation (the round-423
 * narrowByCallPredicate lesson) — enum-member kinds resolve `any`, so the
 * lenient relation relates every brand-intersection union member
 * (`PropertyAccessExpression & { _optionalChainBrand: any }`) to the
 * excluded `NonNullChain` and the filter kept NOTHING. The tsc-source shape
 * is es2020.ts `flattenChain`'s `Debug.assertNotNode(chain, isNonNullChain)`
 * followed by `while (!chain.questionDotToken && …)`.
 */
class AssertNotNodeBrandIntersectionTest {

    private val chainShape = """
        enum SyntaxKind { PropertyAccessExpression, ElementAccessExpression, NonNullExpression }
        interface Node { readonly kind: SyntaxKind; pos: number }
        interface Expression extends Node { e: string }
        interface QToken { q: boolean }
        interface PropertyAccessExpression extends Expression { readonly kind: SyntaxKind.PropertyAccessExpression; readonly expression: Expression; readonly questionDotToken?: QToken; }
        interface ElementAccessExpression extends Expression { readonly kind: SyntaxKind.ElementAccessExpression; readonly expression: Expression; readonly questionDotToken?: QToken; }
        interface NonNullExpression extends Expression { readonly kind: SyntaxKind.NonNullExpression; readonly expression: Expression; }
        type PropertyAccessChain = PropertyAccessExpression & { _optionalChainBrand: any };
        type ElementAccessChain = ElementAccessExpression & { _optionalChainBrand: any };
        type NonNullChain = NonNullExpression & { _optionalChainBrand: any };
        type OptionalChain = PropertyAccessChain | ElementAccessChain | NonNullChain;
        declare function isNonNullChain(node: Node): node is NonNullChain;
        declare function assertNotNode<T extends Node, U extends T>(node: T | undefined, test: (node: Node) => node is U): asserts node is Exclude<T, U>;
    """.trimIndent()

    @Test
    fun `Exclude filtering keeps kind-disjoint brand-intersection members despite the lenient relation`() {
        diagnose(
            chainShape + """

            function flattenChain(chain: OptionalChain) {
                assertNotNode(chain, isNonNullChain);
                const links: OptionalChain[] = [chain];
                while (!chain.questionDotToken) {
                    break;
                }
                return links;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - without the assert the excluded member still lacks the property`() {
        diagnose(
            chainShape + """

            function flattenChain(chain: OptionalChain) {
                while (!chain.questionDotToken) {
                    break;
                }
                return chain;
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }
}
