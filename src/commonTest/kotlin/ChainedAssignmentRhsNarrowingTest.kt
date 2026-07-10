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
 * Round 460: a CHAINED assignment RHS (`location = node = value`) evaluates to
 * the ULTIMATE RHS value, so the assignment-path type-guard narrowing must
 * narrow that reference — tsc transformers/destructuring.ts:113's
 * `location = node = value` inside `if (isDestructuringAssignment(value))`
 * FP'd TS2322 'Expression | undefined' ⊄ 'TextRange' because the outer RHS is
 * a BinaryExpression the Identifier-only gate skipped.
 */
class ChainedAssignmentRhsNarrowingTest {

    private val prelude = """
        interface TextRange { pos: number; end: number; }
        interface Expr extends TextRange { _e: any; }
        interface DestructuringAssignment extends Expr { right: Expr; left: Expr; }
        interface VarDecl extends TextRange { _v: any; }
        declare function isDestructuringAssignment(x: unknown): x is DestructuringAssignment;
        declare function isEmpty(x: Expr): boolean;

    """.trimIndent()

    @Test
    fun `chained assignment with guard-narrowed ultimate RHS relates - no TS2322`() {
        diagnose(prelude + """
            function flatten(node: VarDecl | DestructuringAssignment) {
                let location: TextRange = node;
                let value: Expr | undefined;
                if (isDestructuringAssignment(node)) {
                    value = node.right;
                    while (isEmpty(node.left)) {
                        if (isDestructuringAssignment(value)) {
                            location = node = value;
                            value = node.right;
                        } else {
                            break;
                        }
                    }
                }
                return location;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an unguarded chained assignment still fires`() {
        diagnose(prelude + """
            function f(value: Expr | undefined) {
                let location: TextRange;
                let node: Expr;
                location = node = value;
                return location;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
