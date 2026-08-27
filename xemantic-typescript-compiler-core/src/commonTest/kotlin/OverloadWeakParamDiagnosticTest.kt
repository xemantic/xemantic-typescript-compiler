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
 * (CHK.56) THE TS2769 *DIAGNOSTIC* PATH DID NOT ASK THE WEAK-TYPE RULE, SO A CALL
 * WHOSE EVERY OVERLOAD HAS A DISJOINT WEAK PARAMETER WAS **SILENT**.
 *
 * (CHK.54) gave overload SELECTION the weak rule ([Checker.signatureAcceptsArgs]
 * asks [Checker.weakParamRefusesArg]) and deliberately left the diagnostic path
 * alone. The two then disagreed: [Checker.allArgumentsMatch] accepted what selection
 * refused, so `zzzU(123)` against two all-optional-parameter overloads produced no
 * diagnostic at all, where tsc reports TS2769.
 *
 * ## The design, and why the queue item's "hard part" dissolved
 *
 * The item recorded tsc's answer as `The last overload gave the following error.` +
 * a *no properties in common* subline, and read the elaboration as the work —
 * [Checker.getFirstArgumentError] walks the plain relation, which ACCEPTS the
 * argument, so it finds no failing argument and the overload drops out of the chain.
 * Both halves needed re-measuring:
 *
 *  * **The wording is right and it is TS2559's, not an assignability line.** So the
 *    subline is minted from the weak verdict beside the existing walk, on the path
 *    where the relation SUCCEEDED — [Checker.weakOverloadArgRefuses].
 *  * **The "which overload" half is a tsgo RENDERING and not tsc's.** `tsgo 7.0.2`
 *    prints `The last overload gave the following error.` for 2, 3 and 4 candidates
 *    alike; PRISTINE tsc prints `Overload N of M, '<sig>', gave the following error.`
 *    for every failing candidate below four — 42 baselines in `typescript-repo` carry
 *    `Overload 1 of 2,` against 4 carrying the last-overload form, and
 *    `tsxStatelessFunctionComponentOverload4.errors.txt` carries a *no properties in
 *    common* subline INSIDE exactly that per-overload chain. Round 938's law, paid
 *    again. Our chain has always had the pristine shape, so no "which overload"
 *    policy was needed: the weak verdict is simply one more per-overload error string.
 *
 * ## What is measured and deliberately NOT closed here
 *
 * A weak UNION target still reports nothing outside an overload set — `zzzM6(123)`
 * against a single `(o: { zzzA?: null } | null)` signature, and
 * `const v: { zzzA?: null } | null = "utf8"`, are both TS2559 in tsc and silent
 * here, because [Checker.weakTargetProperties] answers null for a union and the
 * B482 walkers never distribute. That is a different mechanism (the walkers, not
 * the overload helpers) and is queued rather than pinned (round 765).
 */
class OverloadWeakParamDiagnosticTest {

