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
 * Round 463: a plain `=` assignment with a bare-IDENTIFIER RHS filters the
 * antecedent union by the RHS's resolved type in the narrowing walk
 * (`narrowByAssignmentRhs`) — tsc's esDecorators.ts:1485: `result = node` (node:
 * ClassStaticBlockDeclaration) narrows `VisitResult<T> = T | readonly Node[]` to
 * T, so the later `result = [staticBlock, result]` array element reads the
 * narrowed member instead of FP-failing `(T | readonly Node[])[]` vs
 * `VisitResult<T>`. Mirrors the literal-RHS branch: `narrowUnionByRhsAssignment`
 * only ever REFINES a union (unrelatable RHS / non-union antecedents unchanged) —
 * suppression-only.
 */
class IdentifierRhsAssignmentNarrowingTest {

    private val prelude = """
        interface Node { kind: number }
        interface ClassStaticBlockDeclaration extends Node { body: string }
        interface Statement extends Node { s: string }
        type VisitResult<T extends Node | undefined> = T | readonly Node[];
        declare function visitEachChild(node: ClassStaticBlockDeclaration): ClassStaticBlockDeclaration;
        declare function makeBlock(statements: Statement[]): ClassStaticBlockDeclaration;
        declare const pending: boolean;
    """.trimIndent()

    @Test
    fun `an identifier-RHS assignment narrows the union so a later array element reads the member`() {
        diagnose(prelude + """
            function visitClassStaticBlockDeclaration(node: ClassStaticBlockDeclaration): VisitResult<ClassStaticBlockDeclaration> {
                let result: VisitResult<ClassStaticBlockDeclaration>;
                node = visitEachChild(node);
                result = node;
                if (pending) {
                    const statements: Statement[] = [];
                    const staticBlock = makeBlock(statements);
                    result = [staticBlock, result];
                }
                return result;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - without the narrowing assignment the array element keeps the union and fails`() {
        diagnose(prelude + """
            function bad(result: VisitResult<ClassStaticBlockDeclaration>): ClassStaticBlockDeclaration[] {
                const statements: Statement[] = [];
                const staticBlock = makeBlock(statements);
                return [staticBlock, result];
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
