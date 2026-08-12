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
 * (WARM.23) round 896 — the pins for `FlowGraph.nodeToFlow`'s move off
 * `mutableMapOf<Long, FlowNode>` onto [LongKeyMap].
 *
 * The swap introduces EXACTLY ONE new failure mode, and it is not the container:
 * [LongKeyMap] reserves `0L` as its empty-slot sentinel and refuses it at [put]
 * loudly, while `nodeKey(0, 0)` **is** `0L`. So the move needs a key whose written
 * range excludes the sentinel and which is still a bijection, and that is
 * [flowKey]. These pins are that key's contract:
 *
 *  * the sentinel is unreachable from any position `recordFlow` accepts;
 *  * the key is injective, because a collision would merge two nodes' flows —
 *    a WRONG narrowing answer with no crash and no diff anywhere near it;
 *  * `(-1, -1)` — the synthetic-node case — maps ONTO the sentinel, where a
 *    [LongKeyMap] read answers `null`, which is what the LinkedHashMap answered
 *    for a node nothing recorded.
 *
 * The order-independence obligation CLAUDE.md's HashMap-vs-LinkedHashMap entry
 * demands is not pinned here because it is no longer pinnable: [LongKeyMap] has
 * no iterator, no `keys` and no `entries`, so iterating it is a compile error
 * rather than an assertion. That is the whole reason to prefer it over a plain
 * `HashMap` for a container whose audit said "never iterated".
 */
class FlowMapKeyTest {

    /** Positions a real parse produces, plus the two degenerate ones. */
    private val coords = listOf(-1, 0, 1, 2, 7, 63, 64, 255, 4096, 65535, 1_500_000)

    @Test
    fun `the packing nodeKey uses lands on the LongKeyMap sentinel at the origin`() {
        // The reason [flowKey] exists at all. If this ever stops being true the
        // shift is dead weight — and if it stays true and the shift is removed,
        // a zero-width node at offset 0 makes LongKeyMap.put throw.
        assert(nodeKey(0, 0) == 0L)
    }

    @Test
    fun `flowKey never answers the sentinel for a position recordFlow accepts`() {
        // recordFlow refuses `pos < 0`; everything else can be written.
        var written = 0
        for (pos in coords) {
            if (pos < 0) continue
            for (end in coords) {
                if (end < pos) continue
                assert(flowKey(pos, end) != 0L)
                written++
            }
        }
        assert(written > 50)
    }

    @Test
    fun `flowKey maps the synthetic position onto the sentinel and a LongKeyMap reads null there`() {
        assert(flowKey(-1, -1) == 0L)
        val map = LongKeyMap<String>(16)
        map.put(flowKey(0, 0), "origin")
        assert(map.get(flowKey(-1, -1)) == null)
    }

    @Test
    fun `a zero-width node at the origin round-trips through a LongKeyMap`() {
        // This is the case that would have thrown on `nodeKey`: an error-recovery
        // missing node at the start of a file.
        val map = LongKeyMap<String>(16)
        map.put(flowKey(0, 0), "recorded")
        assert(map.get(flowKey(0, 0)) == "recorded")
    }

    @Test
    fun `flowKey is injective over the coordinate grid`() {
        val seen = HashMap<Long, Pair<Int, Int>>()
        var pairs = 0
        for (pos in coords) {
            for (end in coords) {
                val key = flowKey(pos, end)
                val prev = seen.put(key, pos to end)
                assert(prev == null)
                pairs++
            }
        }
        assert(pairs > 100)
    }

    @Test
    fun `flowKey is injective over a dense window of adjacent extents`() {
        // The population a real file produces: many nodes sharing a `pos` and
        // differing only in length, which is exactly the shape round 889's
        // finalizer exists for.
        val seen = HashSet<Long>()
        var pairs = 0
        for (pos in 0 until 200) {
            for (len in 0 until 40) {
                assert(seen.add(flowKey(pos, pos + len)))
                pairs++
            }
        }
        assert(pairs == 8000)
    }

    @Test
    fun `the flow graph of a fixture whose first node starts at the origin answers every recorded node`() {
        // No leading trivia: the first statement's `pos` is 0, so this drives the
        // low corner of the key space through a real build.
        val source = "let a = 1;\nif (a) { a = 2; } else { a = 3; }\nfunction f() { return a; }\n"
        val sourceFile = Parser(source, "origin.ts").parse()
        val graph = FlowGraphBuilder().build(sourceFile)
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        var answered = 0
        var visited = 0
        var atOrigin = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            visited++
            if (node.pos == 0) atOrigin++
            // Both paths must agree: the nodeId array and the map fallback.
            val viaArray = graph.flowAt(node)
            val viaMap = graph.nodeToFlow.get(flowKey(node))
            assert(viaArray === viaMap)
            if (viaArray != null) answered++
            forEachChild(node) { stack.add(it) }
        }
        assert(visited > 20)
        // Non-vacuity: the low corner of the key space is really exercised, and
        // the map really answered somewhere (a graph that recorded nothing would
        // satisfy the agreement check trivially).
        assert(atOrigin > 0)
        assert(answered > 0)
    }
}
