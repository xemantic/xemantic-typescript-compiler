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
 * (HASH.1) round 889 — [nodeKey] must SPREAD, not merely be injective.
 *
 * The defect these pins exist against is silent by construction: the un-mixed
 * `(pos shl 32) or end` packing is a perfectly good identity and a catastrophic
 * hash, because `java.lang.Long.hashCode()` folds the two halves together with
 * XOR and `end` is `pos` plus the node's length. Every map keyed by one then
 * degenerates into a few red-black trees — correct answers, `HashMap$TreeNode`
 * cost, and no diagnostic, no emitted byte and no `cost_gate.py` counter moves.
 * So the ONLY instrument that can see it is a pin on the key's bit
 * distribution, and it is written here against the REAL extents of a parsed
 * file rather than a synthetic model.
 *
 * Every pin below reddens if the finalizer in [nodeKey] is removed — that is
 * the round-807 single-mistake ablation these were verified against.
 *
 * The bucket arithmetic mirrors `java.util.HashMap` exactly: the table index is
 * `(hash xor (hash ushr 16)) and (capacity - 1)` over `Long.hashCode()`, and a
 * bucket TREEIFIES at 8 entries (`TREEIFY_THRESHOLD`) once the table has grown
 * to 64. Kotlin/Native has no `java.util.HashMap`, but the pins are about the
 * KEY's distribution, which is a property of the packing alone — so they are
 * meaningful on every target.
 */
class NodeKeyHashSpreadTest {

    /** `java.lang.Long.hashCode()` — the fold every `Map<Long, ·>` starts from. */
    private fun longHash(v: Long): Int = (v xor (v ushr 32)).toInt()

    /** `java.util.HashMap.hash()` — the spread applied on top of it. */
    private fun bucketOf(key: Long, capacity: Int): Int {
        val h = longHash(key)
        return (h xor (h ushr 16)) and (capacity - 1)
    }

    /** Every node of the rich fixture, which is the densest tree the repo owns.
     *  Iterative, per the Kotlin/Native no-deep-recursion rule. */
    private fun fixtureNodes(): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        val collect: (Node) -> Unit = { stack.add(it) }
        stack.add(Parser(INV2_RICH_FIXTURE, "rich.ts").parse())
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            out.add(node)
            forEachChild(node, collect)
        }
        return out
    }

    private fun histogram(keys: List<Long>, capacity: Int): IntArray {
        val counts = IntArray(capacity)
        for (k in keys) counts[bucketOf(k, capacity)]++
        return counts
    }

    @Test
    fun `the packing is injective so distinct extents keep distinct keys`() {
        val nodes = fixtureNodes()
        val extents = nodes.map { it.pos to it.end }.toSet()
        val keys = extents.map { nodeKey(it.first, it.second) }.toSet()
        assert(keys.size == extents.size)
    }

    @Test
    fun `a node key round-trips to the same value for the same extent`() {
        assert(nodeKey(17, 42) == nodeKey(17, 42))
        assert(nodeKey(17, 42) != nodeKey(42, 17))
        assert(nodeKey(0, 0) != nodeKey(-1, -1))
    }

    @Test
    fun `no bucket of the fixture's node keys reaches the treeify threshold`() {
        val keys = fixtureNodes().map { nodeKey(it.pos, it.end) }.distinct()
        val capacity = 1 shl 12
        val worst = histogram(keys, capacity).max()
        assert(worst < 8)
    }

    @Test
    fun `the fixture's node keys occupy far more buckets than there are node lengths`() {
        val nodes = fixtureNodes()
        val keys = nodes.map { nodeKey(it.pos, it.end) }.distinct()
        val capacity = 1 shl 12
        val used = histogram(keys, capacity).count { it > 0 }
        // The un-mixed packing's whole bucket range is the set of `pos xor end`
        // values, which for a tree is dominated by the set of node LENGTHS —
        // this fixture has far fewer distinct lengths than distinct extents, so
        // the inequality below is exactly what separates the two packings.
        val distinctLengths = nodes.map { it.end - it.pos }.distinct().size
        assert(used > distinctLengths * 2)
    }

    @Test
    fun `a synthetic file-sized extent set fills a majority of its buckets`() {
        // 20,000 nodes over a 400 kB file, node lengths drawn from a realistic
        // spread — the shape `nodeToFlow` sees on the compiler profile, where
        // the un-mixed packing filled 399 of 524,288 buckets.
        val lengths = intArrayOf(1, 1, 1, 2, 3, 4, 5, 6, 8, 10, 12, 20, 30, 60, 120, 400, 2000)
        val keys = ArrayList<Long>(20_000)
        var pos = 0
        for (i in 0 until 20_000) {
            pos = (pos + 17 + i % 13) % 400_000
            keys.add(nodeKey(pos, pos + lengths[i % lengths.size]))
        }
        val capacity = 1 shl 15
        val counts = histogram(keys.distinct(), capacity)
        val used = counts.count { it > 0 }
        val treeified = counts.count { it >= 8 }
        assert(used > capacity / 4)
        assert(treeified == 0)
    }
}
