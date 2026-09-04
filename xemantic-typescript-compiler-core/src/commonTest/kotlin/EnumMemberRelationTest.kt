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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (REL.1) Enum-member types do not discriminate in the relation AT ALL.
 *
 * ROOT CAUSE, located round 740: an enum-MEMBER type annotation resolves to
 * **`anyType`**. `getTypeFromTypeReference` reduces `SK.A` to the bare member name
 * `"A"`, resolves it to a `SymbolFlags.EnumMember` symbol, and
 * `getDeclaredTypeOfSymbolWorker` has no branch for that flag — it falls through to
 * `else -> anyType`. So `kind: SK.A` and `kind: SK.B` are literally the SAME `Type`
 * instance, and every enum-member comparison is vacuously true in both directions.
 * `TypeFlags.EnumLiteral` exists but is **set nowhere**, so the widening rule already
 * written for it is dead code.
 *
 * CLOSED over rounds 741/742 — every expectation below was `@Ignore`d when written and
 * every one is now ON, which was the acceptance criterion the item states: (a) minted
 * the member type, (b0) made an enum-member ACCESS EXPRESSION resolve to it instead of
 * `anyType`, and (b) let the relation reject a distinct sibling. The `@Ignore`s are
 * GONE rather than the tests, so a regression here fails loudly instead of skipping.
 *
 * The order of the file is the order of the argument: the four original expectations,
 * then the positive controls that are the FP firewall (round 740's throwaway probe
 * over-rejected exactly these shapes), then the base-primitive legs (a) had to state
 * explicitly once nothing was vacuously `any` any more, then (b0)'s value-position
 * pins. See `PLAN-PHASE-5.md` (REL.1) for the decomposition.
 */
class EnumMemberRelationTest {

