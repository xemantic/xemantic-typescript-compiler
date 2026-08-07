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
 * Round 461: an element access with a LITERAL index is a narrowable reference —
 * tsc's `isMatchingReference` accepts string/numeric-literal argumentExpressions,
 * so `decls[0].initializer` guarded by `!!decls[0].initializer` narrows the
 * `Expression | undefined` member to `Expression`. [getReferencePath] serializes
 * the segment as `recv[0]`; a non-literal index stays un-narrowable (null path).
 *
 * tsc-source shape: transformers/es2015.ts:2730
 * `!!node.declarationList.declarations[0].initializer &&
 *  getInternalEmitFlags(node.declarationList.declarations[0].initializer)`.
 */
class ElementAccessReferencePathNarrowingTest {

    private val prelude = """
        interface Expr { kind: number }
        interface Decl { initializer?: Expr }
        interface DeclList { declarations: Decl[] }
        interface VarStmt { declarationList: DeclList }
        declare function getFlags(node: Expr): number;
    """.trimIndent()

    @Test
    fun `truthiness and-guard narrows a literal-index element-access path in a later conjunct`() {
        diagnose(prelude + """
            function isWrapper(node: VarStmt) {
                return node.declarationList.declarations.length === 1
                    && !!node.declarationList.declarations[0].initializer
                    && !!(getFlags(node.declarationList.declarations[0].initializer) & 4);
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `if-guard narrows a literal-index element-access path in the then branch`() {
        diagnose(prelude + """
            function f(node: VarStmt) {
                if (node.declarationList.declarations[0].initializer) {
                    getFlags(node.declarationList.declarations[0].initializer);
                }
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a VARIABLE index does not narrow`() {
        diagnose(prelude + """
            function f(node: VarStmt, i: number) {
                if (node.declarationList.declarations[i].initializer) {
                    getFlags(node.declarationList.declarations[i].initializer);
                }
            }
        """) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a guard on index 0 does not narrow index 1`() {
        diagnose(prelude + """
            function f(node: VarStmt) {
                if (node.declarationList.declarations[0].initializer) {
                    getFlags(node.declarationList.declarations[1].initializer);
                }
            }
        """) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an unguarded literal-index access still fires`() {
        diagnose(prelude + """
            function f(node: VarStmt) {
                getFlags(node.declarationList.declarations[0].initializer);
            }
        """) should {
            have(any { it.code == 2345 })
        }
    }
}
