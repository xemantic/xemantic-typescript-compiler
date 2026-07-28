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
 * (REL.1)(c) steps 5b and 5c, rounds 752–753: the `.kind` DOMAIN shapes, and what happened
 * to the two readers that used to decide them.
 *
 * Round 752 flipped both to read their keys type-first — `kindDomainKeysOfType` (the
 * negative-guard `never` veto, via `kindDomainKeysExceed`) and
 * [Checker.discriminantKindKeys] (the type-guard member disjointness test) — and measured
 * both flips UNOBSERVABLE. **Round 753 (5c) then DELETED the first one outright**, together
 * with `kindDomainKeysExceed`, `kindDomainProvesNotSubtype`, their node-side readers, and
 * round 729's `evaluateConditional` patch. [Checker.discriminantKindKeys] survives, because
 * its consumer [Checker.typeGuardMemberDisjoint] still has live callers.
 *
 * **SO THESE PINS NOW PIN THE RELATION ALONE, WHICH IS WHAT THEY WERE WRITTEN FOR.** Round
 * 752 said in this same comment that they would become that "when (5c) deletes
 * `kindDomainKeysExceed`/`kindDomainProvesNotSubtype`" — that is now the case, and every
 * source below is still silent. Each uses a PARENTHESIZED `kind` annotation, which no AST key
 * reader has an arm for, so on a pre-(REL.1)(a) engine there was nothing at all to decide
 * these shapes with.
 *
 * **THE DELETION EVIDENCE, because "the pins still pass" is not on its own evidence of
 * anything.** The veto was ablated before it was cut, and the ablation was instrumented to
 * count the verdicts it suppressed: **it fired 11,667 times on the compiler profile** (of
 * 40,648 consultations) and the output stayed BYTE-IDENTICAL at 46. An ablation that never
 * fires cannot distinguish dead code from load-bearing code — the objlit annotation fallback
 * in [Checker.selectUnionMemberByObjLitDiscriminant] is the counter-example from the same
 * round: byte-identical on this profile when ablated, and load-bearing off it.
 *
 * The reason the veto could go is structural: it was a veto layered over the structural
 * relation, written when an enum member resolved to `anyType` and the relation could not tell
 * two siblings apart. (REL.1)(a)/(b) gave the relation that ability, so the veto only ever
 * agreed with a verdict already reached.
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
     * The shape `kindDomainProvesNotSubtype` was written for (round 472) and which now has no
     * veto behind it at all (round 753 deleted it): the FALSE branch of a type guard must not
     * wash the subject to `never`. Reading `n.w` after `!isB(n)` is what a `never` wash would
     * break — so this is the pin that would have caught the deletion going wrong.
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
     * Round 729's `evaluateConditional` shape: `T extends NB ? never : T` must exclude exactly
     * `NB`, leaving a union whose members still share the readable `.kind`. Round 729 needed a
     * `.kind` domain veto bolted onto the conjunction to get this right; round 753 deleted the
     * veto and the shape still holds, because the relation itself discriminates enum members
     * since (REL.1)(a)/(b). The sharper sibling pin is
     * `DistributiveConditionalTypeTest.a sibling AST interface is not excluded just because it
     * is structurally compatible`, which is the pin round 729 wrote for the patch.
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
