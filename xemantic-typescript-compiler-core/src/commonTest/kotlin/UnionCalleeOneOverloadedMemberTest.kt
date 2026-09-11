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
 * (CHK.97) stage 3, deliverable D2b — a union callee with exactly ONE overloaded member
 * whose signature COMBINATION still refuses reports TS2349 *Each member of the union type
 * … has signatures, but none of those signatures are compatible with each other.*, the
 * same chain D2 gave the TWO-overloaded case.
 *
 * WHAT D2 LEFT BEHIND, AND WHY ITS STATED REASON WAS MEASURED FALSE. (P18.72) split the
 * old `≥2`-overloaded suppression by its REASON and KEPT a silence for exactly one
 * overloaded member, justified as the `unionOfArraysFilterCall` shape — a union where tsc
 * RUNS pass 2 and reports nothing. That shape never reaches the suppression at all:
 * stage 2's ARRAY FALLBACK answers `(Fizz[] | readonly Buzz[]).filter` at the RECEIVER
 * before a union callee is formed, and a libs-free `overloaded | plain` pair COMBINES in
 * pass 1. So the silence was suppressing only TRUE POSITIVES, and this deliverable removes
 * it.
 *
 * THE MECHANISM IS A CENSUS, NOT A GUESS. [Checker.computeCombinedUnionSignatures] can
 * answer null for five reasons and only TWO are reachable from this suppression site (a
 * constituent with no call signatures and a under-two-member union are excluded above it;
 * a non-null PASS-2 fold is never EMPTY, because it maps a non-empty master list):
 *
 *  * TWO OR MORE constituents carry an overload set, where tsc SKIPS pass 2 outright
 *    (`indexWithLengthOverOne === -1`) — D2's half; and
 *  * PASS 2 RAN and refused on GENERIC incompatibility
 *    ([Checker.unionCalleeGenericSignaturesIncompatible], tsc's
 *    `compareTypeParametersIdentical`) — this deliverable's half.
 *
 * In BOTH, tsc's `getUnionSignatures` answers the EMPTY list and `resolveCallExpression`'s
 * `!callSignatures.length` branch prints this chain. `overloadedMembers >= 2` IS
 * `multipleOverloadSets` — the same count over the same `getCallSignaturesOfType` — so the
 * second reason is exactly `overloadedMembers <= 1`, and the widening is the whole of ONE
 * reason rather than a guess about which one applies.
 *
 * THAT CLAIM WAS MEASURED BEFORE IT WAS TAKEN, AND THE MEASUREMENT DECIDED THE DESIGN. The
 * queue item records "thread the refusal REASON out of
 * [Checker.computeCombinedUnionSignatures]" as the clean long-term shape. An instrumented
 * build that did exactly that — a threaded reason memoized beside the answer and printed at
 * the suppression site — agreed with the COUNT on **21 of 21** reachable refusals (8 over
 * the fixture set, 13 over the whole 18,679-test suite) and produced no third reason
 * anywhere. The two designs are therefore indistinguishable, and the count is kept: a
 * second union-id-keyed cache beside [Checker.unionSignatureCache] (needed because a memo
 * HIT would otherwise serve a stale reason) is cost with no receipt. The thread is recorded
 * as a REFUSAL with that measurement, not forgotten.
 *
 * THE SAME CENSUS SIZES THE CHANGE: `overloadedMembers == 1` is reached **ZERO** times by
 * the entire suite, by all eight dashboard profiles and by `cronstrue` and `marked`. Every
 * refusal those populations produce is either `overloaded == 2` (already reporting since
 * D2) or `overloaded == 0` (the `differ` branch). So this widening cannot move a corpus
 * baseline, and these pins are its only gate.
 *
 * RECEIPT (`scripts/ref_matrix.py` over eleven fixtures under `build/bench/chk97f/fixtures`,
 * chain-aware since (P18.72); tsgo 7.0.2 and pristine `typescript@6.0.3` agreeing
 * throughout, zero REF-SPLIT, zero SPAN-DIFF, zero OURS-ONLY in either arm):
 * **missing 8 -> 0, agree 12 -> 15**. The eight formerly-missing rows do NOT all land as
 * AGREE, and the split is worth stating: **three land as AGREE and five land at the right
 * file, line, COLUMN and code with a different display** — every one of the five because
 * the union names a member whose ONLY member is a call signature, which is **(CHK.130)**:
 * [Checker.typeToString] parenthesizes such a member, so we print `ZzzA | (ZzzG)` where
 * both references print `ZzzA | ZzzG`. PRE-EXISTING, unowned, and NOT a D2b defect — the
 * proof is in the fixture `q4`, whose four rows differ only in whether that member carries
 * a PROPERTY beside its call signature: the three that do are byte-identical AGREE, the one
 * that does not is the TEXT-DIFF. D2b does not introduce (CHK.130); it surfaces five more
 * instances of it, because a diagnostic that never fired could not display anything wrong.
 * These pins therefore give the incompatible member a property, so they assert a display
 * all three compilers agree on rather than the one we want (CLAUDE.md's countdown rule).
 *
 * THE 8-PROFILE GRID IS A CONTROL AND THIS ROUND MEASURED WHY, WITH A COUNT. The counting
 * arm is the census above; it reads ZERO union-callee combination refusals of ANY kind on
 * all eight profiles. `added=0 removed=0` there proves INERTNESS and nothing about coverage
 * ((CHK.124)'s lesson).
 *
 * RECORDED residues, measured and NOT pinned:
 *  - `overloadedMembers == 0` reaches the SAME generic refusal and is still decided below
 *    by `differ` plus a recomputed [Checker.unionCalleeGenericSignaturesIncompatible] — a
 *    strictly more conservative approximation of the identical verdict. Collapsing the tail
 *    into "a refused combination is TS2349, whatever the counts" is what the full thread
 *    would license; it is (CHK.94)'s territory and was refused on SCOPE, NOT on evidence.
 *    The evidence in fact points the other way: ablation arm b4, which performs exactly
 *    that collapse, is **0 RED over the whole suite**, so the conservatism is a REDUNDANT
 *    GUARD on every reachable shape — (P18.72)'s a3 finding one layer out.
 *  - the CONSTRUCT twin (`new` on such a union) is a separate branch with its own sentence.
 */
class UnionCalleeOneOverloadedMemberTest {

    private fun rows(src: String) =
        diagnose(src, directives = "// @strict: true")

    private fun codes(src: String) = rows(src).map { it.code }

    private fun chainOf(src: String) = rows(src).map { it.messageChain }

    private fun notCompatible(display: String) =
        listOf("  Each member of the union type '$display' has signatures, but none of those signatures are compatible with each other.")

    /**
     * ONE overloaded member beside one generic single-signature member whose type
     * parameter carries a DIFFERENT constraint — tsc's PASS 2 runs and bails on
     * `compareTypeParametersIdentical`. `ZzzGp`'s `z` property is there only so the union
     * renders as both references render it; see (CHK.130) in this class's KDoc.
     */
    private val incompatible = """
        interface ZzzA { <T extends string>(a: T): void; <T extends string>(a: T, b: number): void }
        interface ZzzGp { <T extends number>(a: T): void; z: number }
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------
    // 1. the positives — exactly one overloaded member, combination refused
    // ------------------------------------------------------------------

    /**
     * The shape the deliverable names. Both references report TS2349 at column 1 with the
     * chain below; we were SILENT, because the one-overloaded suppression consumed the
     * call before any verdict could be reached.
     */
    @Test
    fun `a call on a union of one overloaded and one generically incompatible member is not callable`() {
        val d = rows(
            incompatible + """
            declare const zap: ZzzA | ZzzGp;
            zap("x");
            export {}
            """.trimIndent()
        )
        assert(d.map { it.code to it.message } == listOf(
            2349 to "This expression is not callable."
        ))
        assert(d[0].messageChain == notCompatible("ZzzA | ZzzGp"))
    }

    /**
     * The ARITY row does not win here either: with no combined signature there is no
     * parameter list to count against, so tsc reports the un-callable verdict and nothing
     * else. Pinned because [Checker.unionCalleeArityDiagnostic] runs only on a NON-null
     * combination — a widening that reached it would print an arity taken off one member's
     * overload, an answer neither reference gives.
     */
    @Test
    fun `a wrong-arity call on that union reports only the callability row`() {
        assert(chainOf(
            incompatible + """
            declare const zap: ZzzA | ZzzGp;
            zap("x", 1, 2);
            export {}
            """.trimIndent()
        ) == listOf(notCompatible("ZzzA | ZzzGp")))
    }

    /**
     * A PROPERTY-ACCESS callee — both references put the squiggle on the member NAME,
     * which is what the shared emitter's `computeSpan` already produces. Pinned so the two
     * callers of [Checker.emitUnionCalleeNoCompatibleSignatures] cannot drift in span.
     */
    @Test
    fun `a property access callee of that union is not callable`() {
        assert(chainOf(
            incompatible + """
            declare const zobj: { m: ZzzA | ZzzGp };
            zobj.m("x");
            export {}
            """.trimIndent()
        ) == listOf(notCompatible("ZzzA | ZzzGp")))
    }

    // ------------------------------------------------------------------
    // 2. the negatives — a combination EXISTS, so nothing is reported
    // ------------------------------------------------------------------

    /**
     * THE AXIS IS THE CONSTRAINT, not the presence of a type parameter or of an overload
     * set. This is the positive fixture with `number` changed to `string`, so PASS 1 finds
     * the single signature in the overload set and the union IS callable. Both references
     * silent.
     */
    @Test
    fun `negative control - an identical type parameter constraint combines`() =
        assert(codes(
            """
            interface ZzzA { <T extends string>(a: T): void; <T extends string>(a: T, b: number): void }
            interface ZzzHp { <T extends string>(a: T): void; z: number }
            declare const zah: ZzzA | ZzzHp;
            zah("x");
            export {}
            """.trimIndent()
        ).isEmpty())

    /**
     * ONE overloaded member beside a plain one whose signature PASS 1 matches. The
     * combination succeeds, so the call never reaches the suppression site at all. A
     * widening keyed on "one member is overloaded" rather than on "the combination came up
     * empty" reports here, and this pin is what says so.
     */
    @Test
    fun `negative control - a single overloaded member beside a matching plain one is silent`() =
        assert(codes(
            """
            interface ZzzO { (a: string): void; (a: number): void }
            interface ZzzS { (a: string): void }
            declare const zos: ZzzO | ZzzS;
            zos("x");
            export {}
            """.trimIndent()
        ).isEmpty())

    /**
     * The same with PASS 1 empty and PASS 2 combining. tsc INTERSECTS the parameters
     * (`combineUnionParameters`, contravariance), so the plain member must accept a
     * SUPERTYPE for the combined parameter to stay usable — `unknown` here, where a
     * disjoint `boolean` would intersect to `never` and make the argument itself the error.
     */
    @Test
    fun `negative control - a single overloaded member combines through pass 2`() =
        assert(codes(
            """
            interface ZzzO { (a: string): void; (a: number): void }
            interface ZzzU { (a: unknown): void }
            declare const zou: ZzzO | ZzzU;
            zou("x");
            export {}
            """.trimIndent()
        ).isEmpty())

    /**
     * `unionOfArraysFilterCall`'s own shape — the one the D2 round named as the reason to
     * keep this silence. It is silent for a DIFFERENT reason than that round recorded:
     * stage 2's ARRAY FALLBACK answers the receiver, so no union callee is formed and the
     * suppression site is never reached. Measured by the census in this class's KDoc, which
     * reads ZERO refusals here. Both `Array.filter` and `ReadonlyArray.filter` carry TWO
     * signatures in the embedded lib, so without that fallback this would be D2's `≥2`
     * branch rather than D2b's — i.e. the array fallback is load-bearing for BOTH halves.
     */
    @Test
    fun `negative control - a union of arrays is answered by the array fallback`() =
        assert(codes(
            """
            interface Fizz { id: number; fizz: string }
            interface Buzz { id: number; buzz: string }
            declare const zfb: Fizz[] | readonly Buzz[];
            zfb.filter(item => item.id < 5);
            declare const zfb2: Fizz[] | Buzz[];
            zfb2.every(item => item.id < 5);
            export {}
            """.trimIndent()
        ).isEmpty())

    // ------------------------------------------------------------------
    // 3. the neighbouring verdicts the widened branch must not absorb
    // ------------------------------------------------------------------

    /**
     * A member with NO call signatures is a DIFFERENT verdict with a DIFFERENT chain
     * (case (b), two sentences), decided ABOVE the overload count. Pinned so the widened
     * branch cannot be extended into it.
     */
    @Test
    fun `a non-callable member beside an overloaded one keeps the not-all-constituents chain`() {
        val d = rows(
            """
            interface ZzzO { (a: string): void; (a: number): void }
            interface ZzzN { p: number }
            declare const zon: ZzzO | ZzzN;
            zon("x");
            export {}
            """.trimIndent()
        )
        assert(d.map { it.code } == listOf(2349))
        assert(d[0].messageChain == listOf(
            "  Not all constituents of type 'ZzzO | ZzzN' are callable.",
            "    Type 'ZzzN' has no call signatures.",
        ))
    }

    /**
     * The `overloadedMembers == 0` sibling — TWO single-signature generic members with
     * non-identical type parameters — still reports through the SAME emitter, decided by
     * the `differ` branch BELOW this one rather than by it. Pinned so the widening cannot
     * be mistaken for having taken that path over as well, and so the recorded candidate
     * of collapsing the two has a starting point that is already green.
     */
    @Test
    fun `two single-signature generic members still report through the differ branch`() {
        val d = rows(
            """
            declare const zg: (<T extends number>(a: T) => void) | (<T>(a: string) => void);
            zg(1);
            export {}
            """.trimIndent()
        )
        assert(d.map { it.code } == listOf(2349))
        assert(d[0].messageChain == notCompatible("(<T extends number>(a: T) => void) | (<T>(a: string) => void)"))
    }
}
