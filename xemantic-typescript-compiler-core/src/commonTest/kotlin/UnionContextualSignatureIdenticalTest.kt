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
 * (CHK.97) D3, the IDENTICAL half — a UNION contextual type whose callable members carry
 * IDENTICAL call signatures (parameters identical, return types free to differ) now
 * provides a contextual signature: tsc's union arm of `getContextualSignature`
 * (checker.ts:33224), `compareSignaturesIdentical` with `partialMatch = false`, `this`
 * and return types ignored, answered as `createUnionSignature` — the FIRST member's
 * parameters with the RETURN types unioned. Before, `Checker.callableSignaturesForCtx`
 * refused any union with two or more callable members outright (`if (single != null)
 * return null`), so the callback parameter read `any` and every wrong-typed use of it
 * was silent, while the object-literal-property and `return` positions ALSO reported an
 * OURS-ONLY TS7006 on it.
 *
 * Every pin here asserts the EXACT row list, so a shape that gains a contextual
 * signature is graded by the wrong-typed USE it must now report (`const bad: number =
 * p` -> TS2322, the (CHK.30) rule: the absence of TS7006 proves nothing), and a shape
 * that must NOT gain one is graded by the absence of that same TS2322 — which is the
 * only thing that separates "identical" from "arity-identical" (ablation arm a2).
 *
 * THE INSTRUMENT IS A UNION-TYPED *PARAMETER* (or annotation), never a direct call of a
 * union callee: `zf((p) => …)` with `zf: A | B` hands the argument the COMBINED
 * signature's parameter (`string`), not `A | B`, and both references report TS7006 +
 * TS2345 there — measured, and the reason the first fixture matrix of this round
 * measured the wrong population.
 *
 * RECEIPT (`scripts/ref_matrix.py`, both references agreeing, zero REF-SPLIT): the
 * identical shapes go `missing 1 -> 0` each (call argument, variable annotation,
 * object-literal property, `return`, three members, a nullish member beside two
 * identical ones, differing `this` parameters, differing parameter NAMES, two
 * instantiations of one generic alias) and the two OURS-ONLY TS7006 rows vanish.
 *
 * Residues, MEASURED and OUT OF SCOPE (see each `residue - …` / `negative control - …`
 * pin's KDoc): the DIFFERING half (tsc's TS7006 on the parameter) stays silent; an
 * OVERLOADED member refuses the union (tsc filters its signatures by the arrow's arity);
 * a generic-identical pair (`<T>(x: T) => void | <T>(x: T) => number`) types nothing
 * where both references type `p: T`; a REST contextual parameter types a plain arrow
 * parameter as the ARRAY (`string[]` for `string`, pre-existing on a single member too);
 * reads inside a NESTED arrow's body of a contextually typed parameter report nothing
 * (pre-existing on a single member); and a function expression's `this` is typed from
 * the first member exactly as both references do, but the TS2683 emitter's own
 * `typeIsFunctionWithThisParam` probe does not see through a union and still reports
 * `'this' implicitly has type 'any'` beside the correct row.
 */
class UnionContextualSignatureIdenticalTest {

    private fun rows(source: String): List<Pair<Int, String>> =
        diagnose(source, directives = "// @strict: true").map { it.code to it.message }

    private val stringToNumber = 2322 to "Type 'string' is not assignable to type 'number'."

    @Test
    fun `identical parameters with differing returns type the callback parameter through a call argument`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => number;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: number = p; return 1 });
            export {}
            """
        ) == listOf(stringToNumber))

    @Test
    fun `identical parameters and identical returns type the callback parameter`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: number = p });
            export {}
            """
        ) == listOf(stringToNumber))

    @Test
    fun `three identical members type the callback parameter`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => number;
            type ZzzC = (x: string) => boolean;
            declare function take(cb: ZzzA | ZzzB | ZzzC): void;
            take((p) => { const bad: number = p; return 1 });
            export {}
            """
        ) == listOf(stringToNumber))

    /** A nullish member contributes no signature and does not refuse the pair beside it. */
    @Test
    fun `a nullish member beside two identical members is skipped`() =
        assert(rows(
            """
            const zv: ((x: string) => void) | ((x: string) => number) | undefined =
                (p) => { const bad: number = p; return 1 };
            export {}
            """
        ) == listOf(stringToNumber))

    @Test
    fun `a variable annotation reaches the arrow with the union signature`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => number;
            const zv: ZzzA | ZzzB = (p) => { const bad: number = p; return 1 };
            export {}
            """
        ) == listOf(stringToNumber))

    /** Before the fix this position reported an OURS-ONLY TS7006 on `p` as well. */
    @Test
    fun `an object literal property reaches the arrow with the union signature and no TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => number;
            const zo: { m: ZzzA | ZzzB } = { m: (p) => { const bad: number = p; return 1 } };
            export {}
            """
        ) == listOf(stringToNumber))

    @Test
    fun `an object literal method reaches the union signature`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => number;
            const zo: { m: ZzzA | ZzzB } = { m(p) { const bad: number = p; return 1 } };
            export {}
            """
        ) == listOf(stringToNumber))

    /** Before the fix this position reported an OURS-ONLY TS7006 on `p` as well. */
    @Test
    fun `a return position reaches the arrow with the union signature and no TS7006`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string) => number;
            function mk(): ZzzA | ZzzB {
              return (p) => { const bad: number = p; return 1 };
            }
            export {}
            """
        ) == listOf(stringToNumber))

    /** tsc compares with `ignoreThisTypes`; here the `this` pseudo-parameter is never in
     *  `Signature.parameters`, so the pair is identical by construction. */
    @Test
    fun `differing this parameters are ignored by the identical comparison`() =
        assert(rows(
            """
            interface ZzzJA { a: number }
            interface ZzzJB { b: string }
            type ZzzA = (this: ZzzJA, x: string) => void;
            type ZzzB = (this: ZzzJB, x: string) => number;
            declare function take(cb: ZzzA | ZzzB): void;
            take(function (p) { const bad: number = p; return 1 });
            export {}
            """
        ) == listOf(stringToNumber))

    @Test
    fun `differing parameter names are still identical signatures`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (y: string) => number;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: number = p; return 1 });
            export {}
            """
        ) == listOf(stringToNumber))

    /** Two `Type.Reference` members whose OWN (instantiated) signatures are compared. */
    @Test
    fun `two instantiations of generic aliases with the same argument are identical`() =
        assert(rows(
            """
            type ZzzCb<T> = (x: T) => void;
            type ZzzCn<T> = (x: T) => number;
            declare function take(cb: ZzzCb<string> | ZzzCn<string>): void;
            take((p) => { const bad: number = p; return 1 });
            export {}
            """
        ) == listOf(stringToNumber))

    /**
     * The unioned RETURN type reaches a concise body: the inner arrow's contextual type
     * is `((y: number) => void) | ((y: number) => string)`, identical again, so `y` is
     * typed and the OURS-ONLY TS7006 the arm used to report on it is gone.
     *
     * residue - both references additionally report `Type 'number' is not assignable to
     * type 'string'` for `bad` and `Type 'string' is not assignable to type 'number'` for
     * `bad2`; reads inside a NESTED arrow's body of a contextually typed parameter report
     * nothing here, on a single-member contextual type as well (measured before the fix).
     */
    @Test
    fun `the unioned return type types a nested arrow whose members are identical again`() =
        assert(rows(
            """
            type ZzzA = (x: string) => (y: number) => void;
            type ZzzB = (x: string) => (y: number) => string;
            const zv: ZzzA | ZzzB = (p) => (y) => { const bad: string = y; const bad2: number = p };
            export {}
            """
        ).isEmpty())

    /**
     * The RETURN types are UNIONED, not taken from the first member (ablation arm a3):
     * the inner arrow's contextual type is `((y: number) => void) | ((y: string) => void)`,
     * a DIFFERING pair, so `y` is implicitly `any` — both references report exactly this
     * TS7006, and a first-member-only return would type `y: number` and silence it.
     *
     * residue - both references also report `Type 'string' is not assignable to type
     * 'number'` for `bad2` (the nested-body read gap above).
     */
    @Test
    fun `the return type is the union of the members' returns - a differing nested pair stays implicitly any`() =
        assert(rows(
            """
            type ZzzA = (x: string) => (y: number) => void;
            type ZzzB = (x: string) => (y: string) => void;
            const zv: ZzzA | ZzzB = (p) => (y) => { const bad: boolean = y; const bad2: number = p };
            export {}
            """
        ) == listOf(7006 to "Parameter 'y' implicitly has an 'any' type."))

    /**
     * The DIFFERING half of D3 (ablation arm a2 — an arity-only comparison would type `p:
     * string` and report a false TS2322 here).
     *
     * residue - both references report `Parameter 'p' implicitly has an 'any' type.`; the
     * refusal answers null and today's silence stands, NOT emitted by this round.
     */
    @Test
    fun `negative control - differing parameter types provide no contextual signature`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p });
            export {}
            """
        ).isEmpty())

    /** residue - both references report TS7006 on `p`. */
    @Test
    fun `negative control - differing arity provides no contextual signature`() =
        assert(rows(
            """
            type ZzzA = (x: string) => void;
            type ZzzB = (x: string, y: number) => void;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p });
            export {}
            """
        ).isEmpty())

    /** `(x?: string)` against `(x: string)` differs in `minArgumentCount`, so it is not
     *  identical. residue - both references report TS7006 on `p`. */
    @Test
    fun `negative control - an optional against a required parameter is not identical`() =
        assert(rows(
            """
            type ZzzA = (x?: string) => void;
            type ZzzB = (x: string) => number;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p; return 1 });
            export {}
            """
        ).isEmpty())

    /** The members' OWN instantiated signatures are compared, never the target's
     *  unsubstituted `(x: T) => void` — which would read these two as identical and hand
     *  the arrow a bare `T`. residue - both references report TS7006 on `p`. */
    @Test
    fun `negative control - two instantiations with different arguments are not identical`() =
        assert(rows(
            """
            type ZzzCb<T> = (x: T) => void;
            declare function take(cb: ZzzCb<string> | ZzzCb<number>): void;
            take((p) => { const bad: boolean = p });
            export {}
            """
        ).isEmpty())

    /** A generic beside a non-generic member differs in type-parameter count.
     *  residue - both references report TS7006 on `p`. */
    @Test
    fun `negative control - a generic member beside a non-generic one is not identical`() =
        assert(rows(
            """
            type ZzzA = <T>(x: T) => T;
            type ZzzB = (x: string) => number;
            declare function take(cb: ZzzA | ZzzB): void;
            take((p) => { const bad: boolean = p; return 1 });
            export {}
            """
        ).isEmpty())

    /**
     * residue - an OVERLOADED member is refused: tsc's `getContextualCallSignature` filters
     * the member's signatures by the ARROW's arity (`(p, q)` leaves only `(x: string, y:
     * number): void`), and this checker's helper has no node in hand. Both references
     * report `Type 'string' is not assignable to type 'number'` and `Type 'number' is not
     * assignable to type 'string'`; this pin records today's silence, not a rule.
     */
    @Test
    fun `residue - an overloaded member refuses the union contextual signature`() =
        assert(rows(
            """
            interface ZzzOv { (x: string): void; (x: string, y: number): void }
            type ZzzB = (x: string, y: number) => number;
            declare function take(cb: ZzzOv | ZzzB): void;
            take((p, q) => { const bad: number = p; const bad2: string = q; return 1 });
            export {}
            """
        ).isEmpty())
}
