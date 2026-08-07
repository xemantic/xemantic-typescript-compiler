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
 * An EXPLICIT call type argument that is an intersection carrying a type parameter
 * satisfies the callee's constraint through that parameter's own constraint.
 *
 * Round 729, the third and last site of round 725's rule. That round taught the
 * type-REFERENCE site (`checkConstraintsForTypeArgs`) to look through an intersection
 * to its TypeParam constituent's constraint; round 728 noticed in passing that an
 * explicit CALL type argument of the very same shape — `createNodeArray<NonNullable<T>>
 * (list, pos)` — still reported TS2344, because `checkCallTypeArgConstraints` had only
 * the BARE-TypeParam arm. `NonNullable<X>` is `X & {}`, so the bare arm never sees it.
 *
 * Written against a hand-rolled `NN<X> = X & {}` so it holds on the embedded lib, which
 * declares no utility types at all.
 */
class ExplicitCallTypeArgIntersectionTest {

    private val prelude = """
        interface NodeX { kind: number }
        interface Other { zzz: string }
        interface NodeArrayX<T extends NodeX> extends ReadonlyArray<T> { pos: number }
        declare function createNodeArrayX<T extends NodeX>(elements: readonly T[], pos: number): NodeArrayX<T>
        type NN<X> = X & {}

    """.trimIndent()

    @Test
    fun `the NonNullable form of a nullable-constrained parameter is accepted`() {
        val diagnostics = diagnose(
            prelude + """
                function f<U extends NodeX | undefined>(list: NN<U>[]) {
                    return createNodeArrayX<NN<U>>(list, 0)
                }
            """,
        )
        assert(diagnostics.none { it.code == 2344 })
    }

    @Test
    fun `a plain intersection whose type parameter satisfies the constraint is accepted`() {
        val diagnostics = diagnose(
            prelude + """
                function f<U extends NodeX>(list: (U & { m: 1 })[]) {
                    return createNodeArrayX<U & { m: 1 }>(list, 0)
                }
            """,
        )
        assert(diagnostics.none { it.code == 2344 })
    }

    @Test
    fun `control - an intersection with no type parameter still fails`() {
        // The decisive one: the arm must not degrade into "an intersection never violates
        // a constraint". Nothing here supplies a constraint to consult.
        val diagnostics = diagnose(
            prelude + """
                declare const oa: (Other & { m: 1 })[]
                const ca = createNodeArrayX<Other & { m: 1 }>(oa, 0)
            """,
        )
        assert(diagnostics.any {
            it.code == 2344 &&
                it.message == "Type 'Other & { m: 1; }' does not satisfy the constraint 'NodeX'."
        })
    }

    @Test
    fun `control - a type parameter constrained to an unrelated type still fails`() {
        // The constraint chain must be COMPARED, not merely found.
        val diagnostics = diagnose(
            prelude + """
                function cb<U extends Other>(list: (U & { m: 1 })[]) {
                    return createNodeArrayX<U & { m: 1 }>(list, 0)
                }
            """,
        )
        assert(diagnostics.any {
            it.code == 2344 &&
                it.message == "Type 'U & { m: 1; }' does not satisfy the constraint 'NodeX'."
        })
    }

    @Test
    fun `control - the NonNullable form of an UNCONSTRAINED parameter still fails`() {
        // `NN<U>` with `U` unconstrained supplies nothing, so the nullish strip has no
        // constraint to strip and the genuine TS2344 survives.
        val diagnostics = diagnose(
            prelude + """
                function cc<U>(list: NN<U>[]) {
                    return createNodeArrayX<NN<U>>(list, 0)
                }
            """,
        )
        assert(diagnostics.any {
            it.code == 2344 &&
                it.message == "Type 'NN<U>' does not satisfy the constraint 'NodeX'."
        })
    }
}
