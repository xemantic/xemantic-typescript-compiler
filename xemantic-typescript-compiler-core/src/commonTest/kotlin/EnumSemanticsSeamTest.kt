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
 * (INV.0) step 7: the pins that gate the ENUM seam — the 40 declarations moving out of
 * `Checker.kt` into `EnumSemantics.kt` VERBATIM.
 *
 * The family is already densely covered by BEHAVIOUR tests (`CrossEnumValueIdentityTest`,
 * `EnumLiteralValueRelationTest`, `NumericSourceEnumTargetTest`,
 * `EnumValueDomainRelationTest`, `EnumComparisonDisplayTest`,
 * `EnumAndNullableRelationDisplayTest`, `EnumModuleQualifiedDisplayTest`,
 * `EnumOverrideSignatureDisplayTest`), so this file deliberately does NOT re-pin what
 * those already assert. It pins the TERMS those tests bundle, aggregate or reach only
 * through an absence assertion — every pin here asserts a full message VALUE, and every
 * pin has an ablation that reddens IT AND NO OTHER PIN IN THIS FILE.
 *
 * GROUND TRUTH. Every expectation below was read off tsgo 7.0.2 AND pristine
 * `typescript@6.0.3` over the same fixture, and both references agree byte for byte with
 * this compiler on all of them. They were re-taken against the binary that already
 * carries the extracted collaborator.
 *
 * THE ABLATIONS, one per pin, each inside the moved family:
 *
 *  1. `an enum nested in a declare namespace is ambient and its members are opaque` —
 *     delete `enumDeclIsAmbient`'s
 *     `if (cur is ModuleDeclaration && ModifierFlag.Declare in cur.modifiers) return true`.
 *     The `declare`-namespace enum then reads NON-ambient: its member auto-numbers to `0`
 *     and stops being rejected, and the enum grows a domain that rejects `7`. It is the
 *     only pin here whose enum carries no `declare` MODIFIER of its own, so no other pin
 *     enters that loop at all.
 *
 *  2. `an ambient const enum keeps the domain its ambient plain twin does not have` —
 *     drop `&& !isConst` from `enumValueDomainIsComplete`'s
 *     `if (member.initializer == null && ambient && !isConst) return@getOrPut false`.
 *     The ambient CONST enum then reads incomplete and stops rejecting `7`. That widening
 *     can only make MORE enums incomplete, so pin 1's ambient row — already accepted —
 *     cannot move.
 *
 *  3. `an enums extra member is a one-way failure` — swap `enumTypesRelation`'s member
 *     loop to iterate `targetMembers` instead of `sourceMembers`. Both halves of this pin
 *     invert at once: the reported direction goes silent and the silent one reports. No
 *     other pin here compares two enums of DIFFERENT member counts.
 *
 *  4. `a const enum relates only to itself and reports without a chain` — drop
 *     `enumTypesRelation`'s
 *     `if (!src.flags.hasAny(SymbolFlags.RegularEnum) || !tgt.flags.hasAny(...)) ...
 *     EnumRelFailure.Plain` guard. `Const.E` and `First.E` are value-identical, so the
 *     value walk then relates them and the row disappears. It is the only pin here whose
 *     relation involves a `const` enum.
 *
 *  5. `a union covering every member of one enum collapses to that enum` — change
 *     `enumUnionTargetDisplay`'s `seen.containsAll(all)` to `seen.isNotEmpty()`. The
 *     PARTIAL run then collapses too and the second row reads `boolean | Bar`. It is the
 *     only pin here whose target is a union of enum members.
 *
 *  6. `two operands of different enums are widened to their enums in the comparison` —
 *     make `enumComparisonNoOverlapDisplays` return
 *     `typeToString(leftType) to typeToString(rightType)` unconditionally, i.e. delete the
 *     `basesRelated` branch. The different-enum rows then print `'K.A' and 'J.X'` instead
 *     of `'K' and 'J'`. Pin 7 cannot see it: its rows are a silence and a same-enum pair,
 *     and a same-enum pair takes the surviving branch either way.
 *
 *  7. `a whole enum always overlaps its own member` — replace `enumAtomsOverlap`'s tail
 *     (`if (aMemberEnum != null && bMemberEnum != null) return ...; return true`) with an
 *     unconditional `return enumMemberTypesAreSameMember(a, b)`. `k === ka` then grows a
 *     TS2367 this compiler and both references refuse to emit. Pin 6 keeps its rows: both
 *     of its operands are members, so it takes the same branch before and after.
 *
 *  8. `a module scoped enums member is the same member across files` — reduce
 *     `enumMemberTypesAreSameMember` to `return sourceMember === targetMember`. Post
 *     INV.3(d) a module-scoped enum has no global instance to canonicalize to, so its
 *     per-file `Symbol` instances key different member types and the ACCEPTED row grows a
 *     TS2322 declaring `SK.Second` disjoint from itself. It is the only multi-file pin
 *     here, and the only one whose enum is module-scoped.
 */
class EnumSemanticsSeamTest {

    private fun messages(source: String): List<String> =
        diagnose(source).map { it.message }

