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
 * (JIT.1)(e) round 821 — the behavioural gate for the three-way split of
 * `Checker.tryInferSingleTypeParamFromArgs`, the LAST method in the (JIT.1)
 * census.
 *
 * It was **11,930 bytecodes**, 1.5x HotSpot's 8,000-byte `HugeMethodLimit`, so
 * it was never JIT-compiled and ran interpreted for the whole process. It is now
 * an entry of **1,869** plus `tispGatherAnchorCandidates` (3,054),
 * `tispGatherCallbackCandidates` (5,388) and `tispCheckConstraint` (1,503).
 *
 * **Why this one needed a different argument from every other split in the arc.**
 * Round 820 measured it rather than attempting it: the bytecodes are FLAT (the
 * largest 25-line window is 449 of 11,930, and 22% of the method is INLINED
 * stdlib bodies charged to their call sites), and the body is essentially ONE
 * `for (tp in orderedTps)` loop whose regions all touch the same locals. There is
 * no fat region a contiguity argument can lift, so the boundaries came from a
 * scripted DATA-FLOW analysis — per-region read/write sets, liveness and exit
 * classification — in `scripts/tisp_split_analyze.py`.
 *
 * What that analysis found, and what makes the split exact rather than careful:
 *
 *  * `candidates` is only ever APPENDED to, in both passes. A mutated container
 *    crosses a call boundary for free, so it is a `MutableList` parameter and not
 *    one line of the moved text changes;
 *  * `tpSawAnyArg` is the ONLY rebound local that outlives its region, so it is
 *    RETURNED — never fielded. The pin for it is
 *    [tispGatherAnchorCandidates hands the any-arg flag back as a RETURN value];
 *  * `mapperPairs` is READ by pass 2 (the B83.4b/d branches consult the type
 *    parameters anchored by EARLIER iterations of the `orderedTps` loop) and
 *    never written there, so it is passed as a read-only `List`;
 *  * every one of the 22 whole-function `return null`s in the moved text still
 *    reads `return null`, because each helper returns `Boolean?` and each call
 *    site writes `?: return null`. No region holds a `continue`/`break` that
 *    targeted the caller's loop (measured 0/0/0), which is what makes plain
 *    helpers legal here at all — round 819's target needed a one-iteration frame
 *    for exactly that reason.
 *
 * **What this class pins that a size check cannot.** `HugeMethodLimitTest` sees
 * the bytecode counts; it cannot see that each helper still runs for the shape it
 * owns, that the candidate list really is the caller's, that the any-arg flag
 * survived the boundary, or that the constraint leg's diagnostics still carry the
 * right POSITION — the entry's `source` and `fileName` are both `String?` and
 * `constraint`/`firstWidened` are both `Type`, so a positional permutation would
 * type-check and be wrong. Every argument is therefore passed BY NAME, and
 * [tispCheckConstraint owns the structured-constraint TS2345] checks the reported
 * line and character against the source text rather than only the message.
 *
 * The seam table (each ablation's predicted vs actual failure count, and the two
 * seams no substitution can express) is in
 * `docs/perf/setup-phase-and-huge-methods.md` § 26.
 */
class TispSplitTest {

    // ── tispGatherAnchorCandidates ────────────────────────────────────────────

    /**
     * Pass 1's candidates land in the CALLER's list — the whole point of passing
     * a `MutableList` rather than returning a fresh one. A helper appending to a
     * list of its own would leave `candidates` empty at the caller, the entry
     * would `return null`, `T` would stay open and this TS2345 would vanish.
     */
    @Test
    fun `tispGatherAnchorCandidates appends to the caller's candidate list`() {
        val ds = diagnose(
            """
            declare function identity<T>(value: T): T;
            declare function needString(s: string): void;
            export function f() { needString(identity(42)); }
            """
        )
        val ts2345 = ds.filter { it.code == 2345 }
        assert(ts2345.size == 1)
        assert(
            ts2345[0].message ==
                "Argument of type 'number' is not assignable to parameter of type 'string'."
        )
    }

