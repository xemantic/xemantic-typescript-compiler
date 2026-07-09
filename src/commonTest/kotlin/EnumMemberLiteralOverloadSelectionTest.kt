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
 * Round 459: overload selection by an ENUM-MEMBER literal parameter. An
 * enum-member param annotation (`kind: SyntaxKind.NamedImports`) resolves to
 * `anyType` (enum members are not modeled as literal types), so an enum-member
 * ARG used to match EVERY overload and the FIRST won — tsc's own
 * `parseNamedImportsOrExports(SyntaxKind.NamedExports)` (parser.ts) resolved
 * to the NamedImports overload's return type → FP TS2322 on the assignment to
 * a `NamedExportBindings | undefined` local.
 *
 * `resolveCallOverload` now compares the param annotation's canonical
 * enum-member key set (the round-411 discriminant key space) against the arg's
 * key AST-side: a resolvable non-member is a mismatch, so the correct overload
 * is selected. Both-unresolvable keeps the prior first-match behavior.
 */
class EnumMemberLiteralOverloadSelectionTest {

    private val prelude = """
        enum Kind { A, B }
        interface AShape { aOnly: string; }
        interface BShape { bOnly: number; }

        declare function pick(kind: Kind.A): AShape;
        declare function pick(kind: Kind.B): BShape;

    """.trimIndent()

    @Test
    fun `enum-member arg selects the MATCHING overload - no TS2322`() {
        diagnose(prelude + """
            function f() {
                let b: BShape | undefined;
                b = pick(Kind.B);
                return b;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the selected overload's return is still checked`() {
        // pick(Kind.A) → AShape, which is NOT assignable to BShape.
        diagnose(prelude + """
            function g() {
                let b: BShape | undefined;
                b = pick(Kind.A);
                return b;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `first overload still wins when the arg is not an enum member`() {
        // A non-enum-member arg keeps the prior behavior (no key → no filtering);
        // `pick(k)` with a bare Kind param stays permissive (first arity match).
        diagnose(prelude + """
            function h(k: Kind.A) {
                let a: AShape | undefined;
                a = pick(k);
                return a;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }
}
