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
 * An INFERRED type argument that is an intersection carrying a type parameter must be
 * allowed to satisfy the callee's constraint through that parameter's own constraint.
 *
 * Round 729, closing (LIB.1)'s `parser.ts:3558` false positive. Round 725 taught
 * `checkConstraintsForTypeArgs` (the type-REFERENCE site) that `T & X` satisfies a
 * constraint whenever `constraint(T)` does; the very same question is asked a second
 * time during CALL type-argument INFERENCE, where it was still answered by a bare
 * `checkTypeRelatedTo` — and the relation has no TypeParam-source-via-constraint rule
 * on purpose (round 456 measured adding one as net-zero and reverted it).
 *
 * The consequence is not a TS2344 but a silently OPEN return type: the inference bails
 * wholesale, the callee's declared `NodeArrayX<T>` is handed back un-instantiated, and
 * the mismatch surfaces as a TS2322 whose two sides both print `NodeArrayX<T>`-ish text.
 * tsc's own shape is
 *
 *     function createNodeArray<T extends Node>(elements: readonly T[], pos: number): NodeArray<T>
 *     … createNodeArray(list, pos)   // list: (T & { ... })[]  /  NonNullable<T>[]
 *
 * Round 728 read the same site as a type-PARAMETER NAME COLLISION, because renaming
 * either side made the diagnostic vanish. That reading is DISPROVEN by instrumentation:
 * the constraint check fails IDENTICALLY under both names (the dumped candidate is
 * `Intersection[TypeParam(T)#38, {m:1}]` against constraint `NodeX` either way). The
 * renamed variant was only ACCIDENTALLY clean — its equally-un-inferred return type was
 * swallowed further downstream — which is why the renamed case is pinned here as a
 * target too, not as a control.
 *
 * No lib types are needed to exhibit this: `NonNullable<T>` is merely the real lib's
 * spelling of `T & {}`.
 */
class InferredIntersectionTpConstraintTest {

    private val prelude = """
        interface NodeX { kind: number }
        interface Other { zzz: string }
        interface NodeArrayX<T extends NodeX> extends ReadonlyArray<T> { pos: number }
        declare function mkSame<T extends NodeX>(elements: readonly T[], pos: number): NodeArrayX<T>
        declare function mkDiff<E extends NodeX>(elements: readonly E[], pos: number): NodeArrayX<E>

    """.trimIndent()

    @Test
    fun `an inferred intersection of a type parameter satisfies the callee constraint`() {
        val diagnostics = diagnose(
            prelude + """
                function pG<T extends NodeX>(): NodeArrayX<T & { m: 1 }> {
                    const list: (T & { m: 1 })[] = []
                    return mkSame(list, 0)
                }
            """,
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `the same holds when the callee type parameter has a different name`() {
        // Round 728 believed the collision of the NAME `T` was the trigger; it was not.
        // On unmodified HEAD this variant is clean for an unrelated downstream reason,
        // so it is pinned to keep both spellings honest as the engine changes.
        val diagnostics = diagnose(
            prelude + """
                function pGok<T extends NodeX>(): NodeArrayX<T & { m: 1 }> {
                    const list: (T & { m: 1 })[] = []
                    return mkDiff(list, 0)
                }
            """,
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `the NonNullable spelling of the same shape is accepted`() {
        // `NonNullable<X>` is `X & {}` in the real lib — the empty object literal is
        // tsc's non-nullish marker, and it must not defeat the constraint consultation.
        val diagnostics = diagnose(
            prelude + """
                type NN<X> = X & {}
                function pNN<T extends NodeX>(): NodeArrayX<NN<T>> {
                    const list: NN<T>[] = []
                    return mkSame(list, 0)
                }
            """,
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `control - the inferred argument is the intersection itself, not something permissive`() {
        // THE DISCRIMINATING CONTROL. It errors on both sides of the fix, but the MESSAGE
        // differs, and the message is the whole point: before, the un-inferred
        // `NodeArrayX<T>` was reported; after, `NodeArrayX<T & { m: 1; }>` is — which is
        // only printable if inference actually bound the callee parameter to the
        // intersection. A fix that merely waved the constraint through would print
        // `any` or the open form here.
        val diagnostics = diagnose(
            prelude + """
                function cWrong<T extends NodeX>(): NodeArrayX<T & { m: 2 }> {
                    const list: (T & { m: 1 })[] = []
                    return mkSame(list, 0)
                }
            """,
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message ==
            "Type 'NodeArrayX<T & { m: 1; }>' is not assignable to type 'NodeArrayX<T & { m: 2; }>'.")
    }

    @Test
    fun `control - a primitive argument violating the constraint is still rejected`() {
        // The emitting branch this bail sits inside requires a SIMPLE-CHECKABLE inferred
        // type, which an intersection never is — so the suppression provably cannot reach
        // the diagnostic. This pins that separation.
        val diagnostics = diagnose(
            prelude + """
                declare function idP<T extends NodeX>(x: T): T
                function cPrim() { return idP("abc") }
            """,
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message == "Argument of type 'string' is not assignable to parameter of type 'NodeX'."
        })
    }

    @Test
    fun `control - a type parameter constrained to an unrelated type is not waved through`() {
        // `U extends Other` supplies `Other`, which does not satisfy `NodeX`. The shared
        // consultation must answer NO — the same helper is asked the same question at the
        // type-REFERENCE site, and its TS2344 must survive.
        val diagnostics = diagnose(
            prelude + """
                declare function needsNodeX<T extends NodeX>(elements: readonly T[], pos: number): NodeArrayX<T>
                function cViolate<U extends Other>(): NodeArrayX<U & { m: 1 }> {
                    const list: (U & { m: 1 })[] = []
                    return needsNodeX(list, 0)
                }
            """,
        )
        assert(diagnostics.any {
            it.code == 2344 &&
                it.message == "Type 'U & { m: 1; }' does not satisfy the constraint 'NodeX'."
        })
    }

    @Test
    fun `control - only a SATISFYING constraint makes the inference bind`() {
        // The discriminating PAIR. The two sources differ in exactly one token — the
        // constraint of `U` — and the annotation site is kept out of it (`string` cannot
        // trigger the round-725 rule). With `U extends NodeX` the consultation succeeds,
        // the callee binds to the intersection, and the mismatch against `string` is
        // reported with the BOUND type. With `U extends Other` it fails, the inference
        // bails as before, and the open return type is silent — the pre-existing
        // behaviour this change must not disturb.
        val binds = diagnose(
            prelude + """
                declare function needsNodeX<T extends NodeX>(elements: readonly T[], pos: number): NodeArrayX<T>
                function cBinds<U extends NodeX>(): string {
                    const list: (U & { m: 1 })[] = []
                    return needsNodeX(list, 0)
                }
            """,
        )
        assert(binds.any {
            it.code == 2322 &&
                it.message == "Type 'NodeArrayX<U & { m: 1; }>' is not assignable to type 'string'."
        })
        val doesNot = diagnose(
            prelude + """
                declare function needsNodeX<T extends NodeX>(elements: readonly T[], pos: number): NodeArrayX<T>
                function cBails<U extends Other>(): string {
                    const list: (U & { m: 1 })[] = []
                    return needsNodeX(list, 0)
                }
            """,
        )
        assert(doesNot.none { it.code == 2322 })
    }
}
