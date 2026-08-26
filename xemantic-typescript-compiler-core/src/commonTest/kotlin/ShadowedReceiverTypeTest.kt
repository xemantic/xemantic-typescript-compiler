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
 * (CHK.47) AN **OUTER BINDING OF THE SAME NAME** DEFEATED EVERY BLOCK-SCOPED
 * RECEIVER, AND THE MESSAGE THEN NAMED THE **OUTER** TYPE.
 *
 * `lookupPerFileForNode` is keyed by the FILE, so for a receiver identifier it
 * answers the file-level declaration of that spelling however deeply the reference
 * is nested — and CLAUDE.md's B83.5 leaves every block-scoped declaration out of
 * the binder tables, so the shadowing declaration was invisible to it and to
 * `getTypeOfSymbol` alike. Three of the four shapes below were therefore wrong in
 * the worst direction a checker can be wrong: a CONFIDENT message naming a type the
 * expression does not have. Measured against `tools/tsgo-7.0.2/lib/tsc` on the
 * SHIPPED binary, through the CLI over identical source:
 *
 * | # | shape | ours | tsgo |
 * |---|---|---|---|
 * | A | `const inner: Deep` + `const { inner } = h; inner.zzznope` | `Deep` | `Inner` |
 * | B | `function alpha()` + `function f({ alpha }: Inner) { alpha.zzznope }` | `typeof alpha` | `string` |
 * | C | `const cc: Deep` + `const cc = h.inner; cc.zzznope` | SILENT | `Inner` |
 * | D | `const dd: Deep` + `const dd: Inner = h.inner; dd.zzznope` | `Deep` | `Inner` |
 *
 * All four now match tsgo exactly, message for message and column for column.
 *
 * ### THREE MECHANISMS, NOT ONE — and the queue item named only the first
 *
 *  1. `Checker.cmamLexicalValueShadow` refuses the per-file symbol when an inner
 *     lexical VALUE binding shadows the name, which routes A/C/D into the branch
 *     that reads `currentLocalTypes` and (CHK.46)'s two block-scoped helpers.
 *     `cmamShadowReadingWins` decides which of the two readings wins, and D is what
 *     discriminates it: an ANNOTATED body-local has a real `currentLocalTypes` entry
 *     and must keep it.
 *  2. B is not that walker at all. `spineExEnterNode` — the B431 expando-function
 *     anchor — carries its OWN shadow test, and `spineExFnShadows` compared
 *     `(x.name as? Identifier)?.text`, i.e. it was blind to every destructuring
 *     form. `spineExBindingNameShadows` recurses into binding patterns.
 *  3. C additionally needed round 512's un-inferable-shadow bail to stand down where
 *     a block-scoped helper CAN name the inner binding's type. Its premise is that
 *     the receiver is unknowable; (CHK.46) made that premise false for one
 *     population, and where neither helper answers the bail is untouched.
 *
 * ### CALIBRATION
 *
 * `added=0 removed=0` on all eight tsc profiles, zero corpus baselines moved,
 * `cost_gate` PASSES unrebaselined with `output.errors` 46. On **knip** the change
 * is worth **66 -> 49 diagnostics, seventeen removals and no additions**, and every
 * one of the seventeen is a FALSE POSITIVE confirmed silent in tsgo 7.0.2 — fifteen
 * of them `Property '0' does not exist on type 'Plugin'`, where a `for (const plugin
 * of …)` loop variable had been resolving to the file's own `const plugin: Plugin`,
 * and two the same shape for `Args` at a callback PARAMETER. That row family is the
 * one `PLAN-PHASE-5.md` had already flagged as "a resolution/collision, not
 * narrowing" and left uncharacterized.
 *
 * ### THE ARMS — one mistake each, each diffed against the arm's OWN snapshot,
 * each anchor asserted unique
 *
 * | arm | mistake | RED |
 * |---|---|---|
 * | a1 | `cmamLexicalValueShadow` returns false | 4 — A, B, C, D |
 * | a2 | drop the `perFileIdentSymbol != null` conjunct | **0 pins**, −1 knip row |
 * | a3 | `cmamShadowReadingWins` accepts any recorded type | 1 — D |
 * | a4 | the shadow route falls through instead of refusing | 1 — G |
 * | a5 | `spineExBindingNameShadows` handles only an Identifier | 3 — B, E, E2 |
 * | a6 | only its ARRAY arm off | 2 — E, E2 |
 * | a7 | round 512's bail restored unconditionally | 1 — C |
 * | a8 | drop the single-declaration refusal | **0** |
 * | a9 | drop the declaration-KIND whitelist | **0** |
 * | a10 | `cmamDestructuredReceiverType` ignores `shadowed` | **0** — LEG REMOVED |
 * | a11 | `cmamUnannotatedLocalReceiverType` ignores `shadowed` | 1 — C |
 *
 * a2's uniquely-its-own failure is a knip ROW and not a pin: without it a
 * `catch (error)` reached through an `in` guard goes silent, because `lexicalShadow`
 * would fire where there is no outer declaration to lose to. a8 and a9 are
 * REDUNDANT GUARDS TODAY (round 807) and are kept as documented conservatism — the
 * populations they protect (a merged symbol; a type parameter, a block-scoped class
 * or an import in the lexical tables) are ones no fixture here can build without
 * asserting a wrong answer in BOTH arms, and knip does not reach them either. a10
 * was measured inert on the pins, on knip AND on the 8-profile grid, so the leg was
 * DELETED rather than shipped un-gateable: inside round 512's bail that call is a
 * constant null by construction, since the branch is entered only for a name that is
 * already in `currentShadowedNames`.
 *
 * ### VACUITY
 *
 * Every one of the four pins FAILED on a parent binary rebuilt in this session, and
 * failed with the WRONG VALUE visible in the power-assert diagram (A/B/D) or with no
 * diagnostic at all (C) — never by construction. They are value assertions on the
 * message, which is the only thing that separates "reports about the outer type"
 * from "reports about the inner one"; a count-only pin passes on both binaries for
 * A, B and D.
 */
