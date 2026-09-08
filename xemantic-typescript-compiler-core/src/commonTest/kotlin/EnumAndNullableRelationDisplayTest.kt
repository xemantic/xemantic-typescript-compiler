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
 * (CHK.92) the enum / nullable relation-error DISPLAY residues, and the missing
 * argument-position enum row.
 *
 * Every expectation here is TRANSCRIBED from pristine `typescript@6.0.3`, which agreed with
 * tsgo 7.0.2 on every measured row. The 8-profile grid CANNOT grade any of it — all 46 rows
 * of seven profiles and all 94 of `harness` are `Cannot find name …`, so not one of them
 * names a type — which is why every assertion below pins the message TEXT rather than a
 * code.
 *
 * The four parts:
 *
 *  - (a) the object-literal MEMBER display. There is no fresh-literal expression type here
 *    ((WIDEN.1)), so both per-property emitters printed the widened member type as the
 *    source AND `getWidenedLiteralType`'d the target, giving the self-contradictory
 *    `Type 'number' is not assignable to type 'number'` for `{ p: 6 }` against `p: 5`. tsc
 *    widens NEITHER: `reportRelationError` generalizes the source only when the target could
 *    not hold a singleton, and its object-literal widening is `isLiteralOfContextualType`,
 *    which is per FLAVOUR — the rule that makes `{ e: "z" }` against a NUMERIC enum read
 *    `'string'` while `{ e: 3 }` against the same target reads `'3'`, and the two swap for a
 *    STRING enum. THREE emitters carry this display, not the two the item named: the third
 *    is the nested / return-position leaf in `checkNestedObjLitPropTypes`.
 *
 *  - (b) the only MEANING part. `caasObjLitPerPropertyMismatch` lacked the enum-literal
 *    source leg its declaration twin has AND refused an enum target outright at its
 *    `bothSimple` gate, so all four of `fn({ e: "z" })` / `fn({ e: 3 })` / `fs({ e: "z" })` /
 *    `fs({ e: 3 })` were SILENT against rows both references report.
 *
 *  - (c) tsc's nullable-target strip, one rule at three heads. `isRelatedTo` rewrites a
 *    2-3 member target union with ONE non-nullable remainder to that remainder, but only for
 *    a `DefinitelyNonNullable` source and never when the remainder is itself a union — and
 *    it runs BEFORE the report, so the SOURCE generalization is decided against the STRIPPED
 *    target. We were wrong in BOTH directions: we printed the bare optional-parameter type
 *    always (right for `gU(1)`, wrong for every non-nullable-source-less case) and printed
 *    an EXPLICIT `| undefined` / `| null` in full where tsc strips it.
 *
 *  - (d) a ONE-member enum's declared type IS its member's regular type, so a relation error
 *    naming `Cmp.X` reads `'Cmp'`. The TS2367 operand display is the measured EXCEPTION and
 *    is deliberately NOT changed — see [oneMemberEnumKeepsTheMemberSpellingAtATs2367Operand].
 */
class EnumAndNullableRelationDisplayTest {

    private fun rows(src: String) = diagnose(src)

    private fun messages(src: String, code: Int) =
        rows(src).filter { it.code == code }.map { it.message }

    private val enums = """
        enum NE { A = 1, B = 2 }
        enum SE { A = "a", B = "b" }
        interface HN { e: NE }
        interface HS { e: SE }
        declare function fn(x: HN): void;
        declare function fs(x: HS): void;
    """.trimIndent()

    // ---------------------------------------------------------------- (a) literal targets

    @Test
    fun `an object literal member against a numeric literal target keeps both literals at a declaration`() {
        val m = messages(
            """
            interface LitT { p: 5 }
            const a: LitT = { p: 6 };
            """.trimIndent(),
            2322,
        )
        assert(m == listOf("Type '6' is not assignable to type '5'."))
    }

