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

    @Test
    fun `an optional boolean parameter keeps its undefined because boolean is itself a union`() {
        assert(
            ts2345("gB(1);") ==
                listOf("Argument of type 'number' is not assignable to parameter of type 'boolean | undefined'."),
        )
    }

    @Test
    fun `an optional union parameter keeps its undefined`() {
        assert(
            ts2345("gN(true);") ==
                listOf(
                    "Argument of type 'boolean' is not assignable to parameter of type " +
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

    @Test
    fun `a variable declaration keeps a boolean or undefined annotation whole`() {
        assert(
            messages("const v: boolean | undefined = 1;", 2322) ==
                listOf("Type 'number' is not assignable to type 'boolean | undefined'."),
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
}