    /**
     * Round 440's any-arg fallback: at a RETURN-TYPE site an `any`-typed argument
     * is soft-skipped and, when it is the ONLY reason the candidate list is empty,
     * binds `T = any` instead of leaving the return's type parameter un-inferred.
     *
     * That flag is the one value the data-flow analysis found REBOUND across the
     * boundary, and it is handed back as the helper's return value.
     *
     * **This pin does NOT discriminate the loss of it, measured (arm A4), and is
     * kept as the labelled negative case:** without the flag the call's return
     * stays a bare `T`, and a bare `Type.TypeParam` source relates to most
     * targets, so the argument position is silent either way. The pin below,
     * which reads the same fallback through ARITHMETIC, is the one that fails.
     */
    @Test
    fun `tispGatherAnchorCandidates hands the any-arg flag back as a RETURN value`() {
        val ds = diagnose(
            """
            namespace Debug {
                export function checkDefined<T>(value: T | null | undefined): T { return value as T; }
            }
            interface Loc { pos: number; end: number; }
            declare function getLoc(): unknown;
            declare function needNumber(n: number): void;
            export function f() {
                const { pos } = getLoc() as Loc;
                needNumber(Debug.checkDefined(pos));
            }
            """
        )
        assert(ds.none { it.code == 2345 })
    }

    /**
     * The same fallback read through ARITHMETIC rather than an argument position,
     * because the argument gate turned out not to discriminate it: with the flag
     * forced to `false` the call's return type stays the bare type parameter, and
     * a bare `Type.TypeParam` source relates to most targets, so
     * `needNumber(Debug.checkDefined(pos))` is silent either way.
     *
     * **This one DOES discriminate it** (ablation arm A4: 68 pins ran, this one
     * failed, alone). The reason the argument position cannot and this can is
     * worth keeping: an un-inferred type parameter is invisible to the relation,
     * which accepts it against nearly anything, and visible to the arithmetic
     * pass, which does not.
     */
    @Test
    fun `the any-arg fallback keeps an arithmetic consumer quiet`() {
        val ds = diagnose(
            """
            namespace Debug {
                export function checkDefined<T>(value: T | null | undefined): T { return value as T; }
            }
            interface Loc { pos: number; end: number; }
            declare function getLoc(): unknown;
            export function f(): number {
                const { pos, end } = getLoc() as Loc;
                return Debug.checkDefined(end) - pos;
            }
            """
        )
        assert(ds.none { it.code == 2362 })
        assert(ds.none { it.code == 2365 })
    }

    // ── tispGatherCallbackCandidates ──────────────────────────────────────────

    /**
     * B83.1 gate (f): the callback argument's ANNOTATED parameter anchors `tp`.
     * This is a pass-2-only shape — pass 1 skips function-typed parameters
     * entirely (`isFnTypedOfT = false` there, by construction since B83.4a).
     */
    @Test
    fun `tispGatherCallbackCandidates gathers from an annotated lambda parameter`() {
        val ds = diagnose(
            """
            declare function pick<T>(cb: (x: T) => void): T;
            declare function needString(s: string): void;
            export function f() { needString(pick((n: number) => {})); }
            """
        )
        val ts2345 = ds.filter { it.code == 2345 }
        assert(ts2345.size == 1)
        assert(
            ts2345[0].message ==
                "Argument of type 'number' is not assignable to parameter of type 'string'."
        )
    }

    /**
     * B83.4d: `T` comes from re-typing an UN-annotated lambda body under the
     * binding an EARLIER `orderedTps` iteration put in `mapperPairs` (`S := number`
     * from `arg`, then `x => x` re-typed as `number`).
     *
     * So the helper must see the caller's live `mapperPairs`, not an empty list —
     * with an empty one `otherTpMapped` is null, the branch never fires, `T` stays
     * open and this TS2345 disappears.
     */
    @Test
    fun `tispGatherCallbackCandidates reads the mapperPairs earlier iterations filled`() {
        val ds = diagnose(
            """
            declare function apply<S, T>(arg: S, cb: (x: S) => T): T;
            declare function needString(s: string): void;
            export function f() { needString(apply(1, x => x)); }
            """
        )
        val ts2345 = ds.filter { it.code == 2345 }
        assert(ts2345.size == 1)
        assert(
            ts2345[0].message ==
                "Argument of type 'number' is not assignable to parameter of type 'string'."
        )
    }

    // ── tispCheckConstraint ───────────────────────────────────────────────────

