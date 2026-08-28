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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (WARM.12) round 865 — the pins for the flow-node produced-versus-consumed
 * census.
 *
 * The census answers one question: of the flow nodes `FlowGraphBuilder` mints,
 * how many does any consumer in the checker ever look at? Its whole value is
 * that the answer decides a DESIGN question by measurement rather than by
 * argument — so every way it could be quietly wrong has to redden something.
 *
 *  * **The denominator** — `FrontEnd.flowNodesBuilt` counts `nextId`, the
 *    census counts registrations, and the two must differ by exactly one
 *    placeholder `FlowStart` per graph. A mint site added later without a
 *    registration would otherwise shrink the population silently, which is
 *    round 829's law in its sharpest form: print the denominator and make the
 *    parts sum to it.
 *  * **The per-file attribution** — an inventory opened after the first mint
 *    puts one file's nodes in another file's list, which no aggregate can see.
 *  * **The consumed side** — a dropped consumer hook reports read nodes as
 *    never-read, i.e. it manufactures exactly the finding the round was looking
 *    for. The positive control is a reference the checker demonstrably narrows.
 *  * **The container axis** — the number the decision rests on is the share of
 *    the minting WALK inside containers nothing reads, so a fixture with one
 *    read and one unread function must report both. Since (CHK.63) opened
 *    `canUseTypeEngine`'s nullish-union-versus-primitive gate, an ENTIRELY UNREAD
 *    container is much harder to write: the declaration, assignment and return
 *    readers all consult the flow walk for a primitive target now, so `untouched`
 *    below may hold no assignment to a local, no `return` of a reference and no
 *    initialisation from one — only branches whose operands are `number`, which
 *    `arithOperandType` refuses to flow-consult because its base is not a union.
 *  * **Non-vacuity** — with the flag off nothing may be recorded at all, or the
 *    "probe-gated, behaviour-free" claim is untested.
 */
class FlowNodeCensusTest {

    private fun <T> underCensus(block: () -> T): T {
        val saved = FlowCensus.on
        val savedFront = FrontEnd.mode
        try {
            FlowCensus.reset()
            FlowCensus.on = true
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            return block()
        } finally {
            FrontEnd.mode = savedFront
            FrontEnd.reset()
            FlowCensus.on = saved
            FlowCensus.reset()
        }
    }

    /**
     * One narrowed reference (its chain is walked) plus one function nothing ever
     * asks about (its chain is not).
     *
     * **The guard MUST be an early return.** Since round 785 an `if (guard) { … }`
     * writes its narrow into `currentLocalTypes` for the THEN branch, so the read
     * inside the block is answered without any flow walk at all and the census
     * reports `read 0` on a working instrument — measured, in exactly this
     * fixture, before the shape was corrected. Same law CLAUDE.md already states
     * for enum and argument-position fixtures (round 796), now with a third
     * consumer.
     */
    private val fixture = """
        // @strict: true
        function narrowed(x: string | undefined): number {
            if (x === undefined) {
                return 0;
            }
            let n = 0;
            while (n < x.length) {
                n = n + 1;
            }
            return x.length;
        }

        declare function sink(v: number): void;

        function untouched(a: number, b: number): void {
            if (a > b) {
                sink(1);
            } else {
                sink(2);
            }
            if (a < b) {
                sink(3);
            } else if (b < a) {
                sink(4);
            } else {
                sink(5);
            }
        }
    """.trimIndent()

    @Test
    fun `every mint is registered - the census total is the builder's own id count`() {
        val minted = underCensus {
            FlowGraphBuilder().build(Parser(fixture, "c.ts").parse())
            FlowCensus.summary().minted to FrontEnd.flowNodesBuilt
        }
        // `nextId` also numbers the placeholder FlowStart the builder's field
        // initializer mints and `build` immediately overwrites — one per graph.
        assert(minted.first == minted.second - 1)
        assert(minted.first > 20)
    }

