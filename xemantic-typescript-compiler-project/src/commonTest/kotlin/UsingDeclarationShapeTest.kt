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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.25) round 948 — **the SHAPE a `using` head produces, and the shape it must not.**
 *
 * The core module can only see a `using` declaration through the diagnostics it does or
 * does not emit, which cannot separate "parsed as a declaration" from "parsed as two
 * expression statements that happen to be silent". This module reads the AST directly
 * (round 910's subject), so the contextual-keyword bound becomes a statement about the
 * TREE rather than about an error count: the same two identifiers on one line are a
 * `VariableStatement`, and split across two lines are not.
 */
class UsingDeclarationShapeTest {

    private val config =
        """{ "compilerOptions": { "target": "esnext", "module": "esnext", "strict": false },""" +
            """ "include": ["src/**/*.ts"] }"""

    private fun projectOf(source: String): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                "/proj/src/a.ts" to source,
            ),
        ),
    )

    private fun chainAt(source: String, offset: Int): List<String> {
        val info = projectOf(source).nodeInfoAt("/proj/src/a.ts", offset)
        assert(info != null)
        return listOf(info.kind) + info.ancestorKinds
    }

    @Test
    fun `a using head produces a variable declaration list`() {
        val src = "declare const d: any;\nusing r1 = d;\n"
        val chain = chainAt(src, src.indexOf("r1") + 1)
        assert("VariableDeclaration" in chain)
        assert("VariableDeclarationList" in chain)
        assert("VariableStatement" in chain)
    }

    @Test
    fun `an await using head produces a variable declaration list`() {
        val src = "declare const d: any;\nasync function f() {\n  await using r2 = d;\n}\n"
        val chain = chainAt(src, src.indexOf("r2") + 1)
        assert("VariableDeclaration" in chain)
        assert("VariableDeclarationList" in chain)
        assert("VariableStatement" in chain)
        // …and NOT an await EXPRESSION over the identifier `using`.
        assert("AwaitExpression" !in chain)
    }

    @Test
    fun `the bound - using on its own line is an expression statement, not a declaration`() {
        // The lookahead's SAME-LINE test, read off the tree. `x` here is a USE, so it must
        // not sit under a VariableDeclaration.
        val src = "declare const using: number;\ndeclare const zz: number;\nusing\nzz\n"
        val chain = chainAt(src, src.lastIndexOf("zz") + 1)
        assert("ExpressionStatement" in chain)
        assert("VariableDeclaration" !in chain)
    }

    @Test
    fun `the bound - using followed by a bracket is an element access, not a binding pattern`() {
        // tsc refuses `[` as a destructuring start precisely so this stays an element
        // access; taking it would make `using[i]` an array binding pattern.
        val src = "declare const using: number[];\ndeclare const i: number;\nusing[i];\n"
        val chain = chainAt(src, src.lastIndexOf("using") + 1)
        assert("ElementAccessExpression" in chain)
        assert("VariableDeclaration" !in chain)
    }

    @Test
    fun `regression guard - a const head still produces a variable declaration list`() {
        val src = "const q = 1;\n"
        val chain = chainAt(src, src.indexOf("q") + 1)
        assert("VariableDeclarationList" in chain)
    }
}
