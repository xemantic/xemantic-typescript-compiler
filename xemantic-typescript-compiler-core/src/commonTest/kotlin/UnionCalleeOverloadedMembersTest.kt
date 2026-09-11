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
 * (CHK.97) stage 3, deliverable D2 — a union callee whose members are ALL overloaded
 * reports TS2349 *Each member of the union type … has signatures, but none of those
 * signatures are compatible with each other.*
 *
 * THE MECHANISM, and why it is four lines rather than the redesign the queue item
 * feared. tsc's `getUnionSignatures` runs PASS 1 (the signatures present in every
 * constituent) and, only if that comes up empty, PASS 2 — which it SKIPS outright when
 * two or more constituents carry an overload set (`indexWithLengthOverOne === -1`). The
 * union then has NO call signatures at all, and `resolveCallExpression`'s
 * `!callSignatures.length` branch prints this chain. [Checker.computeCombinedUnionSignatures]
 * already models that skip exactly — its `multipleOverloadSets` local — and simply threw
 * the REASON away, so the fix is to ask, at the one suppression site, WHY the combination
 * refused: two overloaded members is a DIAGNOSTIC, one is a SUPPRESSION.
 *
 * The item recorded a blocker that turned out to be solved upstream: it says retiring the
 * `≥2` suppression is not enough because the `differ` branch below it returns silence for
 * any non-generic differing pair ((CHK.94)'s rule). True, and irrelevant — the refusal
 * reason is decidable ABOVE `differ`, so `differ` never sees these calls.
 *
 * ONE HOME for the sentence: [Checker.emitUnionCalleeNoCompatibleSignatures], shared with
 * the GENERIC refusal ([Checker.unionCalleeGenericSignaturesIncompatible]) that already
 * printed it. Both reasons produce the identical chain in tsc, and a chain sentence is the
 * whole observable here — the 8-profile grid is structurally blind to a display divergence
 * ((PARITY.1)), so two copies would have drifted with nothing to notice.
 *
 * RECEIPT (`scripts/ref_matrix.py`, seven fixtures, tsgo 7.0.2 and pristine
 * `typescript@6.0.3` agreeing on message, chain AND column throughout, zero REF-SPLIT and
 * zero TEXT-DIFF): **agree 3 -> 11, missing 8 -> 0, ours-only 0 -> 0**.
 *
 * The 8-profile grid is `added=0 removed=0` on all eight and is a **CONTROL, not a gate**
 * — a counting arm (a `println` at the branch, with a positive control reading 3 hits on
 * this class's own fixture) found tsc's own 78 sources reach the new branch **ZERO times
 * on every profile**, and zero times on its one-overloaded sibling too. `added=0` there
 * proves INERTNESS and nothing about coverage ((CHK.124)'s lesson). The gate for this
 * deliverable is the CORPUS (`betterErrorForUnionCall`, `unionOfArraysFilterCall`,
 * `functionCallOnConstrainedTypeVariable`, `signatureCombiningRestParameters1/3/4/5`,
 * 1,425 baselines over the four guard letters) plus these pins.
 *
 * RECORDED residues, measured and NOT pinned (CLAUDE.md's countdown rule):
 *  - the CONSTRUCT twin (`new (ZzzA | ZzzB)` with both members carrying several construct
 *    signatures) is a separate branch with its own chain sentence and is untouched here.
 *  - a both-overloaded union beside `undefined` reaches the nullish strip first, so its
 *    verdict is D4's and not this one.
 *  - **the ONE-overloaded suppression this fix KEPT hides a true positive, and the
 *    measured shape is not the one the queue item assumed.** With exactly one overloaded
 *    member the combination can still refuse — PASS 2 bails on GENERIC incompatibility,
 *    which is tsc's own `compareTypeParametersIdentical` — and both references then
 *    report the identical chain where we stay silent:
 *    `interface ZzzA { <T extends string>(a: T): void; <T extends string>(a: T, b: number): void }`
 *    beside `type ZzzG = <T extends number>(a: T) => void`. Closing it means threading the
 *    REFUSAL REASON out of [Checker.computeCombinedUnionSignatures] rather than counting
 *    overload sets at the call site, which is a wider change than D2 and was refused here
 *    on scope. Its ablation arm (emit at `overloadedMembers >= 1`) is 0-RED on the four
 *    corpus guard letters and reddens exactly ONE full-suite pin — the r09 countdown in
 *    [UnionCalleeSignatureTest] that this round inverted — so the widening is measured
 *    and available, not merely conjectured.
 *  - a DISPLAY defect one layer down, found by these fixtures and owned by nobody:
 *    [Checker.typeToString] PARENTHESIZES a union member that has exactly ONE call
 *    signature and no other member, even where it renders as a bare NAME — `ZzzA | ZzzB
 *    | (ZzzS)` and `(ZzzS) | (ZzzT)` where both references print the names
 *    unparenthesized. PRE-EXISTING and nothing to do with this deliverable: the shipped
 *    binary prints it in a plain TS2322 (`Type 'ZzzA | ZzzB | (ZzzS)' is not assignable
 *    to type 'number'`). A member carrying a call signature AND a property renders
 *    correctly, which is why this class's third member has one. `scripts/ref_matrix.py`
 *    cannot see this class of defect at all — its row regexes match only the FIRST line
 *    of a diagnostic, so a chain-only divergence scores as AGREE.
 */
class UnionCalleeOverloadedMembersTest {

    private fun rows(src: String) =
        diagnose(src, directives = "// @strict: true")

    private fun codes(src: String) = rows(src).map { it.code }

    private fun chainOf(src: String) = rows(src).map { it.messageChain }

    /** Two interfaces, each with TWO call signatures — tsc's skipped PASS 2. */
    private val both = """
        interface ZzzA { (a: string): void; (a: number): void }
        interface ZzzB { (a: boolean): void; (a: object): void }
    """.trimIndent() + "\n"

    private fun notCompatible(display: String) =
        listOf("  Each member of the union type '$display' has signatures, but none of those signatures are compatible with each other.")

    // ------------------------------------------------------------------
    // 1. the positives — both members overloaded
    // ------------------------------------------------------------------

    /**
     * The shape the deliverable names. Both references: TS2349 at column 1 with the
     * chain below; we were SILENT, because the `≥2` suppression consumed the call
     * before any verdict could be reached.
     */
    @Test
    fun `a call on a union of two overloaded members is not callable`() {
        val d = rows(
            both + """
            declare const zab: ZzzA | ZzzB;
            zab("x");
            export {}
            """.trimIndent()
        )
        assert(d.map { it.code to it.message } == listOf(
            2349 to "This expression is not callable."
        ))
        assert(d[0].messageChain == notCompatible("ZzzA | ZzzB"))
    }

    /**
     * The ARITY row does not win: with no combined signature there is no parameter list
     * to count against, so tsc reports the un-callable verdict and nothing else. Pinned
     * because the natural mistake is to let [Checker.unionCalleeArityDiagnostic] speak
     * first — which would print `Expected 1 arguments, but got 2.` off one member's
     * overload, an answer neither reference gives.
     */
    @Test
    fun `a wrong-arity call on a union of two overloaded members reports only the callability row`() {
        assert(chainOf(
            both + """
            declare const zab: ZzzA | ZzzB;
            zab(1, 2);
            export {}
            """.trimIndent()
        ) == listOf(notCompatible("ZzzA | ZzzB")))
    }

    /** In INITIALIZER position, so the result type is read as well as the call checked. */
    @Test
    fun `an initializer call on a union of two overloaded members is not callable`() {
        assert(chainOf(
            both + """
            declare const zab: ZzzA | ZzzB;
            const zr = zab(true);
            export {}
            """.trimIndent()
        ) == listOf(notCompatible("ZzzA | ZzzB")))
    }

    /**
     * THREE members, only two of them overloaded — the refusal is about the COUNT of
     * overloaded members, never about the count of members, and the chain names the
     * whole union. Measured identical on both references. `ZzzS` carries a PROPERTY
     * beside its call signature only to dodge the unrelated parenthesization residue
     * recorded in this class's KDoc; the mechanism is indifferent to it.
     */
    @Test
    fun `a third single-signature member does not rescue two overloaded ones`() {
        assert(chainOf(
            both + """
            interface ZzzS { (a: string): void; z: number }
            declare const z3: ZzzA | ZzzB | ZzzS;
            z3("x");
            export {}
            """.trimIndent()
        ) == listOf(notCompatible("ZzzA | ZzzB | ZzzS")))
    }

    /**
     * A PROPERTY-ACCESS callee. Both references put the squiggle on the member NAME
     * (column 6 of `zobj.m("x")`), which is what [Checker.ccetUnionCalleeChecks]'
     * `computeSpan` already produces for the generic refusal — the shared emitter is
     * what keeps the two spans from diverging.
     */
    @Test
    fun `a property access callee of a union of two overloaded members is not callable`() {
        assert(chainOf(
            both + """
            declare const zobj: { m: ZzzA | ZzzB };
            zobj.m("x");
            export {}
            """.trimIndent()
        ) == listOf(notCompatible("ZzzA | ZzzB")))
    }

    // ------------------------------------------------------------------
    // 2. the negatives — a combination EXISTS, so nothing is reported
    // ------------------------------------------------------------------

    /**
     * IDENTICAL overload sets: PASS 1 matches every signature, the union IS callable,
     * and both references are silent. This is the control that separates "two members
     * are overloaded" (irrelevant on its own) from "the combination is empty".
     */
    @Test
    fun `negative control - identical overload sets on both members combine`() =
        assert(codes(
            """
            interface ZzzC { (a: string): string; (a: number): string }
            interface ZzzD { (a: string): string; (a: number): string }
            declare const zcd: ZzzC | ZzzD;
            const zs: string = zcd("y");
            export {}
            """.trimIndent()
        ).isEmpty())

    /**
     * PARTIALLY overlapping overload sets — one shared signature is enough for PASS 1,
     * so both references stay silent even though BOTH members are overloaded. A rule
     * keyed on the overload counts alone (rather than on the combination having come
     * up empty) reports here, and this pin is what says so.
     */
    @Test
    fun `negative control - one shared signature is enough for both overloaded members`() =
        assert(codes(
            """
            interface ZzzP { (a: string): void; (a: number): void }
            interface ZzzQ { (a: string): void; (a: boolean): void }
            declare const zpq: ZzzP | ZzzQ;
            zpq("x");
            export {}
            """.trimIndent()
        ).isEmpty())

    /**
     * ONE overloaded member beside a single-signature one — tsc RUNS pass 2 here and
     * reports nothing. This is the `unionOfArraysFilterCall` shape written without the
     * libs, and the suppression it needs is the one the fix KEPT.
     */
    @Test
    fun `negative control - a single overloaded member beside a plain one is silent`() =
        assert(codes(
            """
            interface ZzzA { (a: string): void; (a: number): void }
            interface ZzzS { (a: string): void }
            declare const zas: ZzzA | ZzzS;
            zas("x");
            export {}
            """.trimIndent()
        ).isEmpty())

    // ------------------------------------------------------------------
    // 3. the other two chains, which share the emission site or sit beside it
    // ------------------------------------------------------------------

    /**
     * The GENERIC refusal ((CHK.94), `betterErrorForUnionCall`) now goes through the
     * same [Checker.emitUnionCalleeNoCompatibleSignatures]. Pinned as a positive so a
     * change to the shared emitter cannot silently move only one of its two callers.
     */
    @Test
    fun `two generic members with non-identical type parameters still report the same chain`() {
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

    /**
     * A member with NO call signatures is a DIFFERENT verdict with a DIFFERENT chain
     * (case (b), two sentences), decided above the overload count. Pinned so the new
     * branch cannot be widened into it.
     */
    @Test
    fun `a non-callable member keeps the not-all-constituents chain`() {
        val d = rows(
            both + """
            interface ZzzN { p: number }
            declare const zan: ZzzA | ZzzN;
            zan("x");
            export {}
            """.trimIndent()
        )
        assert(d.map { it.code } == listOf(2349))
        assert(d[0].messageChain == listOf(
            "  Not all constituents of type 'ZzzA | ZzzN' are callable.",
            "    Type 'ZzzN' has no call signatures.",
        ))
    }
}
