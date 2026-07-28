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
 * (REL.1)(c) step 5b, round 753 — the LAST of the six discriminant-key readers to go
 * type-first: [Checker.selectUnionMemberByObjLitDiscriminant], the object-literal
 * constituent selector.
 *
 * **The union order in these sources is REVERSED on purpose, and that is what makes the
 * pins discriminate.** When the selector returns null, the two consumers do not simply
 * abstain — they fall back to the whole union, and the nested-excess consumer then reads
 * the property off the FIRST constituent. So with the matching constituent placed SECOND,
 * a failed selection is not silence, it is a false positive naming the wrong member's
 * shape (`'id' does not exist in type '{ url: string; }'`). Written the other way round,
 * with the matching constituent first, every pin here passes on both builds — which is how
 * the first attempt was built and why it is worth stating.
 *
 * Each pin therefore asserts ABSENCE in a branch that must be reachable, and each is
 * paired with a PLAIN-annotation control that proves the shape is otherwise accepted, so a
 * pin cannot pass by the source simply never reaching the selector.
 *
 * MEASURED before the flip over the whole compiler profile: **292 sightings, 292 AGREE, 0
 * mismatched, 0 where the type path lost a key the annotation walk had, 0 where it gained
 * one.** The instrument was falsified in the same run, and the [Checker.canonicalEnumSymbol]
 * memo-freeze was checked by recomputing every decision with the memo bypassed: 342 mints,
 * 0 divergences.
 *
 * **THE ANNOTATION FALLBACK STAYS, AND UNLIKE THE FIVE EARLIER SITES IT IS STILL
 * LOAD-BEARING — measured, not assumed.** This is the site that consumes the round-475
 * `TypeQuery` arm; see `an annotation the type path cannot read still selects the
 * constituent` below, which is the shape that keeps it alive.
 */
class ObjLitDiscriminantTypeReadTest {

    /**
     * The witness for the flip: a PARENTHESIZED enum-member annotation. It is legal
     * TypeScript, and `enumMemberKeysOfTypeNode` has no `ParenthesizedType` arm, so the
     * annotation walk yields NO keys and no constituent is selected. The resolved property
     * type is `Kind.Alpha` either way, which is the structural point — the type reader has
     * no arm list to be missing an entry from.
     *
     * FAILS on a build without the flip with
     * `TS2353: … 'id' does not exist in type '{ url: string; }'` — BetaE's shape, read
     * because the selection fell back to the head of the union.
     */
    @Test
    fun `a parenthesized enum member annotation selects the object literal constituent`() {
        val diagnostics = diagnose(
            """
            enum Kind { Alpha, Beta }
            interface AlphaE { k: (Kind.Alpha); data: { id: number } }
            interface BetaE { k: (Kind.Beta); data: { url: string } }
            type E = BetaE | AlphaE
            export const e1: E = { k: Kind.Alpha, data: { id: 1 } }
            """,
        )
        assert(diagnostics.isEmpty())
    }

    /**
     * The reachability control for the pin above: the SAME union, the same reversed order,
     * the same nested literal — only the annotation is unparenthesized, which the
     * annotation walk can read. Silent on both builds. If this one ever starts failing, the
     * pin above is no longer testing what it claims to.
     */
    @Test
    fun `the plain annotation control selects the same constituent`() {
        val diagnostics = diagnose(
            """
            enum Kind { Alpha, Beta }
            interface AlphaP { k: Kind.Alpha; data: { id: number } }
            interface BetaP { k: Kind.Beta; data: { url: string } }
            type P = BetaP | AlphaP
            export const p1: P = { k: Kind.Alpha, data: { id: 1 } }
            """,
        )
        assert(diagnostics.isEmpty())
    }