    /**
     * THE ROUND'S HEADLINE, ASSERTED AS VALUES: code, message, both chain lines and
     * the anchor. tsc 7.0.2 reports TS2769 at the ARGUMENT with the last overload's
     * subline `Type '123' has no properties in common with type '{ zzzB?: null |
     * undefined; zzzG?: string | undefined; }'` — which is line 4 of this chain.
     *
     * The argument keeps its LITERAL type in the weak subline (`Type '123'`) where an
     * ordinary assignability subline widens it (`Argument of type 'number'`); that
     * asymmetry is tsc's and is pinned by this row together with the next one.
     */
    @Test
    fun `two weak overloads report TS2769 with the no-properties-in-common subline`() {
        val d = diagnose("""
            declare function zzzU(o: { zzzA?: null; zzzF?: string }): number
            declare function zzzU(o: { zzzB?: null; zzzG?: string }): string
            const zzzUr = zzzU(123)
        """)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].message == "No overload matches this call.")
        assert(d[0].messageChain == listOf(
            "  Overload 1 of 2, '(o: { zzzA?: null | undefined; zzzF?: string | undefined; }): number', gave the following error.",
            "    Type '123' has no properties in common with type '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.",
            "  Overload 2 of 2, '(o: { zzzB?: null | undefined; zzzG?: string | undefined; }): string', gave the following error.",
            "    Type '123' has no properties in common with type '{ zzzB?: null | undefined; zzzG?: string | undefined; }'.",
        ))
        assert(d[0].line == 3)
        assert(d[0].character == 20)
    }

    /**
     * A weak overload beside an ordinary one: the two sublines are minted by two
     * different mechanisms and both must appear, in declaration order. tsc 7.0.2
     * reports the second (weak) one as its last-overload subline.
     */
    @Test
    fun `a weak overload and a plain one contribute their own sublines`() {
        val d = diagnose("""
            declare function zzzV(o: string): string
            declare function zzzV(o: { zzzA?: null; zzzF?: string }): number
            const zzzVr = zzzV(123)
        """)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].messageChain == listOf(
            "  Overload 1 of 2, '(o: string): string', gave the following error.",
            "    Argument of type 'number' is not assignable to parameter of type 'string'.",
            "  Overload 2 of 2, '(o: { zzzA?: null | undefined; zzzF?: string | undefined; }): number', gave the following error.",
            "    Type '123' has no properties in common with type '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.",
        ))
        assert(d[0].line == 3)
        assert(d[0].character == 20)
    }

    /**
     * A UNION parameter with exactly ONE non-nullish constituent names that
     * CONSTITUENT, not the union — measured on tsc 7.0.2, which renders
     * `(o: ZzzWk | null)` as `'ZzzWk'` and `{ zzzA?: null } | undefined` as
     * `'{ zzzA?: null | undefined; }'`. This is [Checker.weakRefusalDisplayTarget]'s
     * non-null arm, and it is also what makes the `readFileSync` family
     * (`options?: { encoding?: null; flag?: string } | null`) render tsc's sentence.
     */
    @Test
    fun `a union parameter with one non-nullish constituent names the constituent`() {
        val d = diagnose("""
            interface ZzzWk { zzzA?: null }
            declare function zzzY(o: ZzzWk | null): number
            declare function zzzY(o: ZzzWk | null): string
            const zzzYr = zzzY(123)
        """)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].messageChain == listOf(
            "  Overload 1 of 2, '(o: ZzzWk | null): number', gave the following error.",
            "    Type '123' has no properties in common with type 'ZzzWk'.",
            "  Overload 2 of 2, '(o: ZzzWk | null): string', gave the following error.",
            "    Type '123' has no properties in common with type 'ZzzWk'.",
        ))
        assert(d[0].line == 4)
        assert(d[0].character == 20)
    }

    /**
     * …AND TWO OR MORE NON-NULLISH CONSTITUENTS TAKE THE ORDINARY ASSIGNABILITY
     * WORDING, NAMING THE WHOLE UNION. The verdict is the same refusal; only the
     * sentence differs, and getting it wrong here would put TS2559's wording on a
     * row tsc words as TS2345. Measured: `{ zzzA?: null } | string` with a `number`
     * argument is `Argument of type 'number' is not assignable to parameter of type
     * 'string | { zzzA?: null | undefined; }'` in tsc 7.0.2, union order included.
     */
    @Test
    fun `a union parameter with two non-nullish constituents uses the assignability wording`() {
        val d = diagnose("""
            declare function zzzZ(o: { zzzA?: null } | string): number
            declare function zzzZ(o: { zzzB?: null } | string): string
            const zzzZr = zzzZ(123)
        """)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].messageChain == listOf(
            "  Overload 1 of 2, '(o: string | { zzzA?: null | undefined; }): number', gave the following error.",
            "    Argument of type 'number' is not assignable to parameter of type 'string | { zzzA?: null | undefined; }'.",
            "  Overload 2 of 2, '(o: string | { zzzB?: null | undefined; }): string', gave the following error.",
            "    Argument of type 'number' is not assignable to parameter of type 'string | { zzzB?: null | undefined; }'.",
        ))
        assert(d[0].line == 3)
        assert(d[0].character == 20)
    }

    /**
     * THE ANCHOR IS THE FAILING ARGUMENT AND NOT THE FIRST ONE. tsc 7.0.2 reports
     * this fixture — byte for byte, as a standalone file — at `(3,27)`; the anchor
     * comes from [Checker.getFirstFailingArgPosition], which has to see the weak
     * refusal too or it walks past every argument and reports at the callee's
     * fallback position.
     *
     * Every `line`/`character` in this class is tsc's own 1-based line:column for the
     * same source, read off `tools/tsgo-7.0.2/lib/tsc` rather than derived: pin
     * fixtures are kept free of a `@Filename` split so the compiled text and the file
     * tsc saw are the same string.
     */
    @Test
    fun `the weak refusal anchors at the failing argument`() {
        val d = diagnose("""
            declare function zzzAa(a: string, o: { zzzA?: null }): number
            declare function zzzAa(a: string, o: { zzzB?: null }): string
            const zzzAar = zzzAa("x", 123)
        """)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].line == 3)
        assert(d[0].character == 27)
        assert(d[0].messageChain.last() ==
            "    Type '123' has no properties in common with type '{ zzzB?: null | undefined; }'.")
    }

    /**
     * THREE weak overloads produce THREE sublines. Pinned separately because tsgo
     * 7.0.2 collapses this to a single `The last overload gave the following error.`
     * and PRISTINE tsc does not — implementing tsgo's rendering here would compile,
     * look right against the only reference compiler that runs on this box, and
     * silently diverge from every `Overload N of M` baseline in the corpus.
     */
    @Test
    fun `three weak overloads contribute three sublines - not a collapsed last-overload one`() {
        val d = diagnose("""
            declare function zzzGg(o: { zzzA?: null }): number
            declare function zzzGg(o: { zzzB?: null }): string
            declare function zzzGg(o: { zzzC?: null }): boolean
            const zzzGgr = zzzGg(123)
        """)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].messageChain == listOf(
            "  Overload 1 of 3, '(o: { zzzA?: null | undefined; }): number', gave the following error.",
            "    Type '123' has no properties in common with type '{ zzzA?: null | undefined; }'.",
            "  Overload 2 of 3, '(o: { zzzB?: null | undefined; }): string', gave the following error.",
            "    Type '123' has no properties in common with type '{ zzzB?: null | undefined; }'.",
            "  Overload 3 of 3, '(o: { zzzC?: null | undefined; }): boolean', gave the following error.",
            "    Type '123' has no properties in common with type '{ zzzC?: null | undefined; }'.",
        ))
        assert(d[0].line == 4)
        assert(d[0].character == 22)
    }

    /**
     * REFUSAL, AND THE ONE THAT DECIDES THE OBJECT-LITERAL GUARD. An object-literal
     * argument sharing no property with either weak parameter is reported by tsc as
     * an EXCESS-PROPERTY error at the offending property (`Object literal may only
     * specify known properties, and 'zzzZ' does not exist in type
     * '{ zzzB?: null | undefined; }'`, column 23), because the freshness check runs
     * ABOVE the weak check in `isRelatedTo` and every property of a disjoint fresh
     * literal is excess by construction.
     *
     * We do not emit that excess row for an argument the relation ACCEPTED, so the
     * shape stays SILENT — deliberately, rather than acquiring the weak wording one
     * column earlier. A TS2769 naming the wrong span is worse than no TS2769.
     */
    @Test
    fun `an object-literal argument is left to the excess-property rule and stays silent`() {
        val d = diagnose("""
            declare function zzzDd(o: { zzzA?: null }): number
            declare function zzzDd(o: { zzzB?: null }): string
            const zzzDdr: number = zzzDd({ zzzZ: 1 })
        """)
        assert(d.isEmpty())
    }

    /**
     * REFUSAL. Sharing ONE property name is all the rule asks, and the shared name is
     * on the SECOND (weak) overload — declared second on purpose, because
     * [Checker.resolveCallOverload] falls back to `arityMatches[0]` and a refusal of a
     * FIRST-declared overload restores exactly the answer it was meant to remove
     * ((CHK.54) lost three pins to that). Asserted as a VALUE: the weak overload
     * returns `number`, so a correct selection MUST produce the TS2322 below, and a
     * wrong refusal answers `string` and goes silent.
     */
    @Test
    fun `an argument sharing a property with the weak parameter is not refused`() {
        val d = diagnose("""
            interface ZzzSh { zzzF?: string }
            declare function zzzBb(o: { zzzQ: 0 }): string
            declare function zzzBb(o: { zzzA?: null; zzzF?: string }): number
            declare const zzzBbo: ZzzSh
            const zzzBbr: string = zzzBb(zzzBbo)
        """)
        assert(d.map { it.code to it.message } == listOf(
            2322 to "Type 'number' is not assignable to type 'string'."
        ))
    }

    /**
     * REFUSAL. An EMPTY object literal is vacuously assignable to an all-optional
     * target and tsc emits nothing — the guard [Checker.tryEmitWeakTypeAssignment]
     * has always carried, now reachable from the diagnostic path too.
     */
    @Test
    fun `an empty object literal argument is silent`() {
        val d = diagnose("""
            declare function zzzCc(o: { zzzA?: null }): number
            declare function zzzCc(o: { zzzB?: null }): string
            const zzzCcr: number = zzzCc({})
        """)
        assert(d.isEmpty())
    }

    /**
     * CONTROL. A later overload that genuinely ACCEPTS the argument still wins, and
     * refusing the weak first one must not manufacture a TS2769 — the weak overload
     * is declared FIRST here precisely so that a broken "found a matching overload"
     * loop would show up as a spurious diagnostic.
     */
    @Test
    fun `a matching later overload still wins over a refused weak one`() {
        val d = diagnose("""
            declare function zzzFf(o: { zzzA?: null }): number
            declare function zzzFf(o: string): string
            const zzzFfr: string = zzzFf("hi")
        """)
        assert(d.isEmpty())
    }

    /**
     * CONTROL. The pre-existing chain for an ordinary two-overload failure — no weak
     * parameter anywhere — must be byte-identical to what it was before this round.
     */
    @Test
    fun `an ordinary two-overload failure is unchanged`() {
        val d = diagnose("""
            declare function zzzEe(o: string): number
            declare function zzzEe(o: boolean): string
            const zzzEer = zzzEe(123)
        """)
        assert(d.map { it.code } == listOf(2769))
        assert(d[0].messageChain == listOf(
            "  Overload 1 of 2, '(o: string): number', gave the following error.",
            "    Argument of type 'number' is not assignable to parameter of type 'string'.",
            "  Overload 2 of 2, '(o: boolean): string', gave the following error.",
            "    Argument of type 'number' is not assignable to parameter of type 'boolean'.",
        ))
    }
}
