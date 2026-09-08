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
 * (CHK.113) the two residues (CHK.92) measured beside its own parts.
 *
 * Every expectation here is TRANSCRIBED from pristine `typescript@6.0.3`, which agreed with
 * tsgo 7.0.2 on every measured row of both parts. The 8-profile grid can grade NEITHER —
 * the MEANING part adds rows on shapes tsc's own sources do not contain, and every row of
 * the eight profiles is a `Cannot find name …` that names no type at all ((PARITY.1)) — so
 * the assertions below pin the message TEXT and, for (a), a NEGATIVE control per enum shape
 * that must stay silent.
 *
 *  - **(a) MEANING.** A `number` source was silently ACCEPTED against a STRING enum target.
 *    tsc models an all-literal enum as the union of its members' literal types and
 *    `isSimpleTypeRelatedTo` lets a `Number`/`NumberLiteral` source reach only a NUMERIC
 *    enum-literal constituent, so a pure string enum has nothing for a number to relate to.
 *    Ours accepted at the one leg that answers this question, whose value-awareness
 *    (`numericLiteralFitsEnum`, (REL.1)(c)) covers a numeric LITERAL and answers `true` for
 *    every non-literal source — so the wide `number` walked through whatever the enum's
 *    flavour. The gate is POSITIVE EVIDENCE OF STRING-NESS ONLY, read through
 *    `enumMemberEntries` (the view that answers `null` for tsc's OPAQUE member), because the
 *    change can only ADD a diagnostic: an ambient value-less enum, a computed enum, an empty
 *    enum and a MIXED enum all keep today's acceptance, and all four are measured.
 *
 *  - **(b) FORM.** `undefined` and `null` are UNIT types to tsc's
 *    `typeCouldHaveTopLevelSingletonTypes` (`TypeFlags.Unit` contains `Nullable`), so a
 *    target union that still SHOWS a nullish member keeps the SOURCE's literal —
 *    `gB(1)` against `b?: boolean` reads `Type '1'`, where the bare `boolean` target reads
 *    `Type 'number'`. "Still shows one" is the whole rule: tsc's strip runs BEFORE the
 *    report, so `s?: string` is `string` by then and generalizes as before. Two mechanisms
 *    were needed — `ts2322KeepsSourceLiteral` had to STOP generalizing, and the source had
 *    to be re-read from its NODE, because `getTypeOfExpression` answers the base primitive
 *    for a literal node ((WIDEN.1)) so nothing downstream had a literal left to keep. The
 *    node re-read is DISPLAY ONLY and guarded by `literalWidensTo`, which is what keeps the
 *    `x!` shape that produced two earlier false-positive incidents on its current answer.
 */
class NumericSourceEnumTargetTest {

    private fun messages(src: String, code: Int) =
        diagnose(src).filter { it.code == code }.map { it.message }

    private val strEnums = """
        enum SOne { A = "a" }
        enum STwo { A = "a", B = "b" }
        enum NOne { A = 1 }
        enum Mixed { A = 1, B = "b" }
        declare const num: number;
    """.trimIndent()

    // ------------------------------------------------------- (a) MEANING - the added rows

    @Test
    fun `a number source is refused by a one-member string enum at a declaration`() {
        val m = messages("$strEnums\nconst m: SOne = num;", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'SOne'."))
    }

    @Test
    fun `a number source is refused by a two-member string enum at a declaration`() {
        val m = messages("$strEnums\nconst m: STwo = num;", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'STwo'."))
    }

    @Test
    fun `a number source is refused by a string enum at an argument`() {
        val m = messages("$strEnums\ndeclare function take(x: SOne): void;\ntake(num);", 2345)
        assert(m == listOf("Argument of type 'number' is not assignable to parameter of type 'SOne'."))
    }

    @Test
    fun `a number source is refused by a string enum at a return`() {
        val m = messages("$strEnums\nfunction r(): STwo { return num; }", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'STwo'."))
    }

    @Test
    fun `a number source is refused by a string enum at an assignment`() {
        val m = messages("$strEnums\nlet a: SOne = SOne.A;\na = num;", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'SOne'."))
    }

    @Test
    fun `a number source is refused by a string enum at a class property`() {
        val m = messages("$strEnums\nclass C { p: SOne = num; }", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'SOne'."))
    }

    @Test
    fun `a number source is refused by a union whose only object member is a string enum`() {
        val m = messages("$strEnums\nconst u: SOne | boolean = num;", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'boolean | SOne'."))
    }

    @Test
    fun `a number source is refused by an ambient string enum`() {
        val m = messages("declare enum AmbS { P = \"p\" }\ndeclare const num: number;\nconst m: AmbS = num;", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'AmbS'."))
    }

    @Test
    fun `a number source is refused by a const string enum`() {
        val m = messages("const enum CS { A = \"a\" }\ndeclare const num: number;\nconst m: CS = num;", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'CS'."))
    }

    @Test
    fun `a number source is refused by a string enum merged across two blocks`() {
        val m = messages(
            """
            enum Merged { A = "a" }
            enum Merged { B = "b" }
            declare const num: number;
            const m: Merged = num;
            """.trimIndent(),
            2322,
        )
        assert(m == listOf("Type 'number' is not assignable to type 'Merged'."))
    }

    // ------------------------------------- (a) NEGATIVE controls - every shape that stays

    @Test
    fun `negative control - a number source is still accepted by a numeric enum`() {
        assert(messages("$strEnums\nconst m: NOne = num;", 2322).isEmpty())
    }

    @Test
    fun `negative control - a number source is still accepted by a mixed enum`() {
        assert(messages("$strEnums\nconst m: Mixed = num;", 2322).isEmpty())
    }

    @Test
    fun `negative control - a number source is still accepted by a const numeric enum`() {
        assert(messages("const enum CN { A = 1 }\ndeclare const num: number;\nconst m: CN = num;", 2322).isEmpty())
    }

    @Test
    fun `negative control - a number source is still accepted by an ambient enum with no initializers`() {
        assert(messages("declare enum Amb { P, Q }\ndeclare const num: number;\nconst m: Amb = num;", 2322).isEmpty())
    }

    @Test
    fun `negative control - a number source is still accepted by an enum with an unfoldable member value`() {
        val src = """
            enum Computed { A = "x".length ? "y" : "z" }
            declare const num: number;
            const m: Computed = num;
        """.trimIndent()
        assert(messages(src, 2322).isEmpty())
    }

    @Test
    fun `negative control - a number source is still accepted by an empty enum`() {
        assert(messages("enum Empty { }\ndeclare const num: number;\nconst m: Empty = num;", 2322).isEmpty())
    }

    @Test
    fun `negative control - a number source is still accepted by an enum merged from a string and a numeric block`() {
        val src = """
            enum M2 { A = "a" }
            enum M2 { B = 2 }
            declare const num: number;
            const m: M2 = num;
        """.trimIndent()
        assert(messages(src, 2322).isEmpty())
    }

    @Test
    fun `negative control - a string enum still accepts its own member`() {
        assert(messages("$strEnums\nconst m: STwo = STwo.B;", 2322).isEmpty())
    }

    @Test
    fun `negative control - a numeric literal in range is still accepted by a numeric enum`() {
        assert(messages("$strEnums\nconst m: NOne = 1;", 2322).isEmpty())
    }

    @Test
    fun `negative control - a numeric literal out of range is still refused by a numeric enum`() {
        val m = messages("enum NTwo { A = 1, B = 2 }\nconst m: NTwo = 99;", 2322)
        assert(m == listOf("Type '99' is not assignable to type 'NTwo'."))
    }

    // ------------------------------- (b) FORM - a surviving nullish member keeps a literal

    private val nullish = """
        enum E2 { A = 1, B = 2 }
        enum E1 { A = 1 }
        declare function gB(b?: boolean): void;
        declare function gS(s?: string): void;
        declare function uB(b: boolean | undefined): void;
        declare function nB(b: boolean | null): void;
        declare const maybeNum: number | undefined;
        declare const sU: "a" | "b";
    """.trimIndent()

    @Test
    fun `a literal argument keeps its literal against an optional boolean parameter`() {
        val m = messages("$nullish\ngB(1);", 2345)
        assert(m == listOf("Argument of type '1' is not assignable to parameter of type 'boolean | undefined'."))
    }

    @Test
    fun `a literal argument keeps its literal against an explicit boolean or undefined parameter`() {
        val m = messages("$nullish\nuB(1);", 2345)
        assert(m == listOf("Argument of type '1' is not assignable to parameter of type 'boolean | undefined'."))
    }

    @Test
    fun `a literal argument keeps its literal against a boolean or null parameter`() {
        val m = messages("$nullish\nnB(1);", 2345)
        assert(m == listOf("Argument of type '1' is not assignable to parameter of type 'boolean | null'."))
    }

    @Test
    fun `a literal argument keeps its literal against a three member nullable union parameter`() {
        val src = "declare function p3(x: string | number | undefined): void;\np3(true);"
        val m = messages(src, 2345)
        assert(m == listOf("Argument of type 'true' is not assignable to parameter of type 'string | number | undefined'."))
    }

    @Test
    fun `a literal initializer keeps its literal against a boolean or undefined declaration`() {
        val m = messages("$nullish\nconst v: boolean | undefined = 1;", 2322)
        assert(m == listOf("Type '1' is not assignable to type 'boolean | undefined'."))
    }

    @Test
    fun `a literal initializer keeps its literal against a boolean or null declaration`() {
        val m = messages("$nullish\nconst v: boolean | null = 1;", 2322)
        assert(m == listOf("Type '1' is not assignable to type 'boolean | null'."))
    }

    @Test
    fun `a literal return keeps its literal against a boolean or undefined return type`() {
        val m = messages("$nullish\nfunction r(): boolean | undefined { return 1; }", 2322)
        assert(m == listOf("Type '1' is not assignable to type 'boolean | undefined'."))
    }

    @Test
    fun `a string literal keeps its literal against a two member enum or undefined`() {
        val m = messages("$nullish\nconst v: E2 | undefined = \"s\";", 2322)
        assert(m == listOf("""Type '"s"' is not assignable to type 'E2 | undefined'."""))
    }

    @Test
    fun `a negated numeric literal keeps its literal against an optional boolean parameter`() {
        val m = messages("$nullish\ngB(-1);", 2345)
        assert(m == listOf("Argument of type '-1' is not assignable to parameter of type 'boolean | undefined'."))
    }

    @Test
    fun `a no-substitution template literal keeps its literal against an optional boolean parameter`() {
        val m = messages("$nullish\ngB(`x`);", 2345)
        assert(m == listOf("""Argument of type '"x"' is not assignable to parameter of type 'boolean | undefined'."""))
    }

    // -------------------------- (b) NEGATIVE controls - every shape that must still widen

    @Test
    fun `negative control - an optional string parameter still generalizes the source`() {
        val m = messages("$nullish\ngS(1);", 2345)
        assert(m == listOf("Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `negative control - a bare boolean parameter still generalizes the source`() {
        val m = messages("declare function hB(b: boolean): void;\nhB(1);", 2345)
        assert(m == listOf("Argument of type 'number' is not assignable to parameter of type 'boolean'."))
    }

    @Test
    fun `negative control - a bare number parameter still generalizes a boolean literal`() {
        val m = messages("declare function hN(n: number): void;\nhN(true);", 2345)
        assert(m == listOf("Argument of type 'boolean' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `negative control - a bare boolean declaration still generalizes the source`() {
        val m = messages("const d: boolean = 1;", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'boolean'."))
    }

    @Test
    fun `negative control - a non-null asserted source is not re-read from its node`() {
        val m = messages("$nullish\ngB(maybeNum!);", 2345)
        assert(m == listOf("Argument of type 'number' is not assignable to parameter of type 'boolean | undefined'."))
    }

    @Test
    fun `negative control - a literal-union reference source is unchanged`() {
        val m = messages("$nullish\ngB(sU);", 2345)
        assert(m == listOf("""Argument of type '"a" | "b"' is not assignable to parameter of type 'boolean | undefined'."""))
    }

    @Test
    fun `negative control - a one-member enum optional parameter still strips its undefined`() {
        val m = messages("$nullish\ndeclare function gE1(e?: E1): void;\ngE1(\"z\");", 2345)
        assert(m == listOf("""Argument of type '"z"' is not assignable to parameter of type 'E1'."""))
    }
}
