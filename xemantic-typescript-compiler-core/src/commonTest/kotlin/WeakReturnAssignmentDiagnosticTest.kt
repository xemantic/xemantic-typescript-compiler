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
 * (CHK.58) THE WEAK-TYPE RULE AT THE **RETURN** AND **ASSIGNMENT** POSITIONS.
 *
 * Before this, the B482 walkers emitted TS2559 at a var DECL and at a CALL ARGUMENT
 * and **nowhere else**. `function f(): W { return v }` and `x = v` — two of the
 * commonest places a developer gets a type wrong — reported NOTHING for a weak
 * target, and for a weak target reached through a UNION the return position reported
 * the ordinary TS2322 naming the whole union where tsc reports TS2559 naming the
 * surviving constituent. This was not a union defect: the BARE target was silent too,
 * which is what distinguishes it from (CHK.57).
 *
 * **EVERY EXPECTED VALUE BELOW — CODE, MESSAGE, LINE AND CHARACTER — WAS READ OUT OF
 * tsc 7.0.2 OVER THE BYTE-IDENTICAL FIXTURE**, never derived by hand; the fixtures
 * live one per pin under `build/chk58/pinora` (`q01.ts` … `q15.ts`) and
 * [Diagnostic.character] is the CLI's **1-based** column verbatim — re-deriving it as
 * 0-based reddened nine pins in (CHK.57) and three in (CHK.56).
 *
 * THE TWO ANCHORS, both corroborated by PRISTINE tsc rather than by tsgo alone:
 *  * a RETURN is anchored at the **`return` keyword**, six characters — pristine
 *    squiggles `~~~~~~` under `return null;` for a return-type TS2322, and
 *    [Checker.checkReturnAssignabilityCore] has always used that same span;
 *  * an ASSIGNMENT is anchored at the **LHS reference**, its own length — pristine's
 *    `assignmentCompatWithObjectMembersOptionality2.errors.txt` puts a single `~`
 *    under the `c` of `c = d` for exactly this diagnostic.
 *
 * WHAT IS DELIBERATELY NOT CLOSED, each with its own refusal pin below:
 *  * an **OBJECT-LITERAL** source — tsc's freshness/excess check pre-empts the weak
 *    one and this compiler ALREADY matches it byte-exactly in both positions (TS2353
 *    at the offending property), so the weak path must not reach it;
 *  * a **CALLABLE** source — tsc splits TS2559 / TS2560 on whether CALLING the source
 *    yields something assignable to the target, where [Checker.tryEmitWeakTypeAssignment]
 *    emits 2560 for every callable source ((CHK.58) item 2);
 *  * **two or more non-nullish constituents** — tsc's answer is ordinary assignability
 *    naming the whole union, which needs the RELATION to reject;
 *  * an **ENUM-MEMBER** source — [Checker.getTypeOfExpression] answers `any` for `E.A`,
 *    so the weak path never sees it and the pre-existing TS2322 survives ((CHK.58)
 *    item 4).
 */
class WeakReturnAssignmentDiagnosticTest {

    /**
     * THE HEADLINE, RETURN POSITION, BARE weak target — silent before.
     * tsc 7.0.2: `q01.ts(1,54): error TS2559: Type '"utf8"' has no properties in
     * common with type '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.`
     */
    @Test
    fun `a return against a bare weak annotation reports TS2559 at the return keyword`() {
        val d = diagnose("""
            function zzzQ01f(): { zzzA?: null; zzzF?: string } { return "utf8"; }
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '\"utf8\"' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 1)
        assert(d[0].character == 54)
    }

    /**
     * THE HEADLINE, ASSIGNMENT POSITION, BARE weak target — silent before.
     * tsc 7.0.2: `q02.ts(2,1)`, same sentence, anchored at the LHS reference.
     */
    @Test
    fun `an assignment to a bare weak variable reports TS2559 at the reference`() {
        val d = diagnose("""
            let zzzQ02v: { zzzA?: null; zzzF?: string } = {}
            zzzQ02v = "utf8"
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '\"utf8\"' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 1)
    }

