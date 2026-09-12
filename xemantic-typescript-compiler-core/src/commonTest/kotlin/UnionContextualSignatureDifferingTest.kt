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
 * (CHK.97) D3, the DIFFERING half — a UNION contextual type whose callable members carry
 * signatures that are NOT identical provides NO contextual signature (tsc's union arm of
 * `getContextualSignature`, checker.ts:33224, answers undefined the moment
 * `compareSignaturesIdentical` fails), so every un-annotated parameter of the arrow /
 * function expression written against it is implicitly `any`: TS7006 on an identifier
 * parameter, TS7031 on a binding element. Before, the refusal answered null at the two
 * positions the implicit-any walker short-circuits as `typed` — a call argument and an
 * annotated variable / property-declaration initializer — and the parameter stayed
 * silently `any`; the object-literal-property, `return` and `=`-assignment positions
 * already reported, because their null reaches `checkParamsForImplicitAny` directly.
 *
 * WHAT A MEMBER CONTRIBUTES is tsc's `getContextualCallSignature`: its signatures
 * filtered by the ARROW's arity (`isAritySmaller`), exactly one applicable contributes
 * it, several fold through `getIntersectedSignatures`, none contributes nothing — so an
 * overloaded member no longer refuses the union, and a member whose only signature is
 * arity-smaller than the arrow is DROPPED rather than compared (the negative controls).
 *
 * Every pin asserts the EXACT row list: the DIFFERING verdict must produce the TS7006
 * row AND NOTHING ELSE — a wrong-typed use of the parameter (`const bad: boolean = p`)
 * must stay silent, because tsc's parameter is `any` there; ablation arm a3 (typing it
 * from the FIRST member) adds the TS2322 and reddens the same pins.
 *
 * RECEIPT (`scripts/ref_matrix.py`, both references agreeing, zero REF-SPLIT): differing
 * parameter types, differing arity, `(x?: string)` vs `(x: string)`, `Cb<string> |
 * Cb<number>`, a generic beside a non-generic member, an overloaded member beside a plain
 * one, a rest member beside a fixed one, a primitive member beside two differing
 * callables, a function expression, a method callee, a class property initializer, a
 * variable annotation, a generic callee, a binding-pattern parameter and a differing
 * pair reached through the unioned return of an identical outer pair all go `missing 1
 * -> 0` (`agree`), with zero ours-only rows on any fixture. The arm is reached ZERO times
 * on all eight dashboard profiles, on `marked`, `cronstrue` and the 2,400-file generated
 * project, so the grid is a CONTROL; these pins and the corpus are the gate.
 *
 * Residues, MEASURED and OUT OF SCOPE (each has a pin or a KDoc line): a `new`
 * expression's argument keeps today's silence (the implicit-any walker's `new` edge
 * records `typed` with nothing to read); a both-generic pair through a GENERIC callee
 * (`<T>(seed: T, cb: ((x: T) => void) | ((x: T) => number))`) types nothing where both
 * references type `p: string` (the pull leaves the members un-instantiated, the
 * pre-existing generic-identical residue one hop over); a nested arrow's OUTER parameter
 * read inside the inner body reports nothing (pre-existing, single member too); an
 * initialised parameter (`(p = "d") =>`) against a differing union is typed by its
 * initializer in tsc and not here (pre-existing initialiser typing, not this population).
 */
class UnionContextualSignatureDifferingTest {

    private fun rows(source: String): List<Pair<Int, String>> =
        diagnose(source, directives = "// @strict: true").map { it.code to it.message }

    private val pAny = 7006 to "Parameter 'p' implicitly has an 'any' type."
    private val stringToBoolean = 2322 to "Type 'string' is not assignable to type 'boolean'."

