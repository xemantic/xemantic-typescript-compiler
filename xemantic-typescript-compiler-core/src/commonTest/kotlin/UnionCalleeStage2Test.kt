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
 * (CHK.97) stage 2 — the three mechanisms that sit AROUND
 * [Checker.combineUnionSignatures], each measured against tsgo 7.0.2 AND pristine
 * `typescript@6.0.3` before any code was written.
 *
 * **1. tsc's ARRAY FALLBACK** (checker.ts:15949, `getSignaturesOfType`). When a union's
 * members are all instantiations of one `Array`/`ReadonlyArray` member and the two passes
 * produce NOTHING, tsc rewrites the receiver `(A[] | B[])` to `(A | B)[]` — "since we
 * pretend array is covariant anyway" — and reads the member off THAT. It is what makes
 * `(number[] | string[]).filter` legal, and it is the honest replacement for round 302's
 * `≥2`-overloaded silence. tsc asks the question of the MEMBER type through
 * `t.symbol.parent`; a method type here carries neither a parent symbol nor a mapper, so
 * [Checker.unionArrayFallbackReceiver] derives the same rewrite from the RECEIVER instead,
 * and [Checker.unionArrayFallbackMemberType] pays for that with one extra gate — the
 * fallback's answer must be CALLABLE, because a receiver-level route would otherwise
 * happily retype a non-callable member that tsc's signature-list route can never reach.
 *
 * **2. tsc's `getIntersectedSignatures`** (checker.ts:33085), the fold
 * `getContextualCallSignature` applies when a contextual type offers several applicable
 * signatures. This is the one that types a CALLBACK argument of a union callee: PASS 2
 * INTERSECTS the members' parameters, so the callback's own contextual type is an
 * INTERSECTION of function types, and the parameter it hands the arrow is their UNION —
 * `(number[] | string[]).forEach(x => …)` gives `x: string | number`. Two edits carry it:
 * an INTERSECTION arm on [Checker.callableSignaturesForCtx] and a UNION arm on
 * [Checker.cpaComputeArgCtxTypes], which is also `pullContextualTypeAt`'s CallExpression
 * source, so the assignability reader inside the arrow body sees it.
 *
 * **3. the OPTIONAL-call result.** `f?.()` on a `Fn | undefined` callee short-circuits, so
 * its result is the combined return `| undefined`; stage 1 kept `any` there, which made
 * `const n: number = f?.()` silently legal.
 *
 * RECORDED residues, still OUTSIDE this stage (the expectation below is OURS):
 * `getCallSignaturesOfType`'s own union arm still answers the CONCATENATION — a plain
 * replacement by the combined list makes every REFUSED union answer `emptyList()`, i.e.
 * "not callable", so it needs a `?: concatenation` tail and no row measured here moves
 * without it; a union whose members are BOTH overloaded is silent where tsc reports TS2349
 * (retiring the `≥2` suppression alone does NOT change that — the `differ` branch's
 * non-generic silence, (CHK.94), is a SECOND suppression above it); `f?.(1)`'s ARGUMENT is
 * still unchecked (the round-408 pre-pass consumes the call); `this` parameters are not
 * modelled ([Signature] has no `thisParameter`, tsc reports TS2684); and a GENERIC combined
 * signature does not infer its type argument from the call, so `(number[] | string[]).map`
 * answers `any` where tsc answers `(string | number)[]`.
 *
 * MEASURED DIVERGENCES this stage does not close, both FORM: our union member ORDER
 * (`.slice` on `number[] | string[]` prints `number[] | string[]` where BOTH references
 * print `string[] | number[]`), and an intersection of function types printed without
 * parentheses (`(v: number) => void & (v: string) => void`).
 */
class UnionCalleeStage2Test {

    private fun rows(src: String, realLibs: Boolean = false): List<Pair<Int, String>> =
        diagnose(
            src,
            directives = if (realLibs) "// @strict: true\n// @useRealLibs: true" else "// @strict: true",
        ).map { it.code to it.message }

    private fun decl(t: String) = "Type '$t' is not assignable to type 'boolean'."
    private fun arg(a: String, p: String) = "Argument of type '$a' is not assignable to parameter of type '$p'."

    // ------------------------------------------------------------------
    // 1. The ARRAY FALLBACK
    // ------------------------------------------------------------------

    /**
     * `filter` has TWO overloads on BOTH members, so PASS 1 finds nothing and PASS 2 is
     * refused (`multipleOverloadSets`) — exactly tsc's `!length(result)` precondition.
     */
    @Test
    fun `an array union's filter is answered by the fallback array`() =
        assert(rows(
            """
            declare const u: number[] | string[];
            const f: boolean = u.filter(x => true);
            export {}
            """.trimIndent(),
            realLibs = true,
        ) == listOf(2322 to decl("(string | number)[]")))

    /** A READONLY member makes the fallback a `ReadonlyArray` — tsc's `someType`. */
    @Test
    fun `a readonly member still folds into one element union`() =
        assert(rows(
            """
            declare const u: number[] | readonly string[];
            const f: boolean = u.filter(x => true);
            export {}
            """.trimIndent(),
            realLibs = true,
        ) == listOf(2322 to decl("(string | number)[]")))

    /** Three members fold into one element union, not pairwise. */
    @Test
    fun `three array members fold into one element union`() =
        assert(rows(
            """
            declare const u: number[] | string[] | boolean[];
            const f: boolean = u.filter(x => true);
            export {}
            """.trimIndent(),
            realLibs = true,
        ) == listOf(2322 to decl("(string | number | boolean)[]")))

    /** `concat` is the same shape one member over — two overloads on both sides. */
    @Test
    fun `an array union's concat is answered by the fallback array`() =
        assert(rows(
            """
            declare const u: number[] | string[];
            const c: boolean = u.concat();
            export {}
            """.trimIndent(),
            realLibs = true,
        ) == listOf(2322 to decl("(string | number)[]")))

