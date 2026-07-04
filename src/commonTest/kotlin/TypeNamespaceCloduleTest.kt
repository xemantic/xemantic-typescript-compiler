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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `type X = …` + `namespace X { … }` CLODULE gives `X` BOTH a type meaning (the alias) and a
 * value/namespace meaning (the instantiated namespace), so using it as a type must NOT fire
 * TS2709 "Cannot use namespace 'X' as a type" and using it as a value (`X.member`) must NOT
 * fire TS2693 "'X' only refers to a type". Our binder merges `type X` + `namespace X` last-wins
 * (the namespace overwrites the type-alias symbol, dropping the TypeAlias flag), and the
 * AST-based value-usage walker never added an instantiated namespace to the value set — both FP'd
 * on tsc's factory/utilities.ts `type BinaryExpressionState = <fn type>` + `namespace
 * BinaryExpressionState`.
 */
class TypeNamespaceCloduleTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(body.trimIndent(), "t.ts").diagnostics

    private val clodule = """
        type BinaryState = (x: number) => number;
        namespace BinaryState {
            export const enter: BinaryState = (x) => x;
        }
    """.trimIndent()

    @Test
    fun `clodule used as a TYPE does not fire TS2709`() {
        val d = diags(
            "$clodule\nfunction use(currentState: BinaryState): number { return currentState(1); }",
        )
        assertTrue(
            d.none { it.code == 2709 },
            "type X + namespace X used as a type must NOT be TS2709; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `clodule member used as a VALUE does not fire TS2693`() {
        val d = diags(
            "$clodule\nconst stack: BinaryState[] = [BinaryState.enter];",
        )
        assertTrue(
            d.none { it.code == 2693 },
            "type X + namespace X member used as a value must NOT be TS2693; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `a namespace-ONLY name used as a type STILL fires TS2709 (negative control)`() {
        val d = diags(
            """
            namespace NsOnly { export const v = 1; }
            function f(p: NsOnly): void {}
            """,
        )
        assertTrue(
            d.any { it.code == 2709 && it.message.contains("'NsOnly'") },
            "a namespace-only name used as a type must still be TS2709; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `a type-alias-ONLY name used as a value STILL fires TS2693 (negative control)`() {
        val d = diags(
            """
            type TOnly = number;
            const x = TOnly;
            """,
        )
        assertTrue(
            d.any { it.code == 2693 && it.message.contains("'TOnly'") },
            "a type-alias-only name used as a value must still be TS2693; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
