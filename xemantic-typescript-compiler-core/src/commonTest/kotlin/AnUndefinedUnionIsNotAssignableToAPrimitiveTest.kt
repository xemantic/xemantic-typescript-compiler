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
 * (CHK.63): `T | undefined` is no longer silently assignable to `T` when the target
 * is a PRIMITIVE.
 *
 * [Checker.canUseTypeEngine] refused a NULLISH union source against a primitive target
 * outright — "narrowing we don't implement" — which made the commonest mistake in
 * TypeScript invisible at the DECLARATION, ASSIGNMENT and RETURN readers alike. The
 * three things that had to land first are what took eight rounds:
 *
 *  * the RETURN and ASSIGNMENT readers had to reach the FLOW walk for a primitive
 *    target at all (round 784's gate admitted only object-ish and union targets), or
 *    opening the source gate would report every correctly-guarded read;
 *  * (CHK.61)(b)'s checking half — an OPTIONAL member's access type carries
 *    `| undefined` — without which half the population is invisible and with which,
 *    ungated, a true positive is deleted;
 *  * and the four narrowing defects (CHK.64) through (CHK.70) found while pricing it,
 *    each of which was a shipped false positive of its own.
 *
 * **Which reader each pin names is in its own name**, and every fixture is measured
 * against `tools/tsgo-7.0.2/lib/tsc` over the same source — same code, same message,
 * same 1-based position.
 *
 * **FOUR of the twelve are RED on the parent binary `855d0eab`** and are the false
 * negatives this commit closes: the DECLARATION, ASSIGNMENT and RETURN readers, and
 * the optional-member trio. The rest are GREEN on the parent and say so:
 *
 *  * the ARGUMENT reader already reported a nullish union on the parent — it does not
 *    go through [Checker.canUseTypeEngine]'s gate at all. It is here as a CONTROL that
 *    opening the gate does not disturb the one reader that already worked, NOT as
 *    coverage of the gate;
 *  * the UNREACHABLE `return undefined` and the WEAK-TYPE assignment target are
 *    REGRESSION GUARDS for two defects this commit had to fix in order to open the
 *    gate at all, each found by the suite rather than by the dashboard: the return
 *    reader must REFUSE a `never` flow answer (its substitution is suppression-only,
 *    and `never` relates to everything — pristine tsc reports the unreachable row, and
 *    the corpus baseline `functionReturn.ts` is what caught it), and the weak-type
 *    ASSIGNMENT target must see through the `| undefined` an optional member now
 *    carries, or TS2559/TS2560 is lost at `o.weakMember = …`.
 *
 * Six ablation arms, one mistake each, have six DISTINCT red sets — the source gate,
 * the return reader's admission, the assignment reader's admission, the optional
 * member's `| undefined`, the `never` refusal and the weak-target strip.
 */
class AnUndefinedUnionIsNotAssignableToAPrimitiveTest {

    // three lines, so the first line of every body below is line 4
    private val prelude = """
        interface ZzzOpts { pres?: boolean; num?: number }
        declare const zzzOpts: ZzzOpts;
        declare function zzzTake(n: number): void;
    """.trimIndent() + "\n"

    private fun d(body: String) = diagnose(prelude + body.trimIndent())

    // ---- positives, one per reader -------------------------------------------

    @Test
    fun `the DECLARATION reader reports a nullish union at a primitive target`() {
        val rows = d(
            """
            function q1(x: number | undefined): void {
              const c: number = x;
            }
            """,
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'number | undefined' is not assignable to type 'number'.")
        assert(rows[0].line == 5)
    }

    @Test
    fun `the ASSIGNMENT reader reports it`() {
        val rows = d(
            """
            function q2(x: number | undefined): void {
              let v: number = 1;
              v = x;
            }
            """,
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'number | undefined' is not assignable to type 'number'.")
        assert(rows[0].line == 6)
    }

    @Test
    fun `the RETURN reader reports it`() {
        val rows = d(
            """
            function q3(x: number | undefined): number {
              return x;
            }
            """,
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'number | undefined' is not assignable to type 'number'.")
        assert(rows[0].line == 5)
    }

    @Test
    fun `control - the ARGUMENT reader already reported it on the parent`() {
        val rows = d(
            """
            function q4(x: number | undefined): void {
              zzzTake(x);
            }
            """,
        ).filter { it.code == 2345 }
        assert(rows.size == 1)
        assert(
            rows[0].message ==
                "Argument of type 'number | undefined' is not assignable to parameter of type 'number'.",
        )
        assert(rows[0].line == 5)
    }

    @Test
    fun `an OPTIONAL member's access carries the undefined at all three readers`() {
        // (CHK.61)(b)'s checking half. tsgo 7.0.2 reports all three.
        val rows = d(
            """
            const m1: number = zzzOpts.num;
            function q5(): number { return zzzOpts.num; }
            function q6(): void { zzzTake(zzzOpts.num); }
            """,
        ).filter { it.code == 2322 || it.code == 2345 }
        assert(rows.size == 3)
        assert(rows.map { it.line } == listOf(4, 5, 6))
        assert(rows.map { it.code } == listOf(2322, 2322, 2345))
    }

    // ---- the two guards this commit needed -----------------------------------

    @Test
    fun `an UNREACHABLE return of undefined is still reported`() {
        // The return reader's flow answer at an unreachable node is `never`, which
        // relates to everything — and the substitution is suppression-only, so
        // adopting it deletes the row. Pristine tsc reports it; the corpus baseline
        // `functionReturn.ts` is what caught this.
        val rows = d(
            """
            function q7(): string {
              return '';
              return undefined;
            }
            """,
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].line == 6)
    }

    @Test
    fun `the weak-type rule still fires at an OPTIONAL member assignment target`() {
        // An optional member's access type is now `ZzzS9 | undefined`; tsc reaches the
        // weak rule by distributing the relation over the union, and a nullish
        // constituent can never accept a weak source. tsc 7.0.2 reports TS2560 here.
        val d = diagnose(
            """
            interface ZzzS9 { zzzT?: number; zzzE?(): void }
            declare const zzzObj: { zzzHandler?: ZzzS9 };
            zzzObj.zzzHandler = () => ({ zzzT: 1 });
            """.trimIndent(),
        )
        assert(d.map { it.code } == listOf(2560))
    }

    @Test
    fun `a CONDITIONAL right-hand side overwrites the nullish narrow it is guarded by`() {
        // knip's `util/glob-cache.ts statDirMtime` is the shape, and the gate is what
        // makes it visible: a `T | undefined` defaulted INSIDE its own `=== undefined`
        // guard by a ternary. `narrowByAssignmentRhs` had a resolving arm for a bare
        // Identifier and for a PropertyAccess and none for a ConditionalExpression, and
        // no STRUCTURAL test can stand in — the arms are member reads, and a member may
        // be optional. `getTypeOfExpression` answers the ternary exactly (measured: the
        // same `number` tsc 7.0.2 gives, including through a `?.` condition).
        //
        // THE FALSE ARM MUST NOT BE A LITERAL. Written `: 0` this pin is VACUOUS — it
        // passes against a binary with the arm deleted, because a literal arm is already
        // reached elsewhere — and the ablation reading 0 RED is the only thing that says
        // so (round 902). `zzzNaN` is a bare `number` identifier for exactly that reason.
        val d = diagnose(
            """
            declare function zzzGet(k: string): number | undefined;
            declare function zzzStat(d: string): { zzzIsDir(): boolean; zzzMs: number } | undefined;
            declare const zzzNaN: number;
            function q8(dir: string): number {
              let mtime = zzzGet(dir);
              if (mtime === undefined) {
                const stat = zzzStat(dir);
                mtime = stat ? stat.zzzMs : zzzNaN;
              }
              return mtime;
            }
            """.trimIndent(),
        )
        assert(d.none { it.code == 2322 })
    }

    // ---- controls: silent on BOTH binaries -----------------------------------

    @Test
    fun `control - a guard makes every reader silent`() {
        val d = d(
            """
            function c1(x: number | undefined): number {
              if (x === undefined) { return 0; }
              const c: number = x;
              zzzTake(x);
              return x;
            }
            """,
        )
        assert(d.none { it.code == 2322 || it.code == 2345 })
    }

    @Test
    fun `control - a guarded OPTIONAL member read is silent`() {
        val d = d(
            """
            function c2(): number {
              if (zzzOpts.num !== undefined) { return zzzOpts.num; }
              return 0;
            }
            """,
        )
        assert(d.none { it.code == 2322 || it.code == 2345 })
    }

    @Test
    fun `control - a REQUIRED member is unaffected`() {
        val d = d(
            """
            interface ZzzReq { rq: number }
            declare const zzzR: ZzzReq;
            const ok: number = zzzR.rq;
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `control - a nullish TARGET accepts a nullish source`() {
        val d = d(
            """
            const ok2: number | undefined = zzzOpts.num;
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `control - a NON-nullish union at a primitive target is unchanged`() {
        val rows = d(
            """
            function c5(y: number | string): void {
              const c: number = y;
            }
            """,
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | number' is not assignable to type 'number'.")
    }
}
