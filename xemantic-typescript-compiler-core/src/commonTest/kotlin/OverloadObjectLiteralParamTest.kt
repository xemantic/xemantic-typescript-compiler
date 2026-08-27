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
 * (CHK.55) AN OBJECT LITERAL'S LITERAL-VALUED PROPERTIES WIDEN, AND THAT ONE FACT
 * BIT AT **BOTH** OVERLOAD SITES — A FALSE TS2769 AT THE DIAGNOSTIC AND A **WRONG
 * TYPE** AT SELECTION. The queue item carried them as two open items plus a third
 * "mechanism this round did not locate"; measured, they are one mechanism.
 *
 * ## The one sentence
 *
 * [Checker.getTypeOfExpression] types `{ encoding: "utf8" }` as `{ encoding: string }`
 * — this compiler has no fresh-literal machinery — so a target property whose type is
 * a literal (union) rejects the argument. tsc instead contextually types each
 * candidate's arguments, and a fresh literal keeps its literal type.
 *
 * ## Why it looked like three defects
 *
 *  * At [Checker.allArgumentsMatch] (the TS2769 DIAGNOSTIC path) round 728 already had
 *    a rescue, [Checker.objLitLiteralPropsSatisfyParam], but it refused a target
 *    INTERFACE with heritage and a UNION with more than one non-nullish constituent.
 *    The heritage refusal was `knip`'s last overload row (`execSync(cmd, { encoding:
 *    'utf8', stdio: [...] })`, `src/util/git.ts:17:55`) — a pure FALSE POSITIVE, since
 *    selection had already picked the right overload.
 *  * At [Checker.signatureAcceptsArgs] (SELECTION) there was **no rescue at all**, so
 *    every candidate was passed over and the answer came from `resolveCallOverload`'s
 *    `arityMatches[0]` fallback. That is the matrix's row H, and it is a wrong TYPE
 *    with no diagnostic anywhere — a hover that lies.
 *
 * One fixture exhibits both at once (`P3` below): before this round it read a false
 * TS2769 **and** answered the first overload's return type; tsc answers the second's
 * and is silent. That co-occurrence is what identifies the two as one mechanism.
 *
 * ## Ground truth
 *
 * Every expectation here was read out of `tools/tsgo-7.0.2/lib/tsc` on byte-identical
 * source through the project CLI, not reasoned about.
 *
 * ## What is still open, and deliberately not pinned (round 765)
 *
 * (CHK.55)(a) — the TS2769 diagnostic path does not ask [Checker.weakParamRefusesArg],
 * so `zzzU(123)` against a weak-parameter overload set stays silent where tsc reports
 * TS2769. That is a MISSING error rather than a wrong one, and its elaboration needs
 * its own design ([Checker.getFirstArgumentError] finds no failing argument for the
 * overload the weak rule rejects).
 */
class OverloadObjectLiteralParamTest {

    /**
     * `knip`'s last overload row, reduced: `execSync(cmd, { encoding: 'utf8', stdio:
     * [...] })`. The winning overload's parameter is an interface with heritage
     * (`ExecSyncOptionsWithStringEncoding extends ExecSyncOptions`), which round 728's
     * rescue refused outright.
     *
     * Asserted as a VALUE and not as silence: the write probe's target is `number`, so
     * a correctly-selected overload MUST produce `Type 'string' is not assignable to
     * type 'number'`. SELECTION was already right here, so that row is present before
     * and after; what the pin sees is the **absence of the TS2769** beside it. Before
     * this round the list read `[2322, 2769]`.
     */
    @Test
    fun `an object literal against an interface parameter with heritage no longer reports TS2769`() {
        val d = diagnose(EXEC + """
            const zzzOut: number = zzzExec("x", { zzzEnc: "utf8", zzzStdio: ["pipe"] })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'string' is not assignable to type 'number'."
        ))
    }

    /**
     * Matrix row H — the SELECTION half, and the sharpest pin in the class because it
     * has no diagnostic on either arm to lean on. tsc answers `string`; before this
     * round we answered `number` and the whole fixture was SILENT.
     *
     * THE `ZH1` OVERLOAD IS DECLARED **SECOND**, AND THAT IS LOAD-BEARING.
     * [Checker.resolveCallOverload] falls back to `arityMatches[0]` when nothing
     * accepts, so with `ZH1` first the fallback would already return the right answer
     * and the pin could not fail — measured: reversing the two declarations makes the
     * PRE-FIX binary agree with tsc. That is the same trap (CHK.54)'s three refusal
     * pins fell into.
     */
    @Test
    fun `a literal-property object literal selects the later overload rather than the fallback`() {
        val d = diagnose("""
            interface ZH1 { zzzE: "u" }
            declare function zzzH(o: { zzzE?: null }): number
            declare function zzzH(o: ZH1): string
            const zzzOut: number = zzzH({ zzzE: "u" })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'string' is not assignable to type 'number'."
        ))
    }

    /**
     * BOTH HALVES IN ONE FIXTURE — `readFileSync(p, { encoding: 'utf8' })`, whose
     * second overload takes `{ encoding: BufferEncoding; flag?: string } |
     * BufferEncoding`: a union with TWO non-nullish constituents, which round 728's
     * `singleOrNull` refused. Before this round it read a false TS2769 AND answered
     * `ZBuf` (the fallback overload), so the `ZBuf` write probe was silent; tsc
     * answers `string` and reports only the TS2322.
     *
     * The overload set is (CHK.54)'s own `OVERLOADS` constant, one round on.
     */
    @Test
    fun `a union parameter with two non-nullish constituents is offered the rescue`() {
        val d = diagnose("""
            type ZzzEnc = "utf8" | "ascii"
            interface ZBuf { zzzB: number }
            declare function zzzRead(p: string, o?: { zzzEnc?: null; zzzFlag?: string } | null): ZBuf
            declare function zzzRead(p: string, o: { zzzEnc: ZzzEnc } | ZzzEnc): string
            const zzzOut: ZBuf = zzzRead("f", { zzzEnc: "utf8" })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'string' is not assignable to type 'ZBuf'."
        ))
    }

    /**
     * Two overloads that BOTH accept: the first declared still wins, and no TS2769 is
     * reported. Before this round both were refused and the fixture carried a spurious
     * TS2769 beside the (accidentally correct) fallback answer — so this is a positive
     * for the FP removal AND the order control in one, exactly as tsc reports it.
     */
    @Test
    fun `declaration order still decides when both overloads accept the literal`() {
        val d = diagnose("""
            interface ZBase { zzzC?: string }
            interface ZTgt extends ZBase { zzzE: "u" | "v" }
            declare function zzzX(o: ZTgt): number
            declare function zzzX(o: ZTgt): string
            const zzzOut: boolean = zzzX({ zzzE: "u" })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'number' is not assignable to type 'boolean'."
        ))
    }

    /**
     * REFUSAL. An EXCESS property still rejects — the rescue is suppression-only and
     * every source property must exist on the target. tsc reports TS2769 here too.
     */
    @Test
    fun `an excess property still rejects`() {
        val d = diagnose(SIMPLE + """
            const zzzOut: boolean = zzzX({ zzzE: "u", zzzBogus: 1 })
        """)
        assert(d.map { it.code } == listOf(2322, 2769))
    }

    /**
     * REFUSAL, and the DIRECT negative for dropping round 728's heritage refusal:
     * `zzzReq` is required and INHERITED, and the literal does not supply it, so the
     * call must still fail. It does because [Checker.resolveInterfaceMembersCore] folds
     * base members into the derived type's own `members`/`properties` table — which is
     * precisely why the refusal was unnecessary. tsc reports TS2769.
     */
    @Test
    fun `a missing INHERITED required property still rejects`() {
        val d = diagnose("""
            interface ZBase { zzzReq: number }
            interface ZTgt extends ZBase { zzzE: "u" | "v" }
            declare function zzzX(o: number): number
            declare function zzzX(o: ZTgt): string
            const zzzOut: boolean = zzzX({ zzzE: "u" })
        """)
        assert(d.map { it.code } == listOf(2322, 2769))
    }

    /**
     * REFUSAL. A property failing for any reason OTHER than literal widening still
     * rejects — `zzzN: "notnum"` against `zzzN: number` is not a literal rescue. tsc
     * reports TS2769.
     */
    @Test
    fun `a property failing for a non-widening reason still rejects`() {
        val d = diagnose("""
            interface ZBase { zzzC?: string }
            interface ZTgt extends ZBase { zzzE: "u" | "v"; zzzN: number }
            declare function zzzX(o: number): number
            declare function zzzX(o: ZTgt): string
            const zzzOut: boolean = zzzX({ zzzE: "u", zzzN: "notnum" })
        """)
        assert(d.map { it.code } == listOf(2322, 2769))
    }

    /**
     * REFUSAL for the per-constituent fold: a union parameter NONE of whose
     * constituents rescues the literal still rejects. Without it, `targets.any { … }`
     * could be replaced by an unconditional acceptance and nothing else here would
     * notice. tsc reports TS2769.
     */
    @Test
    fun `a union parameter no constituent of which rescues still rejects`() {
        val d = diagnose("""
            declare function zzzV(o: number): number
            declare function zzzV(o: { zzzA: "x" } | { zzzB: "y" }): string
            const zzzOut: boolean = zzzV({ zzzA: "z" })
        """)
        assert(d.map { it.code } == listOf(2322, 2769))
    }

    /**
     * THE THIRD INTERACTION, AND IT WAS FOUND BY TRYING TO FALSIFY AN ABLATION ARM
     * RATHER THAN BY READING THE CODE. For a UNION parameter the rescue and (CHK.54)'s
     * weak-type rule are consulted in the wrong order: `{ zzzA?: 0 }` is weak and
     * accepts ANY non-nullish value structurally, so `checkTypeRelatedTo` SUCCEEDS,
     * the rejecting path where round 728's rescue lives is never taken, and the weak
     * rule then refuses the signature having never asked whether the literal satisfies
     * the OTHER constituent. It does — tsc selects this overload and answers `string`;
     * before the guard we answered `number`.
     *
     * The rescued overload is declared FIRST here, so a wrong refusal is observable as
     * the SECOND overload's `number` rather than being restored by the
     * `arityMatches[0]` fallback.
     */
    @Test
    fun `a literal satisfying one union constituent cancels a weak refusal from another`() {
        val d = diagnose("""
            declare function zzzA7(o: { zzzA?: 0 } | { zzzE: "u" }): string
            declare function zzzA7(o: { zzzE: string }): number
            const zzzOut: boolean = zzzA7({ zzzE: "u" })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'string' is not assignable to type 'boolean'."
        ))
    }

    /**
     * REFUSAL, and the pair of the pin above on the SAME fixture shape: when the
     * literal does NOT satisfy the other constituent (`"zzz"` is not `"u"`), (CHK.54)'s
     * weak refusal must still stand and the SECOND overload must be selected. Without
     * it the guard would be an unconditional cancellation of the weak rule.
     *
     * Deliberately built so that both compilers give a definite, AGREEING answer — the
     * obvious version of this refusal (drop the second overload) leaves tsc reporting a
     * TS2769 we do not yet produce, and pinning that would be a countdown on
     * (CHK.55)(a) rather than a control (round 765).
     */
    @Test
    fun `a literal satisfying NO constituent leaves the weak refusal standing`() {
        val d = diagnose("""
            declare function zzzA7(o: { zzzA?: 0 } | { zzzE: "u" }): string
            declare function zzzA7(o: { zzzE: string }): number
            const zzzOut: boolean = zzzA7({ zzzE: "zzz" })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'number' is not assignable to type 'boolean'."
        ))
    }

    /**
     * CONTROL — green before and after. The same shape with the heritage FLATTENED was
     * already handled by round 728, and must stay byte-identical: this is what says the
     * change WIDENS the rescue rather than replacing it.
     */
    @Test
    fun `a flat object parameter keeps round 728 behaviour`() {
        val d = diagnose("""
            interface ZFlat { zzzEnc: "utf8" | "ascii"; zzzStdio?: string[] }
            declare function zzzF(cmd: string): number
            declare function zzzF(cmd: string, o: ZFlat): string
            declare function zzzF(cmd: string, o?: { zzzEnc?: "utf8" | "ascii" }): string | number
            const zzzOut: number = zzzF("x", { zzzEnc: "utf8", zzzStdio: ["pipe"] })
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'string' is not assignable to type 'number'."
        ))
    }

    private companion object {
        /** `@types/node`'s `execSync`, reduced to the four signatures and the heritage. */
        val EXEC = """
            interface ZzzCommon { zzzStdio?: string[]; zzzEnc?: "utf8" | "ascii" | "buffer" | null }
            interface ZzzOpts extends ZzzCommon { zzzShell?: string }
            interface ZzzOptsStr extends ZzzOpts { zzzEnc: "utf8" | "ascii" }
            interface ZzzOptsBuf extends ZzzOpts { zzzEnc?: "buffer" | null }
            declare function zzzExec(cmd: string): number
            declare function zzzExec(cmd: string, o: ZzzOptsStr): string
            declare function zzzExec(cmd: string, o: ZzzOptsBuf): number
            declare function zzzExec(cmd: string, o?: ZzzOpts): string | number
        """.trimIndent() + "\n"

        val SIMPLE = """
            interface ZBase { zzzC?: string }
            interface ZTgt extends ZBase { zzzE: "u" | "v" }
            declare function zzzX(o: number): number
            declare function zzzX(o: ZTgt): string
        """.trimIndent() + "\n"
    }
}