    /**
     * THE UNION RETURN — the one shape that had a row already, with the WRONG CODE.
     * The parent emitted `TS2322: Type 'string' is not assignable to type
     * '{ … } | null'`; tsc 7.0.2 says `q03.ts(1,61): error TS2559: Type '"utf8"' has
     * no properties in common with type '{ zzzA?: null | undefined; zzzF?: string |
     * undefined; }'.` — the CONSTITUENT, and the literal source type.
     */
    @Test
    fun `a return against a weak union annotation reports TS2559 naming the constituent`() {
        val d = diagnose("""
            function zzzQ03f(): { zzzA?: null; zzzF?: string } | null { return "utf8"; }
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '\"utf8\"' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 1)
        assert(d[0].character == 61)
    }

    /** THE UNION ASSIGNMENT — silent before. tsc 7.0.2: `q04.ts(2,1)`. */
    @Test
    fun `an assignment to a weak union variable reports TS2559 naming the constituent`() {
        val d = diagnose("""
            let zzzQ04v: { zzzA?: null; zzzF?: string } | null = null
            zzzQ04v = "utf8"
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '\"utf8\"' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 1)
    }

    /**
     * A NAMED target renders as its NAME in both positions. tsc 7.0.2:
     * `q05.ts(2,30)` and `q05.ts(4,1)`, both `… has no properties in common with
     * type 'ZzzQ05'.`
     */
    @Test
    fun `an interface weak target is named rather than expanded in both positions`() {
        val d = diagnose("""
            interface ZzzQ05 { zzzA?: null; zzzF?: string }
            function zzzQ05f(): ZzzQ05 { return 123; }
            let zzzQ05v: ZzzQ05 = {}
            zzzQ05v = 123
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all { it.message == "Type '123' has no properties in common with type 'ZzzQ05'." })
        assert(d.map { it.line to it.character }.sortedBy { it.first } == listOf(2 to 30, 4 to 1))
    }

    /**
     * PRISTINE tsc's OWN SHAPE, an identifier source of an interface type —
     * `assignmentCompatWithObjectMembersOptionality2.errors.txt` is `c = d` with a
     * single `~` under `c`. tsc 7.0.2 over this fixture: `q06.ts(5,1)` and
     * `q06.ts(6,31)`, `Type 'ZzzQ06d' has no properties in common with type 'ZzzQ06c'.`
     */
    @Test
    fun `an identifier source with no common property reports in both positions`() {
        val d = diagnose("""
            interface ZzzQ06d { zzzOther: number }
            interface ZzzQ06c { zzzOpt?: number }
            declare let zzzQ06dv: ZzzQ06d
            declare let zzzQ06cv: ZzzQ06c
            zzzQ06cv = zzzQ06dv
            function zzzQ06f(): ZzzQ06c { return zzzQ06dv; }
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all {
            it.message == "Type 'ZzzQ06d' has no properties in common with type 'ZzzQ06c'."
        })
        assert(d.map { it.line to it.character }.sortedBy { it.first } == listOf(5 to 1, 6 to 31))
    }

    /**
     * A PROPERTY-ACCESS assignment target (`o.p = "utf8"`), anchored at the START of
     * the whole reference. tsc 7.0.2: `q07.ts(3,1)`.
     */
    @Test
    fun `an assignment to a weak member reports TS2559 at the start of the reference`() {
        val d = diagnose("""
            interface ZzzQ07 { zzzP: { zzzA?: null; zzzF?: string } }
            declare let zzzQ07v: ZzzQ07
            zzzQ07v.zzzP = "utf8"
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '\"utf8\"' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 3)
        assert(d[0].character == 1)
    }

    /**
     * A CLASS-INSTANCE source — the [Checker.topLevelWeakSource] `new` branch that
     * (CHK.58) item 5 records as missing at the VAR DECL exists here for free, because
     * these two positions read [Checker.getTypeOfExpression] rather than that
     * annotation-driven classifier. tsc 7.0.2: `q15.ts(2,54)` and `q15.ts(4,1)`.
     */
    @Test
    fun `a class instance source with no common property reports in both positions`() {
        val d = diagnose("""
            class ZzzQ15 { zzzQ = 1 }
            function zzzQ15f(): { zzzA?: null; zzzF?: string } { return new ZzzQ15(); }
            let zzzQ15v: { zzzA?: null; zzzF?: string } = {}
            zzzQ15v = new ZzzQ15()
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all {
            it.message == "Type 'ZzzQ15' has no properties in common with type " +
                "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'."
        })
        assert(d.map { it.line to it.character }.sortedBy { it.first } == listOf(2 to 54, 4 to 1))
    }

