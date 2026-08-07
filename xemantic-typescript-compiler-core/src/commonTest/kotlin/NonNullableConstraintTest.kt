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
 * `NonNullable<T>` must satisfy a constraint that `T`'s own constraint satisfies once
 * the nullish part is removed.
 *
 * Round 724, continuing (LIB.1)'s burn-down: four of the remaining 22 real-lib false
 * positives are one shape — `Type 'NonNullable<T>' does not satisfy the constraint
 * 'Node'` (parser.ts x2, visitorPublic.ts x2). tsc writes
 *
 *     function visitNode<TIn extends Node | undefined, …>(
 *         node: TIn, visitor: Visitor<NonNullable<TIn>, TVisited>, …)
 *
 * where `Visitor`'s first parameter requires `extends Node`. The suspicion is that we
 * check the constraint against `TIn`'s RAW constraint `Node | undefined` — which indeed
 * does not satisfy `Node` — instead of against the nullish-stripped one, i.e. the same
 * class of bug as round 720's `Required<T>`: a lib utility type whose whole effect is
 * not applied.
 *
 * Under `@useRealLibs` because `NonNullable` is a real-lib declaration; the curated lib
 * has no utility types at all, so on the default path the name degrades to `any` and is
 * silent. Controls included, per rounds 718/721/722.
 */
class NonNullableConstraintTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @target: es2015"

    private val prelude = """
        interface Node { kind: number }
        interface Visitor<TIn extends Node, TOut> { visit(n: TIn): TOut }

    """.trimIndent()

    @Test
    fun `NonNullable of a nullable-constrained parameter satisfies the non-null constraint`() {
        val diagnostics = diagnose(
            prelude + """
                declare function visitNode<TIn extends Node | undefined>(
                    node: TIn,
                    visitor: Visitor<NonNullable<TIn>, Node>,
                ): void
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2344 })
    }

    @Test
    fun `NonNullable of an already-non-null parameter also satisfies it`() {
        val diagnostics = diagnose(
            prelude + """
                declare function visitNode<TIn extends Node>(
                    node: TIn,
                    visitor: Visitor<NonNullable<TIn>, Node>,
                ): void
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2344 })
    }

    @Test
    fun `control - an unconstrained parameter still fails the constraint`() {
        val diagnostics = diagnose(
            prelude + """
                declare function visitNode<TIn>(
                    node: TIn,
                    visitor: Visitor<TIn, Node>,
                ): void
            """,
            directives = realLibs,
        )
        // A silent control means the probe cannot see TS2344 here at all.
        assert(diagnostics.any { it.code == 2344 })
    }

    @Test
    fun `control - NonNullable of an unconstrained parameter still fails the constraint`() {
        // `NonNullable<TIn>` where TIn is unconstrained is `unknown & {}` — it satisfies
        // nothing. The bail must consult the constituent's CONSTRAINT, so a constituent
        // that has none must not be waved through.
        val diagnostics = diagnose(
            prelude + """
                declare function visitNode<TIn>(
                    node: TIn,
                    visitor: Visitor<NonNullable<TIn>, Node>,
                ): void
            """,
            directives = realLibs,
        )
        assert(diagnostics.any { it.code == 2344 })
    }

    @Test
    fun `control - NonNullable of a parameter constrained to an unrelated type still fails`() {
        // The constraint chain must actually be COMPARED, not merely found: `string`
        // stripped of nullish is still `string`, which does not satisfy `Node`.
        val diagnostics = diagnose(
            prelude + """
                declare function visitNode<TIn extends string | undefined>(
                    node: TIn,
                    visitor: Visitor<NonNullable<TIn>, Node>,
                ): void
            """,
            directives = realLibs,
        )
        assert(diagnostics.any { it.code == 2344 })
    }

    @Test
    fun `control - an intersection with no type parameter that violates the constraint still fails`() {
        // The decisive negative control: the bail must not degrade into "an intersection
        // never violates a constraint". `A & B` carries no type parameter at all, so
        // nothing supplies a constraint and the genuine TS2344 must survive.
        val diagnostics = diagnose(
            prelude + """
                interface A { a: number }
                interface B { b: number }
                declare function visitNode(visitor: Visitor<A & B, Node>): void
            """,
            directives = realLibs,
        )
        assert(diagnostics.any { it.code == 2344 })
    }

    @Test
    fun `control - a nullable-constrained parameter used WITHOUT NonNullable still fails`() {
        // The nullish strip is tied to the `& {}` marker, not applied to every constraint:
        // passing `TIn extends Node | undefined` directly is a real error and stays one.
        val diagnostics = diagnose(
            prelude + """
                declare function visitNode<TIn extends Node | undefined>(
                    node: TIn,
                    visitor: Visitor<TIn, Node>,
                ): void
            """,
            directives = realLibs,
        )
        assert(diagnostics.any { it.code == 2344 })
    }
}