    /**
     * The GATE: a member the two passes DO combine keeps their answer. `push` and
     * `indexOf` intersect their element to `never` (PASS 2) and must not be relaxed to
     * the fallback's `string | number`, which would accept both arguments.
     */
    @Test
    fun `a combined member is not displaced by the fallback`() {
        val d = rows(
            """
            declare const u: number[] | string[];
            u.push(1);
            u.indexOf("s");
            export {}
            """.trimIndent(),
            realLibs = true,
        )
        assert(d == listOf(
            2345 to arg("1", "never"),
            2345 to arg("\"s\"", "never"),
        ))
    }

    /** A NON-array member refuses the fallback outright. */
    @Test
    fun `negative control - a non-array member refuses the fallback`() {
        val d = rows(
            """
            interface Q { filter(cb: (x: number) => boolean): Q }
            declare const u: number[] | Q;
            const f: boolean = u.filter(x => true);
            export {}
            """.trimIndent(),
            realLibs = true,
        )
        assert(d == listOf(2322 to decl("number[] | Q")))
    }

    // ------------------------------------------------------------------
    // 2. getIntersectedSignatures — the CALLBACK of a union callee
    // ------------------------------------------------------------------

    /**
     * The pure shape: no arrays, no methods, one argument. PASS 2 intersects the two
     * callback parameter types and the fold UNIONS them back for the arrow.
     */
    @Test
    fun `a callback argument to a union callee is typed by the unioned parameters`() =
        assert(rows(
            """
            declare const u: ((cb: (v: number) => void) => void) | ((cb: (v: string) => void) => void);
            u(v => { const w: boolean = v; });
            export {}
            """.trimIndent()
        ) == listOf(2322 to decl("string | number")))

    /**
     * And it survives beside a POSITIONAL parameter that intersects to `never` — the two
     * halves of `combineUnionParameters` are the same loop with one combiner each way.
     */
    @Test
    fun `a callback beside an intersecting positional parameter is still typed`() {
        val d = rows(
            """
            declare const u: ((cb: (v: number) => void, b: 1) => void) | ((cb: (v: string) => void, b: 2) => void);
            u(v => { const w: boolean = v; }, 1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("1", "never"),
        ))
    }

    /** The array shape the family is named for. */
    @Test
    fun `forEach on an array union types its callback parameter`() =
        assert(rows(
            """
            declare const u: number[] | string[];
            u.forEach(x => { const w: boolean = x; });
            export {}
            """.trimIndent(),
            realLibs = true,
        ) == listOf(2322 to decl("string | number")))

    /**
     * The fold is reachable from a bare INTERSECTION annotation too — this is
     * `getIntersectedSignatures` with no union callee anywhere, i.e. the mechanism rather
     * than its (CHK.97) caller.
     */
    @Test
    fun `an intersection-typed parameter types the arrow it receives`() =
        assert(rows(
            """
            declare function take(f: ((v: number) => void) & ((v: string) => void)): void;
            take(v => { const w: boolean = v; });
            export {}
            """.trimIndent()
        ) == listOf(2322 to decl("string | number")))

    /** The same fold through a VARIABLE annotation rather than an argument. */
    @Test
    fun `an intersection annotation types an arrow initializer's parameter`() =
        assert(rows(
            """
            const j: ((v: number) => void) & ((v: string) => void) = (v) => { const w: boolean = v; };
            export {}
            """.trimIndent()
        ) == listOf(2322 to decl("string | number")))

    /** NEGATIVE control — a single-signature callee's callback is unchanged. */
    @Test
    fun `negative control - a single array's forEach types its callback as before`() =
        assert(rows(
            """
            declare const a: number[];
            a.forEach(x => { const w: boolean = x; });
            export {}
            """.trimIndent(),
            realLibs = true,
        ) == listOf(2322 to decl("number")))

    // ------------------------------------------------------------------
    // 3. The OPTIONAL-call result
    // ------------------------------------------------------------------

    /** `f?.()` short-circuits, so its result carries `| undefined`. */
    @Test
    fun `an optional call on a nullish union answers the return with undefined`() {
        val d = rows(
            """
            declare const f: ((a: string) => number) | undefined;
            const r: boolean = f?.("x");
            const g: number = f?.("x");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("number | undefined"),
            2322 to "Type 'number | undefined' is not assignable to type 'number'.",
        ))
    }

    /** A union of SEVERAL callable members beside the nullish one combines first. */
    @Test
    fun `an optional call combines the non-nullish members and then adds undefined`() =
        assert(rows(
            """
            declare const f: ((a: string) => number) | ((a: string) => string) | undefined;
            const r: boolean = f?.("x");
            export {}
            """.trimIndent()
        ) == listOf(2322 to decl("string | number | undefined")))

    /**
     * NEGATIVE control — a NON-optional call on the same union is TS2722 and its result
     * carries NO `undefined` (tsc resolves it against the non-nullish part).
     */
    @Test
    fun `negative control - a non-optional call on a nullish union adds no undefined`() {
        val d = rows(
            """
            declare const f: ((a: string) => number) | undefined;
            const r: boolean = f("x");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("number"),
            2722 to "Cannot invoke an object which is possibly 'undefined'.",
        ))
    }

    /** NEGATIVE control — an optional call on a NON-nullish callee is unchanged. */
    @Test
    fun `negative control - an optional call on a non-nullish callee adds no undefined`() =
        assert(rows(
            """
            declare const f: (a: string) => number;
            const r: boolean = f?.("x");
            export {}
            """.trimIndent()
        ) == listOf(2322 to decl("number")))
}
