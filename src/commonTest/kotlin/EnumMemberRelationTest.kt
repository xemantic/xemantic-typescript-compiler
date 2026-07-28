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
import kotlin.test.Ignore
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
 * The tests below are the currently-FAILING expectations. They are `@Ignore`d rather
 * than deleted so the gap stays visible in the skipped count, and so that the session
 * that lands (REL.1) turns them on as its acceptance criterion — the positive controls
 * at the bottom are NOT ignored, because they must keep passing throughout.
 *
 * Blast radius, measured round 740 with a throwaway probe (distinct interned type per
 * enum member + `TypeFlags.Enum` on the enum's own type + an enum-literal disjointness
 * rule in `checkTypeRelatedToCore`): the full corpus went **12,927 / 1 failure**, and
 * the one failure (`enumAssignmentCompat5`) is the MISSING leg, not the added one —
 * a numeric enum member type must stay assignable FROM `number`. See
 * `docs/perf/../../PLAN-PHASE-5.md` (REL.1) for the decomposition.
 */
class EnumMemberRelationTest {

    @Test
    // (REL.1)(b) round 741: ON — the relation now rejects a distinct sibling member.
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
    // (REL.1)(b) round 741: ON.
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
    // (REL.1)(b) round 741: ON — the reverse direction, which is what proved the
    // old behaviour was MUTUAL assignability rather than a one-way leniency.
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
    // (REL.1)(b) round 741: ON — a non-declared enum behaves identically.
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

    @Test
    @Ignore // (REL.1)(b): step (a)'s string leg is deliberately BOTH-ways, so this
    // stays silent for now. tsc rejects it — string enums are nominal, and unlike the
    // numeric leg there is no bit-flag backwards-compatibility rule to justify it.
    // Kept visible in the skipped count rather than pinned as correct.
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
