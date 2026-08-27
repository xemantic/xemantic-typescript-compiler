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
 * (CHK.57) THE WEAK-TYPE RULE, DISTRIBUTED OVER A **UNION** TARGET.
 *
 * (CHK.54) put the weak rule into overload SELECTION and (CHK.56) into the TS2769
 * DIAGNOSTIC path; both ask [Checker.weakParamRefusesArg], which has always folded
 * over a union's constituents. The B482 *walkers* — the ones that actually emit
 * TS2559/TS2560 at a named position — did not: [Checker.weakTargetProperties]
 * answers null for a [Type.Union], so a weak type reached THROUGH a union was
 * silent in every walker position. `T | null` and `T | undefined` are the commonest
 * parameter and variable shapes in real TypeScript (`readFileSync`'s
 * `options?: { encoding?: null; flag?: string } | null` is exactly that), so this
 * was the rule being absent from the majority of the positions where it fires.
 *
 * [Checker.weakUnionRefusalConstituent] is the composition, wired into the two
 * positions (CHK.56) measured as open: the single-signature CALL argument and the
 * top-level VAR DECL. **Every expected value below — code, message, line and
 * character — was read out of tsc 7.0.2 over the byte-identical fixture**, never
 * derived by hand ((CHK.56) reddened three pins that way); the fixtures live at
 * the fixtures live one per pin under `build/chk57/pinora` and `Diagnostic.character`
 * is the CLI's 1-based column verbatim.
 *
 * WHAT IS DELIBERATELY NOT CLOSED, each with its own refusal pin below:
 *  * **two or more non-nullish constituents** — tsc's answer there is the ORDINARY
 *    assignability wording naming the whole union (TS2345 / TS2322), which needs the
 *    RELATION to reject and is a different mechanism;
 *  * **an OBJECT-LITERAL argument** — tsc's freshness/excess check pre-empts the weak
 *    one and squiggles the offending PROPERTY one column to the right (TS2353), so
 *    dragging it into the weak path would put a row at the wrong span;
 *  * **a CALLABLE source** — tsc splits TS2559 / TS2560 on whether CALLING the source
 *    yields something assignable to the target, where [Checker.tryEmitWeakTypeAssignment]
 *    emits 2560 for every callable source; that is a pre-existing BARE-target divergence
 *    (`zzzP14(() => 1)` is TS2559 in tsc and TS2560 here) with its own corpus surface,
 *    so a callable source stays silent rather than acquiring a row with the wrong code.
 */
class WeakUnionTargetDiagnosticTest {

    /**
     * THE HEADLINE, ARGUMENT POSITION. tsc 7.0.2 over the identical file:
     * `p01.ts(2,24): error TS2559: Type '123' has no properties in common with type
     * '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.`
     * The argument keeps its LITERAL type (`Type '123'`) and the message names the
     * surviving CONSTITUENT, not `… | null`.
     */
    @Test
    fun `a single signature with a weak union parameter reports TS2559 naming the constituent`() {
        val d = diagnose("""
            declare function zzzP01(o: { zzzA?: null; zzzF?: string } | null): number
            const zzzP01r = zzzP01(123)
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '123' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 24)
    }

    /**
     * THE HEADLINE, VAR-DECL POSITION — the hole (CHK.54) named and never worked.
     * tsc 7.0.2: `p02.ts(1,7): error TS2559: Type '"utf8"' has no properties in
     * common with type '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.`,
     * anchored at the variable NAME.
     */
    @Test
    fun `a weak union var-decl annotation reports TS2559 at the variable name`() {
        val d = diagnose("""
            const zzzP02v: { zzzA?: null; zzzF?: string } | null = "utf8"
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '\"utf8\"' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 1)
        assert(d[0].character == 7)
    }

