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
 * Round 470: an exhaustive `switch (<param>.<member>.kind)` suppresses TS2366 —
 * the CFA pass has no param scope in getTypeOfExpression, so a property-access
 * switch RECEIVER rooted at a parameter resolves through the param's ANNOTATION
 * and the member's declared union ([paramMemberChainType]). The tsc shape is
 * convertParamsToDestructuredObject's getClassNames: `ValidConstructor { parent:
 * ClassDeclaration | (ClassExpression & {…}) }` with both kinds covered.
 * Pins the exhaustive suppression, the intersection member, and the negative
 * control (a partial switch still fires).
 */
class ParamMemberChainExhaustiveSwitchTest {

    private val prelude = """
        enum SyntaxKind { ClassDeclaration, ClassExpression, Other }
        interface Node2 { kind: SyntaxKind }
        interface ClassDeclaration extends Node2 { kind: SyntaxKind.ClassDeclaration; name?: string }
        interface ClassExpression extends Node2 { kind: SyntaxKind.ClassExpression; name?: string }
        interface VariableDeclaration extends Node2 { kind: SyntaxKind.Other; name: string }
        interface ConstructorDeclaration extends Node2 { parent: Node2 }
        interface ValidConstructor extends ConstructorDeclaration {
            parent: ClassDeclaration | (ClassExpression & { parent: VariableDeclaration });
        }
    """.trimIndent()

    @Test
    fun `an exhaustive switch over a param-member discriminant draws no TS2366`() {
        diagnose(
            prelude + """

            function getClassNames(ctor: ValidConstructor): string[] {
                switch (ctor.parent.kind) {
                    case SyntaxKind.ClassDeclaration:
                        if (ctor.parent.name) return [ctor.parent.name];
                        return ["default"];
                    case SyntaxKind.ClassExpression:
                        return ["anon"];
                }
            }
            """
        ) should {
            have(none { it.code == 2366 })
        }
    }

    @Test
    fun `negative control - a partial switch over the same shape still fires TS2366`() {
        diagnose(
            prelude + """

            function partial(ctor: ValidConstructor): string[] {
                switch (ctor.parent.kind) {
                    case SyntaxKind.ClassDeclaration:
                        return ["a"];
                }
            }
            """
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `negative control - an unannotated param member keeps TS2366`() {
        diagnose(
            """
            enum K { A, B }
            function f(x): string[] {
                switch (x.parent.kind) {
                    case K.A: return ["a"];
                    case K.B: return ["b"];
                }
            }
            """,
            directives = "// @strict: true\n// @noImplicitAny: false",
        ) should {
            have(any { it.code == 2366 })
        }
    }
}
