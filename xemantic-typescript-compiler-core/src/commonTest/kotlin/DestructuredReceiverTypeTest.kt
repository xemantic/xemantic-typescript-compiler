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
 * (CHK.46) A **DESTRUCTURED** NAME WAS TYPED AS A RECEIVER NOWHERE.
 *
 * `const { inner } = h; inner.zzznope` reported NOTHING where
 * `tools/tsgo-7.0.2/lib/tsc` reports TS2339 — and, like (CHK.45)'s population and
 * unlike the queue item's framing, it is **not a block-scoping gap**: a file-level
 * `const`, a body-local and a destructured PARAMETER (declaration, arrow and method
 * form alike) were all equally silent. Two different reasons, both repaired by
 * `Checker.cmamDestructuredReceiverType`:
 *  - a BOUND binding name reaches `cmamGeneralReceiverType`'s `identSymbol` branch,
 *    where `getTypeOfSymbol` has **no `BindingElement` arm** and answers `anyType`;
 *  - an UNBOUND one (a body-local pattern, any parameter pattern) reaches the `else`
 *    branch, where `getTypeOfIdentifier` answers `anyType` as well —
 *    `currentLocalTypes` does not carry it in this pass and `currentParamBindingNames`
 *    is a deliberate blanket `anyType` for the parameter half (M3.1 round 429).
 *
 * The type itself was never the problem: a write probe (`const p: number = inner`)
 * answers `Inner` on the UNFIXED binary, in every one of these shapes. What was
 * missing is the property-access family's own reading of it.
 *
 * ### Calibration
 *
 * `added=0 removed=0` on all eight tsc profiles and knip **66 -> 66 with every row
 * byte-identical**, both against a parent binary rebuilt in the same session; zero
 * corpus baselines moved. The 8 profiles and the corpus are structurally weak
 * instruments for this family — knip is the one that has caught five changes in this
 * arc — so the refusals below are gated on what knip and the profiles measure, not
 * on what reads safest.
 *
 * ### Vacuity, per pin
 *
 * A TS2339 fixture is this family's documented vacuity trap. Every positive here was
 * measured SILENT on the parent binary over identical source through the CLI: a
 * scratch project carrying all four declaration sites reads **0** rows before and the
 * exact tsgo set after (position and message byte-identical, `Inner` for the member
 * shapes and `Cat | Dog` for the union ones). The ablation arm that answers the whole
 * class is a1 — the helper returning null unconditionally — which reddens every
 * positive and no refusal; each refusal has its own arm.
 */
class DestructuredReceiverTypeTest {

    private val prelude = """
        interface Inner { alpha: string }
        interface Middle { inner: Inner }
        interface Holder { inner: Inner; deeper: Middle; rest: number }
        declare const h: Holder;
    """.trimIndent() + "\n"

    private val petPrelude = """
        interface Cat { kind: 'cat'; meow: string }
        interface Dog { kind: 'dog'; bark: string }
        interface Box { pet: Cat | Dog }
        declare const b: Box;
    """.trimIndent() + "\n"

    // --- POSITIVES: the four declaration sites -------------------------------

