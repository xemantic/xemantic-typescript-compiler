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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 459: overload selection by an ENUM-MEMBER literal parameter. An
 * enum-member param annotation (`kind: SyntaxKind.NamedImports`) resolves to
 * `anyType` (enum members are not modeled as literal types), so an enum-member
 * ARG used to match EVERY overload and the FIRST won — tsc's own
 * `parseNamedImportsOrExports(SyntaxKind.NamedExports)` (parser.ts) resolved
 * to the NamedImports overload's return type → FP TS2322 on the assignment to
 * a `NamedExportBindings | undefined` local.
 *
 * `resolveCallOverload` now compares the param annotation's canonical
 * enum-member key set (the round-411 discriminant key space) against the arg's
 * key AST-side: a resolvable non-member is a mismatch, so the correct overload
 * is selected. Both-unresolvable keeps the prior first-match behavior.
 */
class EnumMemberLiteralOverloadSelectionTest {

    private val prelude = """
        enum Kind { A, B }
        interface AShape { aOnly: string; }
        interface BShape { bOnly: number; }

        declare function pick(kind: Kind.A): AShape;
        declare function pick(kind: Kind.B): BShape;

    """.trimIndent()

    @Test
    fun `enum-member arg selects the MATCHING overload - no TS2322`() {
        diagnose(prelude + """
            function f() {
                let b: BShape | undefined;
                b = pick(Kind.B);
                return b;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the selected overload's return is still checked`() {
        // pick(Kind.A) → AShape, which is NOT assignable to BShape.
        diagnose(prelude + """
            function g() {
                let b: BShape | undefined;
                b = pick(Kind.A);
                return b;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `first overload still wins when the arg is not an enum member`() {
        // A non-enum-member arg keeps the prior behavior (no key → no filtering);
        // `pick(k)` with a bare Kind param stays permissive (first arity match).
        diagnose(prelude + """
            function h(k: Kind.A) {
                let a: AShape | undefined;
                a = pick(k);
                return a;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    // --- Round 743: the B136 concrete-overload SWAP re-picked an overload the
    // selection above had already rejected. `resolveCallOverload` correctly chose the
    // GENERIC `<TKind extends ModifierSK>` overload; its return carries an un-inferred
    // TP, so `getReturnTypeOfCallExpression`'s swap looked for a non-generic overload
    // with a concrete return — and took the FIRST one it found without ever asking
    // whether that overload accepts the arguments. tsc's
    // `factory.createToken(SyntaxKind.ReadonlyKeyword)` therefore typed as
    // `SuperExpression`. The swap now requires the candidate to accept the args.
    //
    // The reduction needs the GENERIC overload — two same-arity NON-generic overloads
    // distinguished only by an enum-member parameter already selected correctly (the
    // round-459 gate above), which is why two earlier reductions of this bug came back
    // clean.

    private val genericPrelude = """
        enum SK { SuperKeyword = 1, AbstractKeyword = 2, ReadonlyKeyword = 3 }
        type ModifierSK = SK.AbstractKeyword | SK.ReadonlyKeyword
        interface SuperExpr { kind: SK.SuperKeyword; sup: string }
        interface ModifierTok<T extends ModifierSK> { kind: T; mod: string }
        declare function takeMod(m: ModifierTok<SK.ReadonlyKeyword>): void
        declare function takeSuper(s: SuperExpr): void

    """.trimIndent()

    @Test
    fun `a generic overload selected over an enum-member one is not swapped back to it`() {
        diagnose(genericPrelude + """
            declare function createToken(token: SK.SuperKeyword): SuperExpr;
            declare function createToken<TKind extends ModifierSK>(token: TKind): ModifierTok<TKind>;
            takeMod(createToken(SK.ReadonlyKeyword));
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the generic overload's instantiated return is still checked`() {
        // The mirror of the pin above: before round 743 this was SILENT, because the
        // call really did type as `SuperExpr`. A pin that only asserted silence on the
        // correct target would keep passing on the broken build.
        diagnose(genericPrelude + """
            declare function createToken(token: SK.SuperKeyword): SuperExpr;
            declare function createToken<TKind extends ModifierSK>(token: TKind): ModifierTok<TKind>;
            takeSuper(createToken(SK.ReadonlyKeyword));
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the same pair distinguished by arity already selected correctly`() {
        // Passes on both sides of the fix by construction — an arity-distinguished pair
        // never reaches the type-based loop, so this measures nothing about the swap and
        // is here only to pin that the fix did not disturb the path that already worked.
        diagnose(genericPrelude + """
            declare function createToken2(token: SK.SuperKeyword, extra: string): SuperExpr;
            declare function createToken2<TKind extends ModifierSK>(token: TKind): ModifierTok<TKind>;
            takeMod(createToken2(SK.ReadonlyKeyword));
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    // --- Round 744, (REL.1)(b): when BOTH candidates are GENERIC, round 481's
    // lenience ("an un-inferred bare `Type.TypeParam` parameter matches any argument",
    // which exists because tsc INFERS it) never looked at the type parameter's
    // CONSTRAINT — so two overloads distinguished ONLY by their constraints always
    // picked the first. tsc infers `TKind` and then checks it against `extends …`.
    //
    // The check is only SHARP once an enum-member union discriminates, which is why it
    // lands with (b) and not with round 743, where it would have been inert: before the
    // disjointness rule every member related to every other and `ModifierSK` accepted a
    // `SK.SuperKeyword` argument anyway.

    // The returns are deliberately NON-generic. With `SuperTok<T>` / `ModTok<T>` the
    // selection is still corrected, but every direction goes silent — a parameterized
    // interface compared against another parameterized interface relates leniently here
    // — so a pin written that way would assert silence on a build that never picks
    // right. All four directions below are checked, and two of them must ERROR.
    private val bothGenericPrelude = """
        enum SK { SuperKeyword = 1, AbstractKeyword = 2, ReadonlyKeyword = 3 }
        type ModifierSK = SK.AbstractKeyword | SK.ReadonlyKeyword
        type SuperSK = SK.SuperKeyword
        interface SuperTok { sup: string }
        interface ModTok { mod: string }
        declare function wantMod(m: ModTok): void
        declare function wantSuper(s: SuperTok): void
        declare function make<T extends SuperSK>(token: T): SuperTok;
        declare function make<T extends ModifierSK>(token: T): ModTok;

    """.trimIndent()

    @Test
    fun `two generic overloads are distinguished by their type parameter constraints`() {
        diagnose(bothGenericPrelude + """
            wantMod(make(SK.ReadonlyKeyword));
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the first generic overload still wins for its own constraint`() {
        diagnose(bothGenericPrelude + """
            wantSuper(make(SK.SuperKeyword));
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the constraint-selected return is still checked`() {
        // The mirror direction, so a build that still picks the FIRST overload for a
        // ModifierSK argument fails here instead of passing a silence assertion.
        diagnose(bothGenericPrelude + """
            wantSuper(make(SK.ReadonlyKeyword));
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the first overload's return is still checked too`() {
        diagnose(bothGenericPrelude + """
            wantMod(make(SK.SuperKeyword));
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }
}
