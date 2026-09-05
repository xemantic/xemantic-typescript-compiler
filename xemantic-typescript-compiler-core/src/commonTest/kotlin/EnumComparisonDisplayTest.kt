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
 * (CHK.89) / (CHK.90) / (CHK.83) — three DISPLAY rules and one SILENCE, every row of which
 * was reproduced against tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was
 * written. The two references agree on every row asserted here; the ONE row on which they
 * were measured to DISAGREE (which constituent a union-source chain names) is deliberately
 * not asserted, and is recorded in [Checker]'s own KDoc instead.
 *
 * Why the negative controls carry the weight. Three of the four rules make a display
 * BROADER (`A` -> `K.A`) or NARROWER (`K.A` -> `K`) rather than adding a row, and a
 * display change is invisible to the 8-profile grid — all 46 rows of seven profiles are
 * `Cannot find name …`, which names no type at all. So each rule is pinned together with
 * the neighbouring shape it must NOT move: a namespaced INTERFACE keeps its bare name, a
 * whole enum keeps its own, the (CHK.88) value rule keeps its ORIGINAL operands where the
 * category rule beside it takes their bases, and a `never` target keeps its literal.
 */
class EnumComparisonDisplayTest {

    private val enums = """
        enum K { A, B }
        enum S2 { X = "x", Y = "y" }
        namespace N { export enum Q { X, Y } }
        namespace M { export interface I { a: number } }
        declare const ka: K.A
        declare const kb: K.B
        declare const k: K
        declare const s2: S2
        declare const s2x: S2.X
        declare const qy: N.Q.Y
        declare const num: number
    """.trimIndent() + "\n"

    // ---------------------------------------------------------------------
    // (CHK.89) — a RETURN-position enum-member annotation keeps its enum
    // ---------------------------------------------------------------------

    @Test
    fun `a function declaration's enum-member return annotation renders qualified`() {
        val messages = diagnose(enums + "function f(): K.A { return kb }").map { it.message }
        assert(messages == listOf("Type 'K.B' is not assignable to type 'K.A'."))
    }

    @Test
    fun `an arrow's enum-member return annotation renders qualified`() {
        val messages = diagnose(enums + "const f = (): K.A => kb").map { it.message }
        assert(messages == listOf("Type 'K.B' is not assignable to type 'K.A'."))
    }

    @Test
    fun `a class method's enum-member return annotation renders qualified`() {
        val messages = diagnose(enums + "class C { m(): K.A { return kb } }").map { it.message }
        assert(messages == listOf("Type 'K.B' is not assignable to type 'K.A'."))
    }

    @Test
    fun `a namespaced enum member renders as enum dot member and drops the namespace`() {
        val messages = diagnose(enums + "function f(): N.Q.X { return qy }").map { it.message }
        assert(messages == listOf("Type 'Q.Y' is not assignable to type 'Q.X'."))
    }

    @Test
    fun `an enum-member return inside a function TYPE renders qualified`() {
        val messages = diagnose(enums + "const g: () => K.A = () => kb").map { it.message }
        assert(messages == listOf("Type '() => K.B' is not assignable to type '() => K.A'."))
    }

    @Test
    fun `the declaration twin was already qualified and stays so`() {
        val messages = diagnose(enums + "const w: K.A = kb").map { it.message }
        assert(messages == listOf("Type 'K.B' is not assignable to type 'K.A'."))
    }

    @Test
    fun `negative control - a namespaced INTERFACE return annotation stays BARE`() {
        // Both references print 'I', never 'M.I' — the qualifier is an ENUM-MEMBER rule,
        // not a qualified-name rule. The diagnostic CODE at this position is a separate
        // pre-existing divergence (tsc reports TS2741 here) and is deliberately not
        // asserted; what this control owns is the NAME.
        val messages = diagnose(
            enums + "function f(): M.I { return { b: 1 } as any as { b: number } }",
        ).map { it.message }
        assert(messages.size == 1)
        assert(messages[0].endsWith("is not assignable to type 'I'."))
    }

    @Test
    fun `negative control - a ConstEnum-flagged NAMESPACE does not qualify its enum`() {
        // `SymbolFlags.Enum` is `RegularEnum or ConstEnum` and the binder CASCADES
        // `ConstEnum` onto a namespace whose instance state is `ConstEnumOnly`, so a flag
        // test alone reads `namespace Const` as an enum and renders `Const.E` for a WHOLE
        // enum. The corpus found it (`enumAssignmentCompat3`): pre-qualifying one side
        // disarms the rounds-745-749 same-string retry, and the row read
        // `Type 'E' … to type 'Const.E'` where both references read `Type 'First.E'`.
        val messages = diagnose(
            """
            namespace First { export enum E { a, b, c } }
            namespace Const { export const enum E { a, b, c } }
            declare let k: Const.E
            declare let abc: First.E
            k = abc
            """,
        ).map { it.message }
        assert(messages == listOf("Type 'First.E' is not assignable to type 'Const.E'."))
    }

