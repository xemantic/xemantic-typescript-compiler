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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CALL.3) round 736 — the flow-walk memo's HEIGHT disjunct.
 *
 * `NarrowFlowMemo` used to answer a probe only at a same-or-shallower entry
 * depth, so a flow node reached again by a LONGER path recomputed its whole
 * antecedent subtree. Round 736 measured that as 631,585 of a compiler-profile
 * compile's 4.76 M flow-node arrivals, 426,753 of them at `FlowCondition`
 * nodes, and relaxed the condition with a decidable guard: a stored entry also
 * carries the maximum depth its own subtree reached, so a deeper probe is
 * answered exactly when a fresh computation from there provably cannot reach
 * `NARROW_MAX_DEPTH`.
 *
 * The shapes below make a node reachable at two DIFFERENT depths — nested
 * branch joins of unequal nesting converging on one guard — which is what
 * turns the depth condition from theory into a measured recompute. Each pins
 * the narrowing that must still fire, and each is paired with a control that
 * must still ERROR, so a memo that silently over-serves cannot pass by
 * suppressing everything.
 */
class NarrowMemoDepthTest {

    @Test
    fun `a guard narrowing survives joins of unequal nesting depth`() {
        val d = diagnose(
            """
            declare const a: boolean
            declare const b: boolean
            declare const c: boolean
            function f(x: string | number, out: string[]) {
                if (typeof x !== "string") return
                if (a) {
                    if (b) {
                        if (c) { out.push("1") } else { out.push("2") }
                    } else {
                        out.push("3")
                    }
                } else {
                    out.push("4")
                }
                out.push(x.toUpperCase())
            }
            """,
        )
        d should { have(none { it.code == 2339 }) }
        d should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `negative control - an unguarded union still fails the same call after the joins`() {
        // The (CALL.3) path itself: checkArgumentsAgainstSignature narrows a
        // union-typed argument at its flow position. With NO guard the argument
        // stays `string | number` and TS2345 must fire — a memo that over-served
        // a narrower stored result would silently suppress it.
        val d = diagnose(
            """
            declare const a: boolean
            declare const b: boolean
            declare const c: boolean
            declare function wantsString(s: string): void
            function f(x: string | number, out: string[]) {
                if (a) {
                    if (b) {
                        if (c) { out.push("1") } else { out.push("2") }
                    } else {
                        out.push("3")
                    }
                } else {
                    out.push("4")
                }
                wantsString(x)
            }
            """,
        )
        d should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `a guarded union passes the same call the unguarded one fails`() {
        val d = diagnose(
            """
            declare const a: boolean
            declare const b: boolean
            declare const c: boolean
            declare function wantsString(s: string): void
            function f(x: string | number, out: string[]) {
                if (typeof x !== "string") return
                if (a) {
                    if (b) {
                        if (c) { out.push("1") } else { out.push("2") }
                    } else {
                        out.push("3")
                    }
                } else {
                    out.push("4")
                }
                wantsString(x)
            }
            """,
        )
        d should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `a narrowing established inside one arm does not leak past the join`() {
        val d = diagnose(
            """
            declare const a: boolean
            declare const b: boolean
            function f(x: string | number) {
                if (a) {
                    if (b) {
                        if (typeof x === "string") { x.toUpperCase() }
                    }
                }
                x.toUpperCase()
            }
            """,
        )
        // after the join `x` is back to `string | number` — toUpperCase must fail
        d should { have(any { it.code == 2339 }) }
    }

    @Test
    fun `an assignment reaching a join by two paths of different depth still narrows`() {
        val d = diagnose(
            """
            declare const a: boolean
            declare const b: boolean
            function f(out: string[]) {
                let x: string | undefined
                if (a) {
                    if (b) { x = "one" } else { x = "two" }
                } else {
                    x = "three"
                }
                out.push(x.toUpperCase())
            }
            """,
        )
        d should { have(none { it.code == 2532 }) }
        d should { have(none { it.code == 18048 }) }
        d should { have(none { it.code == 2339 }) }
    }

    @Test
    fun `deeply nested joins over one guard stay consistent as nesting grows`() {
        // The same guard reached at monotonically increasing depths: if the
        // height guard were wrong in either direction, exactly one nesting
        // level would start differing from the others.
        for (levels in 1..12) {
            val open = (1..levels).joinToString("\n") { "if (a) {" }
            val close = (1..levels).joinToString("\n") { "}" }
            val d = diagnose(
                """
                declare const a: boolean
                function f(x: string | number, out: string[]) {
                    if (typeof x !== "string") return
                    $open
                    out.push("x")
                    $close
                    out.push(x.toUpperCase())
                }
                """,
            )
            val bad = d.count { it.code == 2339 || it.code == 2345 }
            assert(bad == 0)
        }
    }

    @Test
    fun `a walk that must widen at a loop join still widens`() {
        // FlowLoopLabel returns the DECLARED type in the non-loop-entry walker;
        // a memo that over-served would report the pre-loop narrowing instead
        // and suppress this TS2345.
        val d = diagnose(
            """
            declare const a: boolean
            declare function wantsString(s: string): void
            function f(x: string | number, n: number) {
                if (typeof x === "string") {
                    for (let i = 0; i < n; i++) {
                        if (a) { x = 1 }
                        wantsString(x)
                    }
                }
            }
            """,
        )
        d should { have(any { it.code == 2345 }) }
    }
}