    @Test
    fun `differing parameter types at a call argument report TS7006 and type nothing`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p });
            export {}
            """
        ) == listOf(pAny))

    /** Both members apply to a one-parameter arrow, the comparison runs and refuses. */
    @Test
    fun `differing arity with both members applicable reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string, y: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p });
            export {}
            """
        ) == listOf(pAny))

    @Test
    fun `a rest member beside a fixed member reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (...xs: string[]) => void;
            type ZzzB = (x: string) => number;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p; return 1 });
            export {}
            """
        ) == listOf(pAny))

    @Test
    fun `a function expression parameter reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take(function (p) { const bad: boolean = p });
            export {}
            """
        ) == listOf(pAny))

    /** tsc reports a binding element as TS7031, not TS7006. */
    @Test
    fun `a binding pattern parameter reports TS7031 on each element`() =
        assert(rows(
            """
            type ZzzA = (x: { a: string }) => void;
            type ZzzB = (x: { b: number }) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take(({ a }) => { const bad: boolean = a });
            export {}
            """
        ) == listOf(7031 to "Binding element 'a' implicitly has an 'any' type."))

    @Test
    fun `a method callee reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare const holder: { take(cb: ZzzA | ZzzB): void };
            holder.take((p) => { const bad: boolean = p });
            export {}
            """
        ) == listOf(pAny))

    @Test
    fun `a variable annotation reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            const zv: ZzzA | ZzzB = (p) => { const bad: boolean = p };
            export {}
            """
        ) == listOf(pAny))

    @Test
    fun `a class property initializer reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            class ZzzC { m: ZzzA | ZzzB = (p) => { const bad: boolean = p } }
            export {}
            """
        ) == listOf(pAny))

    /** This position already reported before the fix — the refusal's null reaches the
     *  object-literal method arm directly; pinned so the positions cannot drift apart. */
    @Test
    fun `an object literal method against a union of differing members reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            const zo: { m: ZzzA | ZzzB } = { m(p) { const bad: boolean = p } };
            export {}
            """
        ) == listOf(pAny))

    /** A non-callable member contributes nothing; the two callables still differ. */
    @Test
    fun `a primitive member beside two differing callables reports TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare function take(cb: ZzzA | ZzzB | string): void;
            take((p) => { const bad: boolean = p });
            export {}
            """
        ) == listOf(pAny))

    /** A generic callee: the declared union names `T`, the INSTANTIATED members are
     *  compared, and they differ by arity. */
    @Test
    fun `a generic callee whose instantiated members differ reports TS7006`() =
        assert(rows(
            """
            declare function take<T>(seed: T, cb: ((x: T) => void) | ((x: T, y: number) => void)): void;
            take("s", (p) => { const bad: boolean = p });
            export {}
            """
        ) == listOf(pAny))

    /** Every un-annotated parameter is implicitly `any`, an optional one included. */
    @Test
    fun `an optional second parameter is reported beside the first`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p, q?) => { const bad: boolean = p });
            export {}
            """
        ) == listOf(pAny, 7006 to "Parameter 'q' implicitly has an 'any' type."))

    /** An identical outer pair types `p` and hands the inner arrow the UNIONED return —
     *  a differing pair — so the inner parameter is implicitly `any`. residue - the read
     *  of `p` inside the inner body is a pre-existing silence (single member too), so this
     *  pin asserts the presence of the inner row rather than the exact list. */
    @Test
    fun `a differing pair reached through the unioned return of an identical pair reports TS7006`() =
        diagnose(
            """
            type ZzzA = (x: string) => (y: number) => void;
            type ZzzB = (x: string) => (y: string) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => (y) => { const bad: boolean = y; const bad2: number = p });
            export {}
            """,
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 7006 && it.message == "Parameter 'y' implicitly has an 'any' type." })
            have(none { it.code == 2322 && it.message.contains("'boolean'") })
        }

    /** Two overloaded members: each contributes the intersection of its two applicable
     *  overloads, the two intersections are identical, and `p` is typed `string`. */
    @Test
    fun `two overloaded members fold each to one signature and type the parameter`() =
        assert(rows(
            """
            interface ZzzOvA { (x: string): void; (x: string, y: number): void }
            interface ZzzOvB { (x: string): number; (x: string, y: number): number }
            declare function take(cb: ZzzOvA | ZzzOvB): void;
            take((p) => { const bad: boolean = p; return 1 });
            export {}
            """
        ) == listOf(stringToBoolean))

    /** A member whose only signature is arity-smaller than the arrow is DROPPED, not
     *  compared: `(p, q)` leaves the two-parameter member alone, which types both. */
    @Test
    fun `negative control - a member that is arity-smaller than the arrow is dropped and the other types the parameters`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string, y: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p, q) => { const bad: number = p; const bad2: string = q });
            export {}
            """
        ) == listOf(
            2322 to "Type 'string' is not assignable to type 'number'.",
            2322 to "Type 'number' is not assignable to type 'string'.",
        ))

    /** An overloaded member beside a plain one where exactly ONE overload applies to
     *  `(p, q)` — that overload is identical to the plain member, so both are typed. */
    @Test
    fun `negative control - an overloaded member with one applicable overload types the parameters`() =
        assert(rows(
            """
            interface ZzzOv { (x: string): void; (x: string, y: number): void }
            type ZzzB = (x: string, y: number) => number;
            declare function take(cb: ZzzOv | ZzzB): void;
            take((p, q) => { const bad: number = p; const bad2: string = q; return 1 });
            export {}
            """
        ) == listOf(
            2322 to "Type 'string' is not assignable to type 'number'.",
            2322 to "Type 'number' is not assignable to type 'string'.",
        ))

    @Test
    fun `negative control - an identical pair types the parameter and reports no TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => number;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p; return 1 });
            export {}
            """
        ) == listOf(stringToBoolean))

    @Test
    fun `negative control - a single callable member beside a nullish one types the parameter`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            declare function take(cb: ZzzA | undefined): void;
            take((p) => { const bad: boolean = p });
            export {}
            """
        ) == listOf(stringToBoolean))

    /** An annotated parameter never asks — the DIFFERING verdict has no population. */
    @Test
    fun `negative control - an annotated parameter against a differing union reports nothing`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p: string) => { const ok: string = p });
            export {}
            """
        ).isEmpty())

    /**
     * residue - both references report `Parameter 'p' implicitly has an 'any' type.` for a
     * `new` expression's argument; the implicit-any walker's `new` edge records `typed`
     * with no parameter to read, so today's silence stands. This pin records that
     * silence, not a rule.
     */
    @Test
    fun `residue - a new expression argument against a differing union stays silent`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare class ZzzK { constructor(cb: ZzzA | ZzzB) }
            new ZzzK((p) => { const bad: boolean = p });
            export {}
            """
        ).isEmpty())
}
