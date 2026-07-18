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

import kotlin.test.Test

/**
 * (ccet-m3) round 590: the checkCallExpressionTypes emissions run from
 * per-Call/New/TaggedTemplate-node spine anchors while the legacy arms
 * truncate their duplicates — every diagnostic appears EXACTLY ONCE
 * (0 = lost to truncation without an anchor; 2 = anchored without the
 * skip). Statements at legacy-unreached positions (switch case exprs,
 * for-in/of iterables, enum initializers) stay silent on both sides.
 */
class CcetAnchorTest {

    private val prelude = """
        declare function f(x: number): void;
    """

    private fun count2345(source: String): Int =
        diagnose(prelude + source).count { it.code == 2345 }

    @Test
    fun `top-level and fn-body call args emit exactly once`() {
        val nTop = count2345("""f("s");""")
        assert(nTop == 1) { "top-level: expected exactly 1 TS2345, got $nTop" }
        val nFn = count2345("""
            function g() { f("s"); }
        """)
        assert(nFn == 1) { "fn body: expected exactly 1 TS2345, got $nFn" }
    }

    @Test
    fun `method arrow and namespace call args emit exactly once`() {
        val nMethod = count2345("""
            class C { m() { f("s"); } }
        """)
        assert(nMethod == 1) { "method: expected exactly 1 TS2345, got $nMethod" }
        val nArrow = count2345("""
            const g = () => { f("s"); };
        """)
        assert(nArrow == 1) { "arrow: expected exactly 1 TS2345, got $nArrow" }
        val nNs = count2345("""
            namespace N { f("s"); }
        """)
        assert(nNs == 1) { "namespace: expected exactly 1 TS2345, got $nNs" }
    }

    @Test
    fun `new-expression and super call args emit exactly once`() {
        val nNew = count2345("""
            class K { constructor(x: number) {} }
            const k = new K("s");
        """)
        assert(nNew == 1) { "new: expected exactly 1 TS2345, got $nNew" }
        val nSuper = count2345("""
            class B { constructor(x: number) {} }
            class D extends B { constructor() { super("s"); } }
        """)
        assert(nSuper == 1) { "super: expected exactly 1 TS2345, got $nSuper" }
    }

    @Test
    fun `arity errors emit exactly once`() {
        val n = diagnose(prelude + """
            function g() { f(); }
        """).count { it.code == 2554 }
        assert(n == 1) { "expected exactly 1 TS2554, got $n" }
    }

    @Test
    fun `static method class-TP skip-gate holds under the anchor`() {
        // The 17.21 rule: a static body's class-T new-expression draws the
        // TS2302s but NOT a TS2345 (T re-resolves errorType in the static
        // scope → the null-arg emission skips). The anchor's verdict must
        // match — the round-589 frame-time flip is the regression this pins.
        val d = diagnose("""
            namespace Editor {
                export class List<T> {
                    constructor(public isHead: boolean, public data: T) {}
                    public static MakeHead(): List<T> {
                        var entry: List<T> = new List<T>(true, null);
                        return entry;
                    }
                }
            }
        """)
        assert(d.none { it.code == 2345 }) {
            "static class-T new must not draw TS2345, got: ${d.filter { it.code == 2345 }.map { it.message }}"
        }
    }

    @Test
    fun `negative control - valid calls stay silent`() {
        val n = count2345("""
            f(1);
            function g() { f(2); }
            const a = () => { f(3); };
        """)
        assert(n == 0) { "expected 0 TS2345, got $n" }
    }
}
