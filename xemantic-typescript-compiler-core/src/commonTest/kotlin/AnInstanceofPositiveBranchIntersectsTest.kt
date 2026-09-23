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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.143) The POSITIVE branch of an `instanceof` must answer tsc's INTERSECTION where
 * the reference and the candidate are unrelated, not REPLACE the reference with the
 * candidate. `Checker.narrowInstanceOfPositiveFallback` is the tail this pins; it is
 * `getNarrowedTypeWorker`'s last resort in `typescript-go-repo/internal/checker/flow.go`.
 *
 * Every positive is a VALUE pin read out of a deliberate mis-assignment to a PRIMITIVE
 * target — TS2322's message is the only instrument here that PRINTS a flow type, and a
 * silence cannot separate "narrowed correctly" from "washed to `never`", which is exactly
 * the failure the union arm had. P5 is the one exception and deliberately so: its
 * subject's pre-change value WAS `never`, so a member read is the sharper instrument
 * there (it moves from two TS2339-on-`never` rows to silence).
 *
 * Every expectation was measured against `tools/tsgo-7.0.2/lib/tsc` on the same source.
 * The KNOWN residues, recorded here rather than pinned (a pin asserting today's wrong
 * answer is a countdown — CLAUDE.md round 765):
 *
 *  * a UNION member of an INTERSECTION is not parenthesized by `typeToString`
 *    (`C1 | Wide & Q` where tsgo prints `(C1 | Wide) & Q`); PRE-EXISTING and measured
 *    byte-identical on the parent binary for a hand-written `(C1 | Wide) & Q`
 *    annotation, i.e. this round only makes it REACHABLE from narrowing;
 *  * a member read on such a type is permissive here — `u.x` after `u instanceof Q`
 *    is silent where tsgo distributes and reports `Property 'x' does not exist on type
 *    '(C1 | Wide) & Q'`. A MISSING row, which is the direction (CHK.143)'s own sizing
 *    predicted: intersections can only silence a member read, never invent one;
 *  * a CONDITIONAL EXPRESSION's union gets no subtype reduction (`cond ? v : v` reads
 *    `P2 | (P2 & Q2)` where tsgo reads `P2`), a separate gap in the ternary's union and
 *    not in the flow join, which C4 pins as working.
 *
 * C7 EXISTS BECAUSE NOTHING ELSE IN THIS REPO SEES ITS GUARD. Ablating the `isMatch &&`
 * that confines the tail to the POSITIVE branch leaves the 8-profile grid green, the
 * whole errors channel of the corpus at **0 mismatches of 3,079**, and (before C7) every
 * pin here green — while a union whose negative branch filters to `never` then answers
 * `Dd1 | Dd2`, which BOTH invents a row tsgo does not have and loses the
 * `Property 'b' does not exist on type 'never'` that it does. The shape the other
 * controls miss is a union of two classes sharing ONE base: C1's structurally identical
 * classes reduce to a NON-union before the last negative step, so they never reach the
 * union arm's tail at all.
 */
class AnInstanceofPositiveBranchIntersectsTest {

    private val prelude =
        """
        class ZzzP { p = 1; }
        class ZzzQ { q = 2; }
        class ZzzB { b = 1; }
        class ZzzD extends ZzzB { d = 2; }
        class ZzzWide { w: string = ""; e: number = 0; }
        class ZzzNarrow { w: string = ""; }
        class ZzzC1 { x: string = ""; }
        declare const zzzP: ZzzP;
        declare const zzzB0: ZzzB;
        declare const zzzWide: ZzzWide;
        declare const zzzUnion: ZzzC1 | ZzzWide;
        declare const zzzAny: any;
        class ZzzDd1 extends ZzzB { d1 = 1; }
        class ZzzDd2 extends ZzzB { d2 = 2; }
        declare const zzzDU: ZzzDd1 | ZzzDd2;

        """.trimIndent() + "\n"

