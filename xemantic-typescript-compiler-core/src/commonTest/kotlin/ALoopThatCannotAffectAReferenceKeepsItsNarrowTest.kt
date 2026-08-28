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
 * (CHK.69): a `FlowLoopLabel` whose body cannot ASSIGN the reference is answered
 * by following its ENTRY antecedent, and a `for-in` / `for-of` leaves through its
 * loop label rather than around it.
 *
 * **The reader every positive here names is the DECLARATION one with a PRIMITIVE
 * target** (`const p: string = h.req`), reached because a NON-nullish union source
 * passes [Checker.canUseTypeEngine]'s primitive gate. The subject is a PROPERTY
 * PATH on purpose: an IDENTIFIER subject is answered from
 * [Checker.currentLocalTypes] by the M1.9 if-arm machinery, which is statement-
 * ordered and loop-blind in BOTH directions, so an identifier fixture is vacuous
 * for the positives AND for the controls (measured — `x = 1` inside a loop does
 * not invalidate a pre-loop narrow on either binary, which is a separate shipped
 * false negative).
 *
 * **Why this is sound.** A loop label's value is the least fixpoint
 * `L = E union (union of narrow_i(L))` over its entry state `E`. When no back edge
 * assigns the reference every back edge is a pure NARROWING of `L`, so iterating
 * from `E` never grows past `E` and the fixpoint IS `E`. [Checker.loopBodyMayAffectName]
 * decides that by pure graph reachability — it resolves no type and asks the binder
 * nothing — and answers TRUE (today's conservative `declaredType`) on anything it
 * cannot rule out. That is the whole of (CHK.66)(b)'s prize at none of its price:
 * unioning the back edges makes every subtree entry-context-dependent and therefore
 * unmemoizable, which measured 15.1 M globals lookups against 0.77 M and 3.4x wall.
 *
 * Every positive below is RED on the parent binary and every control is GREEN on
 * both, which is what separates this from a pin that merely restates the compiler.
 */
class ALoopThatCannotAffectAReferenceKeepsItsNarrowTest {

    private val prelude = """
        declare function cond(): boolean;
        declare function g(): void;
        interface H { req: string | number; alt: string | number; }
        declare const xs: number[];
        declare const obj: { a: number };
    """.trimIndent() + "\n"

    private fun d(body: String) = diagnose(prelude + body.trimIndent())

    // ---- positives: the narrow SURVIVES a loop that cannot touch it -----------

    @Test
    fun `a narrow established before a while survives INSIDE it`() {
        val d = d(
            """
            function q1(h: H): void {
              if (typeof h.req === "string") {
                while (cond()) { const p: string = h.req; g(); }
              }
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a narrow established before a while survives AFTER it`() {
        val d = d(
            """
            function q2(h: H): void {
              if (typeof h.req === "string") {
                while (cond()) { g(); }
                const p: string = h.req;
                g();
              }
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a for-of loop that does not touch the path keeps the narrow`() {
        val d = d(
            """
            function q3(h: H): void {
              if (typeof h.req === "string") {
                for (const n of xs) { const p: string = h.req; g(); }
              }
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `assigning a DIFFERENT member of the same object keeps the narrow`() {
        val d = d(
            """
            function q6(h: H): void {
              if (typeof h.req === "string") {
                while (cond()) { h.alt = 1; }
                const p: string = h.req;
                g();
              }
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a do-while keeps the narrow`() {
        val d = d(
            """
            function q8(h: H): void {
              if (typeof h.req === "string") {
                do { const p: string = h.req; g(); } while (cond());
              }
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    // ---- positives: the for-in / for-of EXIT now carries the loop label -------

    @Test
    fun `a for-of that assigns the path DOES invalidate the narrow after it`() {
        // Before (CHK.69) the `for-of` post-loop label took the PRE-loop flow as its
        // antecedent, so the body was unreachable BACKWARD from here and this read
        // kept a narrow the loop had destroyed — a shipped FALSE NEGATIVE that no
        // `while` / `do` / `for(;;)` shape has, because those exit through their
        // condition, which carries the label.
        val d = d(
            """
            function r1(h: H): void {
              if (typeof h.req === "string") {
                for (const n of xs) { h.req = 1; }
                const p: string = h.req;
              }
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `a for-in that assigns the path DOES invalidate the narrow after it`() {
        val d = d(
            """
            function r7(h: H): void {
              if (typeof h.req === "string") {
                for (const k in obj) { h.req = 1; g(); }
                const p: string = h.req;
              }
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `a for-of nested one if deep still invalidates the narrow`() {
        val d = d(
            """
            function r6(h: H): void {
              if (typeof h.req === "string") {
                for (const n of xs) { if (cond()) { h.req = 1; } }
                const p: string = h.req;
              }
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    // ---- controls: green on BOTH binaries ------------------------------------

    @Test
    fun `control - a loop that ASSIGNS the path destroys the narrow`() {
        val d = d(
            """
            function q4(h: H): void {
              if (typeof h.req === "string") {
                while (cond()) { h.req = 1; }
                const p: string = h.req;
                g();
              }
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `control - an INNER loop that assigns the path destroys the narrow`() {
        // This is the arm that separates the shipped gate from an UNCONDITIONAL
        // entry-follow: the inner `for-of`'s body is only reachable from the outer
        // loop's back edge once the binder routes its exit through its own label.
        val d = d(
            """
            function q5(h: H): void {
              if (typeof h.req === "string") {
                while (cond()) { for (const n of xs) { h.req = 1; } }
                const p: string = h.req;
                g();
              }
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `control - no narrow before the loop leaves the declared union`() {
        val d = d(
            """
            function q7(h: H): void {
              while (cond()) { g(); }
              const p: string = h.req;
              g();
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }
}