    // ---------------------------------------------------------------------
    // enumDeclIsAmbient — the three ways into an ambient context
    // ---------------------------------------------------------------------

    /**
     * `enumDeclIsAmbient` has three legs and the existing suite only ever reaches the
     * first: `EnumLiteralValueRelationTest` and `CrossEnumValueIdentityTest` both spell
     * the enum's OWN `declare` modifier. This pin drives the ENCLOSING
     * `declare namespace` leg instead, and drives it through BOTH consumers in one
     * fixture — `enumMemberEntries`' `opaque` term (a member with no value relates to no
     * literal) and `enumValueDomainIsComplete` (an incomplete domain accepts any number).
     * `EnumValueDomainRelationTest` reaches the second consumer through this leg as an
     * ABSENCE assertion; the member direction and the values are new here.
     *
     * The `Plain` half is the control that keeps the pin from passing for the trivial
     * reason: the very same declarations outside an ambient context auto-number, so the
     * member IS `0` and the enum's domain DOES reject `7`.
     */
    @Test
    fun `an enum nested in a declare namespace is ambient and its members are opaque`() {
        assert(
            messages(
                """
                declare namespace Ambient { enum Op { P, Q } }
                namespace Plain { export enum Cl { P, Q } }
                declare const ap: Ambient.Op.P
                declare const pp: Plain.Cl.P
                const z1: 0 = ap
                const z2: 0 = pp
                const n1: Ambient.Op = 7
                const n2: Plain.Cl = 7
                """,
            ) == listOf(
                "Type 'Op.P' is not assignable to type '0'.",
                "Type '7' is not assignable to type 'Cl'.",
            ),
        )
    }

    /**
     * The `!isConst` term of the opacity rule, isolated. tsc declines to auto-number an
     * initializer-less member of an ambient NON-const enum, and does auto-number one of
     * an ambient CONST enum — so the two enums below are spelled identically apart from
     * that keyword and answer opposite verdicts.
     *
     * `EnumValueDomainRelationTest` pins this as a COUNT (`== 2`) over a fixture that
     * also varies the presence of initializers; here the initializers are held fixed so
     * the keyword is the only variable, and the messages are asserted.
     */
    @Test
    fun `an ambient const enum keeps the domain its ambient plain twin does not have`() {
        assert(
            messages(
                """
                declare enum AmbPlain { X, Y }
                declare const enum AmbConst { X, Y }
                const a: AmbPlain = 7
                const b: AmbConst = 7
                """,
            ) == listOf("Type '7' is not assignable to type 'AmbConst'."),
        )
    }

    // ---------------------------------------------------------------------
    // enumTypesRelation — the terms the existing relation pins do not separate
    // ---------------------------------------------------------------------

    /**
     * `enumTypesRelation` is DIRECTIONAL — every SOURCE member must be present in the
     * TARGET — so a target with extra members accepts while a source with extra members
     * does not. `CrossEnumValueIdentityTest` asserts the failing direction and a
     * differently-shaped negative control; the two directions of ONE pair, which is what
     * the loop's choice of collection decides, are pinned here.
     *
     * The chain line is asserted with it because `enumRelationChainLine`'s `Missing` arm
     * names the target DISPLAY, i.e. the string the top-level message chose — the two
     * cannot be pinned apart.
     */
    @Test
    fun `an enums extra member is a one-way failure`() {
        val d = diagnose(
            """
            namespace First { export enum E { a, b, c } }
            namespace Abcd { export enum E { a, b, c, d } }
            declare var abc: First.E
            declare var abcd: Abcd.E
            abc = abcd
            abcd = abc
            """,
        ).filter { it.code == 2322 }
        assert(d.map { it.message } == listOf("Type 'Abcd.E' is not assignable to type 'First.E'."))
        assert(d[0].messageChain == listOf("  Property 'd' is missing in type 'First.E'."))
    }

    /**
     * tsc requires BOTH sides to be a `RegularEnum`, so a `const` enum relates only to
     * itself however its values compare — and `Const.E` below is value-identical to
     * `First.E`, which is what makes the pin measure the nominal guard rather than the
     * value walk.
     *
     * Two more things ride on the same row and are asserted because a wrong extraction
     * separates them: the displays are still QUALIFIED (`enumCollisionQualifiedDisplays`
     * fires on the equal bare names, whatever the failure kind), and the chain is EMPTY
     * (`EnumRelFailure.Plain` is the one failure tsc reports without an elaboration).
     * `CrossEnumValueIdentityTest`'s no-chain pin uses a differently-NAMED pair, which
     * never reaches the const guard and never qualifies.
     */
    @Test
    fun `a const enum relates only to itself and reports without a chain`() {
        val d = diagnose(
            """
            namespace First { export enum E { a, b, c } }
            namespace Const { export const enum E { a, b, c } }
            declare var abc: First.E
            declare var kk: Const.E
            abc = kk
            kk = kk
            """,
        ).filter { it.code == 2322 }
        assert(d.map { it.message } == listOf("Type 'Const.E' is not assignable to type 'First.E'."))
        assert(d[0].messageChain.isEmpty())
    }

