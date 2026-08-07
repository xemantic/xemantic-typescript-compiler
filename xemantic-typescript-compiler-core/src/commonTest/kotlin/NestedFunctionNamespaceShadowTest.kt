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
 * Round 461: [shadowNestedFunctionNames] also treats a NAMESPACE-member collision as
 * a shadow — `getTypeOfIdentifier` resolves through `lookupInInferenceNamespace`
 * BEFORE globals, so a function nested in a namespace-member function that shadows a
 * same-named (differently-shaped) namespace export previously resolved to the
 * namespace one.
 *
 * tsc-source shape: parser.ts:1865 `syntaxCursor = { currentNode }` inside
 * `namespace Parser`'s reparseTopLevelAwait, where the shorthand references the
 * body-nested `function currentNode(position: number): Node` (B83.5-unbound), NOT
 * the namespace-level `function currentNode(parsingContext, pos?): Node | undefined`.
 */
class NestedFunctionNamespaceShadowTest {

    @Test
    fun `object-literal shorthand of a body-nested function shadowing a namespace export draws no TS2322`() {
        diagnose("""
            interface Node2 { kind: number }
            enum Ctx { A, B }
            interface SyntaxCursor { currentNode(position: number): Node2; }
            namespace P {
                var cursor: SyntaxCursor | undefined;
                function reparse() {
                    cursor = { currentNode };
                    function currentNode(position: number): Node2 {
                        return null as any;
                    }
                }
                function currentNode(parsingContext: Ctx, pos?: number): Node2 | undefined {
                    return undefined;
                }
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - without the nested shadow the namespace function's shape mismatch fires`() {
        diagnose("""
            interface Node2 { kind: number }
            enum Ctx { A, B }
            interface SyntaxCursor { currentNode(position: number): Node2; }
            namespace P {
                var cursor: SyntaxCursor | undefined;
                function reparse() {
                    cursor = { currentNode };
                }
                function currentNode(parsingContext: Ctx, pos?: number): Node2 | undefined {
                    return undefined;
                }
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `file-level collision variant - nested function shadowing a file-level function draws no TS2322`() {
        diagnose("""
            interface Node2 { kind: number }
            interface SyntaxCursor { currentNode(position: number): Node2; }
            var cursor: SyntaxCursor | undefined;
            function reparse() {
                cursor = { currentNode };
                function currentNode(position: number): Node2 {
                    return null as any;
                }
            }
            function currentNode(x: string): string { return x; }
        """) should {
            have(none { it.code == 2322 })
        }
    }
}