    @Test
    fun `an object literal member against a numeric literal target keeps both literals at an argument`() {
        val m = messages(
            """
            interface LitT { p: 5 }
            declare function fl(x: LitT): void;
            fl({ p: 6 });
            """.trimIndent(),
            2322,
        )
        assert(m == listOf("Type '6' is not assignable to type '5'."))
    }

    @Test
    fun `an object literal member against a string literal target keeps both literals`() {
        val m = messages(
            """
            interface LitS { q: "y" }
            const a: LitS = { q: "z" };
            """.trimIndent(),
            2322,
        )
        assert(m == listOf("""Type '"z"' is not assignable to type '"y"'."""))
    }

    @Test
    fun `a string literal member widens against a numeric enum target at a declaration`() {
        val m = messages("$enums\nconst d: HN = { e: \"z\" };", 2322)
        assert(m == listOf("Type 'string' is not assignable to type 'NE'."))
    }

    @Test
    fun `a numeric literal member keeps its literal against a numeric enum target at a declaration`() {
        val m = messages("$enums\nconst d: HN = { e: 3 };", 2322)
        assert(m == listOf("Type '3' is not assignable to type 'NE'."))
    }

    @Test
    fun `a string literal member keeps its literal against a string enum target at a declaration`() {
        val m = messages("$enums\nconst d: HS = { e: \"z\" };", 2322)
        assert(m == listOf("""Type '"z"' is not assignable to type 'SE'."""))
    }

