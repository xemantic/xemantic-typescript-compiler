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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * Inside a DISTRIBUTIVE conditional type the check type parameter denotes the
 * CONSTITUENT being tested, not the whole union.
 *
 * Round 729, closing (LIB.1)'s `utilities.ts:4258`. The distribution loop evaluated each
 * constituent's branch under the UNSHIFTED alias-argument map, so `T` in a branch still
 * resolved to the entire union — which makes `Exclude<T, U> = T extends U ? never : T`
 * an IDENTITY FUNCTION, since every non-matching constituent contributes the whole union
 * back. tsc's `nextToLast = nextToLast.expression as Exclude<BindableStaticNameExpression,
 * Identifier>` therefore kept `Identifier` in the asserted type and failed to assign to
 * `AccessExpression`.
 *
 * Only a NAKED type parameter distributes, which is what bounds the change: `[T] extends
 * [U] ? …` and a concrete-union check type keep evaluating exactly as before.
 *
 * The tests are deliberately written against a HAND-ROLLED alias so they hold on the
 * embedded lib too — the embedded lib declares no utility types at all, so a real-lib
 * `Exclude` would silently degrade to `any` and assert nothing.
 */
class DistributiveConditionalTypeTest {

    private val prelude = """
        interface Ident { kind: 1; escapedText: string }
        interface PropAcc { kind: 2; expression: Ident | PropAcc }
        type MyExclude<T, U> = T extends U ? never : T

    """.trimIndent()

    @Test
    fun `a distributive conditional filters the union instead of returning it whole`() {
        // `MyExclude<Ident | PropAcc, Ident>` is `PropAcc`, so an `Ident` cannot be
        // assigned to it. Before, the result still carried `Ident` and this was silent.
        val diagnostics = diagnose(
            prelude + """
                type R = MyExclude<Ident | PropAcc, Ident>
                declare const i: Ident
                const q: R = i
            """,
        )
        assert(diagnostics.any {
            it.code == 2741 &&
                it.message == "Property 'expression' is missing in type 'Ident' but required in type 'R'."
        })
    }

    @Test
    fun `the surviving constituent is still assignable`() {
        val diagnostics = diagnose(
            prelude + """
                type R = MyExclude<Ident | PropAcc, Ident>
                declare const p: PropAcc
                const q: R = p
            """,
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `the assertion shape from tsc's getAssignmentDeclarationPropertyAccessKind is clean`() {
        // The live site, reduced. The INTERSECTION member is load-bearing — round 728's
        // flat `Ident | PropAcc` version of this probe came back clean for an unrelated
        // reason and nearly buried the defect.
        val diagnostics = diagnose(
            prelude + """
                interface ElemAcc { kind: 3; expression: Ident | PropAcc | ElemAcc }
                interface Decl { d: 1 }
                type LitElemAcc = ElemAcc & Decl & { readonly arg: string } & { readonly expression: any }
                type Names = Ident | PropAcc | LitElemAcc
                type Access = PropAcc | ElemAcc
                declare const lhs: Access
                function f() {
                    let nextToLast = lhs
                    while (nextToLast.expression.kind !== 1) {
                        nextToLast = nextToLast.expression as MyExclude<Names, Ident>
                    }
                }
            """,
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `control - a NON-distributive conditional still tests the union as a whole`() {
        // `[T] extends [U]` is tsc's opt-out of distribution: the check type is a tuple,
        // not a naked type parameter, so the union must be tested in one piece and the
        // answer is the FALSE branch alone. A rebinding that leaked here would evaluate
        // both branches and hand back `Ident | PropAcc`, which would silence the error.
        val diagnostics = diagnose(
            prelude + """
                type NonDist<T, U> = [T] extends [U] ? Ident : PropAcc
                type N = NonDist<Ident | PropAcc, Ident>
                declare const p: PropAcc
                declare const i: Ident
                const ok: N = p
                const bad: N = i
            """,
        )
        assert(diagnostics.size == 1)
        assert(diagnostics[0].code == 2741)
    }

    @Test
    fun `control - a fully-excluded union collapses to never`() {
        val diagnostics = diagnose(
            prelude + """
                type R = MyExclude<Ident, Ident>
                declare const i: Ident
                const q: R = i
            """,
        )
        assert(diagnostics.any {
            it.code == 2322 && it.message == "Type 'Ident' is not assignable to type 'never'."
        })
    }

    @Test
    fun `a sibling AST interface is not excluded just because it is structurally compatible`() {
        // Enum-member types do not discriminate in our relation (`kind: SK.Identifier`
        // happily accepts `SK.PrivateIdentifier`), so `Ident` reads as assignable to
        // `PrivId` — the two differ only in that discriminant. Harmless while `Exclude`
        // was an identity function; once distribution worked it silently DROPPED
        // `Identifier` from `Exclude<PropertyName, PrivateIdentifier>`, which is tsc's
        // factory/utilities.ts:1056. The round-472 `.kind` DOMAIN veto decides it.
        val diagnostics = diagnose(
            """
            declare enum SK { Identifier = 80, PrivateIdentifier = 81, StringLiteral = 11 }
            interface NodeB { readonly kind: SK }
            interface Ident extends NodeB { readonly kind: SK.Identifier; readonly escapedText: string }
            interface PrivId extends NodeB { readonly kind: SK.PrivateIdentifier; readonly escapedText: string }
            interface StrLit extends NodeB { readonly kind: SK.StringLiteral; readonly text: string }
            type MyExclude<T, U> = T extends U ? never : T
            type PropName = Ident | StrLit | PrivId
            type Ex = MyExclude<PropName, PrivId>
            declare const src: Ident | StrLit
            const t: Ex | undefined = src
            """,
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `control - the discriminated sibling itself IS still excluded`() {
        // The veto must not degrade into "an enum-discriminated type is never excluded":
        // matching key domains still take the true branch.
        val diagnostics = diagnose(
            """
            declare enum SK { Identifier = 80, PrivateIdentifier = 81 }
            interface NodeB { readonly kind: SK }
            interface Ident extends NodeB { readonly kind: SK.Identifier; readonly escapedText: string }
            interface PrivId extends NodeB { readonly kind: SK.PrivateIdentifier; readonly escapedText: string }
            type MyExclude<T, U> = T extends U ? never : T
            declare const p: PrivId
            const q: MyExclude<PrivId, PrivId> = p
            """,
        )
        assert(diagnostics.any {
            it.code == 2322 && it.message == "Type 'PrivId' is not assignable to type 'never'."
        })
    }

    @Test
    fun `control - a single non-matching check type is unchanged`() {
        val diagnostics = diagnose(
            prelude + """
                type R = MyExclude<PropAcc, Ident>
                declare const p: PropAcc
                const q: R = p
            """,
        )
        assert(diagnostics.isEmpty())
    }
}
