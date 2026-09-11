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
 * (CHK.97) stage 3 — the ARGUMENT check of a call whose callee TYPE is a NULLISH UNION.
 *
 * The queue item states this as "`f?.(1)`'s ARGUMENT check", and the axis it names is
 * wrong: measured against tsgo 7.0.2 AND pristine `typescript@6.0.3`, `?.` alone is
 * innocent — `g?.(1)` on a plain `Fn` reports, and so does `arr[0]?.(1)` on a `Fn[]`.
 * What was silent is a callee whose TYPE is a union carrying a nullish member, which
 * includes the `?.`-free `if (zu) { zu(1) }` (an Identifier callee is not flow-narrowed,
 * so the DECLARED union reaches the pre-pass). The population is a TYPE, not a token.
 *
 * TWO edits in [Checker.ccetUnionCalleeChecks] and its one caller carry it.
 *
 * **1. the HAND-OFF.** The round-408 pre-pass answered a `Boolean`, and `true` meant the
 * caller RETURNED — so the two FP suppressions it owns (strip the nullish members of an
 * optional call; re-narrow a narrowable reference) could only be spent by consuming the
 * call, and the argument check never ran. It now answers a `Type?`: null = consumed,
 * otherwise the EFFECTIVE callee type the caller resolves signatures against. That type
 * is the STRIPPED one and never the original union — `Fn1 | Fn2 | undefined` combines to
 * a single `never` parameter, where the original union's `getCallSignaturesOfType`
 * CONCATENATION reads as an overload set and prints TS2769. It is the argument-side
 * mirror of the RESULT-side strip stage 2 put in [Checker.getReturnTypeOfCallExpression].
 *
 * **2. the WIDENED STRIP.** The optional-call nullish strip used to sit inside the
 * narrowable-reference gate (`Identifier || PropertyAccessExpression`), which is about
 * narrowABILITY and belongs to the other suppression. For every OTHER callee kind the
 * nullish member survived into the case-(b) verdict and produced an OURS-ONLY TS2349 that
 * neither reference emits — measured **four** of them, one per callee kind: element
 * access, call expression, parenthesized, and a chained optional call.
 *
 * RECEIPT (`scripts/ref_matrix.py`, three fixtures, both references agreeing on message
 * AND span, zero REF-SPLIT and zero TEXT-DIFF throughout): agree 6 -> 18, **ours-only
 * 4 -> 0**, missing 16 -> 4. The 8-profile grid is `added=0 removed=0` on all eight and
 * is a real GATE rather than a control — a positive-control arm counted **49-101
 * hand-offs per profile**, i.e. ~542 calls whose arguments tsc's own sources had never
 * had checked, all of them correct.
 *
 * RECORDED residues, measured and OUTSIDE this stage (no pin asserts them — CLAUDE.md's
 * countdown rule):
 *  - `arr?.[0]` on a `(Fn | undefined)[] | undefined` types as `any`, so the call below it
 *    is silent for a reason that has nothing to do with the callee union; the element
 *    access alone is a missing TS2322 with no call in sight.
 *  - a NON-optional nullish union is TS2721/2722/2723 and CONSUMES the call, where both
 *    references additionally report the argument row.
 *  - a combined signature of ONE surviving member loses the union ARITY path, so
 *    `ar?.("x", "y")` on a `Fn | undefined` is silent — stage 2's measured law that
 *    TS2554 fires for a FUNCTION-DECLARATION callee only; unchanged by this stage
 *    (before and after arms read identically).
 *  - a union of two GENERIC members with non-identical type parameters plus `undefined`
 *    stays silent where both references report TS2349; unchanged by this stage.
 */
class UnionCalleeStage3ArgumentTest {

    private fun rows(src: String): List<Pair<Int, String>> =
        diagnose(src, directives = "// @strict: true").map { it.code to it.message }

    private fun arg(a: String, p: String) =
        "Argument of type '$a' is not assignable to parameter of type '$p'."

    private val fn = "type Fn = (a: string) => void;\n"

    // ------------------------------------------------------------------
    // 1. the HAND-OFF — the argument of a nullish-union callee is checked
    // ------------------------------------------------------------------