    @Test
    fun `a numeric literal member widens against a string enum target at a declaration`() {
        val m = messages("$enums\nconst d: HS = { e: 3 };", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'SE'."))
    }

    // -------------------------------------------------------- (b) the ARGUMENT enum rows

    @Test
    fun `a string literal member against a numeric enum target reports at an argument`() {
        val m = messages("$enums\nfn({ e: \"z\" });", 2322)
        assert(m == listOf("Type 'string' is not assignable to type 'NE'."))
    }

    @Test
    fun `a numeric literal member against a numeric enum target reports at an argument`() {
        val m = messages("$enums\nfn({ e: 3 });", 2322)
        assert(m == listOf("Type '3' is not assignable to type 'NE'."))
    }

    @Test
    fun `a string literal member against a string enum target reports at an argument`() {
        val m = messages("$enums\nfs({ e: \"z\" });", 2322)
        assert(m == listOf("""Type '"z"' is not assignable to type 'SE'."""))
    }

    @Test
    fun `a numeric literal member against a string enum target reports at an argument`() {
        val m = messages("$enums\nfs({ e: 3 });", 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'SE'."))
    }

    @Test
    fun `negative control - a matching enum member argument stays silent`() {
        val m = messages("$enums\nfn({ e: NE.A });\nfs({ e: SE.A });", 2322)
        assert(m.isEmpty())
    }

    // ------------------------------------------------- (c) the nullable-target strip

    private val nullableDecls = """
        enum E2 { A = 1, B = 2 }
        enum E1 { X = 7 }
        declare function gU(s?: string): void;
        declare function gB(b?: boolean): void;
        declare function gN(n?: number | string): void;
        declare function gX(s: string | undefined): void;
        declare function gY(s: string | null): void;
        declare function gZ(s: string | null | undefined): void;
        declare function gC(c?: E1): void;
        declare const uu: unknown;
        declare const nn: null;
        declare const un: number | boolean;
        declare const st: string;
    """.trimIndent()

    private fun ts2345(tail: String) = messages("$nullableDecls\n$tail", 2345)

    @Test
    fun `an optional string parameter strips its undefined for a non-nullable source`() {
        assert(
            ts2345("gU(1);") ==
                listOf("Argument of type 'number' is not assignable to parameter of type 'string'."),
        )
    }

    /**
     * CORRECTED by (CHK.113)(b), which is the residue (CHK.92) recorded beside itself and
     * then pinned here in its UNFIXED form — the third such countdown pin in four rounds.
     * The TARGET half was and stays right; only the SOURCE was wrong, and the two references
     * agree with the corrected expectation verbatim:
     *
     * ```
     * tsgo 7.0.2      t.ts(3,4): error TS2345: Argument of type '1' is not assignable to parameter of type 'boolean | undefined'.
     * pristine 6.0.3  t.ts(3,4): error TS2345: Argument of type '1' is not assignable to parameter of type 'boolean | undefined'.
     * ```
     *
     * The mechanism is the same one this test's own name states: `boolean` is itself a
     * union, so the strip declines and the `| undefined` survives — and a SURVIVING nullish
     * member is a top-level singleton to tsc's `typeCouldHaveTopLevelSingletonTypes`, which
     * is exactly the condition under which the source is NOT generalized either.
     */
    @Test
    fun `an optional boolean parameter keeps its undefined and the source literal`() {
        assert(
            ts2345("gB(1);") ==
                listOf("Argument of type '1' is not assignable to parameter of type 'boolean | undefined'."),
        )
    }

    /**
     * CORRECTED by (CHK.113)(b) — see the sibling above. Measured on both references:
     *
     * ```
     * tsgo 7.0.2      t.ts(5,4): error TS2345: Argument of type 'true' is not assignable to parameter of type 'string | number | undefined'.
     * pristine 6.0.3  t.ts(5,4): error TS2345: Argument of type 'true' is not assignable to parameter of type 'string | number | undefined'.
     * ```
     *
     * Here the strip declines for the OTHER reason — two non-nullable members remain — and
     * the source keeps its literal for the same one.
     */
    @Test
    fun `an optional union parameter keeps its undefined and the source literal`() {
        assert(
            ts2345("gN(true);") ==
                listOf(
                    "Argument of type 'true' is not assignable to parameter of type " +
                        "'string | number | undefined'.",
                ),
        )
    }

    @Test
    fun `an explicit string or undefined parameter strips its undefined`() {
        assert(
            ts2345("gX(1);") ==
                listOf("Argument of type 'number' is not assignable to parameter of type 'string'."),
        )
    }

    @Test
    fun `an explicit string or null parameter strips its null`() {
        assert(
            ts2345("gY(1);") ==
                listOf("Argument of type 'number' is not assignable to parameter of type 'string'."),
        )
    }

    @Test
    fun `a three member nullable parameter strips both nullish members`() {
        assert(
            ts2345("gZ(1);") ==
                listOf("Argument of type 'number' is not assignable to parameter of type 'string'."),
        )
    }

    @Test
    fun `an optional one member enum parameter strips its undefined`() {
        assert(
            ts2345("gC(st);") ==
                listOf("Argument of type 'string' is not assignable to parameter of type 'E1'."),
        )
    }

    @Test
    fun `an unknown source keeps the optional parameter's undefined`() {
        assert(
            ts2345("gU(uu);") ==
                listOf("Argument of type 'unknown' is not assignable to parameter of type 'string | undefined'."),
        )
    }

    @Test
    fun `a null source keeps the optional parameter's undefined`() {
        assert(
            ts2345("gU(nn);") ==
                listOf("Argument of type 'null' is not assignable to parameter of type 'string | undefined'."),
        )
    }

    @Test
    fun `a union source keeps the optional parameter's undefined`() {
        assert(
            ts2345("gU(un);") ==
                listOf(
                    "Argument of type 'number | boolean' is not assignable to parameter of type " +
                        "'string | undefined'.",
                ),
        )
    }

    @Test
    fun `a type parameter source keeps the optional parameter's undefined`() {
        assert(
            ts2345("export function tp<T>(t: T) { gU(t); }") ==
                listOf("Argument of type 'T' is not assignable to parameter of type 'string | undefined'."),
        )
    }

    @Test
    fun `a variable declaration strips an explicit undefined from its annotation`() {
        assert(
            messages("const v: string | undefined = 1;", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    @Test
    fun `a variable declaration strips an explicit null from its annotation`() {
        assert(
            messages("const v: string | null = 1;", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    /**
     * CORRECTED by (CHK.113)(b) — the declaration sibling of the two argument pins above,
     * and the same correction. Both references:
     *
     * ```
     * tsgo 7.0.2      t.ts(1,7): error TS2322: Type '1' is not assignable to type 'boolean | undefined'.
     * pristine 6.0.3  t.ts(1,7): error TS2322: Type '1' is not assignable to type 'boolean | undefined'.
     * ```
     */
    @Test
    fun `a variable declaration keeps a boolean or undefined annotation whole and the source literal`() {
        assert(
            messages("const v: boolean | undefined = 1;", 2322) ==
                listOf("Type '1' is not assignable to type 'boolean | undefined'."),
        )
    }

    @Test
    fun `an optional string property strips its undefined`() {
        assert(
            messages("interface OP { s?: string }\nconst p: OP = { s: 1 };", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    @Test
    fun `an optional boolean property keeps its undefined`() {
        assert(
            messages("interface OP { b?: boolean }\nconst p: OP = { b: 1 };", 2322) ==
                listOf("Type 'number' is not assignable to type 'boolean | undefined'."),
        )
    }

    // ------------------------------------------------------ (d) the one-member enum

    private val oneMember = """
        enum Cmp { X = 0 }
        enum Two { P = 0, Q = 1 }
        declare const st: string;
    """.trimIndent()

    @Test
    fun `a one member enum's member renders as the enum at a variable declaration`() {
        assert(
            messages("$oneMember\nconst a: Cmp.X = st;", 2322) ==
                listOf("Type 'string' is not assignable to type 'Cmp'."),
        )
    }

    @Test
    fun `a two member enum's member keeps its qualified name at a variable declaration`() {
        assert(
            messages("$oneMember\nconst a: Two.P = st;", 2322) ==
                listOf("Type 'string' is not assignable to type 'Two.P'."),
        )
    }

    @Test
    fun `a one member enum's member renders as the enum at an argument`() {
        assert(
            messages("$oneMember\ndeclare function f1(x: Cmp.X): void;\nf1(st);", 2345) ==
                listOf("Argument of type 'string' is not assignable to parameter of type 'Cmp'."),
        )
    }

    @Test
    fun `a two member enum's member keeps its qualified name at an argument`() {
        assert(
            messages("$oneMember\ndeclare function f2(x: Two.P): void;\nf2(st);", 2345) ==
                listOf("Argument of type 'string' is not assignable to parameter of type 'Two.P'."),
        )
    }

    @Test
    fun `a one member enum's member renders as the enum at a return`() {
        assert(
            messages("$oneMember\nexport function r(): Cmp.X { return st; }", 2322) ==
                listOf("Type 'string' is not assignable to type 'Cmp'."),
        )
    }

    @Test
    fun `a one member enum's member renders as the enum at an object literal property`() {
        assert(
            messages("$oneMember\ninterface P1 { m: Cmp.X }\nconst p: P1 = { m: st };", 2322) ==
                listOf("Type 'string' is not assignable to type 'Cmp'."),
        )
    }

    /**
     * (CHK.92)(d) THE MEASURED EXCEPTION, AND A RECORDED REFUSAL.
     *
     * tsc's TS2367 operand display is decided by literal FRESHNESS, not by the enum's member
     * count: measured on pristine 6.0.3 and tsgo 7.0.2, `Cmp.X === 5` and
     * `const cx = Cmp.X; cx === 5` both keep `'Cmp.X'` for a ONE-member enum, while
     * `let lx = Cmp.X; lx === 5` and an annotated `declare const av: Cmp.X` read `'Cmp'`.
     * This checker mints no fresh enum-member type, so the four cases share ONE type and the
     * split is not expressible here. The qualified spelling is therefore kept at that
     * emitter UNCONDITIONALLY, which is what it already printed — the two widened rows stay
     * divergent and are recorded rather than closed by a syntactic proxy that would be a
     * second copy of a rule the type system does not carry.
     */
    @Test
    fun `oneMemberEnumKeepsTheMemberSpellingAtATs2367Operand`() {
        assert(
            messages("$oneMember\nif (Cmp.X === 5) {}", 2367) ==
                listOf(
                    "This comparison appears to be unintentional because the types 'Cmp.X' and '5' " +
                        "have no overlap.",
                ),
        )
    }

    // ================================================================ (CHK.114)
    //
    // Three residues (CHK.113) confirmed on the BEFORE binary, i.e. pre-existing. Every
    // expectation below is TRANSCRIBED from pristine `typescript@6.0.3`, which agreed with
    // tsgo 7.0.2 on all 28 measured rows of the fixture these pins are cut from
    // (`build/bench/chk114-sub/g`). The grid grades none of it, for the reason this class's
    // header gives.

    // -------------------------------------------------- (CHK.114)(a) the RETURN head

    @Test
    fun `a return against an explicitly nullable union strips the undefined from its target`() {
        assert(
            messages("function q(): string | undefined { return 1 }", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    @Test
    fun `a return against an explicitly nullable union strips the null from its target`() {
        assert(
            messages("function q(): string | null { return 1 }", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    @Test
    fun `a return against a three member nullable union strips both nullish members`() {
        assert(
            messages("function q(): string | null | undefined { return 1 }", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    /**
     * NEGATIVE CONTROL for (a): tsc's strip declines when the remainder is itself union-like,
     * and `boolean` IS the union `true | false` there — so this row must keep its
     * `| undefined`, and with the nullish member still SHOWING the source keeps its literal.
     * Both references print exactly this.
     */
    @Test
    fun `a return against a boolean nullable union keeps its undefined and its source literal`() {
        assert(
            messages("function q(): boolean | undefined { return 1 }", 2322) ==
                listOf("Type '1' is not assignable to type 'boolean | undefined'."),
        )
    }

    /** NEGATIVE CONTROL for (a): a union with no nullish member is not the strip's business. */
    @Test
    fun `a return against a non nullable union is unchanged`() {
        assert(
            messages("function q(): string | number { return true }", 2322) ==
                listOf("Type 'boolean' is not assignable to type 'string | number'."),
        )
    }

    /** NEGATIVE CONTROL for (a): a remainder of TWO members is not a candidate. */
    @Test
    fun `a return against a nullable union with a two member remainder keeps its undefined`() {
        assert(
            messages("function q(): string | number | undefined { return true }", 2322) ==
                listOf("Type 'true' is not assignable to type 'string | number | undefined'."),
        )
    }

    /**
     * (CHK.114) stage 0. tsc reports the ORIGINAL target whenever it carries an `aliasSymbol`
     * (checker.ts:22825 and :22878), so a nullable ALIAS is never stripped — without this,
     * wiring the return head would have turned the accidentally-correct `'OptAlias'` into
     * `'string'`.
     *
     * ONLY THE TARGET IS PINNED, DELIBERATELY. Both references print `Type '1'` for the
     * source here and we print `Type 'number'`: with the target unstripped its `undefined` is
     * a unit type, so tsc's `typeCouldHaveTopLevelSingletonTypes` keeps the written literal,
     * and (CHK.113)(b)'s recovery is gated on `nullishTargetSurvivesStrip`, which is a
     * question about the TYPE and cannot see that an alias blocked the strip. That residue is
     * recorded here rather than pinned — a pin asserting today's `'number'` would be a
     * countdown that the round closing it has to invert.
     */
    @Test
    fun `a return against a named nullable alias keeps the alias name as its target`() {
        val m = messages("type OA = string | undefined\nfunction q(): OA { return 1 }", 2322)
        val single = m.singleOrNull() ?: ""
        assert(single.endsWith("is not assignable to type 'OA'."))
    }

    // ------------------------------------ (CHK.114)(b) the OPTIONAL CLASS PROPERTY head

    @Test
    fun `an optional class property shows the undefined its effective type carries`() {
        assert(
            messages("class Ca { p?: boolean = 1 }", 2322) ==
                listOf("Type '1' is not assignable to type 'boolean | undefined'."),
        )
    }

    @Test
    fun `an optional class property whose remainder strips reads the bare remainder`() {
        assert(
            messages("class Cb { s?: string = 1 }", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    @Test
    fun `an optional class property with a two member remainder keeps its undefined`() {
        assert(
            messages("class Cc { t?: string | number = true }", 2322) ==
                listOf("Type 'true' is not assignable to type 'string | number | undefined'."),
        )
    }

    @Test
    fun `a static an ecmascript private and a private optional class property all add undefined`() {
        val statik = messages("class Cf { static st?: boolean = 1 }", 2322)
        val hashed = messages("class Cg { #h?: boolean = 1 }", 2322)
        val privat = messages("class Ch { private pv?: boolean = 1 }", 2322)
        val expected = listOf("Type '1' is not assignable to type 'boolean | undefined'.")
        assert(statik == expected)
        assert(hashed == expected)
        assert(privat == expected)
    }

    /** NEGATIVE CONTROL for (b): a REQUIRED property carries no `undefined` to show. */
    @Test
    fun `a required class property does not add undefined to its target`() {
        assert(
            messages("class Ce { req: boolean = 1 }", 2322) ==
                listOf("Type 'number' is not assignable to type 'boolean'."),
        )
    }

    /** NEGATIVE CONTROL for (b): a required non union target is untouched in both halves. */
    @Test
    fun `a required string class property is unchanged`() {
        assert(
            messages("class Cd { w: string = 1 }", 2322) ==
                listOf("Type 'number' is not assignable to type 'string'."),
        )
    }

    /**
     * NEGATIVE CONTROL for (CHK.114) stage 0's SECOND half, and the one shape that separates
     * "the target as WRITTEN is a name" from "the annotation is a name": `z?: RI2` spells a
     * TypeReference, but the union is SYNTHESIZED by the optional-add and so carries no
     * `aliasSymbol` in tsc. Both references read `'RI2'`; an alias guard keyed on the
     * annotation alone reads `'RI2 | undefined'`.
     */
    @Test
    fun `an optional class property naming an interface strips the synthesized undefined`() {
        assert(
            messages("interface RI2 { a: number; b: number }\nclass Ci { z?: RI2 = {} }", 2739) ==
                listOf("Type '{}' is missing the following properties from type 'RI2': a, b"),
        )
    }

    /**
     * Stage 0 at the class-property head. Target only, for the reason
     * the return-position alias pin above gives.
     */
    @Test
    fun `an optional class property typed by a nullable alias keeps the alias name`() {
        val m = messages("type OA = string | undefined\nclass Cj { p?: OA = 1 }", 2322)
        val single = m.singleOrNull() ?: ""
        assert(single.endsWith("is not assignable to type 'OA'."))
    }

    // ------------------------- (CHK.114)(c) the ONE-member enum collapse on the SOURCE

    private val arity = """
        namespace Ns { export enum QOne { A = 7 } export enum QTwo { A = 1, B = 2 } }
        enum NOne { A }
        enum SOne { A = "a" }
        enum NTwo { A, B }
        enum STwo { A = "a", B = "b" }
    """.trimIndent()

    @Test
    fun `a one member enum's member renders as the enum as a SOURCE at a declaration`() {
        assert(
            messages("$arity\nconst z3: STwo = NOne.A", 2322) ==
                listOf("Type 'NOne' is not assignable to type 'STwo'."),
        )
    }

    @Test
    fun `a one member enum's member renders as the enum as a SOURCE at an argument`() {
        assert(
            messages("$arity\ndeclare function fq(x: STwo): void;\nfq(NOne.A)", 2345) ==
                listOf("Argument of type 'NOne' is not assignable to parameter of type 'STwo'."),
        )
    }

    @Test
    fun `a one member enum's member renders as the enum as a SOURCE at a return`() {
        assert(
            messages("$arity\nfunction fr(): STwo { return NOne.A }", 2322) ==
                listOf("Type 'NOne' is not assignable to type 'STwo'."),
        )
    }

    @Test
    fun `a one member enum's member renders as the enum as a SOURCE at an object literal member`() {
        assert(
            messages("$arity\ninterface Pb { m: STwo }\nconst z8: Pb = { m: NOne.A }", 2322) ==
                listOf("Type 'NOne' is not assignable to type 'STwo'."),
        )
    }

    @Test
    fun `a one member enum's member renders as the enum as a SOURCE at a class property`() {
        assert(
            messages("$arity\nclass Zc { c: STwo = NOne.A }", 2322) ==
                listOf("Type 'NOne' is not assignable to type 'STwo'."),
        )
    }

    /**
     * NEGATIVE CONTROL for (c), AND THE ROW THAT REFUTES THE ITEM'S FRAMING. (CHK.114) states
     * the collapse as "a CROSS-FLAVOUR enum member source does not collapse"; measured on both
     * references, a cross-flavour pair of TWO-member enums keeps its qualified spelling in
     * BOTH directions. The axis is the SOURCE enum's member COUNT — a one-member enum's
     * declared type IS its member's type in tsc — which is (CHK.92)(d)'s fact on the other
     * side of the relation. The rows that looked cross-flavour had a one-member source.
     */
    @Test
    fun `a two member enum's member keeps its qualified spelling as a SOURCE in both directions`() {
        assert(
            messages("$arity\nconst z5: STwo = NTwo.A", 2322) ==
                listOf("Type 'NTwo.A' is not assignable to type 'STwo'."),
        )
        assert(
            messages("$arity\nconst z6: NTwo = STwo.A", 2322) ==
                listOf("Type 'STwo.A' is not assignable to type 'NTwo'."),
        )
    }

    /**
     * NEGATIVE CONTROL for (c): the TARGET's arity decides the TARGET's display and says
     * nothing about the source's. A two-member source against a ONE-member target keeps its
     * member while the target collapses — both halves in one row.
     */
    @Test
    fun `a two member source against a one member enum target keeps the member and collapses the target`() {
        assert(
            messages("$arity\nconst z7: SOne = NTwo.A", 2322) ==
                listOf("Type 'NTwo.A' is not assignable to type 'SOne'."),
        )
    }

    @Test
    fun `a one member enum in a namespace collapses to its bare name against an enum target`() {
        assert(
            messages("$arity\nconst z2: STwo = Ns.QOne.A", 2322) ==
                listOf("Type 'QOne' is not assignable to type 'STwo'."),
        )
    }

    /**
     * (c) DOES NOT BREAK THE (PARITY.3) NAMESPACE QUALIFICATION: against a GENERALIZING
     * target the source is widened to the parent enum first, so the qualification still owns
     * the display and the namespace path survives. Both references print `'Ns.QOne'` for this
     * non-module fixture (a MODULE one would read `import("…").Ns.QOne` — the (P18.14)-refused
     * module-prefix residue, which is why the fixture is deliberately script-scoped).
     *
     * IT IS NOT AN ORDERING CONTROL, AND SAYING SO WOULD BE A CLAIM THE MEASUREMENT REFUSES.
     * Arm a7 reverses the two legs of `relationErrorSourceRender` and reads **0 RED** over all
     * 59 pins, and round 813's whole-output diff over the eight (CHK.114) fixtures (~120 rows,
     * covering every position, both enum flavours, both arities and the namespaced case) is
     * BYTE-IDENTICAL between the two binaries. The order is REDUNDANT by construction — the
     * qualification fires only when the source has already been widened past `EnumMember`, so
     * the collapse can never see a type it would answer for — and it is recorded as
     * measured-redundant rather than claimed as coverage.
     */
    @Test
    fun `a namespaced one member enum still qualifies against a generalizing target`() {
        assert(
            messages("$arity\nconst z1: string = Ns.QOne.A", 2322) ==
                listOf("Type 'Ns.QOne' is not assignable to type 'string'."),
        )
    }
}
