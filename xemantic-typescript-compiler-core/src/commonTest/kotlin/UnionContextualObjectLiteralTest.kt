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
 */


package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.148): an OBJECT LITERAL against a UNION contextual type out of which no single
 * constituent can be SELECTED - tsgo's `getTypeOfPropertyOfContextualTypeEx`
 * (`checker.go:30312`), which does not select at all: it `mapTypeEx`es the member lookup
 * over the constituents and UNIONS what it finds. Discrimination is an earlier, optional
 * refinement, and `isPossiblyDiscriminantValue` deliberately excludes context-dependent
 * expressions - so a member written as a TERNARY defeats selection in BOTH compilers and
 * the union-of-members answer is the whole mechanism rather than a fallback.
 *
 * This is the other half of (CHK.142)(b), and it took FOUR pieces, none of which moves a
 * row alone - each was measured inert before the next was added:
 *
 *  1. the union survives into the member lookup when nothing selects
 *    ([Checker.contextualMemberTypeAcrossUnion]);
 *  2. a LITERAL member under a context that wants a literal keeps its literal type
 *    ([Checker.objLitLiteralUnderContext]) - this engine answers the BASE PRIMITIVE for
 *    every literal, so without it the discriminant is `string`;
 *  3. a VARIABLE DECLARATION whose annotation is a union installs it as a context at all
 *    - the gate was `targetType is Type.Object` and refused every union outright, which
 *    is why (1) and (2) were unreachable from that position;
 *  4. the ARGUMENT walker's FP firewall asks the WHOLE union and not only its
 *    constituents, so (CHK.142)(b)'s discriminated SOURCE SPLIT can be consulted.
 *
 * EVERY EXPECTED VALUE WAS MEASURED against `tools/tsgo-7.0.2/lib/tsc --noEmit -p`.
 * THE PRIZE is `marked`'s `src/Tokenizer.ts:19`, whose `type` member is exactly this
 * shape; that library goes 2 ours-only rows to 1 with no new row.
 */
class UnionContextualObjectLiteralTest {

    private val prelude = """
        interface Link  { type: 'link';  raw: string }
        interface Image { type: 'image'; raw: string }
        declare const c: boolean;
        declare const k: 'image' | 'link';
    """.trimIndent() + "\n"

    private fun errs(src: String): List<Diagnostic> = diagnose(prelude + src)

    private fun rows(src: String): Int = errs(src).count { it.code == 2322 || it.code == 2345 }

    /** The SOURCE type named by the single TS2322 — i.e. what the literal really became. */
    private fun sourceOf(src: String): String =
        errs(src).single { it.code == 2322 }.message
            .substringAfter("Type '").substringBefore("' is not assignable")

    // ---- piece 3 + 2: the member keeps its literal type under a union annotation ------

    @Test
    fun `a ternary discriminant under a union annotation keeps its literal union`() {
        // THE VALUE, not a silence: a `never` target makes the message name the member
        // type the literal really recorded. Before this it read `{ type: string; … }`.
        assert(
            sourceOf("const v: never = { type: c ? 'image' : 'link', raw: 'x' };")
                == "{ type: string; raw: string; }"
        )
        // …and with the union annotation supplying the context it relates.
        assert(rows("const v: Link | Image = { type: c ? 'image' : 'link', raw: 'x' };") == 0)
    }

    @Test
    fun `a bare literal discriminant under a union annotation still relates`() {
        // The round-472 discriminant SELECTION owns this one - it must keep working.
        assert(rows("const v: Link | Image = { type: 'link', raw: 'x' };") == 0)
    }

    @Test
    fun `a union-typed discriminant reference under a union annotation relates`() {
        assert(rows("const v: Link | Image = { type: k, raw: 'x' };") == 0)
    }

    // ---- piece 1: the union reaches a NON-discriminant member too --------------------

    @Test
    fun `a callback member under a union annotation is contextually typed`() {
        // The parameter `n` must acquire `number` from the union's members, not `any`.
        // Asserted as a VALUE through a deliberate mis-assignment inside the body.
        val d = diagnose(
            """
            interface A { f: (n: number) => void; k: 'a' }
            interface B { f: (n: number) => void; k: 'b' }
            declare const c: boolean;
            const v: A | B = { f: (n) => { const p: never = n; }, k: c ? 'a' : 'b' };
            """
        )
        val named = d.single { it.code == 2322 }.message
        assert(named == "Type 'number' is not assignable to type 'never'.")
    }

    // ---- piece 4: the ARGUMENT firewall asks the whole union -------------------------

    @Test
    fun `an argument literal splitting on its discriminant is accepted`() {
        assert(
            rows(
                """
                declare function take(v: Link | Image): void;
                take({ type: k, raw: 'x' });
                """
            ) == 0
        )
    }

    @Test
    fun `an argument literal with an optional discriminant is accepted`() {
        assert(
            rows(
                """
                interface Blue { color: 'blue' }
                interface Yellow { color?: 'yellow' }
                declare function draw(v: Blue | Yellow): void;
                declare const cc: 'blue' | 'yellow' | undefined;
                draw({ color: cc });
                """
            ) == 0
        )
    }

    @Test
    fun `a ternary discriminant in ARGUMENT position is contextually typed too`() {
        // Piece 5. The argument install carried the SAME `paramType is Type.Object` gate
        // as the var-decl one, so this stayed wrong after the var-decl form was fixed -
        // and NO OTHER PIN HERE SEES IT (measured: the arm that drops the argument
        // install reddens none of the rest and moves exactly this shape).
        assert(
            rows(
                """
                declare function take(v: Link | Image): void;
                take({ type: c ? 'image' : 'link', raw: 'x' });
                """
            ) == 0
        )
    }

    @Test
    fun `a NON-fresh identifier argument splitting on its discriminant is accepted`() {
        // The firewall has TWO branches and they had the SAME defect: the non-fresh one
        // asks `constituents.any { … }` as well, so a variable whose type splits over the
        // union was a confident TS2345 on legal code.
        assert(
            rows(
                """
                const o = { type: k, raw: 'x' };
                declare function take(v: Link | Image): void;
                take(o);
                """
            ) == 0
        )
    }

    // ---- the refusals: none of this may accept what tsgo rejects ---------------------

    @Test
    fun `a member value outside every constituent is still refused`() {
        assert(rows("const v: Link | Image = { type: 'nope', raw: 'x' };") == 1)
    }

    @Test
    fun `a non-discriminant member that fails is still refused`() {
        assert(rows("const v: Link | Image = { type: k, raw: 1 };") == 1)
    }

    @Test
    fun `a literal whose value relates to no constituent combination is refused`() {
        assert(
            rows(
                """
                interface AA { p: 'a'; q: 'x' }
                interface AB { p: 'a'; q: 'y' }
                interface BA { p: 'b'; q: 'x' }
                const v: AA | AB | BA = { p: c ? 'a' : 'b', q: c ? 'x' : 'y' };
                """
            ) == 1
        )
    }

    @Test
    fun `a single-constituent target is untouched by the union path`() {
        assert(rows("const v: Link = { type: c ? 'link' : 'link', raw: 'x' };") == 0)
        assert(rows("const v: Link = { type: c ? 'image' : 'link', raw: 'x' };") == 1)
    }

    @Test
    fun `a non-literal contextual member does not take the literal path`() {
        // `raw` is `string` on both constituents, so nothing here admits a literal and
        // the member must stay `string` rather than acquiring `'x'`.
        assert(
            sourceOf("const v: never = { type: k, raw: 'x' };")
                == "{ type: \"image\" | \"link\"; raw: string; }"
        )
    }
}
