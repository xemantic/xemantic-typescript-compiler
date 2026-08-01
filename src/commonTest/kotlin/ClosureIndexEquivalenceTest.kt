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
import kotlin.test.fail

/**
 * (ENGINE.2b) round 788 — [FlowGraph.innermostClosureAt] must answer EXACTLY what
 * B464's linear `closureStarts` scan answered, for every position of every shape.
 *
 * The reference implementation lives HERE, in [scanReference], transcribed from the
 * loop `emitTs18048ForClosureCapturedUndefinedReceiver` used before round 788 — so
 * these pins are self-discriminating: they do not need an ablated binary, because a
 * wrong index disagrees with the reference in the same run.
 *
 * The shapes that could break the index are the ones a naive replacement gets wrong:
 * a position BETWEEN two sibling closures (the backward walk must reach the enclosing
 * one, not the preceding sibling), a position AFTER every closure, deep nesting (the
 * enclosing-chain walk, not a linear rescan), and same-named parameters at two
 * nesting levels (the answer must be the INNER closure — that is what makes B464's
 * `root in closure.localNames` capture test mean anything).
 */
class ClosureIndexEquivalenceTest {

    /** The pre-788 linear scan, verbatim: among containers whose `[pos, end)`
     *  contains [pos], the one with the greatest `container.pos`, incumbent kept on
     *  a tie (the original comparison was a strict `>`). */
    private fun scanReference(graph: FlowGraph, pos: Int): FlowStart? {
        var found: FlowStart? = null
        var bestPos = -1
        for (cs in graph.closureStarts) {
            val c = cs.container ?: continue
            if (pos >= c.pos && pos < c.end && c.pos > bestPos) {
                bestPos = c.pos
                found = cs
            }
        }
        return found
    }

    private fun graphOf(source: String, fileName: String = "t.ts"): FlowGraph =
        FlowGraphBuilder().build(Parser(source.trimIndent(), fileName).parse())

    /** Every position from -1 to text length + 1 must agree with [scanReference]. */
    private fun assertAgreesEverywhere(source: String, graph: FlowGraph) {
        for (p in -1..source.trimIndent().length + 1) {
            val fast = graph.innermostClosureAt(p)
            val slow = scanReference(graph, p)
            if (fast !== slow) {
                fail(
                    "innermostClosureAt($p) diverges from the reference scan " +
                        "(fast container pos ${fast?.container?.pos}, slow ${slow?.container?.pos})"
                )
            }
        }
    }

    private val richFixture = """
        let outer: string | undefined;
        function host(a: string | undefined) {
            const first = () => { return a.length; };
            const second = function (b: string | undefined) {
                const inner = () => { return b.length; };
                return inner();
            };
            const third = () => () => () => outer.length;
            return [first, second, third];
        }
        const top = () => { return outer.length; };
        let tail = 1;
    """

    @Test
    fun `the index agrees with the linear scan at every position of a rich fixture`() {
        val graph = graphOf(richFixture)
        assert(graph.closureStarts.size >= 6)
        assertAgreesEverywhere(richFixture, graph)
    }

    @Test
    fun `a file with no closures at all answers null everywhere`() {
        val src = """
            let a = 1;
            function f(x: number) { return x + a; }
        """
        val graph = graphOf(src)
        assert(graph.closureStarts.isEmpty())
        for (p in -1..src.trimIndent().length + 1) assert(graph.innermostClosureAt(p) == null)
    }

    @Test
    fun `a position between two SIBLING closures reaches the enclosing one not the preceding sibling`() {
        // A closure is registered only when it is nested inside another function-like
        // (Flow.kt's `enclosing != null` gate), so the shape needs a hosting function.
        val src = """
            let g: string | undefined;
            function host() {
                const mid = () => {
                    const a = () => g.length;
                    const gap = g;
                    const b = () => g.length;
                    return [a, b];
                };
                return mid;
            }
        """
        val text = src.trimIndent()
        val graph = graphOf(src)
        val gapPos = text.indexOf("const gap")
        val inner = graph.innermostClosureAt(gapPos)
        assert(inner != null)
        // the answer is the OUTER `mid` arrow, whose range spans the whole body
        assert(inner!!.container!!.pos < text.indexOf("const a"))
        assert(inner.container!!.end > text.indexOf("return [a, b]"))
        assertAgreesEverywhere(src, graph)
    }

    @Test
    fun `a position after every closure answers the enclosing closure or null`() {
        val src = """
            let g: string | undefined;
            function host() {
                const a = () => g.length;
                const tail = g;
                return [a, tail];
            }
            const afterAll = g;
        """
        val text = src.trimIndent()
        val graph = graphOf(src)
        assert(graph.closureStarts.size == 1)
        // inside `host` but past the arrow: no closure contains it
        assert(graph.innermostClosureAt(text.indexOf("const tail")) == null)
        // past the whole function too
        assert(graph.innermostClosureAt(text.indexOf("const afterAll")) == null)
        assertAgreesEverywhere(src, graph)
    }

    @Test
    fun `ten levels of nesting answer the INNERMOST closure`() {
        val src = """
            let g: string | undefined;
            const deep = () => () => () => () => () => () => () => () => () => () => g.length;
        """
        val text = src.trimIndent()
        val graph = graphOf(src)
        val at = text.indexOf("g.length")
        val fast = graph.innermostClosureAt(at)
        val slow = scanReference(graph, at)
        assert(fast === slow)
        assert(fast != null)
        // innermost ⇒ no other closure container starts later and still contains `at`
        val innerPos = fast!!.container!!.pos
        assert(graph.closureStarts.none {
            val c = it.container
            c != null && c.pos > innerPos && at >= c.pos && at < c.end
        })
        assertAgreesEverywhere(src, graph)
    }

    @Test
    fun `a shadowed parameter name resolves to the INNER closure`() {
        val src = """
            const outer = (v: string | undefined) => {
                const inner = (v: string | undefined) => v.length;
                return inner(v);
            };
        """
        val text = src.trimIndent()
        val graph = graphOf(src)
        val at = text.indexOf("v.length")
        val fast = graph.innermostClosureAt(at)
        assert(fast === scanReference(graph, at))
        assert(fast != null)
        // the INNER arrow owns `v` as a local — which is what makes B464's
        // `root in closure.localNames` capture test decide the right way
        assert("v" in fast!!.localNames)
        assert(fast.container!!.pos > text.indexOf("const inner") - 1)
    }

    @Test
    fun `a closure inside a nested function body agrees at every position`() {
        val src = """
            let g: string | undefined;
            function a() {
                function b() {
                    const c = () => g.length;
                    return c;
                }
                return b;
            }
        """
        assertAgreesEverywhere(src, graphOf(src))
    }

    @Test
    fun `two graphs built from different files do not share an index`() {
        val a = graphOf(
            "let g: string | undefined; function h() { const f = () => g.length; return f; }", "a.ts",
        )
        val b = graphOf("let h = 1;", "b.ts")
        assert(b.closureStarts.isEmpty())
        assert(b.innermostClosureAt(20) == null)
        assert(a.innermostClosureAt(a.closureStarts[0].container!!.pos) != null)
    }

    @Test
    fun `an object-literal method and an argument arrow both index correctly`() {
        val src = """
            let g: string | undefined;
            declare function run(cb: () => number): number;
            const o = {
                m: function () { return run(() => g.length); },
            };
        """
        assertAgreesEverywhere(src, graphOf(src))
    }
}