    // ---------------------------------------------------------------------
    // enumUnionTargetDisplay — tsc's formatUnionTypes
    // ---------------------------------------------------------------------

    /**
     * Two of `enumUnionTargetDisplay`'s three ingredients, which no pin currently reaches
     * (`EnumLiteralValueRelationTest` pins only its nullish tail): a CONSECUTIVE run
     * covering every member of one enum collapses to the bare enum name, and the
     * non-enum constituents come FIRST whatever the annotation spelled.
     *
     * `Foo` has two members and both are named, so it collapses; `Bar` has three and two
     * are named, so it does not — the pair is what separates the full-cover test from a
     * mere "some member of this enum is present" test, and `boolean` leading both rows is
     * what pins the split.
     */
    @Test
    fun `a union covering every member of one enum collapses to that enum`() {
        assert(
            messages(
                """
                namespace X { export enum Foo { A = 0, B = 1 } }
                namespace X { export enum Bar { A = 0, B = 1, C = 2 } }
                declare const s: string
                const t1: X.Foo.A | X.Foo.B | boolean = s
                const t2: X.Bar.A | X.Bar.B | boolean = s
                """,
            ) == listOf(
                "Type 'string' is not assignable to type 'boolean | Foo'.",
                "Type 'string' is not assignable to type 'boolean | Bar.A | Bar.B'.",
            ),
        )
    }

    // ---------------------------------------------------------------------
    // the TS2367 comparison rule — CHK.86
    // ---------------------------------------------------------------------

    private val enumPair = """
        enum K { A = 0, B = 1 }
        enum J { X = 0, Y = 1 }
        declare const k: K
        declare const ka: K.A
        declare const kb: K.B
        declare const jx: J.X
    """.trimIndent() + "\n"

    /**
     * tsc's `getBaseTypesIfUnrelated`, which is the whole of (CHK.86)'s display rule:
     * widen both operands to their enums and print the widened pair only if it is STILL
     * unrelated. Two members of ONE enum widen to the same enum, which IS related, so the
     * members survive; two atoms of DIFFERENT enums widen to two unrelated enums, so the
     * enums are printed — and a MEMBER against a WHOLE enum takes the same widening.
     *
     * The `'K.A' and 'K.B'` row is pinned elsewhere (`EnumMemberLocalFlowTest`) and is
     * kept here as the branch control: without it the ablation below is indistinguishable
     * from a rule that never widens anything. The two `'K' and 'J'` rows — both operands
     * enum-flavoured and the widening actually taken — are pinned by nothing today.
     */
    @Test
    fun `two operands of different enums are widened to their enums in the comparison`() {
        val d = diagnose(enumPair + "ka === kb\nka === jx\nk === jx").filter { it.code == 2367 }
        assert(
            d.map { it.message } == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and 'K.B' have no overlap.",
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
            ),
        )
    }

    /**
     * `enumAtomsOverlap`'s last two lines: a same-member test is asked ONLY when both
     * atoms are members, and anything pairing a whole enum with one of its own members
     * always overlaps. Reading the same-member verdict unconditionally makes `k === ka` a
     * false TS2367 — a comparison every real narrowing guard is written as.
     *
     * The pin is a full-list assertion rather than an absence one precisely so it catches
     * that ADDED row, and the member pair beside it is the positive control that keeps
     * the list from being satisfied by a rule that emits nothing at all.
     */
    @Test
    fun `a whole enum always overlaps its own member`() {
        assert(
            diagnose(enumPair + "k === ka\nka === kb").filter { it.code == 2367 }.map { it.message } == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and 'K.B' have no overlap.",
            ),
        )
    }

    // ---------------------------------------------------------------------
    // enumMemberTypesAreSameMember — deliberately NOT an identity test
    // ---------------------------------------------------------------------

    /**
     * The trap `enumMemberTypesAreSameMember`'s own KDoc records: post-INV.3(d) a
     * MODULE-scoped enum has no global instance for `canonicalEnumSymbol` to reach, so
     * its per-file `Symbol` instances key DIFFERENT member types for the same member and
     * an identity verdict declares a member disjoint from itself. Every enum in tsc's own
     * sources is module-scoped, `SyntaxKind` included.
     *
     * A single-file fixture cannot express it — one file, one instance — so this is the
     * only multi-file pin in the file. The ACCEPTED row is what discriminates, and it is
     * asserted as an exact one-element list so that an added row fails it; the rejected
     * row beside it is the control that keeps the rule from being satisfied by a blanket
     * accept.
     */
    @Test
    fun `a module scoped enums member is the same member across files`() {
        assert(
            messages(
                """
                // @Filename: a.ts
                export enum SK { First = 0, Second = 1 }

                // @Filename: b.ts
                import { SK } from "./a"
                export declare const s: SK.Second
                export const same: SK.Second = s
                export const other: SK.First = s
                """,
            ) == listOf("Type 'SK.Second' is not assignable to type 'SK.First'."),
        )
    }
}
