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
import kotlin.test.Test
import kotlin.test.fail

/**
 * INV.2(b): [FlowGraph.flowAt] — the pilot nodeId-array side table — must be
 * BEHAVIOR-PRESERVING vs the legacy `nodeToFlow[nodeKey(node)]` lookup:
 *
 *  - for every IN-TREE node the array answer equals the map answer BY
 *    CONSTRUCTION (the array is pre-computed from the finished map, so the
 *    Long key's extent-ALIASING — wrapper and same-extent child sharing one
 *    entry — is reproduced, not "fixed");
 *  - any node NOT in the graph's tree (a synthesized node carrying recorded
 *    extents, a foreign file's node) takes the exact legacy map path via the
 *    identity ownership check.
 */
class Inv2FlowLookupTest {

    private fun allNodes(root: SourceFile): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        val push: (Node) -> Unit = { stack.add(it) }
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            out.add(node)
            forEachChild(node, push)
        }
        return out
    }

    @Test
    fun `flowAt agrees with the legacy map lookup for every node of the rich fixture`() {
        val sourceFile = Parser(INV2_RICH_FIXTURE, "rich.ts").parse()
        val graph = FlowGraphBuilder().build(sourceFile)
        var checked = 0
        var recorded = 0
        for (node in allNodes(sourceFile)) {
            val legacy = graph.nodeToFlow[nodeKey(node)]
            val fast = graph.flowAt(node)
            if (fast !== legacy) {
                fail(
                    "flowAt(${node::class.simpleName} at pos ${node.pos}) diverges from the legacy " +
                        "map lookup (legacy ${if (legacy == null) "null" else "non-null"}, " +
                        "fast ${if (fast == null) "null" else "non-null"})"
                )
            }
            checked++
            if (legacy != null) recorded++
        }
        have(checked > 400, "fixture unexpectedly small: $checked nodes")
        have(recorded > 50, "fixture unexpectedly flow-poor: $recorded recorded nodes")
    }

    @Test
    fun `a synthesized node carrying recorded extents falls back to the map answer`() {
        val sourceFile = Parser(INV2_RICH_FIXTURE, "rich.ts").parse()
        val graph = FlowGraphBuilder().build(sourceFile)
        val recordedNode = allNodes(sourceFile).first { graph.nodeToFlow[nodeKey(it)] != null }
        // A fresh node with the SAME extents but nodeId −1 / foreign identity: the
        // ownership check must reject the array path and serve the legacy map hit.
        val ghost = Identifier(text = "ghost", pos = recordedNode.pos, end = recordedNode.end)
        val viaGhost = graph.flowAt(ghost)
        val legacy = graph.nodeToFlow[nodeKey(ghost)]
        have(viaGhost != null, "ghost lookup lost the legacy map hit")
        have(viaGhost === legacy, "ghost lookup must be the exact legacy map answer")
    }

    @Test
    fun `a foreign file's node takes the legacy map path`() {
        val fileA = Parser(INV2_RICH_FIXTURE, "a.ts").parse()
        val graphA = FlowGraphBuilder().build(fileA)
        val fileB = Parser("let solo = 1;", "b.ts").parse()
        // Every B node has a small nodeId — valid as an index into A's arrays — but
        // fails A's identity ownership check, so the answer must be A's map answer.
        for (node in allNodes(fileB)) {
            val fast = graphA.flowAt(node)
            val legacy = graphA.nodeToFlow[nodeKey(node)]
            if (fast !== legacy) {
                fail(
                    "foreign ${node::class.simpleName} (nodeId ${(node as NodeBase).nodeId}) " +
                        "diverges from A's legacy map answer"
                )
            }
        }
    }
}
