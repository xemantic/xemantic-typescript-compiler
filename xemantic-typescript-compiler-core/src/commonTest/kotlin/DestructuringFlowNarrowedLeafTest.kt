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
 * (CHK.173) B5c (P18.226) — a destructured leaf is typed from the FLOW-NARROWED
 * initializer, as tsgo's `getFlowTypeOfDestructuring` types it: `if (!o.x) return;
 * const { x } = o` reads `x` as the synthetic reference `o.x` narrowed at the
 * initializer (`string`, not the member's `string | null`), and the pattern's source is
 * the initializer's own flow type (`if (o.k === "a") { const { v } = o }` destructures
 * the narrowed constituent). Before, every reader of a destructured leaf took the
 * member's DECLARED type: a false TS2345 at an argument, the wrong type named by a
 * TS2322, a missing TS2362, and a false TS18047 on a FILE-level leaf.
 *
 * Every fixture is a cell of `build/bench/p18226-agent/cells/m{1,2,4}` and every
 * expectation is tsgo 7.0.2's WHOLE row list for it. The controls are tsgo's own
 * refusals: a parameter pattern, a root written between guard and declaration (`o = o2`,
 * `o.p = q`), a leaf read in a closure, a parenthesized / non-null-asserted initializer
 * and an object rest element all keep the declared type.
 */
class DestructuringFlowNarrowedLeafTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private val PRELUDE = "declare function takeS(s: string): void; declare function takeN(n: number): void;\n"

    private fun rowsOf(body: String): List<String> =
        diagnose(PRELUDE + body + "\nexport {}\n", STRICT)
            .map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    // The census N4 cells (g1 b06 / nar n4p / ng n4a-c) — silent in tsgo, and the shape
    // the coming G1 arm would otherwise report as possibly-null.

    @Test
    fun `a leaf of a narrowed member path is not possibly null`() {
        val rows = rowsOf("function f(o: { x: string | null }) { if (!o.x) return; const { x } = o; takeS(x); x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `an optional object member narrowed by a guard`() {
        val rows = rowsOf("function f(st: { cache?: { a: string } }) { if (!st.cache) return; const { cache } = st; cache.a }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a leaf narrowed inside the guarded block`() {
        val rows = rowsOf("function f(st: { cache?: { a: string } }) { if (st.cache) { const { cache } = st; cache.a } }")
        assert(rows.isEmpty())
    }

    // Each reader of a destructured leaf.

    @Test
    fun `the declaration reader names the narrowed type`() {
        val rows = rowsOf("function f(o: { x: string | null }) { if (!o.x) return; const { x } = o; const p: number = x }")
        assert(rows == listOf("2:80 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `the argument reader accepts the narrowed leaf`() {
        val rows = rowsOf("function f(o: { x: string | null }) { if (o.x === null) return; const { x } = o; takeS(x) }")
        assert(rows.isEmpty())
    }

    @Test
    fun `the arithmetic reader sees the narrowed leaf`() {
        val rows = rowsOf("function f(o: { x: string | null }) { if (!o.x) return; const { x } = o; x - 1 }")
        assert(rows == listOf(
            "2:74 TS2362: The left-hand side of an arithmetic operation must be of type 'any', 'number', 'bigint' or an enum type.",
        ))
    }

    @Test
    fun `a file-level leaf through its symbol`() {
        val rows = rowsOf(
            "declare const o: { x: string | null }; if (!o.x) throw 0; const { x } = o; x.length; takeS(x); const p: number = x;",
        )
        assert(rows == listOf("2:102 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    // Pattern shapes.

    @Test
    fun `a renamed leaf`() {
        val rows = rowsOf("function f(o: { x: string | null }) { if (!o.x) return; const { x: y } = o; takeS(y) }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a nested pattern reads the nested path`() {
        val rows = rowsOf("function f(o: { p: { a: string | null } }) { if (!o.p.a) return; const { p: { a } } = o; takeS(a) }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a default value over a narrowed leaf`() {
        val rows = rowsOf(
            "function f(o: { x?: string | null }) { if (!o.x) return; const { x = 'd' } = o; takeS(x); const p: number = x }",
        )
        assert(rows == listOf("2:97 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `an array pattern reads the element path`() {
        val rows = rowsOf("function f(t: [string | null]) { if (t[0] === null) return; const [a] = t; takeS(a); const p: number = a }")
        assert(rows == listOf("2:92 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `an array pattern nested in an object pattern`() {
        val rows = rowsOf("function f(o: { t: [string | null] }) { if (o.t[0] !== null) { const { t: [a] } = o; takeS(a) } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a typeof guard on the member path`() {
        val rows = rowsOf("function f(o: { x: string | number }) { if (typeof o.x === 'string') { const { x } = o; takeS(x) } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a guard written inside the closure that destructures`() {
        val rows = rowsOf("function f(o: { x: string | null }) { const g = () => { if (!o.x) return; const { x } = o; takeS(x) } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `the pattern source is the narrowed discriminated constituent`() {
        val rows = rowsOf(
            "type U = { k: 'a', v: string } | { k: 'b', v: number }; " +
                "function f(o: U) { if (o.k === 'a') { const { v } = o; takeS(v) } }",
        )
        assert(rows.isEmpty())
    }

    // An empty object literal default over a WEAK member type (tsgo's subtype-reduced
    // join drops the `{}`) — exposed on tsc's own `textChanges.ts:1321-1324` once the
    // pattern source is flow-narrowed; the un-narrowed union shape shipped it before.

    private val WEAK = "interface A { ind?: number; pre?: string } interface B extends A { j?: string } interface R { req: number }\n"

    private fun weakRowsOf(body: String): List<String> =
        diagnose(WEAK + body + "\nexport {}\n", STRICT)
            .map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    @Test
    fun `an empty object default over a weak member type reads the member type`() {
        val rows = weakRowsOf("function f(c: { options?: A }) { const { options = {} } = c; options.ind; const p: string = options }")
        assert(rows == listOf("2:81 TS2322: Type 'A' is not assignable to type 'string'."))
    }

    @Test
    fun `an empty object default over a union of weak member types`() {
        val rows = weakRowsOf(
            "function f(c: { k: 1, options?: A } | { k: 2, options?: B }) { const { options = {} } = c; options.ind }",
        )
        assert(rows.isEmpty())
    }

    @Test
    fun `an empty object default over a narrowed source`() {
        val rows = weakRowsOf(
            "function f(c: { k: 1, options?: A } | { k: 2, options?: B } | { k: 3 }) " +
                "{ if (c.k === 3) return; const { options = {} } = c; options.ind }",
        )
        assert(rows.isEmpty())
    }

    // Controls — tsgo keeps the declared type.

    @Test
    fun `control - an unguarded discriminated source`() {
        val rows = rowsOf(
            "type U = { k: 'a', v: string } | { k: 'b', v: number }; function f(o: U) { const { v } = o; takeS(v) }",
        )
        assert(rows == listOf(
            "2:99 TS2345: Argument of type 'string | number' is not assignable to parameter of type 'string'.",
        ))
    }

    @Test
    fun `control - a destructured parameter is not narrowed`() {
        val rows = rowsOf("function f({ x }: { x: string | null }) { const p: number = x }")
        assert(rows == listOf("2:49 TS2322: Type 'string | null' is not assignable to type 'number'."))
    }

    @Test
    fun `control - the root reassigned between the guard and the declaration`() {
        val rows = rowsOf(
            "function f(o: { x: string | null }, o2: { x: string | null }) { if (!o.x) return; o = o2; const { x } = o; takeS(x) }",
        )
        assert(rows == listOf(
            "2:114 TS2345: Argument of type 'string | null' is not assignable to parameter of type 'string'.",
        ))
    }

    @Test
    fun `control - an intermediate member reassigned`() {
        val rows = rowsOf(
            "function f(o: { p: { x: string | null } }, q: { x: string | null }) " +
                "{ if (!o.p.x) return; o.p = q; const { p: { x } } = o; takeS(x) }",
        )
        assert(rows == listOf(
            "2:130 TS2345: Argument of type 'string | null' is not assignable to parameter of type 'string'.",
        ))
    }

    @Test
    fun `control - a leaf destructured in a closure after an outer guard`() {
        val rows = rowsOf(
            "function f(o: { x: string | null }) { if (!o.x) return; const g = () => { const { x } = o; takeS(x) } }",
        )
        assert(rows == listOf(
            "2:98 TS2345: Argument of type 'string | null' is not assignable to parameter of type 'string'.",
        ))
    }

    @Test
    fun `control - a parenthesized initializer is not a reference`() {
        val rows = rowsOf("function f(o: { x: string | null }) { if (!o.x) return; const { x } = (o); const p: number = x }")
        assert(rows == listOf("2:82 TS2322: Type 'string | null' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a non-null asserted initializer is not a reference`() {
        val rows = rowsOf(
            "function f(o: { x: string | null } | null) { if (!o || !o.x) return; const { x } = o!; const p: number = x }",
        )
        assert(rows == listOf("2:94 TS2322: Type 'string | null' is not assignable to type 'number'."))
    }

    @Test
    fun `control - an object rest element keeps the member type`() {
        val rows = rowsOf("function f(o: { x: string | null, y: number }) { if (!o.x) return; const { ...r } = o; const p: number = r.x }")
        assert(rows == listOf("2:94 TS2322: Type 'string | null' is not assignable to type 'number'."))
    }
}