    @Test
    fun `every kind the builder mints is registered`() {
        val q = underCensus {
            FlowGraphBuilder().build(Parser(fixture, "c.ts").parse())
            FlowCensus.summary()
        }
        // The denominator pin above sees a LOST mint site only through the total,
        // which the per-file pin below also moves; this one names the kind, so a
        // factory that stops registering is attributable rather than merely
        // detectable. FlowUnreachable is the kind the round's own headline is
        // about (minted 17,161 and read 0 on the compiler profile) and it is the
        // one a fixture must deliberately contain — every `return` mints one.
        assert(q.mintedByKind[FlowCensus.K_START] > 0)
        assert(q.mintedByKind[FlowCensus.K_UNREACHABLE] > 0)
        assert(q.mintedByKind[FlowCensus.K_BRANCH] > 0)
        assert(q.mintedByKind[FlowCensus.K_LOOP] > 0)
        assert(q.mintedByKind[FlowCensus.K_ASSIGN] > 0)
        assert(q.mintedByKind[FlowCensus.K_CONDITION] > 0)
    }

    @Test
    fun `no file's inventory holds a node another file minted`() {
        val foreign = underCensus {
            FlowGraphBuilder().build(Parser(fixture, "a.ts").parse())
            val b = Parser("function b() { return 1; }", "b.ts").parse()
            FlowGraphBuilder().build(b)
            // Identity, not a count: an inventory opened one statement too late
            // leaves b.ts's own FlowStart in a.ts's list while both totals stay
            // plausible, and only the node's own container can see that.
            Triple(
                FlowCensus.files[0].file,
                FlowCensus.files[0].nodes.count { it is FlowStart && it.container === b },
                FlowCensus.files[1].nodes.count { it is FlowStart && it.container === b },
            )
        }
        assert(foreign.first == "a.ts")
        assert(foreign.second == 0)
        assert(foreign.third == 1)
    }

    @Test
    fun `the flow chain of a narrowed reference is reported as read`() {
        // `underCensus` resets in its `finally`, so anything the assertions read
        // must be captured INSIDE the block — a counter read outside is 0 on a
        // working instrument, which is the same reading a dead one gives.
        val captured = underCensus {
            diagnose(fixture, directives = "")
            FlowCensus.summary() to FlowCensus.touchCalls[FlowCensus.CH_NARROW]
        }
        val q = captured.first
        // The positive control: something must be read, or a census that reports
        // "nothing is ever consumed" would pass vacuously and the round's whole
        // finding would be an instrument artefact.
        assert(q.read > 0)
        assert(q.readByKind[FlowCensus.K_CONDITION] > 0)
        assert(q.minted > q.read)
        // …and the main narrowing walk's own channel must be live. Without this
        // the pins above are satisfied by `flowAt`'s hand-out alone — which is a
        // DIFFERENT channel observing the same entry node — so dropping the walk
        // hook, the one that reaches the whole antecedent CHAIN (991,970 touches
        // against 176,767 hand-outs on the compiler profile), leaves every
        // assertion green. Measured: it did, in this round's first ablation
        // batch (round 807's redundant-signal mechanism, seen from the pin side).
        assert(captured.second > 0)
    }

    @Test
    fun `a function nothing narrows is reported as an entirely unread container`() {
        val q = underCensus {
            diagnose(fixture, directives = "")
            FlowCensus.summary()
        }
        // Both directions in one fixture: at least one container is entirely
        // unread (so the axis can see the population the design question is
        // about) and at least one is not (so it is not reporting everything).
        assert(q.containersUnread > 0)
        assert(q.containers > q.containersUnread)
        // And the WALK share is what the decision rests on — a container axis
        // that carried no visit counts would answer 0% and read as "there is
        // nothing here", which is a different claim from "there is nothing
        // worth skipping" (round 758).
        assert(q.walkVisitsUnread > 0)
        assert(q.walkVisits > q.walkVisitsUnread)
    }

    @Test
    fun `negative control - with the flag off the census records nothing`() {
        val saved = FlowCensus.on
        try {
            FlowCensus.reset()
            FlowCensus.on = false
            diagnose(fixture, directives = "")
            val q = FlowCensus.summary()
            assert(q.minted == 0L)
            assert(q.files == 0)
            var touches = 0L
            for (c in 0 until FlowCensus.NCH) touches += FlowCensus.touchCalls[c]
            assert(touches == 0L)
        } finally {
            FlowCensus.on = saved
            FlowCensus.reset()
        }
    }
}