    /** The shape the queue item names. Both references: TS2345 at the argument. */
    @Test
    fun `an optional call over a Fn or undefined callee checks its argument`() =
        assert(rows(
            fn + """
            declare const f: Fn | undefined;
            f?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** `null` is the other nullish constituent and takes the same leg. */
    @Test
    fun `a Fn or null callee checks its argument`() =
        assert(rows(
            fn + """
            declare const p: Fn | null;
            p?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** Both nullish constituents at once — the strip removes every one of them. */
    @Test
    fun `a Fn or undefined or null callee checks its argument`() =
        assert(rows(
            fn + """
            declare const ok: Fn | undefined | null;
            ok?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** An anonymous call-signature member, not a named alias. */
    @Test
    fun `an anonymous call signature beside undefined checks its argument`() =
        assert(rows(
            """
            declare const zo: { (a: string): void } | undefined;
            zo?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** An OPTIONAL MEMBER — a PropertyAccess callee whose type is `T | undefined`. */
    @Test
    fun `an optional member called optionally checks its argument`() =
        assert(rows(
            """
            declare const meth: { m?: (a: string) => void };
            meth.m?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /**
     * NO `?.` ANYWHERE. An Identifier callee is not flow-narrowed, so the DECLARED
     * `Fn | undefined` reaches the pre-pass, whose SECOND suppression re-narrows it —
     * and then used to consume the call. This is the pin that proves the population is
     * a callee TYPE rather than the optional-call token.
     */
    @Test
    fun `a truthiness-narrowed plain call over a nullish union checks its argument`() =
        assert(rows(
            fn + """
            declare const zu: Fn | undefined;
            if (zu) { zu(1); }
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /**
     * THE INVARIANT-1 PIN. Two DIFFERING callable members plus `undefined`: the stripped
     * pair COMBINES into one signature whose parameter is `string & number` = `never`, so
     * the row names `never`. Resolving against the ORIGINAL union instead would read
     * `getCallSignaturesOfType`'s CONCATENATION as an overload set and print TS2769 —
     * a different code, which is why this pin asserts the whole row.
     */
    @Test
    fun `a differing callable pair beside undefined combines to a never parameter`() =
        assert(rows(
            """
            type Fn1 = (a: string) => void;
            type Fn2 = (a: number) => void;
            declare const a: Fn1 | Fn2 | undefined;
            a?.(true);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("true", "never")))

    // ------------------------------------------------------------------
    // 2. the WIDENED STRIP — four callee kinds that used to be OURS-ONLY TS2349
    // ------------------------------------------------------------------

    /**
     * An ELEMENT-ACCESS callee. Before the strip was hoisted out of the
     * narrowable-reference gate this was `This expression is not callable.` with a
     * `Not all constituents ... are callable.` chain — a false positive on legal code.
     */
    @Test
    fun `an element access callee over a nullish element union checks its argument`() =
        assert(rows(
            fn + """
            declare const arr: (Fn | undefined)[];
            arr[0]?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** A CALL-EXPRESSION callee. */
    @Test
    fun `a call expression callee returning a nullish union checks its argument`() =
        assert(rows(
            fn + """
            declare function mk(): Fn | undefined;
            mk()?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** A PARENTHESIZED callee - neither an Identifier nor a PropertyAccess. */
    @Test
    fun `a parenthesized callee over a nullish union checks its argument`() =
        assert(rows(
            fn + """
            declare const q: Fn | undefined;
            (q)?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** A CHAINED optional call - the callee is itself an optional CallExpression. */
    @Test
    fun `a chained optional call checks the inner call's argument`() =
        assert(rows(
            fn + """
            declare const c2: (() => Fn | undefined) | undefined;
            c2?.()?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    // ------------------------------------------------------------------
    // 3. NEGATIVE CONTROLS - each pinned as the POSITIVE it reports
    // ------------------------------------------------------------------

    /**
     * THE INVARIANT-2 PIN. A `number` member is not callable, so the `allCallable` gate
     * REFUSES the hand-off and the case-(b) verdict stands. Dropping that gate turns this
     * into a TS2345 about an argument of a callee that has no signatures.
     */
    @Test
    fun `a union carrying a non-callable member stays TS2349 under an optional call`() =
        assert(rows(
            fn + """
            declare const bad: Fn | number | undefined;
            bad?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2349 to "This expression is not callable."))

    /** Every constituent non-callable - the case-(a) path, reached through the caller. */
    @Test
    fun `an all non-callable union under an optional call stays TS2349`() =
        assert(rows(
            """
            declare const nc: number | string | undefined;
            nc?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2349 to "This expression is not callable."))

    /**
     * THE INVARIANT-4 PIN. A NON-optional call over a nullish union is TS2721/2722/2723
     * and CONSUMES the call; the hand-off must not make that path emit twice, nor let the
     * case-(b) TS2349 escape beside it. Asserted as counts because both references
     * additionally report the argument row here and a full-list expectation would be a
     * countdown pin for that recorded residue.
     */
    @Test
    fun `a non-optional call over a nullish union reports TS2722 exactly once`() {
        val r = rows(
            fn + """
            declare const f: Fn | undefined;
            f(1);
            export {}
            """.trimIndent()
        )
        assert(r.count { it.first == 2722 } == 1)
        assert(r.none { it.first == 2349 })
        assert(r.none { it.first == 2769 })
    }

    /** A non-nullish union whose members are not callable together keeps case (c). */
    @Test
    fun `a non-nullish differing union still reports its combined arity`() =
        assert(rows(
            """
            declare const dd: ((a: string) => void) | ((a: number, b: number) => void);
            dd(1);
            export {}
            """.trimIndent()
        ) == listOf(2554 to "Expected 2 arguments, but got 1."))

    /** A NON-optional member call was already checked and must stay so. */
    @Test
    fun `a required member called optionally checks its argument`() =
        assert(rows(
            """
            declare const m1: { m: (a: string) => void };
            m1.m?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))

    /** A non-union optional call was never in the population and must stay reporting. */
    @Test
    fun `an optional call over a plain function type still checks its argument`() =
        assert(rows(
            fn + """
            declare const g: Fn;
            g?.(1);
            export {}
            """.trimIndent()
        ) == listOf(2345 to arg("number", "string")))
}