    @Test
    fun `P1 - an unrelated candidate makes the positive branch an intersection`() {
        diagnose(
            prelude +
                """
                export function zzzP1(): void {
                  if (zzzP instanceof ZzzQ) { const p: string = zzzP; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzP & ZzzQ' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("Type 'ZzzQ' is not assignable") })
        }
    }

    @Test
    fun `P2 - the read AFTER the join is the declared type again`() {
        diagnose(
            prelude +
                """
                export function zzzP2(): void {
                  if (zzzP instanceof ZzzQ) { }
                  const p: string = zzzP;
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzP' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("ZzzQ") })
        }
    }

    @Test
    fun `P3 - a reference assignable to the candidate keeps the REFERENCE`() {
        diagnose(
            prelude +
                """
                export function zzzP3(): void {
                  if (zzzWide instanceof ZzzNarrow) { const p: string = zzzWide; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzWide' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("Type 'ZzzNarrow' is not assignable") })
        }
    }

    @Test
    fun `P4 - a primitive reference intersects rather than being replaced`() {
        diagnose(
            prelude +
                """
                export function zzzP4(s: string): void {
                  if (s instanceof ZzzQ) { const p: number = s; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'string & ZzzQ' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("Type 'ZzzQ' is not assignable to type 'number'") })
        }
    }

    @Test
    fun `P5 - a union with NO matching constituent no longer washes to never`() {
        diagnose(
            prelude +
                """
                export function zzzP5(): void {
                  if (zzzUnion instanceof ZzzQ) { zzzUnion.q; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `P6 - the corpus shape - an unrelated instanceof does not pollute the join`() {
        diagnose(
            prelude +
                """
                export function zzzP6(s: Set<string> | Set<number>): void {
                  s = new Set<number>();
                  if (s instanceof Promise) { }
                  s.add(42);
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `C1 - the NEGATIVE branch of structurally identical classes still washes to never`() {
        diagnose(
            """
            class ZzzZ1 { x: string = ""; }
            class ZzzZ2 { x: string = ""; }
            class ZzzZ3 { x: string = ""; }
            declare const z: ZzzZ1 | ZzzZ2 | ZzzZ3;
            export function zzzC1(): void {
              if (!(z instanceof ZzzZ1)) {
                if (!(z instanceof ZzzZ2)) {
                  if (!(z instanceof ZzzZ3)) { z.x; }
                }
              }
            }
            """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2339 && it.message.contains("does not exist on type 'never'") })
        }
    }

    @Test
    fun `C2 - a DERIVED candidate still narrows down to the candidate`() {
        diagnose(
            prelude +
                """
                export function zzzC2(): void {
                  if (zzzB0 instanceof ZzzD) { const p: string = zzzB0; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzD' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("&") })
        }
    }

    @Test
    fun `C3 - the NEGATIVE branch of an unrelated candidate keeps the reference`() {
        diagnose(
            prelude +
                """
                export function zzzC3(): void {
                  if (!(zzzP instanceof ZzzQ)) { const p: string = zzzP; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzP' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("&") })
        }
    }

    @Test
    fun `C4 - an early-return guard reaches the same tail`() {
        diagnose(
            prelude +
                """
                export function zzzC4(): void {
                  if (!(zzzP instanceof ZzzQ)) return;
                  const p: string = zzzP;
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzP & ZzzQ' is not assignable") })
        }
    }

    @Test
    fun `C7 - a union whose NEGATIVE branch filters to never still answers never`() {
        diagnose(
            prelude +
                """
                export function zzzC7a(): void {
                  if (!(zzzDU instanceof ZzzB)) { const p: string = zzzDU; }
                }
                export function zzzC7b(): void {
                  if (!(zzzDU instanceof ZzzB)) { zzzDU.b; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2339 && it.message.contains("does not exist on type 'never'") })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `C6 - two structurally identical classes answer the CANDIDATE`() {
        diagnose(
            prelude +
                """
                export function zzzC6(c: ZzzC1): void {
                  if (c instanceof ZzzC1b) { const p: string = c; }
                }
                class ZzzC1b { x: string = ""; }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzC1b' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("Type 'ZzzC1' is not assignable") })
        }
    }

    @Test
    fun `C5 - an any subject still answers the candidate on the positive branch`() {
        diagnose(
            prelude +
                """
                export function zzzC5(): void {
                  if (zzzAny instanceof ZzzQ) { const p: string = zzzAny; }
                }
                """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'ZzzQ' is not assignable") })
            have(none { it.code == 2322 && it.message.contains("&") })
        }
    }
}
