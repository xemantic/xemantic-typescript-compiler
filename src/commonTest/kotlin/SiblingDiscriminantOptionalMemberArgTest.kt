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
 * Round 470: the optional-member-arg TS2345 emitter
 * (tryEmitOptionalMemberArgVsRequiredNamedTs2345) consults the SIBLING-DISCRIMINANT
 * narrowed receiver — a `switch (x.kind)` case / `if (x.kind === …)` guard narrows
 * the receiver to a member that declares the property REQUIRED, so the access
 * cannot be undefined (tsc convertParamsToDestructuredObject's
 * `functionDeclaration.name` after `case SyntaxKind.MethodDeclaration:` and
 * fixUnreferenceableDecoratorMetadata's `importDeclaration.name` after the
 * kind-equality guard). The unguarded access keeps firing.
 */
class SiblingDiscriminantOptionalMemberArgTest {

    private val prelude = """
        enum SyntaxKind { FunctionDeclaration, MethodDeclaration, ImportEquals, ImportClause }
        interface Node2 { kind: SyntaxKind }
        interface Identifier extends Node2 { text: string }
        interface FunctionDeclaration extends Node2 { kind: SyntaxKind.FunctionDeclaration; name?: Identifier }
        interface MethodDeclaration extends Node2 { kind: SyntaxKind.MethodDeclaration; name: Identifier }
        type ValidFunctionDeclaration = FunctionDeclaration | MethodDeclaration;
        declare function getSymbolForContextualType(node: Node2): string;
    """.trimIndent()

    @Test
    fun `a switch-case narrowed receiver with a required member suppresses the optional-arg TS2345`() {
        diagnose(
            prelude + """

            function f(functionDeclaration: ValidFunctionDeclaration): string {
                switch (functionDeclaration.kind) {
                    case SyntaxKind.MethodDeclaration:
                        return getSymbolForContextualType(functionDeclaration.name);
                    default:
                        return "";
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `an if-equality narrowed receiver with a required member suppresses too`() {
        diagnose(
            prelude + """

            interface ImportEqualsDeclaration extends Node2 { kind: SyntaxKind.ImportEquals; name: Identifier }
            interface ImportClause extends Node2 { kind: SyntaxKind.ImportClause; name?: Identifier }
            declare function insertModifierBefore(before: Node2): void;
            function g(importDeclaration: ImportClause | ImportEqualsDeclaration) {
                if (importDeclaration.kind === SyntaxKind.ImportEquals) {
                    insertModifierBefore(importDeclaration.name);
                    return;
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the unguarded optional-member arg still fires`() {
        diagnose(
            prelude + """

            function h(functionDeclaration: ValidFunctionDeclaration) {
                return getSymbolForContextualType(functionDeclaration.name);
            }
            """
        ) should {
            have(any { it.code == 2345 && "Identifier | undefined" in it.message })
        }
    }

    @Test
    fun `negative control - a case whose narrowed member stays optional still fires`() {
        diagnose(
            prelude + """

            function f(functionDeclaration: ValidFunctionDeclaration): string {
                switch (functionDeclaration.kind) {
                    case SyntaxKind.FunctionDeclaration:
                        return getSymbolForContextualType(functionDeclaration.name);
                    default:
                        return "";
                }
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
