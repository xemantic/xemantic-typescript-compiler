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
 * (CHK.70)(c): assigning a LITERAL to a reference whose flow type is nullish-only
 * OVERWRITES it — the post-state is reduced from the DECLARATION, never from the
 * pre-assignment narrowing.
 *
 * **The reader every pin here names is the RETURN one with a UNION target** (round
 * 784's gate admits `Type.Union` targets to the flow walk on the shipped binary, so
 * none of these needs (CHK.63)'s primitive-target gate to discriminate).
 *
 * (CHK.63)(a) established this rule and routed the two arms of
 * [Checker.narrowByAssignmentRhs] that RESOLVE their right-hand side's type through
 * [Checker.assignmentReduceBase]. The LITERAL arm — the one that reads the right-hand
 * side SYNTACTICALLY, via [Checker.getLiteralRhsTypeForAssignment] — was left
 * filtering the antecedent, and [Checker.narrowUnionByRhsAssignment] answers a
 * non-union receiver unchanged. So after
 *
 *     let r: string | undefined = undefined;
 *     r = "";
 *
 * the `""` could not restore a member the antecedent had already lost and `r` stayed
 * `undefined` — the same defect (CHK.63)(a) fixed one arm over, in the spelling that
 * tsc's own `harness/tsserverLogger.ts replaceAll` is written in (a nullish guard
 * whose ELSE branch falls through to `result = ""`).
 *
 * The bound is (CHK.63)(a)'s and is unchanged: only a NULLISH-ONLY antecedent reduces
 * from the declaration. With a live non-nullish narrowing the reduction would widen it
 * back to the declaration, which is a false positive in the other direction — see
 * `c3` below, and [Checker.assignmentReduceBase]'s own KDoc, which records that bound
 * as reasoned rather than measured.
 *
 * Ground truth for every fixture is `tools/tsgo-7.0.2/lib/tsc` over the same source:
 * silent on all four positives, TS2322 on `c1` and `c2`, silent on `c3`.
 * Every positive is RED on the parent `191927d4` and every control unchanged there.
 */
class ALiteralAssignmentOverwritesANullishNarrowTest {

    private val prelude = """
        declare function maybeS(): string | undefined;
        declare function cond(): boolean;
        declare const s: string;
    """.trimIndent() + "\n"

    private fun d(body: String) = diagnose(prelude + body.trimIndent())

    @Test
    fun `a string literal assigned over an undefined narrow reduces from the declaration`() {
        val d = d(
            """
            function q1(): string | boolean {
              let r: string | undefined = undefined;
              r = "";
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `the guard-else-then-assign spelling tsc's own replaceAll is written in`() {
        val d = d(
            """
            function q2(): string | boolean {
              let r: string | undefined = maybeS();
              if (r !== undefined) { return r; }
              r = "";
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a numeric literal does the same`() {
        val d = d(
            """
            function q3(): number | boolean {
              let r: number | undefined = undefined;
              r = 0;
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `the guard-else-then-assign followed by a compound-assigning loop`() {
        // Both halves of (CHK.70) on one reference: (c) makes the loop's ENTRY state
        // `string`, and (a) keeps it there across a back edge that only `+=`s. Either
        // one alone leaves this reported — which is why the gate needed both.
        val d = d(
            """
            function q4(): string | boolean {
              let r: string | undefined = maybeS();
              if (r !== undefined) { return r; }
              r = "";
              while (cond()) { r += s; }
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    // ---- controls ------------------------------------------------------------

    @Test
    fun `control - no assignment at all still reports`() {
        val d = d(
            """
            function c1(): string | boolean {
              let r: string | undefined = undefined;
              return r;
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `control - a literal that does not remove the nullish member still reports`() {
        val d = d(
            """
            function c2(): string | boolean {
              let r: string | undefined = maybeS();
              if (r !== undefined) { return r; }
              r = undefined;
              return r;
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `control - a NON-nullish antecedent keeps the pass-through and stays silent`() {
        val d = d(
            """
            function c3(v: string | number): string | boolean {
              if (typeof v === "string") {
                v = "a";
                return v;
              }
              return "";
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }
}
