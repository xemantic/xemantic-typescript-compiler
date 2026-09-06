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
 * (CHK.97) stage 1 — a UNION callee's signatures are tsc's `getUnionSignatures`
 * ([Checker.combineUnionSignatures], checker.ts:14313), every row below measured
 * against tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was written
 * (the two agree on every row here; the recorded divergence is the tuple base's
 * union ORDER, which no row here prints).
 *
 * What the family closes. The call RESULT was `any` for EVERY union callee —
 * `getReturnTypeOfCallExpression` / `…NewExpression` answered before any signature
 * was read — so ~30 measured TS2322 rows never fired and every downstream reader of
 * such a call was vacuous. Beside that: rest members were unchecked, a nullish
 * callee reported TS2349 for tsc's TS2722, `new (typeof A | typeof B)(…)` was a
 * false TS2351 whenever the members' constructor parameters differed at all, and the
 * ordinary call path read the CONCATENATED signature list as an overload set
 * (TS2769 where the combined signature's own TS2345 is the answer).
 *
 * The two passes, and why each is separately pinned. PASS 1 keeps a signature
 * PRESENT in every constituent — generic members need an exact match, non-generic
 * ones may partial-match with returns ignored — as a clone of the FIRST member's
 * with the returns unioned, and it can produce SEVERAL signatures, i.e. a genuine
 * overload set. PASS 2 runs only when pass 1 is empty AND at most ONE constituent is
 * overloaded, folding the members pairwise: the LONGEST parameter list, positions
 * INTERSECTED, a missing position contributing `unknown`, `minArgumentCount` = MAX,
 * and a REST position carrying `Array<the intersection of the ELEMENT types>` — not
 * the intersection of the array types, (CHK.83)'s rest lesson.
 *
 * RECORDED residues, each measured and each OUTSIDE this stage. THREE were closed by
 * (CHK.97) stage 2 and their pins live in `UnionCalleeStage2Test`: tsc's ARRAY FALLBACK
 * (checker.ts:15949), tsc's `getIntersectedSignatures` (:33085) for a callback ARGUMENT
 * of a union callee, and the OPTIONAL-call result. What is still open (the expectation
 * below is OURS): a union whose members are BOTH overloaded is silent where tsc reports
 * TS2349 (it was a WRONG TS2345 before), and retiring the `≥2` suppression alone does
 * NOT change that — the `differ` branch's non-generic silence, (CHK.94), is a SECOND
 * suppression above it; `getCallSignaturesOfType`'s own union arm still answers the
 * concatenation, so only the ONE call site reads the combined list; TS7006 does not fire
 * for an un-annotated parameter whose contextual type is a union of DIFFERING signatures
 * (tsc's `getContextualSignature` answers undefined there); `f?.(1)`'s ARGUMENT is still
 * unchecked; `this` parameters are not modelled at all (tsc intersects them and reports
 * TS2684 — [Signature] has no `thisParameter`); and TS2554 fires for a
 * FUNCTION-DECLARATION callee only, in BOTH directions, so a combined signature's
 * arity is never reported — the item's "the too-FEW TS2554 comes free" is measured
 * FALSE and is a pre-existing gap that has nothing to do with unions.
 */
class UnionCalleeSignatureTest {

    private fun rows(src: String, realLibs: Boolean = false): List<Pair<Int, String>> =
        diagnose(
            src,
            directives = if (realLibs) "// @strict: true\n// @useRealLibs: true" else "// @strict: true",
        ).map { it.code to it.message }

    private fun decl(t: String) = "Type '$t' is not assignable to type 'boolean'."
    private fun arg(a: String, p: String) = "Argument of type '$a' is not assignable to parameter of type '$p'."

    // ------------------------------------------------------------------
    // PASS 2 — the members are folded pairwise
    // ------------------------------------------------------------------

    /** r01: differing single parameters intersect to `never`; the returns union. */
    @Test
    fun `two single-signature members intersect their parameter and union their return`() {
        val d = rows(
            """
            declare const u: ((a: string) => number) | ((a: number) => string);
            const r: boolean = u("x");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("\"x\"", "never"),
        ))
    }

    /** r26: the same union reached through a type ALIAS. */
    @Test
    fun `an alias-typed union callee combines the same way`() {
        val d = rows(
            """
            type F = ((a: string) => number) | ((a: number) => string);
            declare const u: F;
            const r: boolean = u("x");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("\"x\"", "never"),
        ))
    }

    /** r25: object parameters fall to `getIntersectionType` INSIDE the reducer. */
    @Test
    fun `object parameters combine to an intersection and a missing member is reported`() {
        val d = rows(
            """
            declare const u: ((a: { p: number }) => number) | ((a: { q: string }) => string);
            const r: boolean = u({ p: 1, q: "s" });
            u({ p: 1 });
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("{ p: number; }", "{ p: number; } & { q: string; }"),
        ))
    }

    /** r04b: a REST position is `Array<the intersection of the ELEMENTS>`. */
    @Test
    fun `two rest members intersect their ELEMENT types - not their array types`() {
        val d = rows(
            """
            declare const u: ((...a: string[]) => number) | ((...a: number[]) => string);
            const r: boolean = u();
            u("x");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("\"x\"", "never"),
        ))
    }

    /** r19: `never | void` reduces to `void`, and a single combined signature reports TS2345 - not TS2769. */
    @Test
    fun `a never-returning member beside a void one answers void and reports one argument row`() {
        val d = rows(
            """
            declare const u: ((m: string) => never) | ((m: string) => void);
            const r: boolean = u("x");
            u(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("void"),
            2345 to arg("number", "string"),
        ))
    }

    /** r20: a member that is a callable OBJECT with an extra property still combines. */
    @Test
    fun `a callable object member combines with a bare function member`() {
        val d = rows(
            """
            declare const u: { (x: string): number; p: 1 } | ((x: string) => string);
            const r: boolean = u("x");
            u(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("number", "string"),
        ))
    }

    // ------------------------------------------------------------------
    // PASS 1 — a signature present in every constituent
    // ------------------------------------------------------------------

    /** r01b: the wider member PARTIAL-matches, so the narrower one's parameter list wins. */
    @Test
    fun `a partial match keeps the matched member's own parameter list`() {
        val d = rows(
            """
            declare const u: ((a: string) => number) | ((a: string | number) => string);
            const r: boolean = u("x");
            u(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("number", "string"),
        ))
    }

    /** r18: identical parameters and differing returns produce ONE signature. */
    @Test
    fun `identical parameters with differing returns union only the return`() {
        val d = rows(
            """
            declare const u: ((a: string) => string) | ((a: string) => number);
            const r: boolean = u("x");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(2322 to decl("string | number")))
    }

    /** r02: the LONGER parameter list wins and its arity governs both calls. */
    @Test
    fun `a longer parameter list is the one pass 1 keeps`() {
        val d = rows(
            """
            declare const u: ((a: string) => number) | ((a: string, b: number) => string);
            const r: boolean = u("x");
            const r2: boolean = u("x", 1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2554 to "Expected 2 arguments, but got 1.",
            2322 to decl("string | number"),
        ))
    }

    /** r03: an OPTIONAL trailing parameter makes pass 1 keep BOTH members - a genuine overload set. */
    @Test
    fun `an optional trailing parameter yields two signatures and the second is argument-checked`() {
        val d = rows(
            """
            declare const u: ((a: string) => number) | ((a: string, b?: number) => string);
            const r: boolean = u("x");
            const r2: boolean = u("x", 1);
            u("x", "y");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2322 to decl("string | number"),
            2345 to arg("string", "number"),
        ))
    }

    /** r22: three arities - pass 1 keeps the longest and both results are the single return. */
    @Test
    fun `three members of ascending arity keep the longest parameter list`() {
        val d = rows(
            """
            declare const u: ((a: string) => number) | ((a: string, b: number) => number) | ((a: string, b: number, c: boolean) => number);
            const r: boolean = u("x");
            const r2: boolean = u("x", 1, true);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("number"),
            2554 to "Expected 3 arguments, but got 1.",
            2322 to decl("number"),
        ))
    }

    /** r04: a rest member beside a fixed one - pass 1 keeps the FIXED one. */
    @Test
    fun `a rest member partial-matches a fixed one and the fixed parameter list wins`() {
        val d = rows(
            """
            declare const u: ((...a: string[]) => number) | ((a: string) => string);
            const r: boolean = u("x");
            u(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("number", "string"),
        ))
    }

    /** r08: ONE overloaded member - only the signature present in both survives. */
    @Test
    fun `an overloaded member contributes only the signature its partner also has`() {
        val d = rows(
            """
            interface A { (a: string): number; (a: number): number }
            interface B { (a: string): string; (a: boolean): string }
            declare const u: A | B;
            const r: boolean = u("x");
            u(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("number", "string"),
        ))
    }

    /** r10: pass 1 keeps TWO signatures, and only the arity-viable one is argument-checked. */
    @Test
    fun `pass 1 can produce an overload set and the arity-viable candidate reports alone`() {
        val d = rows(
            """
            interface A { (a: string): number; (a: number, b: string): number }
            declare const u: A | ((a: string | number) => string);
            const r: boolean = u("x");
            const r2: boolean = u(1, "y");
            u(true);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2322 to decl("string | number"),
            2345 to arg("boolean", "string"),
        ))
    }

    /** r13/r13b: the same rule at an interface METHOD's union. */
    @Test
    fun `a method reached through a union receiver combines its signatures`() {
        val d = rows(
            """
            interface Foo { m(a: string): number }
            interface Bar { m(a: number): string }
            declare const u: Foo | Bar;
            const r: boolean = u.m("x");
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("\"x\"", "never"),
        ))
    }

    @Test
    fun `a method union partial-matches through the wider member`() {
        val d = rows(
            """
            interface Foo { m(a: string): number }
            interface Bar { m(a: string | number): string }
            declare const u: Foo | Bar;
            const r: boolean = u.m("x");
            u.m(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("string | number"),
            2345 to arg("number", "string"),
        ))
    }

    // ------------------------------------------------------------------
    // Arity — `minArgumentCount` is the MAX of the members'
    // ------------------------------------------------------------------

    /**
     * The combined arity is reported by [Checker.unionCalleeArityDiagnostic] and not by
     * the ordinary gate: TS2554 fires for a FUNCTION-DECLARATION callee only, in BOTH
     * directions, and a union callee is a VARIABLE by construction — so the queue item's
     * "the too-FEW TS2554 comes free" is MEASURED FALSE.
     */
    @Test
    fun `too few arguments for the combined signature is TS2554 at the callee`() {
        val d = diagnose(
            """
            declare const u: ((a: string) => number) | ((a: string, b: number) => string);
            u("x");
            export {}
            """.trimIndent(),
            directives = "// @strict: true",
        )
        assert(d.map { it.code to it.message } ==
            listOf(2554 to "Expected 2 arguments, but got 1."))
        assert(d[0].character == 1)
    }

    /** The MAX of three members' minimums governs. */
    @Test
    fun `the combined minimum is the MAX of the members' minimums`() =
        assert(rows("""
            declare const u: ((a: string) => number) | ((a: string, b: number) => number) | ((a: string, b: number, c: boolean) => number);
            u("x");
            export {}
            """.trimIndent()) == listOf(2554 to "Expected 3 arguments, but got 1."))

    /** r04: too MANY arguments, anchored at the first excess one. */
    @Test
    fun `too many arguments for the combined signature is TS2554 at the first excess`() {
        val d = diagnose("""
            declare const u: ((...a: string[]) => number) | ((a: string) => string);
            u("x", "y");
            export {}
            """.trimIndent(), directives = "// @strict: true")
        assert(d.map { it.code to it.message } ==
            listOf(2554 to "Expected 1 arguments, but got 2."))
        assert(d[0].character == 8)
    }

    /** An optional trailing parameter widens the reported RANGE, as tsc prints it. */
    @Test
    fun `an optional parameter makes the arity a range`() =
        assert(rows("""
            declare const h: ((x: number, y?: string) => void) | ((x: boolean, y?: string) => void);
            h(1, "a", 3);
            export {}
            """.trimIndent()) == listOf(2554 to "Expected 1-2 arguments, but got 3."))

    /** A REST member admits any count — no arity row. */
    @Test
    fun `a combined rest signature admits any argument count`() =
        assert(rows("""
            declare const u: ((...a: string[]) => number) | ((...a: string[]) => string);
            u("x", "y", "z");
            export {}
            """.trimIndent()).isEmpty())

    /**
     * PASS 2's own arity: `minArgumentCount` is the MAX of the two members'. Here pass 1
     * finds nothing (neither parameter list subsumes the other), so the fold runs and the
     * combined minimum is 2 — `Expected 2 arguments, but got 1.` on tsgo 7.0.2 and
     * pristine `typescript@6.0.3` alike. The PASS-1 arity pins above cannot see this: a
     * pass-1 clone carries its own member's `minArgumentCount` and never the fold's.
     */
    @Test
    fun `the PASS 2 fold takes the MAX of the two minimums`() =
        assert(rows("""
            declare const u: ((a: string) => number) | ((a: number, b: number) => string);
            u("x");
            export {}
            """.trimIndent()) == listOf(2554 to "Expected 2 arguments, but got 1."))

    // ------------------------------------------------------------------
    // Generic members
    // ------------------------------------------------------------------

    /** r06: two generic members with IDENTICAL type parameters combine. */
    @Test
    fun `two generic members with identical type parameters combine`() {
        val d = rows(
            """
            declare const u: (<T>(a: T) => T) | (<T>(a: T) => string);
            const r: boolean = u(1);
            export {}
            """.trimIndent()
        )
        // RESIDUE: pristine prints `string | 1` — our inference widens the argument
        // literal before it reaches the combined signature's own type parameter.
        assert(d == listOf(2322 to decl("string | number")))
    }

    /** r07 / `betterErrorForUnionCall`: NON-identical type parameters REFUSE - the guard that must stay. */
    @Test
    fun `two generic members whose type parameters differ are not callable`() {
        val d = diagnose(
            """
            declare const u: (<T extends number>(a: T) => void) | (<T>(a: string) => void);
            u("x");
            export {}
            """.trimIndent(),
            directives = "// @strict: true",
        )
        assert(d.map { it.code } == listOf(2349))
        assert(d[0].messageChain == listOf(
            "  Each member of the union type '(<T extends number>(a: T) => void) | (<T>(a: string) => void)'" +
                " has signatures, but none of those signatures are compatible with each other."
        ))
    }

    // ------------------------------------------------------------------
    // Refusals the caller's own verdicts still own
    // ------------------------------------------------------------------

    /** r11: a member with NO call signatures stays case (b). */
    @Test
    fun `a non-callable member keeps the not-all-constituents verdict`() {
        val d = diagnose(
            """
            declare const u: (() => void) | { x: number };
            u();
            export {}
            """.trimIndent(),
            directives = "// @strict: true",
        )
        assert(d.map { it.code } == listOf(2349))
        assert(d[0].messageChain == listOf(
            "  Not all constituents of type '(() => void) | { x: number; }' are callable.",
            "    Type '{ x: number; }' has no call signatures.",
        ))
    }

    /** r11b: no callable member at all stays case (a). */
    @Test
    fun `no callable member keeps the no-constituent verdict`() {
        val d = diagnose(
            """
            declare const u: { x: number } | { y: string };
            u();
            export {}
            """.trimIndent(),
            directives = "// @strict: true",
        )
        assert(d.map { it.code } == listOf(2349))
        assert(d[0].messageChain == listOf(
            "  No constituent of type '{ x: number; } | { y: string; }' is callable."
        ))
    }

    /**
     * r09: BOTH members overloaded — tsc's `indexWithLengthOverOne === -1` bail. The
     * combination is refused and the `≥2`-overloaded suppression answers, so this is
     * SILENT where tsc reports TS2349; before this family it was a WRONG TS2345 off
     * the first signature of each member.
     */
    @Test
    fun `a union whose members are both overloaded refuses the combination`() =
        assert(rows(
            """
            interface A { (a: string): number; (a: number): number }
            interface B { (a: boolean): string; (a: object): string }
            declare const u: A | B;
            u("x");
            export {}
            """.trimIndent()
        ).isEmpty())

    // ------------------------------------------------------------------
    // A nullish callee — tsc checks nullability BEFORE it asks for signatures
    // ------------------------------------------------------------------

    /** r14: `f("x")` on `Fn | undefined` is TS2722, not the case-b TS2349. */
    @Test
    fun `a possibly-undefined callee is TS2722 and the call still resolves its return`() {
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

    @Test
    fun `a possibly-null callee is TS2721`() =
        assert(rows(
            """
            declare const f: ((a: string) => number) | null;
            f("x");
            export {}
            """.trimIndent()
        ) == listOf(2721 to "Cannot invoke an object which is possibly 'null'."))

    @Test
    fun `a callee possibly null or undefined is TS2723`() =
        assert(rows(
            """
            declare const f: ((a: string) => number) | null | undefined;
            f("x");
            export {}
            """.trimIndent()
        ) == listOf(2723 to "Cannot invoke an object which is possibly 'null' or 'undefined'."))

    /** An OPTIONAL call short-circuits, so nothing is reported for the callee itself. */
    @Test
    fun `an optional call on a nullish union reports nothing about the callee`() =
        assert(rows(
            """
            declare const f: ((a: string) => number) | undefined;
            f?.("x");
            export {}
            """.trimIndent()
        ).isEmpty())

    /** A nullish member beside a NON-callable one is still a genuine case-b TS2349. */
    @Test
    fun `a nullish member beside a non-callable one keeps the case-b verdict`() {
        val d = diagnose(
            """
            declare const f: ((a: string) => number) | { x: number } | undefined;
            f("x");
            export {}
            """.trimIndent(),
            directives = "// @strict: true",
        )
        assert(d.map { it.code } == listOf(2349))
    }

    // ------------------------------------------------------------------
    // The CONSTRUCT side
    // ------------------------------------------------------------------

    /** r17: differing constructor parameters COMBINE - they were a false TS2351. */
    @Test
    fun `a union of constructors combines and is not a TS2351`() {
        val d = rows(
            """
            class A { constructor(a: string) {} }
            class B { constructor(a: number) {} }
            declare const C: typeof A | typeof B;
            const r: boolean = new C("x");
            new C(true);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("A | B"),
            2345 to arg("\"x\"", "never"),
            2345 to arg("true", "never"),
        ))
    }

    /** r17b: the wider constructor partial-matches, so the narrower parameter list wins. */
    @Test
    fun `a union of constructors partial-matches through the wider member`() {
        val d = rows(
            """
            class A { constructor(a: string) {} }
            class B { constructor(a: string | number) {} }
            declare const C: typeof A | typeof B;
            const r: boolean = new C("x");
            new C(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2322 to decl("A | B"),
            2345 to arg("number", "string"),
        ))
    }

    /** `newOperator`'s guard: a member with NO construct signatures is still TS2351. */
    @Test
    fun `a union with a non-constructable member is still TS2351`() {
        val d = diagnose(
            """
            class A { constructor(a: string) {} }
            declare const C: typeof A | { x: number };
            new C("x");
            export {}
            """.trimIndent(),
            directives = "// @strict: true",
        )
        assert(d.map { it.code } == listOf(2351))
    }

    // ------------------------------------------------------------------
    // Real-lib receivers — the tuple / array unions
    // ------------------------------------------------------------------

    /**
     * r12: `[1] | [1, 2]` — pass 1's clone comes from the FIRST member, so the combined
     * `push` element is `1` and MEMBER ORDER is load-bearing. `push(1)` is legal.
     */
    @Test
    fun `a tuple union's push takes the FIRST member's element type`() {
        val d = rows(
            """
            declare const t: [1] | [1, 2];
            t.push(3);
            t.push(1);
            const i: boolean = t.indexOf(1);
            t.indexOf("s");
            export {}
            """.trimIndent(),
            realLibs = true,
        )
        assert(d == listOf(
            2345 to arg("3", "1"),
            2322 to decl("number"),
            2345 to arg("\"s\"", "1"),
        ))
    }

    /** r15: `number[] | string[]` — `push`'s and `indexOf`'s element intersect to `never`. */
    @Test
    fun `an array union's element positions intersect to never`() {
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

    /**
     * (CHK.97) stage 2 LANDED — tsc's ARRAY FALLBACK now answers this row; the pin lives
     * in `UnionCalleeStage2Test` and the shape is kept here as the boundary between the
     * two stages: `filter` has TWO overloads on BOTH members, so PASS 1 finds nothing and
     * PASS 2 is refused, which is exactly tsc's `!length(result)` precondition.
     */
    @Test
    fun `an array union's filter is answered by the array fallback`() =
        assert(rows(
            """
            declare const u: number[] | string[];
            const f: boolean = u.filter(x => true);
            export {}
            """.trimIndent(),
            realLibs = true,
        ) == listOf(2322 to decl("(string | number)[]")))

    // ------------------------------------------------------------------
    // The memo is keyed by the union's own Type.id
    // ------------------------------------------------------------------

    /**
     * Two DIFFERENT unions of the same member COUNT in one file must not share an
     * answer — INV.5(a) interns unions by their member-id list, so `Type.id` is exact
     * where any coarser key is a WRONG PROGRAM that no single-union row can see.
     */
    @Test
    fun `two same-sized unions in one file do not share a combined signature`() {
        val d = rows(
            """
            declare const u1: ((a: string) => number) | ((a: string) => string);
            declare const u2: ((a: boolean) => number) | ((a: boolean) => string);
            u1(1);
            u2(1);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2345 to arg("number", "string"),
            2345 to arg("number", "boolean"),
        ))
    }

    /** And the memo must SERVE: the same union called twice answers identically. */
    @Test
    fun `one union called twice answers the same combined signature`() {
        val d = rows(
            """
            declare const u: ((a: string) => number) | ((a: string) => string);
            u(1);
            u(true);
            export {}
            """.trimIndent()
        )
        assert(d == listOf(
            2345 to arg("number", "string"),
            2345 to arg("boolean", "string"),
        ))
    }

    // ------------------------------------------------------------------
    // Negative controls — a non-union callee is untouched
    // ------------------------------------------------------------------

    @Test
    fun `negative control - a single-signature callee is unchanged`() =
        assert(rows(
            """
            declare const f: (a: string) => number;
            const r: boolean = f("x");
            f(1);
            export {}
            """.trimIndent()
        ) == listOf(
            2322 to decl("number"),
            2345 to arg("number", "string"),
        ))

    @Test
    fun `negative control - a literal argument against a rest of literals is legal`() =
        assert(rows(
            """
            declare function h(...xs: 1[]): void;
            h(1);
            export {}
            """.trimIndent()
        ).isEmpty())
}
