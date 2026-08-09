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
import kotlin.test.fail

/**
 * (WARM.11) round 864 — the INV.2(b) side table is filled from the nodes
 * `recordFlow` actually wrote instead of by a second whole-tree walk, and the
 * two fills must answer identically for **every** node.
 *
 * ## What each test is for
 *
 * The differential ([flowAt agrees…]) is the general net. On its own it is weak
 * in exactly the way round 863's M6 arm exposed: a fill that answers the same
 * `null` everywhere agrees with the oracle on every node the oracle also
 * answers `null` for. So two further tests carry the attribution.
 *
 *  * **Non-vacuity** — the two arms must VISIT different populations
 *    (876,324 nodes against 262,404 on the compiler profile). Without this an
 *    inert `FlowIndex.legacy` makes the differential compare a binary against
 *    itself and pass forever, which is round 807's blind-pin mechanism.
 *  * **The recorded population is answered** — the new fill must leave a
 *    NON-NULL slot for every node it visits, i.e. the fill is not merely
 *    "quiet", it is the map's own answer.
 *
 * The end-to-end control ([the two fills produce the same diagnostics]) is what
 * would catch a divergence the node-by-node comparison cannot express, since
 * `flowAt` feeds narrowing and narrowing is where this compiler's
 * false-positive risk lives.
 */
class FlowIndexEquivalenceTest {

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

    /**
     * Build the graph for [FLOW_INDEX_FIXTURE] under one arm, with the
     * [FrontEnd] census armed so the population each fill visits is observable.
     * Restores both process-global modes whatever happens — a test that leaks
     * `FlowIndex.legacy` would silently re-point every alphabetically later
     * test class at the other arm.
     */
    private fun buildUnder(legacy: Boolean, fileName: String): Triple<SourceFile, FlowGraph, Long> {
        val savedLegacy = FlowIndex.legacy
        val savedMode = FrontEnd.mode
        try {
            FlowIndex.legacy = legacy
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            val sourceFile = Parser(FLOW_INDEX_FIXTURE, fileName).parse()
            val graph = FlowGraphBuilder().build(sourceFile)
            return Triple(sourceFile, graph, FrontEnd.idxNodes)
        } finally {
            FrontEnd.mode = savedMode
            FrontEnd.reset()
            FlowIndex.legacy = savedLegacy
        }
    }

    @Test
    fun `flowAt agrees between the recorded-node fill and the whole-tree walk for every node`() {
        val (fileNew, graphNew, _) = buildUnder(legacy = false, fileName = "new.ts")
        val (fileOld, graphOld, _) = buildUnder(legacy = true, fileName = "old.ts")
        val nodesNew = allNodes(fileNew)
        val nodesOld = allNodes(fileOld)
        // The two parses of the same text produce the same tree in the same
        // pre-order, so a positional zip compares the SAME syntactic node.
        if (nodesNew.size != nodesOld.size) {
            fail("the two arms parsed different trees: ${nodesNew.size} vs ${nodesOld.size} nodes")
        }
        var answered = 0
        var empty = 0
        for (i in nodesNew.indices) {
            val a = graphNew.flowAt(nodesNew[i])
            val b = graphOld.flowAt(nodesOld[i])
            // Flow nodes are per-build instances, so identity cannot be compared
            // across arms — `id` is the graph-local identity and it is minted by
            // the SAME walk in both arms, which is exactly what must agree.
            val aId = a?.id
            val bId = b?.id
            if (aId != bId) {
                val kind = nodesNew[i]::class.simpleName
                fail(
                    "flowAt($kind at pos ${nodesNew[i].pos}) diverges: recorded-node fill " +
                        "answered ${aId ?: "null"}, whole-tree walk answered ${bId ?: "null"}"
                )
            }
            if (aId == null) empty++ else answered++
        }
        // Both classes must be populated or the comparison above is one-sided.
        assert(answered > 30)
        assert(empty > 30)
    }

    @Test
    fun `the two fills visit different populations - the arm is not inert`() {
        val (_, _, visitedNew) = buildUnder(legacy = false, fileName = "new.ts")
        val (_, _, visitedOld) = buildUnder(legacy = true, fileName = "old.ts")
        // The whole-tree walk visits every node; the recorded-node fill visits
        // only what `recordFlow` wrote. If these were equal, `FlowIndex.legacy`
        // would be selecting nothing and the differential above would be
        // comparing a binary against itself.
        assert(visitedNew > 0)
        assert(visitedOld > visitedNew)
    }

    @Test
    fun `every node the recorded-node fill visits is answered by the map`() {
        val savedLegacy = FlowIndex.legacy
        val savedMode = FrontEnd.mode
        try {
            FlowIndex.legacy = false
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            FlowGraphBuilder().build(Parser(FLOW_INDEX_FIXTURE, "hits.ts").parse())
            // 100%: a recorded node's key is in the map by construction, so a
            // visited-but-unanswered slot would mean the fill is reading a
            // different key than `recordFlow` wrote.
            assert(FrontEnd.idxNodes > 0)
            assert(FrontEnd.idxHits == FrontEnd.idxNodes)
        } finally {
            FrontEnd.mode = savedMode
            FrontEnd.reset()
            FlowIndex.legacy = savedLegacy
        }
    }

    @Test
    fun `the two fills produce the same diagnostics`() {
        val savedLegacy = FlowIndex.legacy
        try {
            FlowIndex.legacy = false
            val fresh = diagnose(FLOW_INDEX_FIXTURE).map { "${it.code}@${it.start}:${it.message}" }
            FlowIndex.legacy = true
            val legacy = diagnose(FLOW_INDEX_FIXTURE).map { "${it.code}@${it.start}:${it.message}" }
            assert(fresh == legacy)
        } finally {
            FlowIndex.legacy = savedLegacy
        }
    }
}

/**
 * A fixture whose point is BREADTH of recorded positions: identifiers read in
 * every flow-affecting construct the builder has an arm for, so the differential
 * above compares a populated table rather than a handful of slots — plus plenty
 * of un-recorded syntax (types, interfaces, parameters, literals) so the
 * "answered null" class is populated too.
 */
private val FLOW_INDEX_FIXTURE = """
    interface Shape { kind: string; size: number }

    function classify(s: Shape | undefined, n: number, flag: boolean): string {
        if (s === undefined) {
            return "none";
        }
        let acc = 0;
        for (let i = 0; i < n; i++) {
            acc += i;
            if (acc > 10) break;
        }
        while (flag) {
            flag = !flag;
        }
        do {
            n--;
        } while (n > 0);
        switch (s.kind) {
            case "circle":
                acc = s.size;
                break;
            default:
                acc = 0;
        }
        try {
            acc = acc / n;
        } catch (e) {
            acc = -1;
        } finally {
            n = 0;
        }
        const arrow = (x: number) => x + acc;
        const obj = { a: acc, b: n, m() { return acc; } };
        const arr = [acc, n, obj.a];
        for (const el of arr) {
            acc += el;
        }
        for (const key in obj) {
            acc += key.length;
        }
        const t = flag ? s.kind : "other";
        const u = s?.kind ?? "fallback";
        return t + u + arrow(acc) + arr.length;
    }

    class Box {
        value = 0;
        get doubled() { return this.value * 2; }
        set doubled(v: number) { this.value = v / 2; }
        method(a: number) {
            this.value = a;
            return this.value;
        }
    }

    namespace NS {
        export const inner = 1;
        export function f() { return inner; }
    }

    const box = new Box();
    box.method(classify(undefined, 1, true).length);
""".trimIndent()
