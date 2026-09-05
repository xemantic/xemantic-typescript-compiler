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
 * (CHK.83) — the two VALUE rules between an enum member and a literal, in both
 * directions, every row reproduced against tsgo 7.0.2 AND pristine `typescript@6.0.3`
 * before any code was written. The two references agree on every row asserted here.
 *
 * RULE 1 — an enum MEMBER source against a string/number LITERAL target relates only by
 * VALUE (tsc's `isSimpleTypeRelatedTo`: `s & NumberLiteral && s & EnumLiteral && t &
 * NumberLiteral && !(t & EnumLiteral) && s.value === t.value`, and the string twin). Ours
 * accepted every member against every literal of its flavour, because `NumberLike`
 * includes `NumberLiteral` and the leg tested the flavour alone — so `const l1: 5 = em`
 * was silent at the declaration, the argument, the assignment and the return while the
 * WHOLE enum against `5` reported. A member whose value tsc does not know (an ambient
 * member with no initializer, a computed member) relates to NO literal.
 *
 * RULE 2 — a string/number LITERAL against an enum-flavored target is judged by the
 * literal's VALUE at every position: round 745 had it at the declaration for a NUMERIC
 * literal and an enum-flagged target only, so `fEnum(3)` at an ARGUMENT arrived as
 * `number` (which relates to every numeric enum) and was silent, `const d: E | undefined
 * = 3` saw no enum on the union, and a string literal against a string enum printed the
 * widened `'string'` where both references print the literal.
 *
 * Every positive pin has a negative control beside it — the same shape with a value the
 * enum DOES hold — because both defects are silences and a pin that asserts a row
 * appears cannot tell a value rule from an unconditional rejection.
 */
class EnumLiteralValueRelationTest {

    private val enums = """
        enum E { A = 1, B = 2 }
        enum S { X = "x", Y = "y" }
        enum H { A = 1, B = "b" }
        declare enum Amb { P, Q }
        enum Cmp { X = "ab".length }
        declare const em: E.A
        declare const ew: E
        declare const sx: S.X
        declare const hb: H.B
        declare const ap: Amb.P
        declare const cx: Cmp.X
        declare function fEnum(e: E): void
        declare function fS(s: S): void
        declare function fMem(e: E.A): void
        declare function fOpt(e: E | undefined): void
        declare function fRest(...e: E[]): void
        declare function take5(x: 5): void
        declare function take1(x: 1): void
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [enums] (line 0 is the directive); `character` is tsc's 1-based column. */
    private val rowLine = enums.count { it == '\n' } + 1

    // ---------------------------------------------------------------------
    // RULE 1 — an enum MEMBER against a LITERAL target relates by value
    // ---------------------------------------------------------------------

    @Test
    fun `an enum member against a number literal of another value is rejected at the declaration`() {
        val d = diagnose(enums + "const l1: 5 = em")
        assert(d.map { it.message } == listOf("Type 'E.A' is not assignable to type '5'."))
        assert(d[0].code == 2322)
        assert(d[0].line == rowLine)
        assert(d[0].character == 7)
    }

    @Test
    fun `negative control - an enum member against the number literal of its own value is silent`() {
        assert(diagnose(enums + "const l2: 1 = em").isEmpty())
    }

    @Test
    fun `an enum member against a literal union holding no member value is rejected`() {
        val messages = diagnose(enums + "const l5: 2 | 3 = em").map { it.message }
        assert(messages == listOf("Type 'E.A' is not assignable to type '2 | 3'."))
    }

    @Test
    fun `negative control - an enum member against a literal union holding its value is silent`() {
        assert(diagnose(enums + "const l6: 1 | 2 = em").isEmpty())
    }

    @Test
    fun `an enum member against a number literal parameter of another value is rejected at the argument`() {
        val d = diagnose(enums + "take5(em)")
        assert(d.map { it.message } == listOf("Argument of type 'E.A' is not assignable to parameter of type '5'."))
        assert(d[0].code == 2345)
        assert(d[0].line == rowLine)
        assert(d[0].character == 7)
    }

    @Test
    fun `negative control - an enum member against the literal parameter of its own value is silent`() {
        assert(diagnose(enums + "take1(em)").isEmpty())
    }

    @Test
    fun `an enum member against a number literal target is rejected at the assignment`() {
        val messages = diagnose(enums + "let la: 5 = 0 as any\nla = em").map { it.message }
        assert(messages == listOf("Type 'E.A' is not assignable to type '5'."))
    }

    @Test
    fun `an enum member against a number literal return type is rejected at the return`() {
        val messages = diagnose(enums + "function ret5(): 5 { return em }").map { it.message }
        assert(messages == listOf("Type 'E.A' is not assignable to type '5'."))
    }

    @Test
    fun `a string enum member against a string literal of another value is rejected`() {
        val messages = diagnose(enums + "const s1: \"z\" = sx").map { it.message }
        assert(messages == listOf("Type 'S.X' is not assignable to type '\"z\"'."))
    }

    @Test
    fun `negative control - a string enum member against the string literal of its own value is silent`() {
        assert(diagnose(enums + "const s2: \"x\" = sx").isEmpty())
    }

    @Test
    fun `a string member of a mixed enum is judged by its own value`() {
        val messages = diagnose(enums + "const hz: \"z\" = hb").map { it.message }
        assert(messages == listOf("Type 'H.B' is not assignable to type '\"z\"'."))
        assert(diagnose(enums + "const hzb: \"b\" = hb").isEmpty())
    }

    @Test
    fun `an ambient member with no initializer is opaque and relates to no literal`() {
        // tsc gives `declare enum Amb { P }`'s `P` NO value, so `Amb.P` is beside no
        // literal at all; we auto-number it to 0 for the Transformer, which is exactly
        // why the value must be read through the tsc-value view and not `enumValues` —
        // read the latter and this row is silent.
        val messages = diagnose(enums + "const a0: 0 = ap").map { it.message }
        assert(messages == listOf("Type 'Amb.P' is not assignable to type '0'."))
    }

    @Test
    fun `a computed member relates to no literal`() {
        // The VERDICT is pinned and the display is not: both references print the
        // single-member enum's NAME here (`Type 'Cmp'`, tsc's "the parent's declared type
        // IS this member" rule) and we still print `Cmp.X` — a recorded residue.
        val d = diagnose(enums + "const c1: 5 = cx")
        assert(d.map { it.code } == listOf(2322))
        assert(d[0].message.endsWith("is not assignable to type '5'."))
    }

    @Test
    fun `negative control - an enum member still relates to the wide primitive`() {
        assert(diagnose(enums + "const n1: number = em").isEmpty())
        assert(diagnose(enums + "const nn: 1 | number = em").isEmpty())
        assert(diagnose(enums + "const ns: string = sx").isEmpty())
    }

    @Test
    fun `negative control - the whole enum against a literal reported before and still does`() {
        val messages = diagnose(enums + "const l3: 5 = ew").map { it.message }
        assert(messages == listOf("Type 'E' is not assignable to type '5'."))
    }

    // ---------------------------------------------------------------------
    // RULE 2 — a LITERAL against an enum-flavored target is judged by value everywhere
    // ---------------------------------------------------------------------

    @Test
    fun `a number literal the enum cannot hold is rejected at the argument`() {
        val d = diagnose(enums + "fEnum(3)")
        assert(d.map { it.message } == listOf("Argument of type '3' is not assignable to parameter of type 'E'."))
        assert(d[0].code == 2345)
        assert(d[0].line == rowLine)
        assert(d[0].character == 7)
    }

    @Test
    fun `negative control - a number literal the enum holds and a wide number are silent at the argument`() {
        assert(diagnose(enums + "fEnum(1)").isEmpty())
        assert(diagnose(enums + "fEnum(1 + 2)").isEmpty())
        assert(diagnose(enums + "declare const n: number\nfEnum(n)").isEmpty())
    }

    @Test
    fun `a zero literal the enum cannot hold is rejected at the argument`() {
        val messages = diagnose(enums + "fEnum(0)").map { it.message }
        assert(messages == listOf("Argument of type '0' is not assignable to parameter of type 'E'."))
    }

    @Test
    fun `a string literal against a string enum parameter prints the literal`() {
        // The verdict was already right (no string is assignable to a string enum);
        // the DISPLAY read the widened `'string'` where both references print `'"z"'`.
        val messages = diagnose(enums + "fS(\"z\")").map { it.message }
        assert(messages == listOf("Argument of type '\"z\"' is not assignable to parameter of type 'S'."))
    }

    @Test
    fun `a number literal against an enum MEMBER parameter is judged by value`() {
        val messages = diagnose(enums + "fMem(3)").map { it.message }
        assert(messages == listOf("Argument of type '3' is not assignable to parameter of type 'E.A'."))
        assert(diagnose(enums + "fMem(1)").isEmpty())
    }

    @Test
    fun `a number literal against a union parameter carrying the enum is judged by value`() {
        val messages = diagnose(enums + "fOpt(3)").map { it.message }
        assert(messages == listOf("Argument of type '3' is not assignable to parameter of type 'E | undefined'."))
        assert(diagnose(enums + "fOpt(1)").isEmpty())
    }

    @Test
    fun `a number literal against a rest parameter's enum element is judged by value`() {
        val messages = diagnose(enums + "fRest(3)").map { it.message }
        assert(messages == listOf("Argument of type '3' is not assignable to parameter of type 'E'."))
        assert(diagnose(enums + "fRest(1, 2)").isEmpty())
    }

    @Test
    fun `an overload set whose enum overload cannot hold the literal reports pristine's chain`() {
        val d = diagnose(
            enums + "declare function fOvl(e: E): void\ndeclare function fOvl(e: string): void\nfOvl(3)",
        )
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].message == "No overload matches this call.")
        assert(
            d[0].messageChain == listOf(
                "  Overload 1 of 2, '(e: E): void', gave the following error.",
                "    Argument of type '3' is not assignable to parameter of type 'E'.",
                "  Overload 2 of 2, '(e: string): void', gave the following error.",
                "    Argument of type 'number' is not assignable to parameter of type 'string'.",
            ),
        )
    }

    @Test
    fun `negative control - a non-null-asserted undefined is not a literal and stays silent`() {
        // The firewall the round-745 helper records: `literalTypeOfExpression` answers
        // `undefined` for `undefined!`, and reading THAT as the source rejects it against
        // every enum. Only a string/number literal may pass.
        assert(diagnose(enums + "fEnum(undefined!)").isEmpty())
    }

    @Test
    fun `a number literal against a union declaration carrying the enum is judged by value`() {
        val messages = diagnose(enums + "const du: E | undefined = 3").map { it.message }
        assert(messages == listOf("Type '3' is not assignable to type 'E | undefined'."))
        assert(diagnose(enums + "const du2: E | undefined = 1").isEmpty())
    }

    @Test
    fun `an enum union display appends null then undefined last`() {
        // tsc's `formatUnionTypes` skips the nullable constituents and appends them
        // after everything else, `null` before `undefined`, whatever their ids.
        val messages = diagnose(enums + "const q2: E | null | undefined = 3").map { it.message }
        assert(messages == listOf("Type '3' is not assignable to type 'E | null | undefined'."))
    }

    @Test
    fun `a number literal against an enum return type is judged by value at both return spellings`() {
        val block = diagnose(enums + "function r3(): E { return 3 }").map { it.message }
        assert(block == listOf("Type '3' is not assignable to type 'E'."))
        val arrow = diagnose(enums + "const r4 = (): E => 3").map { it.message }
        assert(arrow == listOf("Type '3' is not assignable to type 'E'."))
        assert(diagnose(enums + "function r1(): E { return 1 }").isEmpty())
        assert(diagnose(enums + "const r2 = (): E => 1").isEmpty())
    }

    @Test
    fun `a string literal against a string enum declaration prints the literal`() {
        val messages = diagnose(enums + "const sd: S = \"z\"").map { it.message }
        assert(messages == listOf("Type '\"z\"' is not assignable to type 'S'."))
    }

    @Test
    fun `a number literal member of a fresh object literal against an enum member type is judged by value`() {
        val d = diagnose(enums + "const o3: { e: E } = { e: 3 }")
        assert(d.map { it.message } == listOf("Type '3' is not assignable to type 'E'."))
        assert(d[0].code == 2322)
        assert(d[0].character == 24)
        assert(diagnose(enums + "const o1: { e: E } = { e: 1 }").isEmpty())
    }

    @Test
    fun `the same object literal member rule holds one level down and at a return`() {
        val nested = diagnose(enums + "const on: { a: { e: E } } = { a: { e: 3 } }").map { it.message }
        assert(nested == listOf("Type '3' is not assignable to type 'E'."))
        val returned = diagnose(enums + "function ro(): { e: E } { return { e: 3 } }").map { it.message }
        assert(returned == listOf("Type '3' is not assignable to type 'E'."))
        assert(diagnose(enums + "const on1: { a: { e: E } } = { a: { e: 1 } }").isEmpty())
    }
}
