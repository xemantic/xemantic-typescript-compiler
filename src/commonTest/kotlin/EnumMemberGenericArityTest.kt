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
 * The RIGHT segment of an ENUM-qualified name (`SyntaxKind.ThisType`, `TypeMapKind.Array`) in
 * type position is an enum-MEMBER literal type, NOT the same-named generic lib type
 * (`ThisType<T>` / `Array<T>`). The generic-arity check (`checkTypeArgCount`) resolves the
 * right segment as a simple name and matched the lib generic, FP-emitting TS2314 "Generic
 * type 'X<T>' requires 1 type argument(s)". It now skips a qualified name whose qualifier
 * resolves to an enum. This was 3 self-compile FPs (tsc's `| SyntaxKind.ThisType` unions and
 * `{ kind: TypeMapKind.Array; … }`).
 */
class EnumMemberGenericArityTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(body.trimIndent(), "t.ts").diagnostics

    @Test
    fun `enum member named like a generic lib type does NOT fire TS2314`() {
        val d = diags(
            """
            enum SyntaxKind { ThisType, Other }
            type NodeKind = SyntaxKind.ThisType | SyntaxKind.Other;
            declare const k: NodeKind;
            """,
        )
        assertTrue(
            d.none { it.code == 2314 },
            "SyntaxKind.ThisType (enum member) must NOT be TS2314; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `enum member named Array does NOT fire TS2314`() {
        val d = diags(
            """
            const enum TypeMapKind { Simple, Array }
            type K = TypeMapKind.Array;
            declare const k: K;
            """,
        )
        assertTrue(
            d.none { it.code == 2314 },
            "TypeMapKind.Array (enum member) must NOT be TS2314; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `a bare generic lib type used without type args STILL fires TS2314 (negative control)`() {
        // `Array` used bare (not enum-qualified) genuinely requires 1 type argument.
        val d = diags("type Bad = Array;")
        assertTrue(
            d.any { it.code == 2314 && it.message.contains("Array") },
            "bare `Array` (no type args) must still be TS2314; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `a namespace-qualified generic used without type args STILL fires TS2314 (negative control)`() {
        // The qualifier is a NAMESPACE (not an enum), and the member IS a generic type missing
        // its arg — the enum-qualifier skip must not apply here.
        val d = diags(
            """
            namespace N { export interface Box<T> { v: T; } }
            type Bad = N.Box;
            """,
        )
        assertTrue(
            d.any { it.code == 2314 && it.message.contains("Box") },
            "N.Box (namespace-qualified generic, no args) must still be TS2314; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