    @Test
    fun `a FILE-LEVEL destructured const reports a missing property`() {
        val d = diagnose(prelude + "const { inner: fileLevel } = h;\nfileLevel.zzznope;")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a BODY-LOCAL destructured const reports a missing property`() {
        val d = diagnose(prelude + "export function f() { const { inner } = h; inner.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a destructured PARAMETER reports a missing property`() {
        val d = diagnose(prelude + "export function f({ inner }: Holder) { inner.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a destructured ARROW parameter reports a missing property`() {
        val d = diagnose(prelude + "export const f = ({ inner }: Holder) => { inner.zzznope; };")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a destructured METHOD parameter reports a missing property`() {
        val d = diagnose(prelude + "export class K { m({ inner }: Holder) { inner.zzznope; } }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * The message names the MEMBER's type, not the pattern's source — a count-only
     * pin cannot tell the two apart, and getting it wrong is exactly what a
     * receiver resolved through the wrong table looks like (the outer-binding
     * collision recorded as an open gap in the round note reads `Deep` for an
     * `Inner`). Byte-identical to tsgo 7.0.2 on the same source.
     */
    @Test
    fun `the message names the MEMBER type and not the pattern source`() {
        val d = diagnose(prelude + "export function f({ inner }: Holder) { inner.zzznope; }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    // --- POSITIVES: the pattern shapes ---------------------------------------

    @Test
    fun `a RENAMED binding reports against the property it renames`() {
        val d = diagnose(prelude + "export function f() { const { inner: local } = h; local.zzznope; }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Inner'.")
    }

    @Test
    fun `a NESTED binding pattern reports a missing property`() {
        val d = diagnose(prelude + "export function f() { const { deeper: { inner } } = h; inner.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a binding with a DEFAULT value reports a missing property`() {
        val d = diagnose(prelude + "export function f() { const { inner = h.inner } = h; inner.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * The declaration ANNOTATION arm of `typeCaptureDestructured`. Every other
     * variable-declaration pin above is un-annotated and therefore reaches the
     * INITIALIZER arm instead; the parameter pins reach the `Parameter` arm. Three
     * owners, three pins.
     *
     * The initializer arm's own limit is measured and recorded rather than pinned
     * (round 765): where the initializer is itself a BODY-LOCAL name
     * (`const wrapper = h; const { inner } = wrapper`) it answers `any`, because a
     * body-local is what B83.5 leaves unbound — gap (b), untouched by this round.
     */
    @Test
    fun `an ANNOTATED destructuring declaration reports a missing property`() {
        val d = diagnose(prelude + "export function f() { const { inner }: Holder = h; inner.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- POSITIVES: the UNION reading, which goes to (CHK.45)'s elaboration ---

    /**
     * A destructured UNION is substituted at `rawForNarrowing` rather than at the
     * two `any` bails, so it is decided by `cmamCheckUnionReceiverNarrowing` — which
     * consults the flow. That is (CHK.44)'s measured split and the reason the
     * narrowing refusal below can be green at the same time as this one.
     */
    @Test
    fun `a destructured UNION reports a property on NO constituent`() {
        val d = diagnose(petPrelude + "export function f() { const { pet } = b; pet.zzznope; }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Cat | Dog'.")
    }

    @Test
    fun `a destructured UNION reports a property on SOME constituent`() {
        val d = diagnose(petPrelude + "export function f() { const { pet } = b; pet.meow; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- NEGATIVES ------------------------------------------------------------

    @Test
    fun `negative control - a member that EXISTS stays silent`() {
        val d = diagnose(prelude + "export function f({ inner }: Holder) { inner.alpha; }")
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - a member that EXISTS on a body-local pattern stays silent`() {
        val d = diagnose(prelude + "export function f() { const { inner } = h; inner.alpha; }")
        assert(d.none { it.code == 2339 })
    }

    /**
     * The load-bearing negative: a destructured UNION narrowed by a discriminant
     * must stay silent. It is what makes the union substitution safe, and it is the
     * only negative here that a wrong CONSUMER choice (the two `any` bails instead
     * of `rawForNarrowing`) would break — those branches consult no flow.
     */
    @Test
    fun `negative control - a NARROWED destructured union stays silent`() {
        val d = diagnose(
            petPrelude + "export function f() { const { pet } = b; if (pet.kind === 'cat') { pet.meow; } }"
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - a NARROWED destructured union parameter stays silent`() {
        val d = diagnose(
            petPrelude + "export function f({ pet }: Box) { if (pet.kind === 'dog') { pet.bark; } }"
        )
        assert(d.none { it.code == 2339 })
    }

    // --- REFUSALS: each a measured false NEGATIVE tsc reports ------------------

    /**
     * A REST element's type is the source MINUS the named members, which this does
     * not compute; tsc reports `zzznope` against the residual `{ inner: Inner; }`
     * and we stay silent on purpose.
     *
     * The pin is the FALSE-POSITIVE direction rather than that false negative,
     * because only the FP direction is uniquely this guard's: a rest element whose
     * NAME happens to be a member of the source (`const { other, ...inner } = h`)
     * would otherwise adopt that member's type and report a LEGAL access. Written
     * with a name that does not collide, the refusal is redundant — `symbols` comes
     * back empty and `singleOrNull` refuses anyway (measured: arm a2 read 0 RED).
     */
    @Test
    fun `refusal - a REST element does not adopt a same-named member's type`() {
        val d = diagnose(
            """
            interface Inner { alpha: string }
            interface Holder { inner: Inner; other: number }
            declare const h: Holder;
            export function f() { const { other, ...inner } = h; inner.inner; }
            """.trimIndent()
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * An ARRAY pattern is out of scope by construction: `typeCaptureDestructured`
     * answers null for anything whose pattern is not an `ObjectBindingPattern`, so
     * tuple and iterable destructuring never reaches the member lookup. tsc reports.
     */
    @Test
    fun `refusal - an ARRAY pattern is not typed`() {
        val d = diagnose(
            prelude + "declare const xs: Inner[];\nexport function f() { const [head] = xs; head.zzznope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * A UNION source answers the member name once PER CONSTITUENT, and the receiver
     * is then a union of those member types which this does not build; a single
     * answer is required. tsc reports.
     */
    @Test
    fun `refusal - a UNION source answering per constituent is not typed`() {
        val d = diagnose(
            """
            interface Left { shared: Inner }
            interface Right { shared: Middle }
            interface Inner { alpha: string }
            interface Middle { beta: string }
            declare const either: Left | Right;
            export function f() { const { shared } = either; shared.zzznope; }
            """.trimIndent()
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * A CLASS instance type is refused because both callers hand this type straight
     * back as the receiver type, which routes AROUND their own `instanceof`-narrowing
     * bail (`tryEmitClassInstanceMissingTs2339`). Refusing keeps that bail's verdict.
     */
    @Test
    fun `refusal - a CLASS instance member type is not typed`() {
        val d = diagnose(
            """
            class Shape { area: number = 0 }
            interface Bag { shape: Shape }
            declare const bag: Bag;
            export function f() { const { shape } = bag; shape.zzznope; }
            """.trimIndent()
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * (CHK.44)'s measured guard, inherited: a `T | undefined` annotation exists in
     * order to be narrowed, and narrowing a destructured reference is not modelled —
     * so handing the walker the declared type reports against a type the code has
     * already excluded. tsc reports TS18048 for this shape, never TS2339.
     */
    @Test
    fun `refusal - a NULLISH member type is not typed`() {
        val d = diagnose(
            prelude + "interface Maybe { m: Inner | undefined }\ndeclare const q: Maybe;\n" +
                "export function f() { const { m } = q; m.zzznope; }"
        )
        assert(d.none { it.code == 2339 })
    }
}