    /**
     * B98.r118: a primitive argument whose inferred type fails a STRUCTURED
     * constraint is TS2345 with the CONSTRAINT as the displayed parameter type.
     *
     * The position is checked against the source text, not just asserted to
     * exist: `source` and `fileName` are both `String?` in the helper's parameter
     * list, so a positional permutation type-checks — it would report the whole
     * program text as the file name and compute the line and character from the
     * string `"t.ts"`.
     */
    @Test
    fun `tispCheckConstraint owns the structured-constraint TS2345`() {
        val source = """
            interface Item { id: number }
            declare function foo<T extends Item>(x: T): void;
            export function f() { foo("abc"); }
        """.trimIndent()
        val ds = diagnose(source)
        val ts2345 = ds.filter { it.code == 2345 }
        assert(ts2345.size == 1)
        val d = ts2345[0]
        assert(
            d.message ==
                "Argument of type 'string' is not assignable to parameter of type 'Item'."
        )
        assert(d.fileName == "t.ts")
        // `diagnose` prepends the directives line, so the reported position is
        // resolved against exactly this text. Both halves matter: `line` is
        // computed from `source` and `start` from the ARGUMENT node, so a
        // `source`/`fileName` permutation — which type-checks, both being
        // `String?` — resolves the line against the four characters `t.ts` and
        // lands on the directives line instead of this one.
        //
        // ONE HARNESS FACT, measured here rather than assumed: `line`/`character`
        // are resolved against the text INCLUDING the directives line `diagnose`
        // prepends, while `start` is an offset into the text WITHOUT it (the two
        // differ by exactly 17 characters for this fixture). That is HEAD's
        // behaviour — it reproduces identically on the pre-split binary — so this
        // pin uses the (line, character) pair, which is the half the constraint
        // helper computes from `source` and therefore the half a permutation
        // destroys.
        val full = "// @strict: true\n" + source
        val ln = d.line
        val ch = d.character
        assert(ln != null)
        assert(ch != null)
        val lineText = full.split("\n")[ln]
        assert(lineText.contains("foo(\"abc\")"))
        assert(ch in lineText.indexOf("foo(")..lineText.indexOf(");"))
    }

    /**
     * B273: when the failing candidate was anchored by an ARROW/FUNCTION argument's
     * RETURN, TypeScript reports TS2322 at the RETURN EXPRESSION with a related
     * TS6502 at the callback signature — not the coarse whole-argument TS2345.
     *
     * Both diagnostics are built inside the constraint helper, and the related
     * information is derived from the PARAMETER declaration, which is why `params`
     * is in its signature at all.
     */
    @Test
    fun `tispCheckConstraint owns the callback-return TS2322 and its TS6502 related info`() {
        val ds = diagnose(
            """
            interface Item { id: number }
            declare function chain<T extends Item>(cb: (x: number) => T): void;
            export function f() { chain(x => "abc"); }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'Item'.")
        val related = ts2322[0].relatedInformation
        assert(related.size == 1)
        assert(related[0].code == 6502)
        assert(
            related[0].message ==
                "The expected type comes from the return type of this signature."
        )
        // the coarse whole-argument form must NOT also fire
        assert(ds.none { it.code == 2345 })
    }

    /**
     * NEGATIVE CONTROL for the constraint leg: a candidate that SATISFIES the
     * constraint is silent, so the pins above are discriminating a real rejection
     * and not merely the presence of a constraint.
     */
    @Test
    fun `a satisfied constraint emits nothing - negative control`() {
        val ds = diagnose(
            """
            interface Item { id: number }
            declare function foo<T extends Item>(x: T): void;
            export function f(it: Item) { foo(it); }
            """
        )
        assert(ds.none { it.code == 2345 })
        assert(ds.none { it.code == 2322 })
    }

    // ── what STAYED in the entry ──────────────────────────────────────────────

    /**
     * The multi-argument conflict detection and its literal-form TS2345 stay in
     * the entry — they read `sig` (for `tparamMentionedInFunctionTypeDeep`) and
     * `effectiveCandidates`, and at 323 bytecodes they buy no margin. The pin is
     * here so a later round that DOES move them has a behavioural gate: the
     * display must stay LITERAL (`'3'` / `'""'`), which is what distinguishes this
     * emission from the standard argument loop's widened one.
     */
    @Test
    fun `the multi-argument conflict emission keeps its literal display`() {
        val ds = diagnose(
            """
            declare function g<T>(a: T, b: T, cb: (x: T) => void): void;
            export function f() { g("", 3, x => {}); }
            """
        )
        val ts2345 = ds.filter { it.code == 2345 }
        assert(ts2345.size == 1)
        assert(
            ts2345[0].message == "Argument of type '3' is not assignable to parameter of type '\"\"'."
        )
    }
}
