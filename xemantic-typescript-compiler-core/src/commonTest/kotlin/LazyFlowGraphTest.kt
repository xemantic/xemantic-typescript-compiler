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
 * (INC.9) THE FLOW GRAPH IS BUILT ON FIRST ASK, AND NOTHING ELSE MOVES.
 *
 * `FlowGraphBuilder` is **126 ms of a 523 ms incremental floor** on tsc's own 78
 * sources — the largest single mechanism a `recheckOnly` query pays for, and one
 * whose product a partition then reads for exactly ONE file. `BinderResult` now
 * defers it to the first ask.
 *
 * The whole risk lives in one direction and it is silent. Round 865: a missing side
 * table degrades to a correct fallback, but a missing FLOW NODE makes `flowAt`
 * answer null, nothing narrows, and the compiler emits a FALSE POSITIVE — so this
 * had to be defer-and-build, never omit. Both halves are pinned here: that the graph
 * is NOT built until asked (otherwise the change is inert and the floor cannot move),
 * and that a narrowing which can only come from a FLOW WALK still happens (otherwise
 * the change is a false-positive factory).
 *
 * The narrowing fixture is an EARLY-RETURN guard with an ASSIGNMENT probe, and both
 * halves of that shape were forced by measurement rather than chosen. A block-shaped
 * guard narrows through `currentLocalTypes` with no flow read at all (round 785), so
 * it pins nothing — it is kept below as the control that says so. And a property READ
 * on an un-narrowed `string | number` emits NOTHING here, so the first draft of this
 * class, written with `x.length`, was vacuous in both directions; its negative control
 * is what caught that, which is the only reason a vacuous pin ever gets caught.
 */
class LazyFlowGraphTest {

    private val guarded = """
        export function f(x: string | number): void {
            if (typeof x === "number") return;
            const s: string = x;
        }
    """

    private fun bind(source: String, fileName: String = "t.ts"): BinderResult =
        Binder(CompilerOptions()).bind(Parser(source.trimIndent(), fileName).parse())

    @Test
    fun `binding a file does not build its flow graph`() {
        val result = bind(guarded)
        assert(!result.flowGraphBuilt)
    }

    @Test
    fun `asking for the flow graph builds it, and asking again returns the same instance`() {
        val result = bind(guarded)
        val first = result.flowGraph
        assert(result.flowGraphBuilt)
        val second = result.flowGraph
        assert(first === second)
    }

    /**
     * The graph a deferred build produces is the graph this file describes. A graph
     * that came out EMPTY, or one built from another file, would satisfy the identity
     * assertions above and silently delete narrowing.
     */
    @Test
    fun `the deferred graph belongs to its own file`() {
        val result = bind(guarded, "own.ts")
        val graph = result.flowGraph
        assert(graph.sourceFile === result.sourceFile)
        assert(graph.sourceFile.fileName == "own.ts")
    }

    /**
     * The behavioural half: a narrowing that ONLY a flow walk can perform still
     * happens.
     */
    @Test
    fun `a narrowing that needs the flow walk still happens`() {
        diagnose(guarded) should {
            have(none { it.code == 2322 })
        }
    }

    /** Negative control: the same assignment with no guard DOES report TS2322. */
    @Test
    fun `negative control - the same assignment without the guard reports TS2322`() {
        diagnose(
            """
            export function f(x: string | number): void {
                const s: string = x;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    /**
     * The second control, and the one that says the fixture above is about the FLOW
     * GRAPH rather than about narrowing in general: the block-shaped guard is silent
     * too, and it is silent for a DIFFERENT reason — the condition pass records its
     * THEN branch into `currentLocalTypes`, with no flow read at all. An ablation that
     * empties the graph therefore reddens the early-return fixture and leaves this one
     * green, and that asymmetry is what makes the pair a pin on the deferred graph
     * rather than on the checker merely still working.
     */
    @Test
    fun `control - the block-shaped guard narrows without any flow read`() {
        diagnose(
            """
            export function f(x: string | number): void {
                if (typeof x === "string") { const s: string = x; }
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
