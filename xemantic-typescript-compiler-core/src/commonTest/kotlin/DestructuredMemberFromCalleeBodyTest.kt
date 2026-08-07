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
 * Round 465 (M3.4): a destructuring ASSIGNMENT whose RHS calls an
 * UN-ANNOTATED (nested, B83.5-unbound) function proves a destructured
 * member non-nullish by scanning the callee's body — every return must be
 * an object literal carrying the member with a non-nullish value, where
 * identifier values resolve through the callee's OWN params and body-local
 * const decls (never the caller's same-named nullable binding). The
 * tsc-source shape is esDecorators.ts `({ referencedName, name } =
 * visitReferencedPropertyName(member.name))` followed by
 * `propertyName = { computed: true, name: referencedName }` against the
 * ESDecorateName discriminated union.
 */
class DestructuredMemberFromCalleeBodyTest {

    private val esDecorateShape = """
        interface Expression { e: string }
        interface StringLiteral extends Expression { s: string }
        interface Identifier extends Expression { i: string }
        type ESDecorateName = { computed: true; name: Expression; } | { computed: false; name: Identifier; };
        interface Factory {
            createStringLiteralFromNode(node: Expression): StringLiteral;
            getGeneratedNameForNode(node: Expression): Identifier;
        }
        interface Context { factory: Factory; other: number }
        declare function hoist(e: Expression): void;
    """.trimIndent()

    @Test
    fun `destructured member proves non-nullish from the un-annotated callee's object-literal returns`() {
        diagnose(
            esDecorateShape + """

            function transform(context: Context) {
                const { factory } = context;

                function partial(member: Expression) {
                    let referencedName: Expression | undefined;
                    let name: Expression | undefined;
                    let propertyName: ESDecorateName;
                    ({ referencedName, name } = visitReferencedPropertyName(member));
                    propertyName = { computed: true, name: referencedName };
                    return propertyName;
                }

                function visitReferencedPropertyName(node: Expression) {
                    if (node.e === "lit") {
                        const referencedName = factory.createStringLiteralFromNode(node);
                        const name = node;
                        return { referencedName, name };
                    }
                    const referencedName = factory.getGeneratedNameForNode(node);
                    hoist(referencedName);
                    const name = node;
                    return { referencedName, name };
                }
                return partial;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a return whose member value can be undefined keeps the error`() {
        diagnose(
            esDecorateShape + """

            function transform(context: Context) {
                function partial(member: Expression, maybe: Expression | undefined) {
                    let referencedName: Expression | undefined;
                    let propertyName: ESDecorateName;
                    ({ referencedName } = visitName(maybe));
                    propertyName = { computed: true, name: referencedName };
                    return propertyName;
                }

                function visitName(node: Expression | undefined) {
                    const referencedName = node;
                    return { referencedName };
                }
                return partial;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a return missing the member keeps the error`() {
        diagnose(
            esDecorateShape + """

            function transform(context: Context) {
                const { factory } = context;
                function partial(member: Expression) {
                    let referencedName: Expression | undefined;
                    let propertyName: ESDecorateName;
                    ({ referencedName } = visitName(member));
                    propertyName = { computed: true, name: referencedName };
                    return propertyName;
                }

                function visitName(node: Expression) {
                    if (node.e === "lit") {
                        const referencedName = factory.createStringLiteralFromNode(node);
                        return { referencedName };
                    }
                    return { other: node };
                }
                return partial;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
