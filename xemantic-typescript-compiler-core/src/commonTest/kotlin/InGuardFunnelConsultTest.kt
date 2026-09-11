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
 * (CHK.122) THE `in`-GUARD CONSULT BELONGS AT THE **EMISSION FUNNEL**, NOT ON THE
 * RECEIVER-TYPING ROUTES.
 *
 * `if ('zzzNope' in v) { v.zzzNope }` is LEGAL — tsc narrows an object type by `in`
 * to `T & Record<'zzzNope', unknown>` — and `Checker.narrowByInOperator`'s non-union
 * arm deliberately answers the UNCHANGED type, which is correct for a suppression
 * consumer and invisible to any identity test. So an emission that consults nothing
 * is a false positive on idiomatic duck-typing.
 *
 * ### THE QUEUE ITEM NAMED ONE ROUTE AND THE POPULATION IS FOUR
 *
 * (CHK.122) was queued as "the flow route lacks the consult where (CHK.121)'s seam
 * has one", with the fix given as "give the flow route the same consult". Measured
 * against `tools/tsgo-7.0.2/lib/tsc` AND pristine `typescript@6.0.3` — which agree
 * on every cell — the ours-only false positives were **four**, and only ONE is the
 * route the item names:
 *
 *  1. `Checker.cmamNarrowedAnyReceiverType` — an annotated body-local with an
 *     IDENTIFIER initializer (the item's case);
 *  2. `Checker.cmamDestructuredReceiverType` — `const { inner } = h`;
 *  3. a **FILE-LEVEL** `const`, and
 *  4. a **PARAMETER**.
 *
 * The last two never touch the `any` bail those helpers live on at all: their
 * receiver is genuinely typed, so no receiver-typing helper runs and there is
 * nothing there to give a consult to. Fixing the routes one at a time could not
 * have reached them. The consult therefore went where every route ARRIVES —
 * `Checker.cmamEmitMissingProperty`, under `prop == null` — and the three existing
 * per-route consults were KEPT: they refuse SUPPLYING a type, which is upstream of
 * and cheaper than refusing the emission, and each is pinned by its own class.
 *
 * ### EXHAUSTION DECLINES HERE AND REFUSES THERE — MEASURED, NOT ASSUMED
 *
 * `cmamInGuardMayAddProperty` answers TRUE (refuse) when its 512-antecedent budget
 * runs out, on the argument that silence costs a false negative and a guess costs a
 * false positive. That is right for the three routes, which gate a narrow helper
 * population. At the funnel it is wrong, and the first build measured it: with the
 * refusing form the dashboard profiles read `refused=5 exhausted=5` on services,
 * server AND harness — **every refusal it made on tsc's own sources was blind, and
 * not one was a guard it had found.** A flow graph deeper than the budget is
 * ordinary in real code and has nothing to do with `in` guards.
 *
 * So the funnel asks with `refuseOnExhaustion = false`, which buys the property that
 * makes the change defensible: **it can suppress an emission only when it has
 * positively found an `in` condition naming this property on this reference path.**
 * On all eight profiles it then reads `refused=0` — it suppresses nothing there, so
 * no row can move — with `exhausted=5` on services/server/harness recording the
 * declines the refusing form would have deleted silently.
 *
 * The residue is pinned as a residue rather than hidden: an `in`-guarded read
 * separated from its guard by ~130+ branching statements stays a false positive.
 * That needs BOTH conditions, where the refusing form needed only the depth.
 *
 * ### WHY EVERY POSITIVE HERE HAS AN UNGUARDED TWIN
 *
 * Every pin below asserts a SILENCE, and a blanket suppression would satisfy all of
 * them. The twin — the same receiver, the same absent member, no guard — asserts the
 * row still fires, so the pair discriminates a consult from a deletion. A pin
 * asserting only the silence would be green on a binary that had simply stopped
 * checking member existence.
 *
 * ### WHAT THESE 16 PINS DISCRIMINATE, MEASURED
 *
 * One mistake at a time (`scripts/chk122-ablate.sh`), with BOTH controls — which is
 * what makes the two 0-RED pins interpretable rather than merely unexplained:
 *
 *  - `a1` drop the funnel consult ............ 5 RED (all four routes + the shallow pair)
 *  - `a2` restore `refuseOnExhaustion = true`  1 RED (the residue pin, and only it)
 *  - `a3` ignore the property NAME ........... 1 RED
 *  - `a4` ignore the reference PATH .......... 2 RED
 *  - `c-green` comment-only control .......... 0 RED
 *  - `c-red` always suppress ................. 8 RED (every twin + the three exactness pins)
 *
 * **TWO PINS DISCRIMINATE NO ARM, AND THAT IS BY DESIGN RATHER THAN A GAP** — both
 * are served by a PRE-EXISTING route consult that no arm here ablates, so they pin
 * that this round left those contracts alone:
 * `a guard on the NESTED path does suppress the nested read`
 * (`Checker.cmamCheckNestedObjectReceiver`'s own consult) and
 * `the UN-ANNOTATED route consult still suppresses`
 * (`Checker.cmamUnannotatedLocalReceiverType`'s). Neither is evidence about the
 * funnel; `NestedAccessReceiverTest` and `UnannotatedLocalReceiverTest` own them.
 */
class InGuardFunnelConsultTest {

    private val prelude = """
        interface ZzzCfg { known: number }
        interface ZzzHolder { inner: ZzzCfg }
        declare const zzzCfgV: ZzzCfg;
        declare const zzzHolderV: ZzzHolder;
        declare function sink(x: unknown): void;
    """.trimIndent() + "\n"

    private fun ts2339(src: String) = diagnose(prelude + src).count { it.code == 2339 }

    // --- ROUTE 1: the flow route, which is the one the queue item named ------

    @Test
    fun `an in-guarded read through the FLOW route is silent - the item's own case`() {
        assert(
            ts2339(
                "export function f(): void { const v: ZzzCfg = zzzCfgV; " +
                    "if ('zzzNope' in v) { sink(v.zzzNope) } }"
            ) == 0
        )
    }

    @Test
    fun `twin - the same FLOW-route read with no guard still reports`() {
        assert(
            ts2339("export function f(): void { const v: ZzzCfg = zzzCfgV; sink(v.zzzNope) }") == 1
        )
    }

    // --- ROUTE 2: destructured, which the item did not name -----------------

    @Test
    fun `an in-guarded read through a DESTRUCTURED receiver is silent`() {
        assert(
            ts2339(
                "export function f(): void { const { inner } = zzzHolderV; " +
                    "if ('zzzNope' in inner) { sink(inner.zzzNope) } }"
            ) == 0
        )
    }

    @Test
    fun `twin - the same DESTRUCTURED read with no guard still reports`() {
        assert(
            ts2339(
                "export function f(): void { const { inner } = zzzHolderV; sink(inner.zzzNope) }"
            ) == 1
        )
    }

    // --- ROUTE 3: a FILE-LEVEL const, which reaches no `any` bail at all ----

    @Test
    fun `an in-guarded read of a FILE-LEVEL const is silent`() {
        assert(
            ts2339(
                "const zzzTop: ZzzCfg = zzzCfgV;\n" +
                    "export function f(): void { if ('zzzNope' in zzzTop) { sink(zzzTop.zzzNope) } }"
            ) == 0
        )
    }

    @Test
    fun `twin - the same FILE-LEVEL read with no guard still reports`() {
        assert(
            ts2339(
                "const zzzTop: ZzzCfg = zzzCfgV;\n" +
                    "export function f(): void { sink(zzzTop.zzzNope) }"
            ) == 1
        )
    }

    // --- ROUTE 4: a PARAMETER, likewise genuinely typed ----------------------

    @Test
    fun `an in-guarded read of a PARAMETER is silent`() {
        assert(
            ts2339(
                "export function f(v: ZzzCfg): void { if ('zzzNope' in v) { sink(v.zzzNope) } }"
            ) == 0
        )
    }

    @Test
    fun `twin - the same PARAMETER read with no guard still reports`() {
        assert(ts2339("export function f(v: ZzzCfg): void { sink(v.zzzNope) }") == 1)
    }

    // --- THE CONSULT IS EXACT IN BOTH ARGUMENTS -----------------------------

    /**
     * A guard naming a DIFFERENT property must not suppress. Without this the pins
     * above are satisfied by "any `in` anywhere disables the check".
     */
    @Test
    fun `a guard naming a DIFFERENT property does not suppress`() {
        assert(
            ts2339(
                "export function f(v: ZzzCfg): void { if ('zzzOther' in v) { sink(v.zzzNope) } }"
            ) == 1
        )
    }

    /**
     * A guard on a DIFFERENT reference path must not suppress — the consult compares
     * `getReferencePath`, not just the property name.
     */
    @Test
    fun `a guard on a DIFFERENT reference path does not suppress`() {
        assert(
            ts2339(
                "export function f(v: ZzzCfg, w: ZzzCfg): void { " +
                    "if ('zzzNope' in w) { sink(v.zzzNope) } }"
            ) == 1
        )
    }

    /** A NESTED path is matched as a whole: `h.inner` is not `h`. */
    @Test
    fun `a guard on the CONTAINER does not suppress a read of the nested receiver`() {
        assert(
            ts2339(
                "export function f(h: ZzzHolder): void { " +
                    "if ('zzzNope' in h) { sink(h.inner.zzzNope) } }"
            ) == 1
        )
    }

    /**
     * …and a guard on the nested path itself does suppress, which is the pair.
     *
     * **UNDISCRIMINATED for this round**: the row is suppressed by
     * `Checker.cmamCheckNestedObjectReceiver`'s own pre-existing consult, above the
     * funnel, so every arm of `scripts/chk122-ablate.sh` leaves it green. It is here
     * as the pair of the container pin above, not as coverage of the funnel.
     */
    @Test
    fun `a guard on the NESTED path does suppress the nested read`() {
        assert(
            ts2339(
                "export function f(h: ZzzHolder): void { " +
                    "if ('zzzNope' in h.inner) { sink(h.inner.zzzNope) } }"
            ) == 0
        )
    }

    // --- THE EXHAUSTION CONTRACT --------------------------------------------

    /**
     * **residue - a guard separated from its read by a flow deeper than the
     * 512-antecedent budget still reports.**
     *
     * This asserts today's WRONG answer and says so in its name, per CLAUDE.md's
     * countdown rule: both references are silent here. It is the deliberate cost of
     * `refuseOnExhaustion = false` at the funnel, and it is the pin that would
     * notice if someone changed that decision back — at which point the five blind
     * refusals per profile come back with it.
     *
     * ~130 branching statements is where the budget trips; 150 is used so the pin is
     * not sitting on the boundary.
     */
    @Test
    fun `residue - a guard beyond the antecedent budget does not suppress`() {
        val filler = (0 until 150).joinToString("\n") { "  if (n === $it) { sink($it); }" }
        assert(
            ts2339(
                "export function f(n: number, v: ZzzCfg): void {\n" +
                    "  if ('zzzNope' in v) {\n$filler\n    sink(v.zzzNope);\n  }\n}"
            ) == 1
        )
    }

    /** Its pair: the identical shape with a SHALLOW flow is suppressed. */
    @Test
    fun `the same guard with a shallow flow does suppress`() {
        assert(
            ts2339(
                "export function f(n: number, v: ZzzCfg): void {\n" +
                    "  if ('zzzNope' in v) {\n    sink(v.zzzNope);\n  }\n}"
            ) == 0
        )
    }

    // --- THE THREE EXISTING ROUTE CONSULTS KEEP THEIR OWN CONTRACT ----------

    /**
     * The route consults were NOT switched to `refuseOnExhaustion = false`. Their
     * argument still holds — they gate whether a narrow helper population gets a
     * type at all — and changing them would have been an unmeasured second change
     * riding along. This pins that their default is unchanged by exercising the
     * un-annotated route, whose consult sits at
     * `Checker.cmamUnannotatedLocalReceiverType`'s tail.
     */
    @Test
    fun `the UN-ANNOTATED route consult still suppresses`() {
        assert(
            ts2339(
                "export function f(): void { const v = zzzCfgV; " +
                    "if ('zzzNope' in v) { sink(v.zzzNope) } }"
            ) == 0
        )
    }

    @Test
    fun `twin - the same UN-ANNOTATED read with no guard still reports`() {
        assert(ts2339("export function f(): void { const v = zzzCfgV; sink(v.zzzNope) }") == 1)
    }
}
