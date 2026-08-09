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
 *    read and one unread function must report both.
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
            return x.length;
        }

        function untouched(a: number, b: number): number {
            let total = 0;
            for (let i = 0; i < a; i++) {
                if (i % 2 === 0) {
                    total = total + b;
                } else {
                    total = total - b;
                }
            }
            return total;
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
    fun `a mint lands in the inventory of the file that minted it`() {
        val sizes = underCensus {
            FlowGraphBuilder().build(Parser(fixture, "a.ts").parse())
            val afterA = FrontEnd.flowNodesBuilt
            FlowGraphBuilder().build(Parser("function b() { return 1; }", "b.ts").parse())
            val afterB = FrontEnd.flowNodesBuilt
            listOf(
                FlowCensus.files[0].file, FlowCensus.files[1].file,
                FlowCensus.files[0].nodes.size.toString(),
                FlowCensus.files[1].nodes.size.toString(),
                (afterA - 1).toString(), (afterB - afterA - 1).toString(),
            )
        }
        assert(sizes[0] == "a.ts")
        assert(sizes[1] == "b.ts")
        // Each file's inventory holds exactly its OWN mints: an inventory opened
        // after the first factory call would push a.ts's start node into the
        // previous file's list and shift both counts.
        assert(sizes[2] == sizes[4])
        assert(sizes[3] == sizes[5])
    }

    @Test
    fun `the flow chain of a narrowed reference is reported as read`() {
        val q = underCensus {
            diagnose(fixture, directives = "")
            FlowCensus.summary()
        }
        // The positive control: something must be read, or a census that reports
        // "nothing is ever consumed" would pass vacuously and the round's whole
        // finding would be an instrument artefact.
        assert(q.read > 0)
        assert(q.readByKind[FlowCensus.K_CONDITION] > 0)
        assert(q.minted > q.read)
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