    /**
     * `| undefined` is the same shape — [Checker.weakRefusalDisplayTarget] drops both
     * nullish flags. tsc 7.0.2: `p03.ts(2,24)`, identical sentence.
     */
    @Test
    fun `an undefined constituent is dropped exactly as a null one is`() {
        val d = diagnose("""
            declare function zzzP03(o: { zzzA?: null; zzzF?: string } | undefined): number
            const zzzP03r = zzzP03(123)
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '123' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 24)
    }

    /**
     * A NAMED constituent renders as its name and not as its expansion — tsc 7.0.2:
     * `p04.ts(3,25): … has no properties in common with type 'ZzzP04'.`
     */
    @Test
    fun `an interface constituent is named rather than expanded`() {
        val d = diagnose("""
            interface ZzzP04 { zzzA?: null; zzzF?: string }
            declare function zzzP04f(o: ZzzP04 | null): number
            const zzzP04r = zzzP04f(123)
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type '123' has no properties in common with type 'ZzzP04'.")
        assert(d[0].line == 3)
        assert(d[0].character == 25)
    }

    /**
     * A NON-FRESH object source (the shape the object-literal refusal below is the
     * complement of): tsc 7.0.2 `p05.ts(3,24)` names the widened source type
     * `'{ zzzZ: number; }'`. Freshness, not shape, is the axis — exactly as (CHK.56)
     * measured for the overload path.
     */
    @Test
    fun `a non-fresh object source reaches the weak union rule`() {
        val d = diagnose("""
            declare function zzzP05(o: { zzzA?: null; zzzF?: string } | null): number
            const zzzP05o = { zzzZ: 1 }
            const zzzP05r = zzzP05(zzzP05o)
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '{ zzzZ: number; }' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 3)
        assert(d[0].character == 24)
    }

    /**
     * A REST parameter's effective target is the ARRAY ELEMENT type, which may itself
     * be the union — the walker's pre-existing rest unwrapping and the new union
     * distribution have to compose. tsc 7.0.2: `p06.ts(2,24)`.
     */
    @Test
    fun `a rest parameter whose element type is a weak union also reports`() {
        val d = diagnose("""
            declare function zzzP06(...o: ({ zzzA?: null; zzzF?: string } | null)[]): number
            const zzzP06r = zzzP06(123)
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '123' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 24)
    }

    /**
     * BOTH positions in one file, through a type ALIAS constituent — tsc 7.0.2 names
     * the alias in both: `p07.ts(3,25)` and `p07.ts(4,7)`.
     */
    @Test
    fun `an alias constituent is named in the call and in the var-decl position alike`() {
        val d = diagnose("""
            type ZzzP07 = { zzzA?: null; zzzF?: string }
            declare function zzzP07f(o: ZzzP07 | null): number
            const zzzP07r = zzzP07f(123)
            const zzzP07v: ZzzP07 | null = "utf8"
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d[0].message == "Type '123' has no properties in common with type 'ZzzP07'.")
        assert(d[0].line == 3)
        assert(d[0].character == 25)
        assert(d[1].message == "Type '\"utf8\"' has no properties in common with type 'ZzzP07'.")
        assert(d[1].line == 4)
        assert(d[1].character == 7)
    }

    /**
     * CONTROL — a source that SHARES a property with the weak constituent is assignable
     * and tsc emits nothing. This is the rule's whole premise; it is green on every
     * binary in this arc, so it is recorded as a control and not claimed as coverage.
     */
    @Test
    fun `control - a source sharing a property with the constituent stays silent`() {
        val d = diagnose("""
            declare function zzzP08(o: { zzzA?: null; zzzF?: string } | null): number
            const zzzP08o = { zzzA: null }
            const zzzP08r = zzzP08(zzzP08o)
        """)
        assert(d.isEmpty())
    }

    /**
     * CONTROL — an EMPTY source is vacuously assignable to an all-optional target, and
     * an explicit `null` matches the union's own nullish constituent. tsc emits nothing
     * for either. Green on every binary; recorded as a control.
     */
    @Test
    fun `control - an empty literal and an explicit null stay silent`() {
        val d = diagnose("""
            declare function zzzP09(o: { zzzA?: null; zzzF?: string } | null): number
            const zzzP09r = zzzP09({})
            const zzzP09v: { zzzA?: null; zzzF?: string } | null = null
        """)
        assert(d.isEmpty())
    }

    /**
     * REFUSAL — an OBJECT-LITERAL argument must NOT acquire the weak wording. tsc 7.0.2
     * reports `p11.ts(2,26): error TS2353: Object literal may only specify known
     * properties, and 'zzzZ' does not exist in type '{ zzzA?: null | undefined;
     * zzzF?: string | undefined; }'` — at the PROPERTY, two columns right of where the
     * weak sentence would sit. We do not emit that excess row for an argument the
     * relation ACCEPTED, so the shape stays SILENT; a diagnostic at the wrong span is
     * worse than none. Reddens when the object-literal guard is dropped.
     */
    @Test
    fun `refusal - an object-literal argument against a weak union stays silent`() {
        val d = diagnose("""
            declare function zzzP11(o: { zzzA?: null; zzzF?: string } | null): number
            const zzzP11r = zzzP11({ zzzZ: 1 })
        """)
        assert(d.isEmpty())
    }

    /**
     * REFUSAL — TWO OR MORE non-nullish constituents are not this rule's. tsc 7.0.2
     * words them as ordinary assignability naming the WHOLE union
     * (`p12.ts(2,24)` TS2345, `p12.ts(3,7)` TS2322), which needs the relation to
     * reject; emitting TS2559 here would be the right verdict with the wrong sentence.
     * Silent today — the open half of (CHK.57). Reddens when the single-survivor test
     * is dropped.
     */
    @Test
    fun `refusal - two non-nullish constituents do not take the weak wording`() {
        val d = diagnose("""
            declare function zzzP12(o: { zzzA?: null } | string): number
            const zzzP12r = zzzP12(123)
            const zzzP12v: { zzzA?: null } | string = 123
        """)
        assert(d.none { it.code == 2559 })
        assert(d.none { it.code == 2560 })
    }

    /**
     * REFUSAL — a CALLABLE source stays silent through a union. tsc 7.0.2 words it
     * TS2559 (`p13.ts(2,24)`), because calling `() => number` does not yield anything
     * assignable to the weak object; [Checker.tryEmitWeakTypeAssignment] would word it
     * TS2560. That split is a pre-existing BARE-target divergence — pinned as such by
     * the control below — so the union path refuses rather than adding a wrong-CODE
     * row. Reddens when the callable guard is dropped.
     */
    @Test
    fun `refusal - a callable source against a weak union stays silent`() {
        val d = diagnose("""
            declare function zzzP13(o: { zzzA?: null; zzzF?: string } | null): number
            const zzzP13r = zzzP13(() => 1)
        """)
        assert(d.isEmpty())
    }

    /**
     * CONTROL — the BARE (non-union) weak target is unchanged in both positions, and
     * these are the exact values (CHK.56) recorded as already byte-identical to tsc:
     * `p14.ts(2,24)` and `p14.ts(3,7)`. Green on every binary in this round; its job is
     * to fail if the union branch is ever reached by widening the bare one rather than
     * by adding a disjoint branch beside it.
     */
    @Test
    fun `control - a bare weak target reports exactly as before in both positions`() {
        val d = diagnose("""
            declare function zzzP14(o: { zzzA?: null; zzzF?: string }): number
            const zzzP14r = zzzP14(123)
            const zzzP14v: { zzzA?: null; zzzF?: string } = "utf8"
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d[0].line == 2)
        assert(d[0].character == 24)
        assert(d[1].line == 3)
        assert(d[1].character == 7)
    }

    /**
     * CONTROL — a fresh object-literal INITIALIZER against a weak union var decl keeps
     * tsc's excess-property row at tsc's own column: tsc 7.0.2 `p15.ts(1,58): error
     * TS2353 …`, which this compiler already matched before (CHK.57) and still matches.
     * The var-decl union branch cannot reach an object literal
     * ([Checker.topLevelWeakSource] answers null for one), and this pin is what says so.
     */
    @Test
    fun `control - an object-literal initializer keeps the excess-property row`() {
        val d = diagnose("""
            const zzzP15v: { zzzA?: null; zzzF?: string } | null = { zzzZ: 1 }
        """)
        assert(d.map { it.code } == listOf(2353))
        assert(d[0].message ==
            "Object literal may only specify known properties, and 'zzzZ' does not " +
            "exist in type '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 1)
        assert(d[0].character == 58)
    }
}