    /**
     * CONTROL — a source that SHARES a property name is legal in both positions and
     * tsc 7.0.2 is silent over `q09.ts`. Always green; it is what says the new rule
     * is the weak rule and not "an object source is refused".
     */
    @Test
    fun `negative control - a source sharing a property is silent in both positions`() {
        val d = diagnose("""
            interface ZzzQ09 { zzzA?: null; zzzF?: string }
            declare let zzzQ09s: { zzzF: string }
            function zzzQ09f(): ZzzQ09 { return zzzQ09s; }
            let zzzQ09v: ZzzQ09 = {}
            zzzQ09v = zzzQ09s
        """)
        assert(d.isEmpty())
    }

    /**
     * CONTROL — a NON-weak target keeps its ordinary TS2322 in both positions, at the
     * same two spans. tsc 7.0.2: `q10.ts(1,40)` and `q10.ts(3,1)`, byte-identical to
     * what this compiler already emitted.
     */
    @Test
    fun `negative control - a non-weak target still reports TS2322 unchanged`() {
        val d = diagnose("""
            function zzzQ10f(): { zzzA: number } { return "utf8"; }
            let zzzQ10v: { zzzA: number } = { zzzA: 1 }
            zzzQ10v = "utf8"
        """)
        assert(d.map { it.code } == listOf(2322, 2322))
        assert(d.all {
            it.message == "Type 'string' is not assignable to type '{ zzzA: number; }'."
        })
        assert(d.map { it.line to it.character }.sortedBy { it.first } == listOf(1 to 40, 3 to 1))
    }

    /**
     * THE OBJECT-LITERAL BOUNDARY, which this compiler already matched byte for byte
     * and which the weak path must NOT take over: tsc runs the freshness/excess check
     * ABOVE the weak one, so a disjoint fresh literal is TS2353 at the offending
     * PROPERTY — `q11.ts(1,63)` and `q11.ts(3,13)` — where the weak wording would name
     * the whole literal further left.
     */
    @Test
    fun `an object literal source keeps tsc's excess-property row at the property`() {
        val d = diagnose("""
            function zzzQ11f(): { zzzA?: null; zzzF?: string } { return { zzzZ: 1 }; }
            let zzzQ11v: { zzzA?: null; zzzF?: string } = {}
            zzzQ11v = { zzzZ: 1 }
        """)
        assert(d.map { it.code } == listOf(2353, 2353))
        assert(d.all {
            it.message == "Object literal may only specify known properties, and " +
                "'zzzZ' does not exist in type " +
                "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'."
        })
        assert(d.map { it.line to it.character }.sortedBy { it.first } == listOf(1 to 63, 3 to 13))
    }

