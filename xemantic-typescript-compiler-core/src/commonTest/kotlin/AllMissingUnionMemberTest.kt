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
 * (CHK.45) A PROPERTY MISSING ON **EVERY** UNION CONSTITUENT WAS REPORTED ONLY
 * FOR TWO HAND-PICKED MEMBER SHAPES, AND THE GAP HAS NOTHING TO DO WITH WHERE
 * THE RECEIVER IS DECLARED.
 *
 * `cmamCheckUnionReceiverNarrowing`'s union elaboration has two verdicts. PARTIAL
 * coverage (some member has the property, some do not) fires for any member set,
 * because a member that answered "yes" is the witness that the member tables were
 * resolved. ALL-MISSING has no witness — every member answered "no", and a "no"
 * from an incompletely resolved table is CLAUDE.md's B153 — so it was gated to
 * `allWellResolved` (every member a string/number literal or a single-declaration
 * heritage-free interface) or `allAnonPlainObjects`.
 *
 * Measured against `tools/tsgo-7.0.2/lib/tsc`, that whitelist dropped every union
 * carrying a FUNCTION type, a CONSTRUCTOR type, a PRIMITIVE, a TUPLE, or a TYPE
 * LITERAL beside a named interface. **It is not a block-scoping gap**: the very
 * same source is silent for a PARAMETER, for a FILE-LEVEL `const` and for a
 * body-local alike, which is why (CHK.44) — who found it through a body-local and
 * filed it as "(a) a member on NO constituent" — could pin nothing with a `.nope`
 * fixture. The parameter and file-level positives below are in this class for
 * exactly that reason.
 *
 * ### The refusals are the round's calibration, not caution
 *
 * Removing the gate ENTIRELY is `added=0 removed=0` on all eight tsc profiles and
 * moves ZERO corpus baselines — and still costs **two false positives on knip**
 * (`walk.ts`'s `item.members` / `item.jsDocTags` on `Export | undefined`), both a
 * cross-file interface WITH a heritage clause whose member table we do not fully
 * resolve. So the widening is a per-member trust predicate
 * (`Checker.cmamAllMissingTrustedMember`) and everything it adds is OUTSIDE the
 * named-interface world; the interface arm keeps the shipped rule verbatim. Every
 * `stays silent` test below is a REFUSAL with a measured reason, and tsc reports
 * all of them — they are false negatives we keep on purpose.
 *
 * ### Vacuity
 *
 * A TS2339 fixture is the documented vacuity trap of this family. Every positive
 * here was measured SILENT on the parent binary over the identical source (a
 * scratch project through the CLI), and the ablation arm that answers the whole
 * class is `allMissingTrusted = false`, which reddens every positive and no
 * refusal. The refusals' own arm is the opposite one — the unconditional gate.
 */
class AllMissingUnionMemberTest {

    private val prelude = """
        interface Alfa { alfa: number }
        interface Bravo { bravo: number }
        type Fn = () => number;
        declare const uaf: Alfa | Fn;
    """.trimIndent() + "\n"

    // --- POSITIVES: the population the round closed --------------------------

    @Test
    fun `a FUNCTION-type constituent no longer hides an all-missing property`() {
        val d = diagnose(prelude + "export function f(c: Alfa | Fn) { c.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * tsc names the union AND chains the FIRST missing constituent, for the
     * all-missing case exactly as for partial coverage — measured on
     * `tools/tsgo-7.0.2/lib/tsc`. A count-only pin cannot see the chain, and the
     * chain is what says the receiver was resolved rather than guessed.
     */
    @Test
    fun `the message names the union and chains the first missing constituent`() {
        val d = diagnose(prelude + "export function f(c: Alfa | Fn) { c.zzznope; }")
        val diag = d.single { it.code == 2339 }
        assert(diag.message == "Property 'zzznope' does not exist on type 'Alfa | (Fn)'.")
        assert(diag.messageChain == listOf("  Property 'zzznope' does not exist on type 'Alfa'."))
    }

    @Test
    fun `a PRIMITIVE constituent no longer hides an all-missing property`() {
        val d = diagnose(prelude + "export function f(c: Alfa | string) { c.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `an anonymous TYPE LITERAL constituent no longer hides an all-missing property`() {
        val d = diagnose(prelude + "export function f(c: Alfa | { lit: number }) { c.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * A tuple is an anonymous object carrying a NUMBER index signature, so the
     * naive "any index signature provides the property" refusal loses it. The
     * shared `cmamIndexSignatureProvides` applies round 479's rule — a number
     * index signature supplies only a NUMERIC-literal name.
     */
    @Test
    fun `a TUPLE constituent no longer hides an all-missing non-numeric property`() {
        val d = diagnose(prelude + "export function f(c: Alfa | [number, string]) { c.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a union of two FUNCTION types reports an all-missing property`() {
        val d = diagnose(prelude + "export function f(c: Fn | (() => string)) { c.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a CONSTRUCTOR-type constituent reports an all-missing property`() {
        val d = diagnose(prelude + "export function f(c: Alfa | (new () => Alfa)) { c.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * (CHK.44)'s recorded KNOWN GAP — `const c: A | F = u; c.nope` inside a
     * function body — closed here. Its cause was this whitelist and not that
     * round's receiver typing, which the two controls below make visible.
     */
    @Test
    fun `a BLOCK-SCOPED local reports an all-missing property`() {
        val d = diagnose(prelude + "export function f() { const c: Alfa | Fn = uaf; c.zzznope; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * THE CONTROL THAT NAMES THE MIS-DIAGNOSIS: a FILE-LEVEL receiver is bound,
     * has always resolved to its declared union, and was STILL silent before this
     * round — so the gap was never about where the declaration sits.
     */
    @Test
    fun `a FILE-LEVEL const reports an all-missing property - the shape that proves it is not a scoping gap`() {
        val d = diagnose(prelude + "const c: Alfa | Fn = uaf;\nc.zzznope;\nexport {};")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * (CHK.44) also filed "(b) an UN-ANNOTATED local" as a separate mechanism. It
     * SPLITS: the file-level form was silent for THIS round's reason (its inferred
     * type is the same all-missing union) and reports now; the BLOCK-SCOPED form
     * is a genuinely different gap — B83.5 leaves the declaration unbound and no
     * initializer is typed for it — and is still silent, pinned as such in
     * `BlockScopedReceiverTypeTest`.
     */
    @Test
    fun `a FILE-LEVEL UN-ANNOTATED const reports an all-missing property`() {
        val d = diagnose(prelude + "const c = uaf;\nc.zzznope;\nexport {};")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- NEGATIVE CONTROLS ---------------------------------------------------

    /** A property present on EVERY constituent is not an error anywhere. */
    @Test
    fun `negative control - a member on EVERY constituent stays silent`() {
        val d = diagnose(
            prelude + "interface C1 { shared: number }\ninterface C2 { shared: string }\n" +
                "export function f(c: C1 | C2) { c.shared; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * A member on ONE constituent, read after a narrow that leaves only that
     * constituent, is legal — the widening must not reach a narrowed receiver.
     */
    @Test
    fun `negative control - a member on ONE constituent after a correct narrow stays silent`() {
        val d = diagnose(
            prelude + "export function f(c: Alfa | Fn) { if (typeof c !== 'function') { c.alfa; } }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * PARTIAL coverage was already reported before this round — it is here so a
     * green all-missing suite can never be read as covering it.
     */
    @Test
    fun `control - partial coverage was already reported`() {
        val d = diagnose(prelude + "export function f(c: Alfa | Fn) { c.alfa; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- REFUSALS: measured false negatives we keep on purpose ---------------

    /**
     * THE REFUSAL THE WHOLE PREDICATE EXISTS FOR. A constituent whose interface
     * carries a heritage clause is the shape of knip's two false positives; tsc
     * reports this and we deliberately do not.
     */
    @Test
    fun `refusal - a HERITAGE-carrying interface constituent stays silent`() {
        val d = diagnose(
            prelude + "interface Base { bb: number }\ninterface Derived extends Base { dd: number }\n" +
                "export function f(c: Alfa | Derived) { c.zzznope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** A generic instantiation is a `Type.Reference`; its members are not consulted here. */
    @Test
    fun `refusal - a GENERIC INSTANTIATION constituent stays silent`() {
        val d = diagnose(
            prelude + "interface Box<T> { boxed: T }\n" +
                "export function f(c: Alfa | Box<number>) { c.zzznope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** A class instance type's member table is built from a different declaration kind. */
    @Test
    fun `refusal - a CLASS instance constituent stays silent`() {
        val d = diagnose(
            prelude + "class Kls { k: number = 1 }\n" +
                "export function f(c: Alfa | Kls) { c.zzznope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** An intersection folds its own constituents, each of which needs its own verdict. */
    @Test
    fun `refusal - an INTERSECTION constituent stays silent`() {
        val d = diagnose(
            prelude + "export function f(c: Alfa | (Bravo & { extra: number })) { c.zzznope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * CLAUDE.md: an enum's members live on `Symbol.exports` and on NO type at
     * all, so every member of an enum-flavoured object reads as absent — the one
     * refusal whose relaxation would fire on legal code.
     */
    @Test
    fun `refusal - an ENUM constituent stays silent`() {
        val d = diagnose(
            prelude + "enum En { X = 1 }\nexport function f(c: Alfa | En) { c.zzznope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * An anonymous object with NO members and no call signature carries no
     * evidence that its table was resolved at all, so `{}` and an index-only
     * literal are refused.
     */
    @Test
    fun `refusal - an EMPTY anonymous object constituent stays silent`() {
        val d = diagnose(prelude + "export function f(c: Alfa | {}) { c.zzznope; }")
        assert(d.none { it.code == 2339 })
    }
}
