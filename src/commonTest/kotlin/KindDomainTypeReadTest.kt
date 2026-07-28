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
 * (REL.1)(c) step 5b, round 752: the two `.kind` DOMAIN readers —
 * [Checker.kindDomainKeysOfType] (the negative-guard `never` veto, via
 * `kindDomainKeysExceed`) and [Checker.discriminantKindKeys] (the type-guard member
 * disjointness test) — read their keys type-first, annotation walk kept as fallback.
 *
 * **THESE PINS PASS ON A BUILD WITHOUT THE FLIP TOO, AND THAT IS THE POINT.** They are not
 * weak pins pretending to be strong ones; they record a MEASUREMENT. Each source below uses a
 * PARENTHESIZED `kind` annotation, which no AST key reader has an arm for — the witness that
 * flipped the other three call sites in this round — and each is silent on both builds.
 *
 * The reason is structural: both of these readers are VETOES layered over the structural
 * relation, written when an enum member resolved to `anyType` and the relation could not tell
 * two siblings apart. (REL.1)(a)/(b) gave the relation that ability, so a veto that declines
 * to answer no longer changes the outcome — it only ever agreed with what the relation was
 * about to decide anyway. Three shapes were built to expose a difference and none did:
 * a negative guard over a wide-domain subject, a positive guard over a three-member union,
 * and a conditional-type exclusion.
 *
 * So what these pin is the SILENCE — that the flip did not start dropping a union member or
 * washing a subject to `never` somewhere the veto used to abstain. The measured agreement is
 * in the flipped readers' own docs: 207/207 and 738/738 sightings agreeing over the whole
 * compiler profile, with 0 where the annotation walk answered and the type path did not.
 *
 * When (5c) deletes `kindDomainKeysExceed`/`kindDomainProvesNotSubtype`, the first pin here
 * becomes a pin on the relation alone — which is the intended end state, and the reason it is
 * written against observable narrowing rather than against the veto.
 */
class KindDomainTypeReadTest {

    private val kinded = """
        // @filename: /src/types.ts
        export enum SK { Alpha, Beta, Gamma }
        export interface NA { readonly kind: (SK.Alpha); a: number }
        export interface NB { readonly kind: (SK.Beta); b: string }
        export interface NC { readonly kind: (SK.Gamma); c: boolean }
        export type N = NA | NB | NC
        export interface Wide { readonly kind: (SK.Alpha | SK.Beta | SK.Gamma); w: number }
    """.trimIndent()

    /**
     * [Checker.kindDomainProvesNotSubtype]'s shape (round 472): the FALSE branch of a type
     * guard must not wash the subject to `never` when its `.kind` domain provably exceeds the
     * guard target's. Reading `n.w` after `!isB(n)` is what a `never` wash would break.
     */
    @Test
    fun `a negative guard over a wide parenthesized kind domain keeps the subject usable`() {
        val diagnostics = diagnose(
            """
            $kinded
            // @filename: /src/user.ts
            import { NB, Wide } from "./types";
            declare function isB(n: Wide): n is NB;
            export function f(n: Wide): number {
                if (!isB(n)) { return n.w; }
                return 0;
            }
            """,
        )
        assert(diagnostics.isEmpty())
    }

    /**
     * [Checker.typeGuardMemberDisjoint]'s shape: the TRUE branch keeps only the member whose
     * `.kind` key set meets the target's, so `n.b` must resolve.
     */
    @Test
    fun `a positive guard over parenthesized kind members narrows to the matching member`() {
        val diagnostics = diagnose(
            """
            $kinded
            // @filename: /src/user.ts
            import { N, NB } from "./types";
            declare function isB(n: N): n is NB;
            export function f(n: N): string {
                if (isB(n)) { return n.b; }
                return "";
            }
            """,
        )
        assert(diagnostics.isEmpty())
    }

    /**
     * The round-729 `evaluateConditional` consumer: `T extends NB ? never : T` must exclude
     * exactly `NB`, leaving a union whose members still share the readable `.kind`.
     */
    @Test
    fun `a conditional exclusion over parenthesized kind members stays silent`() {
        val diagnostics = diagnose(
            """
            $kinded
            // @filename: /src/user.ts
            import { N, NB } from "./types";
            type NotB<T> = T extends NB ? never : T;
            export function f(x: NotB<N>): number {
                if (x.kind === 0) { return 1; }
                return 2;
            }
            """,
        )
        assert(diagnostics.isEmpty())
    }
}