    @Test
    // (REL.1)(b) round 742: ON — the relation now rejects a distinct sibling member.
    fun `a sibling enum member is not assignable to another member of the same enum`() {
        diagnose(
            """
            declare enum SK { A, B }
            declare const b: SK.B
            const k: SK.A = b
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    // (REL.1)(b) round 742: ON.
    fun `sibling node types differing only in the kind discriminant are not assignable`() {
        diagnose(
            """
            declare enum SK { A, B }
            interface Ident { readonly kind: SK.A; text: string }
            interface Priv { readonly kind: SK.B; text: string }
            declare const id: Ident
            const p: Priv = id
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    // (REL.1)(b) round 742: ON — the reverse direction, which is what proved the old
    // behaviour was MUTUAL assignability rather than a one-way leniency.
    fun `the reverse direction is rejected too - sibling node types are not mutually assignable`() {
        diagnose(
            """
            declare enum SK { A, B }
            interface Ident { readonly kind: SK.A; text: string }
            interface Priv { readonly kind: SK.B; text: string }
            declare const pv: Priv
            const q: Ident = pv
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    // (REL.1)(b) round 742: ON — needed (b0) too: the RHS is an ACCESS expression.
    fun `a plain enum member is not assignable to a sibling member`() {
        diagnose(
            """
            enum E { X, Y }
            const e: E.X = E.Y
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    // --- positive controls: these pass TODAY and must keep passing after (REL.1) ---
    // They are the FP firewall for the fix: the round-740 probe's single corpus
    // failure was an over-rejection of exactly this shape.

    @Test
    fun `negative control - a member widens to its own enum type`() {
        diagnose(
            """
            declare enum SK { A, B }
            const w: SK = SK.A
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a member is assignable to itself`() {
        diagnose(
            """
            declare enum SK { A, B }
            declare const a: SK.A
            const m: SK.A = a
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - number is assignable to a numeric enum member type`() {
        diagnose(
            """
            enum E { A, B, C }
            declare const n: number
            let a: E.A = 0
            a = n
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a string enum member is assignable to string`() {
        diagnose(
            """
            enum Ext { Dts = ".d.ts", Dmts = ".d.mts" }
            declare function take(s: string): void
            take(Ext.Dts)
            """,
        ) should { have(none { it.code == 2345 }) }
    }

    // --- (REL.1)(a) round 741: the base-primitive legs, both legs both directions.
    // Step (a) mints a distinct type per enum member; everything that used to be
    // vacuously true because the member WAS `anyType` now has to be an explicit rule,
    // and round 740 measured that the ENTIRE gap is this one family. The controls
    // above are the shapes its probe over-rejected; these four are the legs stated
    // directly, so a leg that is dropped or narrowed fails here rather than 700 lines
    // into a corpus baseline.

    @Test
    fun `a declared numeric enum member type is assignable to number`() {
        diagnose(
            """
            enum E { A, B }
            declare const a: E.A
            const n: number = a
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `number is assignable to a declared numeric enum member parameter`() {
        diagnose(
            """
            enum E { A, B }
            declare const n: number
            declare function take(e: E.A): void
            take(n)
            """,
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `a declared string enum member type is assignable to string`() {
        diagnose(
            """
            enum Ext { Dts = ".d.ts", Dmts = ".d.mts" }
            declare const d: Ext.Dts
            const s: string = d
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * The one knock-on round 740's probe measured and round 741 had to close:
     * `typeof x === "object"` must classify an enum MEMBER as NOT-object, exactly as
     * it already did for an enum. While every member was `anyType` the union collapsed
     * and the question never arose; with distinct members it survives the narrow and
     * the property access on it FPs TS2339 (tsc program.ts:1341,
     * `ResolutionMode | Partial<CreateSourceFileOptions> | undefined`).
     */
    @Test
    fun `a typeof object narrow drops enum member constituents`() {
        diagnose(
            """
            enum ModuleKind { CommonJS = 1, ESNext = 99 }
            interface Opts { impliedNodeFormat: ModuleKind.ESNext }
            declare const result: ModuleKind.ESNext | ModuleKind.CommonJS | Opts | undefined
            const x = typeof result === "object" ? result.impliedNodeFormat : result
            """,
        ) should { have(none { it.code == 2339 }) }
    }

    // --- (REL.1)(b0) round 742: an enum-member ACCESS EXPRESSION types as the MEMBER.
    // Until this round it typed as `anyType` — an enum's own type carries no member
    // table, so the property lookup missed and the access fell through to the anyType
    // tail. That is what made round 740's `take(Ext.Dts)` control vacuous and what made
    // the relation unable to reject `const e: E.X = E.Y` however sharp its rule was.
    // Each pin below is stated so it FAILS on an `anyType` access — `any` relates to
    // everything, so a pin that merely expects silence would measure nothing.

    @Test
    fun `a numeric enum member expression is not assignable to a string parameter`() {
        diagnose(
            """
            enum E { A, B }
            declare function take(s: string): void
            take(E.A)
            """,
        ) should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `a string enum member expression is not assignable to a number parameter`() {
        diagnose(
            """
            enum Ext { Dts = ".d.ts", Dmts = ".d.mts" }
            declare function take(n: number): void
            take(Ext.Dts)
            """,
        ) should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `an enum member type prints qualified by its enum`() {
        // (PARITY.2): a `never` parameter, not a `string` one. This pin's subject is the
        // MEMBER rendering, and a `string` target now takes tsc's `reportRelationError`
        // generalization (`Argument of type 'E'`), which would hide exactly what is
        // being pinned. `never` is a target tsc suppresses the generalization for;
        // measured byte-identical in tsgo 7.0.2 and pristine `typescript@6.0.3`.
        val messages = diagnose(
            """
            enum E { A, B }
            declare function take(s: never): void
            take(E.A)
            """,
        ).filter { it.code == 2345 }.map { it.message }
        assert(messages == listOf("Argument of type 'E.A' is not assignable to parameter of type 'never'."))
    }

    /**
     * The other half of (b0): the member type must WIDEN. tsc gives `let x = E.A` the
     * enum type, not the member, so a later `x = E.B` is legal — without the widening
     * rule the mint alone would make ordinary enum code an error.
     */
    @Test
    fun `negative control - a let initialized from a member widens to the enum`() {
        diagnose(
            """
            enum E { A, B }
            let x = E.A
            x = E.B
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * The flags-accumulator shape, which is how tsc's own sources start almost every
     * enum-valued local (`let transformFlags = TransformFlags.None`). It is a
     * SEPARATE widening path from the one above: the TS2322 assignment check reads
     * the target's declared type out of the pass's local-type map, whose recorder has
     * its own inline literal-widener. Round 742 measured 23 profile FPs from that one
     * omission.
     */
    @Test
    fun `negative control - an accumulator initialized from a member accepts other members`() {
        diagnose(
            """
            enum TF { None = 0, A = 1, B = 2 }
            export function f(): TF {
                let flags = TF.None
                flags = TF.A
                if (flags === TF.A) { flags = TF.B }
                return flags
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * The union sibling of the pin above — tsc's checker.ts writes
     * `let variance = mods & Out ? (mods & In ? VarianceFlags.Invariant : …) : …`,
     * whose inferred type is a UNION of member types, and widening has to distribute
     * over it or the later `variance = VarianceFlags.Independent` is an error.
     */
    @Test
    fun `negative control - a member union from a ternary widens per constituent`() {
        diagnose(
            """
            enum VF { Invariant = 0, Covariant = 1, Contravariant = 2, Independent = 4 }
            declare const outMod: boolean
            declare const inMod: boolean
            export function f(): VF {
                let variance = outMod
                    ? (inMod ? VF.Invariant : VF.Covariant)
                    : VF.Contravariant
                variance = VF.Independent
                return variance
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a member expression is assignable to its own enum`() {
        diagnose(
            """
            enum E { A = 1, B = 2 }
            const c: E = E.A
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * (b0) unmasked two pre-existing gaps that `anyType` had been hiding, both in the
     * arithmetic/comparison pass, both fixed at the root rather than re-masked. These
     * pin the roots directly, in shapes independent of enums where possible.
     */
    @Test
    fun `negative control - a type parameter constrained to an enum is relationally comparable`() {
        diagnose(
            """
            enum SK { First = 0, Last = 10 }
            function f<T extends SK>(token: T): boolean {
                return token >= SK.First && token <= SK.Last
            }
            f(SK.First)
            """,
        ) should { have(none { it.code == 2365 }) }
    }

    @Test
    fun `negative control - an inner const shadows an enclosing annotated binding`() {
        diagnose(
            """
            interface Ident { readonly xk: number }
            enum State { Try, Catch, Finally }
            interface Exc { state: State }
            export function outer(): void {
                let state: Ident
                function inner(exception: Exc): boolean {
                    const state = exception.state
                    return state < State.Finally
                }
                state = { xk: 1 }
                inner({ state: State.Try })
            }
            """,
        ) should { have(none { it.code == 2365 }) }
    }

    @Test
    // (REL.1)(b) round 742: ON — the string TARGET half of step (a)'s leg is DELETED.
    // It existed only for behaviour-preservation symmetry with the former `anyType`;
    // tsc rejects it, because a string enum is nominal and, unlike the numeric
    // direction, has no bit-flag backwards-compatibility rule to justify it.
    fun `string is not assignable to a declared string enum member parameter`() {
        diagnose(
            """
            enum Ext { Dts = ".d.ts", Dmts = ".d.mts" }
            declare const s: string
            declare function take(e: Ext.Dts): void
            take(s)
            """,
        ) should { have(any { it.code == 2345 }) }
    }
}
