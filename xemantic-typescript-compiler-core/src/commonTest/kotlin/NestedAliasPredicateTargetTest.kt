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
 * Round 460: a type-guard whose predicate target is a FUNCTION-BODY-local type
 * alias (B83.5-unbound) resolves via the nested-alias map in
 * `narrowByCallPredicate` — tsc utilities.ts resolveNameHelper's nested
 * `isSelfReferenceLocation(node): node is SelfReferenceLocation` (the alias is
 * declared inside the enclosing function) never narrowed, so
 * `lastSelfReferenceLocation = location` FP'd TS2322 'Node' ⊄
 * 'Declaration | undefined' (utilities.ts:11856).
 */
class NestedAliasPredicateTargetTest {

    @Test
    fun `nested guard with a function-local alias target narrows for the assignment`() {
        diagnose("""
            enum SyntaxKind { A = 1, B = 2, C = 3 }
            interface Declaration { _declarationBrand: any; kind: SyntaxKind; }
            interface FunctionDecl extends Declaration { kind: SyntaxKind.A; f: string; }
            interface ClassDecl extends Declaration { kind: SyntaxKind.B; c: string; }
            interface PlainNode { kind: SyntaxKind; }
            declare function next(n: PlainNode): PlainNode | undefined;
            function resolveNameHelper(location: PlainNode | undefined) {
                let lastSelfReferenceLocation: Declaration | undefined;
                while (location) {
                    if (isSelfReferenceLocation(location)) {
                        lastSelfReferenceLocation = location;
                    }
                    location = next(location);
                }
                return lastSelfReferenceLocation;

                type SelfReferenceLocation = FunctionDecl | ClassDecl;
                function isSelfReferenceLocation(node: PlainNode): node is SelfReferenceLocation {
                    return true;
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an unguarded assignment of the wide node still fires`() {
        diagnose("""
            enum SyntaxKind { A = 1 }
            interface Declaration { _declarationBrand: any; kind: SyntaxKind; }
            interface PlainNode { kind: SyntaxKind; }
            function f(location: PlainNode) {
                let last: Declaration | undefined;
                last = location;
                return last;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