class ShadowedReceiverTypeTest {

    private val prelude = """
        interface Inner { alpha: string }
        interface Deep { beta: number }
        interface Holder { inner: Inner }
        declare const h: Holder;
        declare function use(x: unknown): void;
        declare const dcl: Deep;
    """.trimIndent() + "\n"

    @Test
    fun `A - a body-local DESTRUCTURED name under a colliding file-level const`() {
        val d = diagnose(
            prelude + "const inner: Deep = dcl;\nuse(inner.beta);\n" +
                "export function f() { const { inner } = h; use(inner.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    @Test
    fun `B - a destructured PARAMETER under a colliding file-level function`() {
        val d = diagnose(
            prelude + "export function alpha() {}\n" +
                "export function f({ alpha }: Inner) { use(alpha.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'string'.")
    }

    @Test
    fun `C - an UN-ANNOTATED body-local const under a colliding file-level const`() {
        val d = diagnose(
            prelude + "const cc: Deep = dcl;\nuse(cc.beta);\n" +
                "export function f() { const cc = h.inner; use(cc.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    @Test
    fun `D - an ANNOTATED body-local const under a colliding file-level const`() {
        val d = diagnose(
            prelude + "const dd: Deep = dcl;\nuse(dd.beta);\n" +
                "export function f() { const dd: Inner = h.inner; use(dd.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    /**
     * The ARRAY half of `spineExBindingNameShadows`. We are SILENT here where tsgo
     * reports `Inner` — `cmamDestructuredReceiverType` refuses a non-object pattern
     * ([typeCaptureDestructured] answers null for one), which is a known false
     * negative recorded in the round note. What this pin owns is the direction that
     * matters: the expando anchor must not answer about the file-level `alpha`.
     */
    @Test
    fun `E - an ARRAY-pattern parameter must not report the outer function type`() {
        val d = diagnose(
            prelude + "export function alpha() {}\n" +
                "export function f([alpha]: [Inner]) { use(alpha.zzznope); }"
        )
        assert(d.none { it.code == 2339 && it.message.contains("typeof alpha") })
    }

    /**
     * The same for an array-pattern BODY-LOCAL, which reaches
     * `spineExFnShadows`' VariableStatement leg rather than its parameter leg.
     */
    @Test
    fun `E2 - an ARRAY-pattern body-local must not report the outer function type`() {
        val d = diagnose(
            prelude + "export function alpha() {}\n" +
                "export function f() { const [alpha] = [1]; use(alpha.zzznope); }"
        )
        assert(d.none { it.code == 2339 && it.message.contains("typeof alpha") })
    }

    /**
     * THE REFUSAL AT THE END OF THE SHADOW ROUTE. When a shadow is established and
     * NEITHER block-scoped helper can name the inner binding's type, the route stops
     * rather than letting `getTypeOfIdentifier` answer — which would restore exactly
     * the outer reading the round removes.
     *
     * A REST element is the cheapest shape that reaches it: `cmamDestructuredReceiverType`
     * refuses one (its type is the source minus the named members, which that helper
     * does not compute), and the name is not in `currentLocalTypes`, so the fallback
     * would resolve the file-level `const inner: Deep`. tsgo reports
     * `{ inner: Inner; }` here and we are silent — a known false negative — but the
     * pin owns the direction that matters, and arm a4 (fall through instead of
     * refusing) is what reddens it.
     */
    @Test
    fun `G - a REST element under a colliding file-level const must not report the outer type`() {
        val d = diagnose(
            prelude + "interface H2 { inner: Inner; other: number }\ndeclare const h2: H2;\n" +
                "const inner: Deep = dcl;\nuse(inner.beta);\n" +
                "export function f() { const { other, ...inner } = h2; use(inner.zzznope); }"
        )
        assert(d.none { it.code == 2339 && it.message.contains("'Deep'") })
    }

    // --- CONTROLS -------------------------------------------------------------

    /**
     * CONTROL, always green on both binaries: with NO inner binding of the name,
     * the file-level declaration is still the right answer and still reports.
     * `cmamLexicalValueShadow` must not fire for it.
     */
    @Test
    fun `control - with no inner binding the file-level type still reports`() {
        val d = diagnose(prelude + "const solo: Deep = dcl;\nuse(solo.beta);\nuse(solo.zzznope);")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Deep'.")
    }

    /**
     * CONTROL, always green on both binaries: an inner binding of a DIFFERENT name
     * leaves the file-level reading alone.
     */
    @Test
    fun `control - an inner binding of another name does not shadow`() {
        val d = diagnose(
            prelude + "const solo2: Deep = dcl;\nuse(solo2.beta);\n" +
                "export function f() { const other = h.inner; use(other.alpha); use(solo2.zzznope); }"
        )
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Deep'.")
    }
}
