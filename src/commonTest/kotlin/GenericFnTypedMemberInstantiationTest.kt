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
 * Round 465 (M3.1): a generic interface member whose type CONTAINS a function
 * type mentioning the outer type parameter must substitute it when the
 * interface is instantiated — `instantiateType` deliberately no-ops
 * fn-shaped Type.Objects (the CLAUDE.md gotcha), so a method's fn-typed
 * RETURN (`select(index): ((node: T) => T) | undefined`) and a fn-typed
 * property's NESTED fn return kept the raw `T` through the relation and a
 * conforming object literal failed. The tsc-source shape is emitter.ts's
 * `OrdinalParentheizerRuleSelector<TypeNode>` initializer.
 */
class GenericFnTypedMemberInstantiationTest {

    private val selectorShape = """
        interface Node { pos: number }
        interface TypeNode extends Node { t: string }
        interface ParenthesizerRules {
            parenthesizeLeadingTypeArgument(typeNode: TypeNode): TypeNode;
        }
        declare const parenthesizer: ParenthesizerRules;
        declare function mk(t: TypeNode): TypeNode;
    """.trimIndent()

    @Test
    fun `a method's fn-typed union return substitutes the outer type param`() {
        diagnose(
            selectorShape + """

            interface OrdinalParentheizerRuleSelector<T extends Node> {
                select(index: number): ((node: T) => T) | undefined;
            }
            var s: OrdinalParentheizerRuleSelector<TypeNode> = {
                select: index => index === 0 ? parenthesizer.parenthesizeLeadingTypeArgument : undefined,
            };
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a fn-typed property's nested fn return substitutes the outer type param`() {
        diagnose(
            selectorShape + """

            interface Sel<T extends Node> { get: (index: number) => ((node: T) => T) | undefined; }
            var s: Sel<TypeNode> = { get: index => index === 0 ? mk : undefined };
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a genuinely mismatched fn return still fails`() {
        diagnose(
            selectorShape + """

            interface Other extends Node { o: boolean }
            declare function mkOther(o: Other): Other;
            interface Sel<T extends Node> { get(index: number): (node: T) => T; }
            var s: Sel<TypeNode> = { get: index => mkOther };
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