    /**
     * (CHK.59) THE CALLABLE SOURCE, **CLOSED** — this was a refusal pin until (CHK.59)
     * gave [Checker.tryEmitWeakValuePosition] the second, CALL-ONLY anchor it needed.
     * `() => 1` returns `number`, which is not assignable to the weak target, so the
     * code is TS**2559** and the anchors are the ordinary ones. tsc 7.0.2 over
     * `build/chk59/pin/q12.ts`: `(1,54)` at the `return` keyword and `(3,1)` at the LHS.
     * The 2560 half — where the anchor moves to the EXPRESSION — lives in
     * [WeakCallableSourceAnchorTest].
     */
    @Test
    fun `a callable source whose result is disjoint reports TS2559 at the ordinary anchors`() {
        val d = diagnose("""
            function zzzQ12f(): { zzzA?: null; zzzF?: string } { return () => 1; }
            let zzzQ12v: { zzzA?: null; zzzF?: string } = {}
            zzzQ12v = () => 1
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all {
            it.message == "Type '() => number' has no properties in common with type " +
                "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'."
        })
        assert(d.map { it.line } == listOf(1, 3))
        assert(d.map { it.character } == listOf(54, 1))
    }

    /**
     * REFUSAL — TWO OR MORE non-nullish constituents. tsc words those as ordinary
     * assignability naming the WHOLE union, which needs the RELATION to reject; no
     * TS2559 may appear. tsc 7.0.2 over `q16.ts`: `q16.ts(1,57)` and `q16.ts(3,1)`,
     * `Type 'number' is not assignable to type '{ zzzA?: null | undefined; } |
     * { zzzB?: null | undefined; }'` — the return row is byte-exact here already and
     * the assignment row is a pre-existing hole in the ORDINARY assignability walk
     * (silent), unrelated to the weak rule and untouched by it.
     *
     * **THE FIXTURE MUST CARRY TWO *WEAK* CONSTITUENTS**, which (CHK.57) measured the
     * hard way: with a NON-weak one such as `{ zzzA?: null } | string`, dropping the
     * single-survivor test hands [Checker.weakRefusalDisplayTarget] the `string`
     * constituent (the resolved union's member ORDER is not its display order), on
     * which every weak predicate bails anyway — so that shape is a DEAD arm and pins
     * nothing. Verified: arm a5 (`singleOrNull` -> `firstOrNull`) reddens this pin and
     * left the `| string` version green.
     */
    @Test
    fun `refusal - two non-nullish constituents do not take the weak wording`() {
        val d = diagnose("""
            function zzzQ16f(): { zzzA?: null } | { zzzB?: null } { return 123; }
            let zzzQ16v: { zzzA?: null } | { zzzB?: null } = {}
            zzzQ16v = 123
        """)
        assert(d.none { it.code == 2559 || it.code == 2560 })
        assert(d.map { it.code } == listOf(2322))
        assert(d[0].message == "Type 'number' is not assignable to type " +
            "'{ zzzA?: null | undefined; } | { zzzB?: null | undefined; }'.")
        assert(d[0].line == 1)
        assert(d[0].character == 57)
    }

    /**
     * (CHK.59) THE ENUM-MEMBER SOURCE, **CLOSED** — a refusal pin until this round.
     * tsc 7.0.2 over `build/chk59/pin/q08.ts`: `(2,54)` at the `return` keyword and
     * `(4,1)` at the LHS, TS2559 naming the MEMBER. The parent emitted TS2322 with the
     * right display and the wrong code. The member-COUNT boundary and the CALL-ARGUMENT
     * position live in [WeakEnumSourcePositionsTest].
     */
    @Test
    fun `an enum member source reports TS2559 in both positions`() {
        val d = diagnose("""
            enum ZzzQ08 { A = "a", B = "b" }
            function zzzQ08f(): { zzzA?: null; zzzF?: string } { return ZzzQ08.A; }
            let zzzQ08v: { zzzA?: null; zzzF?: string } = {}
            zzzQ08v = ZzzQ08.A
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all {
            it.message == "Type 'ZzzQ08.A' has no properties in common with type " +
                "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'."
        })
        assert(d.map { it.line } == listOf(2, 4))
        assert(d.map { it.character } == listOf(54, 1))
    }

    /**
     * CONTROL — an EMPTY object literal and an explicit `null` are vacuously assignable
     * to an all-optional target; tsc 7.0.2 is silent over the whole of `q14.ts`. This
     * is the guard [Checker.tryEmitWeakTypeAssignment] has always carried, re-asserted
     * at the two new positions.
     */
    @Test
    fun `negative control - an empty literal and an explicit null stay silent`() {
        val d = diagnose("""
            function zzzQ14f(): { zzzA?: null; zzzF?: string } { return {}; }
            function zzzQ14g(): { zzzA?: null; zzzF?: string } | null { return null; }
            let zzzQ14v: { zzzA?: null; zzzF?: string } | null = null
            zzzQ14v = null
        """)
        assert(d.isEmpty())
    }
}