    @Test
    fun `negative control - a WHOLE namespaced enum return annotation stays bare`() {
        val messages = diagnose(
            enums + "function f(): N.Q { return \"s\" as any as string }",
        ).map { it.message }
        assert(messages == listOf("Type 'string' is not assignable to type 'Q'."))
    }

    // ---------------------------------------------------------------------
    // (CHK.90) — the TS2367 CATEGORY rule applies getBaseTypesIfUnrelated
    // ---------------------------------------------------------------------

    @Test
    fun `an enum member compared to a string prints its ENUM`() {
        val messages = diagnose(enums + "const c = ka === \"z\"").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'string' have no overlap.",
            ),
        )
    }

    @Test
    fun `an enum member compared to a boolean prints its ENUM`() {
        val messages = diagnose(enums + "const c = ka === true").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'boolean' have no overlap.",
            ),
        )
    }

    @Test
    fun `a namespaced enum member compared to a string prints its enum unqualified`() {
        val messages = diagnose(enums + "const c = qy === \"z\"").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'Q' and 'string' have no overlap.",
            ),
        )
    }

    @Test
    fun `a STRING enum member compared to a number prints its enum`() {
        val messages = diagnose(enums + "const c = s2x === 3").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'S2' and 'number' have no overlap.",
            ),
        )
    }

    @Test
    fun `a WHOLE string enum compared to a number reports where it was silent`() {
        val messages = diagnose(enums + "const c = s2 === 3").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'S2' and 'number' have no overlap.",
            ),
        )
    }

    @Test
    fun `a WHOLE string enum compared to a boolean reports where it was silent`() {
        val messages = diagnose(enums + "const c = s2 === true").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'S2' and 'boolean' have no overlap.",
            ),
        )
    }

    @Test
    fun `negative control - the value rule keeps the ORIGINAL operands`() {
        // (CHK.88)'s rule fires here instead, and it must NOT take the bases: an enum's
        // base IS related to a numeric literal's, so tsc keeps both originals.
        val messages = diagnose(enums + "const c = ka === 1").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and '1' have no overlap.",
            ),
        )
    }

    @Test
    fun `negative control - a string enum against a literal it cannot hold keeps its literal`() {
        val messages = diagnose(enums + "const c = s2 === \"zz\"").map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'S2' and '\"zz\"' have no overlap.",
            ),
        )
    }

    @Test
    fun `negative control - a numeric enum against a number stays silent`() {
        val messages = diagnose(enums + "const c = k === num").map { it.message }
        assert(messages.isEmpty())
    }

    @Test
    fun `negative control - a string enum against a string stays silent`() {
        val messages = diagnose(enums + "declare const st: string\nconst c = s2 === st").map { it.message }
        assert(messages.isEmpty())
    }

    // ---------------------------------------------------------------------
    // (CHK.83) — a `readonly` array PARAMETER, and the chain sub-line
    // ---------------------------------------------------------------------

    @Test
    fun `a primitive argument against a readonly array parameter reports`() {
        val messages = diagnose(
            """
            declare const s: string
            declare function fRo(x: readonly string[]): void
            fRo(s)
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'string' is not assignable to parameter of type 'readonly string[]'.",
            ),
        )
    }

    @Test
    fun `a primitive-only UNION argument against a readonly array parameter reports`() {
        val messages = diagnose(
            """
            declare const u: "a" | "b"
            declare function fRo(x: readonly string[]): void
            fRo(u)
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'string' is not assignable to parameter of type 'readonly string[]'.",
            ),
        )
    }

    @Test
    fun `negative control - a matching readonly array argument stays silent`() {
        val messages = diagnose(
            """
            declare const xs: readonly string[]
            declare function fRo(x: readonly string[]): void
            fRo(xs)
            """,
        ).map { it.message }
        assert(messages.isEmpty())
    }

    @Test
    fun `a union-source chain sub-line takes the same generalization as its outer line`() {
        val d = diagnose(
            """
            declare const u: "a" | "b"
            interface Box { v: number }
            const e: Box = u
            """,
        )
        assert(d.size == 1)
        assert(d[0].message == "Type 'string' is not assignable to type 'Box'.")
        assert(d[0].messageChain == listOf("  Type 'string' is not assignable to type 'Box'."))
    }

    @Test
    fun `the chain sub-line generalizes at an ARGUMENT position too`() {
        val d = diagnose(
            """
            declare const u: "a" | "b"
            interface Box { v: number }
            declare function fBox(x: Box): void
            fBox(u)
            """,
        )
        assert(d.size == 1)
        assert(d[0].message == "Argument of type 'string' is not assignable to parameter of type 'Box'.")
        assert(d[0].messageChain == listOf("  Type 'string' is not assignable to type 'Box'."))
    }

    @Test
    fun `negative control - a never target keeps the constituent's literal`() {
        val d = diagnose(
            """
            declare const u: "a" | "b"
            declare function pn(x: never): void
            pn(u)
            """,
        )
        assert(d.size == 1)
        assert(d[0].message == "Argument of type '\"a\" | \"b\"' is not assignable to parameter of type 'never'.")
    }
}
