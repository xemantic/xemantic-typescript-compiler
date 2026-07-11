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
 * Round 472: contextual-type distribution into NESTED object literals + the
 * enum-discriminant union-member selection (`selectUnionMemberByObjLitDiscriminant`,
 * the round-411 canonical `symId#member` key space wired into objlit contexts).
 *
 * tsc signatureHelp.ts:379 returns `{ …, invocation: { kind: InvocationKind.Call,
 * node: parent }, … }` against `ArgumentListInfo | undefined` where `parent` was
 * guard-narrowed Node → JsxOpeningLikeElement and `invocation`'s target is a
 * kind-discriminated 3-member union: the nested objlit needs (a) the property's
 * contextual type propagated in, (b) the union member selected by the enum-member
 * discriminant (enum-member types resolve to `any`, so only the DECLARED annotation
 * can decide), and (c) the guard-narrowed Identifier value accepted via the
 * monotone ctxAcceptsNarrow rule. findAllReferences.ts:1000 is the ARRAY-literal
 * sibling (`return [{ definition: { type: DefinitionKind.X, file: node }, … }]` vs
 * `readonly SymbolAndEntries[] | undefined`) needing element-type distribution.
 */
class NestedObjLitEnumDiscriminantContextTest {

    private val prelude = """
        interface Node { kindNum: number; parent: Node; }
        interface CallLikeExpression extends Node { _callBrand: any; }
        interface JsxOpeningLikeElement extends CallLikeExpression { attributes: number; }
        declare function isJsxOpeningLikeElement(n: Node): n is JsxOpeningLikeElement;

        const enum InvocationKind { Call, TypeArgs, Contextual }
        interface CallInvocation {
            readonly kind: InvocationKind.Call;
            readonly node: CallLikeExpression;
        }
        interface TypeArgsInvocation {
            readonly kind: InvocationKind.TypeArgs;
            readonly called: string;
        }
        interface ContextualInvocation {
            readonly kind: InvocationKind.Contextual;
            readonly signature: string;
            readonly node: Node;
        }
        type Invocation = CallInvocation | TypeArgsInvocation | ContextualInvocation;
        interface ArgumentListInfo {
            readonly isTypeParameterList: boolean;
            readonly invocation: Invocation;
            readonly argumentIndex: number;
        }
    """.trimIndent()

    @Test
    fun `a guard-narrowed value in a nested enum-discriminated objlit relates`() {
        diagnose(
            prelude + "\n" + """
            function getInfo(parent: Node): ArgumentListInfo | undefined {
                if (isJsxOpeningLikeElement(parent)) {
                    return {
                        isTypeParameterList: false,
                        invocation: { kind: InvocationKind.Call, node: parent },
                        argumentIndex: 0,
                    };
                }
                return undefined;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed value in the nested objlit still fails`() {
        diagnose(
            prelude + "\n" + """
            function getInfoBad(parent: Node): ArgumentListInfo | undefined {
                return {
                    isTypeParameterList: false,
                    invocation: { kind: InvocationKind.Call, node: parent },
                    argumentIndex: 0,
                };
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `an array-literal return distributes the element type into nested objlits`() {
        diagnose(
            prelude + "\n" + """
            interface SourceFile extends Node { fileName: string; }
            interface Entry { textSpan: number; }
            declare function isSourceFile(n: Node): n is SourceFile;
            declare function getRefs(): readonly Entry[] | undefined;
            declare const emptyArray: never[];

            const enum DefinitionKind { Symbol, Label, TripleSlashReference }
            type Definition =
                | { readonly type: DefinitionKind.Symbol; readonly symbol: string; }
                | { readonly type: DefinitionKind.Label; readonly node: string; }
                | { readonly type: DefinitionKind.TripleSlashReference; readonly file: SourceFile; };
            interface SymbolAndEntries {
                readonly definition: Definition;
                readonly references: readonly Entry[];
            }

            function get(node: Node): readonly SymbolAndEntries[] | undefined {
                if (isSourceFile(node)) {
                    return [{
                        definition: { type: DefinitionKind.TripleSlashReference, file: node },
                        references: getRefs() || emptyArray,
                    }];
                }
                return undefined;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a wrong discriminant member shape still fails`() {
        // The objlit's discriminant selects TypeArgsInvocation, whose `called` is a
        // string — a number value must keep failing.
        diagnose(
            prelude + "\n" + """
            function getBad2(): ArgumentListInfo | undefined {
                return {
                    isTypeParameterList: false,
                    invocation: { kind: InvocationKind.TypeArgs, called: 42 },
                    argumentIndex: 0,
                };
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
