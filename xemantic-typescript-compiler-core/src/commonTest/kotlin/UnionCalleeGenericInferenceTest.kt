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
 * (CHK.97) D5 — generic INFERENCE through a union-combined signature, measured against
 * tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was written (every row
 * below is one both references report, zero REF-SPLIT).
 *
 * `(number[] | string[]).map(x => 1)` reaches the ordinary single-signature inference
 * path with the PASS-2 combined signature ([Checker.combineSignaturesOfUnionMembers]):
 * `<U>(callbackfn: ((value: number, …) => U) & ((value: string, …) => U), thisArg?): U[]`.
 * tsc infers `U` from that INTERSECTION-typed callback parameter by inferring the
 * arrow's type to each constituent, with the arrow itself typed through
 * `getContextualCallSignature`'s `getIntersectedSignatures` fold. This checker's
 * inference family classifies a callback by the SHAPE of the parameter type, and every
 * callback arm demands an anonymous `Type.Object` — so the intersection passed the gate
 * (a function object's signatures are invisible to `typeMentionsTypeParam`, so it read as
 * "concrete") and the callback pass gathered NOTHING: the call's result was the raw
 * `U[]`, refused by every assignability reader as a foreign type parameter, silently.
 * MEASURED with a stderr line: `gate-passed tps=1` then `candidates tp=U n=0`.
 *
 * The fix is a VIEW, [Checker.inferenceParamType]: an intersection of anonymous function
 * types is presented to the two classifying sites as ONE anonymous function type
 * carrying [Checker.getIntersectedSignatures] over the constituents' signatures — the
 * constituents' parameters unioned (`value: string | number`), the first constituent's
 * return (the combined signature's own `U`) kept. Every pin here reads the call's RESULT
 * through a wrong-typed use, per (CHK.30): the silence of a bare `const r = xs.map(…)` is
 * exactly what the defect produced.
 *
 * RECORDED residues, each measured on a PLAIN array too and therefore NOT this
 * mechanism's: an array-literal body (`x => [x]`) is refused by design (round 466's
 * heterogeneous-literal bail in `retypeInferenceBodyExpr`; both references print
 * `(string | number)[][]`); an object-literal body (`x => ({ v: x })`) is not a named-like
 * candidate; `x => x + 1` on a union operand is silent where both references report
 * TS2365 and infer `any[]`; a type-predicate `filter` callback's `S[]` is not inferred
 * because `tryInferPredicateOverloadReturn` reads only a `FunctionDeclaration` /
 * `MethodDeclaration` and lib `filter` is a `MethodSignature`. Two that ARE this shape's:
 * a PASS-2 combined return `U[] | U` prints its chain sub-line by the (CHK.132) picker
 * (ours names `number[]`, both references `number`); and a tuple-union identity map
 * prints `(1 | 1 | 2)[]` — two `1` literal instances minted by two tuple bases are not
 * interned into one member (the references disagree on that row's ORDER anyway).
 */
class UnionCalleeGenericInferenceTest {

    private fun rows(source: String, strict: Boolean = true): List<Pair<Int, String>> =
        diagnose(
            source,
            directives = if (strict) "// @strict: true\n// @useRealLibs: true" else "// @strict: false\n// @useRealLibs: true",
        ).map { it.code to it.message }

    private fun decl(t: String) = "Type '$t' is not assignable to type 'boolean'."

    @Test
    fun `map with an un-annotated arrow returning a literal infers the type argument`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: boolean = zxs.map(x => 1);
            export {}
            """
        ) == listOf(2322 to decl("number[]")))

    @Test
    fun `map with an annotated callback return infers the type argument`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: boolean = zxs.map((x): string => String(x));
            export {}
            """
        ) == listOf(2322 to decl("string[]")))

    /** The (CHK.30) shape from the item: a body READING the unioned parameter. */
    @Test
    fun `a body reading the unioned parameter infers from the read`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: string = zxs.map(x => x.toString().length);
            export {}
            """
        ) == listOf(2322 to "Type 'number[]' is not assignable to type 'string'."))

    /** The identity callback is the discriminator for the FOLD: the view's parameter is
     *  the constituents' UNION, so `U` binds to `string | number`, never to the first
     *  member's `number` alone. */
    @Test
    fun `the identity callback carries the unioned element type`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: boolean = zxs.map(x => x);
            export {}
            """
        ) == listOf(2322 to decl("(string | number)[]")))

    /** Two different union callees in one file: the view is memoized per INTERSECTION id,
     *  so the second call must not be served the first call's fold. */
    @Test
    fun `two distinct union callees in one file each get their own intersected view`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            declare const zbs: boolean[] | string[];
            const bad1: boolean = zxs.map(x => x);
            const bad2: boolean = zbs.map(x => x);
            export {}
            """
        ) == listOf(2322 to decl("(string | number)[]"), 2322 to decl("(string | boolean)[]")))

    @Test
    fun `a readonly member still infers through the combined signature`() =
        assert(rows(
            """
            declare const zro: readonly number[] | string[];
            const bad: boolean = zro.map(x => 1);
            export {}
            """
        ) == listOf(2322 to decl("number[]")))

    @Test
    fun `a tuple union receiver infers through its array bases`() =
        assert(rows(
            """
            declare const zt: [1] | [1, 2];
            const bad: boolean = zt.map(x => x * 2);
            export {}
            """
        ) == listOf(2322 to decl("number[]")))

    @Test
    fun `a function expression callback infers the type argument`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: boolean = zxs.map(function (x) { return String(x); });
            export {}
            """
        ) == listOf(2322 to decl("string[]")))

    @Test
    fun `a block body with a single return infers the type argument`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: boolean = zxs.map(x => { return x; });
            export {}
            """
        ) == listOf(2322 to decl("(string | number)[]")))

    /** The PASS-2 shape on user code: differing callback parameters, so the members'
     *  callbacks are intersected and the returns unioned (`U[] | U`). Both references
     *  print `number | number[]`; the chain sub-line is (CHK.132)'s and is not pinned. */
    @Test
    fun `a user-declared generic method combined in pass 2 infers through the intersected callback`() =
        assert(rows(
            """
            interface ZzzA { m<U>(f: (x: number) => U): U[] }
            interface ZzzB { m<U>(f: (x: string) => U): U }
            declare const zab: ZzzA | ZzzB;
            const bad: boolean = zab.m(x => 1);
            export {}
            """
        ) == listOf(2322 to decl("number | number[]")))

    /** PASS 1 keeps the first member's own signature (an exact generic match), so no
     *  intersection is formed and the ordinary callback arm infers — unchanged. */
    @Test
    fun `negative control - a pass 1 identical generic pair infers through the first member's signature`() =
        assert(rows(
            """
            interface ZzzA { m<U>(f: (x: number) => U): U[] }
            interface ZzzB { m<U>(f: (x: number) => U): U[] }
            declare const zab: ZzzA | ZzzB;
            const bad: boolean = zab.m(x => String(x));
            export {}
            """
        ) == listOf(2322 to decl("string[]")))

    /** `reduce` with a seed selects the NON-generic `reduce(cb, initial: T): T` overload
     *  through the array fallback (`T` = `string | number`) — no combined generic
     *  signature is formed, unchanged, and both references print `string | number`. */
    @Test
    fun `negative control - reduce with a seed is answered without inference`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: boolean = zxs.reduce((acc, x) => acc + String(x), "");
            export {}
            """
        ) == listOf(2322 to decl("string | number")))

    /** A single-object callback parameter never reaches the view. */
    @Test
    fun `negative control - a plain array's map still infers through the ordinary arm`() =
        assert(rows(
            """
            declare const zn: number[];
            const bad: boolean = zn.map(x => String(x));
            export {}
            """
        ) == listOf(2322 to decl("string[]")))

    /**
     * residue - both references report `Type 'number[]' is not assignable to type 'string'.`
     * on a NON-strict project too: tsc's inference infers to each constituent regardless
     * of `noImplicitAny`, while the fold this view is built from
     * ([Checker.getIntersectedSignatures]) is gated on it exactly as tsc's CONTEXTUAL fold
     * is. Under `strict: false` the view is absent and today's silence stands; this pin
     * records that silence, not a rule.
     */
    @Test
    fun `residue - a non-strict project does not infer through the intersected callback`() =
        assert(rows(
            """
            declare const zxs: number[] | string[];
            const bad: string = zxs.map(x => 1);
            export {}
            """,
            strict = false,
        ).isEmpty())
}
