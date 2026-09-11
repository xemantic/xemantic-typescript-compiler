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
 * (CHK.130) — WHEN a union member is printed inside parentheses. Every row here was
 * adjudicated against tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was
 * written, over one mixed fixture holding all fifteen shapes; the two references agree
 * on every row asserted, so there is no REF-SPLIT in this class.
 *
 * **The rule is about WHAT IS PRINTED, not about the member's resolved SHAPE**, and both
 * halves have to be pinned or the fix is unfalsifiable. The predicate this replaced asked
 * "does this member have exactly one call-or-construct signature and nothing else", which
 * is equally true of a bare function type — which genuinely needs the parentheses to
 * reparse — and of an interface or a type alias that prints as its own NAME, which does
 * not. So a pin set holding only the recovered rows cannot tell a correct fix from one
 * that dropped the parentheses everywhere, and the must-parenthesize half below is what
 * makes the ablation attributable.
 *
 * Two things a reader should not mistake for coverage. A display change is invisible to
 * the 8-profile grid ((PARITY.1)) — measured on this round's own capture, its 416 rows are
 * 410 `Cannot find name`/`Cannot find namespace`, 3 TS7006, 2 TS2339 and one TS2593, and
 * **not one of them names a union at all** — so the grid is a control here and these pins
 * plus the corpus are the whole gate. And the interface case is ORDER-DEPENDENT in the
 * shipped binary, because a `Type.Interface`'s member tables are lazy (round 833): it
 * renders bare in a plain TS2322 and parenthesized in the union-callee TS2349 whose own
 * resolution has just filled them in, which is why the interface rows below are driven
 * through the CALLEE path and not through a mis-assignment.
 *
 * **Which pin sees which mistake, measured, one mistake per arm and each arm against the
 * FULL suite.** Restoring the resolved-SHAPE predicate reddens exactly the three
 * must-NOT-parenthesize pins and **zero corpus baselines** — which is the receipt that the
 * corpus cannot see this direction at all, and matches the independent enumeration finding
 * that none of the 2,910 ACTIVE `.errors.txt` baselines contains a parenthesized bare-NAME
 * union member. Dropping the parentheses altogether reddens exactly the four
 * must-parenthesize pins AND four corpus baselines, so the opposite direction IS corpus-gated.
 * The four neighbour pins at the bottom are red under neither and are undiscriminated by
 * construction — they are recorded as controls, not claimed as coverage; they move only
 * under the parenthesize-everything arm, which reddens all eleven and 345 tests overall.
 */
class UnionMemberParenthesizationDisplayTest {

    private val prelude = """
        declare function zzzFn(a: string): void
        type ZzzG = (a: string) => void
        type ZzzC = new () => object
        interface ZzzS { (a: string): void }
        interface ZzzGi<T> { (a: T): void }
        interface ZzzA { (a: string): void; (a: number): void }
        interface ZzzB { (a: boolean): void; (a: object): void }
        interface ZzzP { p: number }
        interface ZzzQ { q: number }
    """.trimIndent() + "\n"

    private fun display(source: String): List<String> =
        diagnose(prelude + source).map { it.message }

    // ------------------------------------------------------------------
    // MUST NOT be parenthesized — a member that prints as a NAME
    // ------------------------------------------------------------------

    @Test
    fun `an alias to a function type is a union member with no parentheses`() {
        assert(
            display("declare const m: ZzzG | number\nconst bad: boolean = m") ==
                listOf("Type 'number | ZzzG' is not assignable to type 'boolean'.")
        )
    }

    @Test
    fun `an alias to a constructor type is a union member with no parentheses`() {
        assert(
            display("declare const m: ZzzC | number\nconst bad: boolean = m") ==
                listOf("Type 'number | ZzzC' is not assignable to type 'boolean'.")
        )
    }

    @Test
    fun `an interface whose only member is a call signature is not parenthesized`() {
        // Driven through the union CALLEE, which resolves the interface's member table
        // first — the shipped-binary shape in which the defect was measured, and the
        // `build/bench/chk97f/fixtures/p3` row it recovers.
        // `messageChain` entries carry the renderer's own nesting indentation, which is
        // not what this pin is about — trimmed, so an indentation change elsewhere cannot
        // redden a parenthesization pin.
        val chains = diagnose(prelude + "declare const z: ZzzA | ZzzB | ZzzS\nz(\"x\")")
            .flatMap { it.messageChain }.map { it.trim() }
        assert(
            chains == listOf(
                "Each member of the union type 'ZzzA | ZzzB | ZzzS' has signatures, " +
                    "but none of those signatures are compatible with each other."
            )
        )
    }

    /**
     * A CONTROL, not a discriminator: a `Type.Reference`'s member tables are lazy, so on
     * the pre-fix binary its `callSignatures` were still null here and the old predicate
     * never fired — measured green under the restore-the-old-predicate arm.
     */
    @Test
    fun `a generic interface instantiation is a union member with no parentheses`() {
        assert(
            display("declare const m: ZzzGi<string> | number\nconst bad: boolean = m") ==
                listOf("Type 'number | ZzzGi<string>' is not assignable to type 'boolean'.")
        )
    }

    // ------------------------------------------------------------------
    // MUST be parenthesized — a member that prints as an ARROW form
    // ------------------------------------------------------------------

    @Test
    fun `an anonymous function type is a parenthesized union member`() {
        assert(
            display("declare const m: ((a: string) => void) | number\nconst bad: boolean = m") ==
                listOf("Type 'number | ((a: string) => void)' is not assignable to type 'boolean'.")
        )
    }

    @Test
    fun `an anonymous construct signature is a parenthesized union member`() {
        assert(
            display("declare const m: (new () => object) | number\nconst bad: boolean = m") ==
                listOf("Type 'number | (new () => object)' is not assignable to type 'boolean'.")
        )
    }

    @Test
    fun `a nullish function type keeps its parentheses and renders undefined last`() {
        assert(
            display("declare const m: (() => void) | undefined\nconst bad: boolean = m") ==
                listOf("Type '(() => void) | undefined' is not assignable to type 'boolean'.")
        )
    }

    @Test
    fun `a typeof of a function declaration is a parenthesized union member`() {
        assert(
            display("declare const m: typeof zzzFn | number\nconst bad: boolean = m") ==
                listOf("Type 'number | ((a: string) => void)' is not assignable to type 'boolean'.")
        )
    }

    // ------------------------------------------------------------------
    // Neighbours the rule must leave exactly where they were
    // ------------------------------------------------------------------

    @Test
    fun `an object type with a call signature beside a property is not parenthesized`() {
        assert(
            display("declare const m: { (a: string): void; z: number } | number\nconst bad: boolean = m") ==
                listOf(
                    "Type 'number | { (a: string): void; z: number; }' " +
                        "is not assignable to type 'boolean'."
                )
        )
    }

    @Test
    fun `an anonymous object type with two call signatures is not parenthesized`() {
        assert(
            display(
                "declare const m: { (a: string): void; (a: string, b: number): void } | number\n" +
                    "const bad: boolean = m"
            ) == listOf(
                "Type 'number | { (a: string): void; (a: string, b: number): void; }' " +
                    "is not assignable to type 'boolean'."
            )
        )
    }

    @Test
    fun `an array of an interface whose only member is a call signature is not parenthesized`() {
        assert(
            display("declare const m: ZzzS[] | number\nconst bad: boolean = m") ==
                listOf("Type 'number | ZzzS[]' is not assignable to type 'boolean'.")
        )
    }
}