    /**
     * The selection must still be a SELECTION: an object literal naming a constituent must
     * not make a sibling's property acceptable. Reading `url` out of an `AlphaE` literal is
     * an excess property whichever way the union is ordered, so this fires on both builds —
     * it guards the flip against turning the filter into a pass-through.
     */
    @Test
    fun `a parenthesized annotation still rejects the sibling constituent's property`() {
        val diagnostics = diagnose(
            """
            enum Kind { Alpha, Beta }
            interface AlphaX { k: (Kind.Alpha); data: { id: number } }
            interface BetaX { k: (Kind.Beta); data: { url: string } }
            type X = BetaX | AlphaX
            export const x1: X = { k: Kind.Alpha, data: { id: 1, url: "u" } }
            """,
        )
        assert(diagnostics.any { it.code == 2353 })
    }

    /**
     * **THE PIN THAT KEEPS THE ANNOTATION FALLBACK ALIVE.** `typeof CloseTag` over a
     * top-level `const CloseTag = "close"` resolves to the WIDENED `string`, which carries
     * no discriminant key, while `enumMemberKeysOfTypeNode`'s round-475 `TypeQuery` arm
     * still yields `lit:s:close`. The probe classifies this shape as TYPEBLIND with a real
     * decision difference (`ast=true type=false`), and a build with the fallback cut out
     * emits `TS2353: … 'id' does not exist in type '{ url: string; }'` here — the wrong
     * constituent, exactly the failure the reversed order is arranged to expose.
     *
     * So this is the ablation, written down: the compiler profile stays byte-identical at 46
     * with the fallback removed, and that is not evidence of deadness, only that the profile
     * has no such shape. Delete the fallback and this pin turns red.
     */
    @Test
    fun `an annotation the type path cannot read still selects the constituent`() {
        val diagnostics = diagnose(
            """
            const CloseTag = "close"
            const OpenTag = "open"
            interface CloseEvent { kind: typeof CloseTag; data: { id: number } }
            interface OpenEvent { kind: typeof OpenTag; data: { url: string } }
            type Ev = OpenEvent | CloseEvent
            export const ev1: Ev = { kind: CloseTag, data: { id: 1 } }
            """,
        )
        assert(diagnostics.none { it.code == 2353 })
    }

    /**
     * The companion that makes the pin above non-vacuous, and the ablation stated in the
     * form that actually turns red. Same union, same reversed order — but the nested
     * literal now carries `OpenEvent`'s property. A TS2353 can only be reported here if the
     * selector CHOSE `CloseEvent`; if the selection stops happening the descent falls back
     * to the head of the union, `url` becomes a legitimate property of `OpenEvent`, and the
     * source goes silent.
     *
     * So a build with the annotation fallback removed does not fail the `none` pin above by
     * some indirect route — it fails HERE, by falling silent. That asymmetry is the whole
     * argument for keeping the fallback at this one site.
     */
    @Test
    fun `the type-blind annotation shape is really reaching the selector`() {
        val diagnostics = diagnose(
            """
            const CloseTag2 = "close"
            const OpenTag2 = "open"
            interface CloseEvent2 { kind: typeof CloseTag2; data: { id: number } }
            interface OpenEvent2 { kind: typeof OpenTag2; data: { url: string } }
            type Ev2 = OpenEvent2 | CloseEvent2
            export const ev2: Ev2 = { kind: CloseTag2, data: { url: "u" } }
            """,
        )
        assert(diagnostics.any { it.code == 2353 })
    }

    /**
     * A string-literal discriminant carries its key in the resolved type as well as in the
     * annotation, so both readers answer and the flip is a re-derivation. Pinned because the
     * `lit:s:` namespace shares [Checker.discriminantKeysOfMember] with the enum keys — a
     * regression in the union distribution would surface here first.
     */
    @Test
    fun `a string literal discriminant selects the object literal constituent`() {
        val diagnostics = diagnose(
            """
            interface AlphaS { tag: "alpha"; data: { id: number } }
            interface BetaS { tag: "beta"; data: { url: string } }
            type S = BetaS | AlphaS
            export const s1: S = { tag: "alpha", data: { id: 1 } }
            """,
        )
        assert(diagnostics.isEmpty())
    }
}
