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
 * Pins the round-464 invariant: generic call-return inference binds a type
 * parameter from the arg's flow-NARROWED type, not its declared union — tsc's
 * typeSerializer.ts `serializeEntityNameAsExpression` clones a switch-narrowed
 * `node` (`const name = cloneNode(node)` under `case SyntaxKind.Identifier:`)
 * and returns it against a narrower union; inference from the DECLARED
 * `EntityName` made the return FP TS2322.
 *
 * The refinement is gated: union-declared bare Identifier/PropertyAccess args
 * only, and the narrowed type must strictly relate to the declared type —
 * so inference only ever gets MORE precise.
 */
class GenericInferenceNarrowedArgTest {

    private val prelude = """
        interface Nd { kind: string; parent: Nd; }
        interface Ident extends Nd { kind: "ident"; text: string; }
        interface Qual extends Nd { kind: "qual"; right: Ident; }
        type EntityName = Ident | Qual;
        type Serialized = Ident | (Qual & { brand: string });
        declare function cloneNode<T extends Nd>(node: T): T;
        declare function serializeQual(node: Qual): Serialized;
    """.trimIndent() + "\n"

    @Test
    fun `a switch-narrowed union arg binds T to the narrowed member in return inference`() {
        diagnose(prelude + """
            function serialize(node: EntityName): Serialized {
                switch (node.kind) {
                    case "ident":
                        const name = cloneNode(node);
                        return name;
                    default:
                        return serializeQual(node as Qual);
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an if-guarded union arg binds T to the narrowed member`() {
        diagnose(prelude + """
            declare function isIdent(n: Nd): n is Ident;
            function serialize2(node: EntityName): Ident {
                if (isIdent(node)) {
                    const name = cloneNode(node);
                    return name;
                }
                return node.right;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a narrow to the WRONG union member still errors against the return target`() {
        diagnose(prelude + """
            declare function isQual(n: Nd): n is Qual;
            function serialize3(node: EntityName): Ident {
                if (isQual(node)) {
                    const name = cloneNode(node);
                    return name;
                }
                return node;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
