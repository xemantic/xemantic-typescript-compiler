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
 * (CHK.173) Round B2: Round A's identifier-receiver TS1804x arm
 * (`Checker.emitTs1804xForNullableIdentifierReceiver`) now also reads (G2) an OPTIONAL
 * parameter `x?: T` as `T | undefined` — tsgo's declared type — and (G3b) a non-union
 * `null` / `undefined` declared type, except the `null` keyword itself (tsgo TS18050).
 * Every fixture is a cell of the (P18.215) Round B census
 * (`build/scratch-p18215-census/cells/g2`, `g3`); every expectation is tsgo 7.0.2's
 * TS18046-TS18050 row list for it (other codes are filtered out: TS2684 on p12 and
 * TS2540 on p22 are not this arm's). Residues NOT pinned (tsgo reports, we do not yet):
 * p18 (type-parameter receiver, G3a), p19 (for-of expression, G4 reach), p20 (a
 * destructured parameter leaf with a default), p28 / p29 (a contextually typed
 * optional parameter).
 */
class OptionalParamNullishReceiverTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private fun nullishRows(line: String): List<String> =
        diagnose(line + "\nexport {}\n", STRICT)
            .filter { it.code in 18046..18050 }
            .map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }
            .sorted()

    @Test
    fun `p01 reports`() {
        val rows = nullishRows("function f(x?: string) { return x.length }")
        assert(rows == listOf("1:33 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p08 reports`() {
        val rows = nullishRows("const f = (x?: string) => x.length")
        assert(rows == listOf("1:27 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p09 reports`() {
        val rows = nullishRows("class C { m(x?: string) { return x.length } }")
        assert(rows == listOf("1:34 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p10 reports`() {
        val rows = nullishRows("class C { constructor(x?: string) { x.length } }")
        assert(rows == listOf("1:37 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p11 reports`() {
        val rows = nullishRows("function f(x?: { n: number }) { return x.n }")
        assert(rows == listOf("1:40 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p12 reports`() {
        val rows = nullishRows("function f(x?: () => void) { x.call(null) }")
        assert(rows == listOf("1:30 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p13 reports`() {
        val rows = nullishRows("function f(x?: string) { return () => x.length }")
        assert(rows == listOf("1:39 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p21 reports`() {
        val rows = nullishRows("function f(x?: string) { return x.nope }")
        assert(rows == listOf("1:33 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p22 reports`() {
        val rows = nullishRows("function f(x?: string) { x.length = 1 }")
        assert(rows == listOf("1:26 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p23 reports`() {
        val rows = nullishRows("function f(this: void, x?: string) { return x.length }")
        assert(rows == listOf("1:45 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p25 reports`() {
        val rows = nullishRows("function f(x?: string) { function g() { return x.length } return g }")
        assert(rows == listOf("1:48 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p27 reports`() {
        val rows = nullishRows("declare function f(x?: string): void; function h(x?: Map<string, number>) { return x.size }")
        assert(rows == listOf("1:84 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p30 reports`() {
        val rows = nullishRows("function f(x?: string) { switch (typeof x) { case 'string': return x.length } return x.length }")
        assert(rows == listOf("1:86 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `q19 reports`() {
        val rows = nullishRows("function f(x?: string) { try { return x.length } catch { } }")
        assert(rows == listOf("1:39 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `q23 reports`() {
        val rows = nullishRows("function f(x?: number) { return x.toFixed() }")
        assert(rows == listOf("1:33 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `q24 reports`() {
        val rows = nullishRows("abstract class A { abstract m(x?: string): void } class B extends A { m(x?: string) { x.length } }")
        assert(rows == listOf("1:87 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `q25 reports`() {
        val rows = nullishRows("declare function f(cb: (x?: string) => void): void; f(function (x?: string) { x.length })")
        assert(rows == listOf("1:79 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `p07 reports`() {
        val rows = nullishRows("function f(x?: string | null) { return x.length }")
        assert(rows == listOf("1:40 TS18049: 'x' is possibly 'null' or 'undefined'."))
    }

    @Test
    fun `n01 reports`() {
        val rows = nullishRows("declare const n: null; n.foo;")
        assert(rows == listOf("1:24 TS18047: 'n' is possibly 'null'."))
    }

    @Test
    fun `n02 reports`() {
        val rows = nullishRows("declare const n: undefined; n.foo;")
        assert(rows == listOf("1:29 TS18048: 'n' is possibly 'undefined'."))
    }

    @Test
    fun `n03 reports`() {
        val rows = nullishRows("function f(n: null) { return n.foo }")
        assert(rows == listOf("1:30 TS18047: 'n' is possibly 'null'."))
    }

    @Test
    fun `n04 reports`() {
        val rows = nullishRows("function f(n: undefined) { return n.foo }")
        assert(rows == listOf("1:35 TS18048: 'n' is possibly 'undefined'."))
    }

    @Test
    fun `n10 reports`() {
        val rows = nullishRows("declare let n: null; n.foo = 1;")
        assert(rows == listOf("1:22 TS18047: 'n' is possibly 'null'."))
    }

    @Test
    fun `n12 reports`() {
        val rows = nullishRows("function f(n: null) { return n.toString() }")
        assert(rows == listOf("1:30 TS18047: 'n' is possibly 'null'."))
    }

    @Test
    fun `p02 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x) return x.length; return 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p03 stays silent`() {
        val rows = nullishRows("function f(x?: string) { return x?.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p04 stays silent`() {
        val rows = nullishRows("function f(x?: string) { x = x || ''; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p05 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x === undefined) return; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p06 stays silent`() {
        val rows = nullishRows("function f(x: string = 'a') { return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p14 stays silent`() {
        val rows = nullishRows("function f(x?: string) { x ??= 'a'; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p15 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (!x) throw 0; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p16 stays silent`() {
        val rows = nullishRows("function f(x?: any) { return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p24 stays silent`() {
        val rows = nullishRows("function f(...x: string[]) { return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `p26 stays silent`() {
        val rows = nullishRows("function f(x?: string) { { const x = 'a'; x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q01 stays silent`() {
        val rows = nullishRows("function f(x?: string) { x = x ?? 'a'; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q02 stays silent`() {
        val rows = nullishRows("function f(x?: string) { x ||= 'a'; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q03 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x === void 0) return; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q04 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (typeof x === 'undefined') return; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q05 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x == null) return; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q06 stays silent`() {
        val rows = nullishRows("declare function assert(v: unknown): asserts v; function f(x?: string) { assert(x); return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q07 stays silent`() {
        val rows = nullishRows("function f(x?: string) { return x!.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q08 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (!x) { x = 'a' } return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q09 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x) { return () => x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q10 stays silent`() {
        val rows = nullishRows("function f(x?: string) { const g = () => { if (x) x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q11 stays silent`() {
        val rows = nullishRows("function f(x?: string, y = x) { return 1 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q12 stays silent`() {
        val rows = nullishRows("function f(x?: string) { while (!x) { x = 'a' } return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q13 stays silent`() {
        val rows = nullishRows("function f(x?: string) { for (; x; ) { x.length; break } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q14 stays silent`() {
        val rows = nullishRows("function f(x?: string) { switch (x) { case 'a': return x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q15 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x !== undefined && x.length) return 1 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q16 stays silent`() {
        val rows = nullishRows("function f(x?: string) { return x && x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q17 stays silent`() {
        val rows = nullishRows("function f(x?: string) { return x ? x.length : 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q18 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x === undefined) x = ''; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q20 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x?.length) return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q21 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (x instanceof String) return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `q22 stays silent`() {
        val rows = nullishRows("function f(x?: string) { if (typeof x === 'string') return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `the null keyword stays TS18050 only`() {
        val rows = diagnose("null.foo;\nnull.toBAZ();\n", STRICT).map { "${it.line}:${it.character} TS${it.code}" }.sorted()
        assert(rows == listOf("1:1 TS18050", "2:1 TS18050"))
    }

    @Test
    fun `a nested function parameter of the same name shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { return (x: string) => x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a catch variable of the same name shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { try { } catch (x) { } return 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a for-header let of the same name shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { for (let x = 'a'; x.length < 3; ) { x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a var of the same name in a nested function shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { function g() { var x = 'a'; return x.length } return g }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a for-of const of the same name shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string, a: string[] = []) { for (const x of a) x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a block let with a definite assignment assertion shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { { let x!: string; return x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a block function declaration of the same name shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { { function x() {} return x.name } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a block class declaration of the same name shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { { class x { static n = 1 } return x.n } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a block let assigned before the read shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { { let x: string; x = 'a'; return x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a block enum of the same name shadows the optional parameter`() {
        val rows = nullishRows("function f(x?: string) { { enum x { A } return x.A } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a var redeclaring the optional parameter reads the parameter`() {
        val rows = nullishRows("function f(x?: string) { var x: string; return x.length }")
        assert(rows == listOf("1:48 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `t01 a method-call reassignment from this narrows the optional parameter`() {
        val rows = nullishRows("interface SF { text: string } class N { getSF(): SF { return null! } m(sf?: SF) { if (!sf) { sf = this.getSF(); } return sf.text } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `t03 an unbraced reassignment from this narrows the optional parameter`() {
        val rows = nullishRows("interface SF { text: string } class N { getSF(): SF { return null! } m(sf?: SF) { if (!sf) sf = this.getSF(); return sf.text } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `t04 a reassignment from this narrows a union-declared parameter`() {
        val rows = nullishRows("interface SF { text: string } class N { getSF(): SF { return null! } m(sf: SF | undefined) { if (!sf) { sf = this.getSF(); } return sf.text } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `t05 a property reassignment from this narrows the optional parameter`() {
        val rows = nullishRows("interface SF { text: string } class N { sf0!: SF; m(sf?: SF) { if (!sf) { sf = this.sf0; } return sf.text } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `t07 a reassignment from this inside an arrow narrows the optional parameter`() {
        val rows = nullishRows("interface SF { text: string } class N { getSF(): SF { return null! } m(sf?: SF) { const g = () => { if (!sf) sf = this.getSF(); return sf.text }; return g } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `t06 a reassignment from a nullable this method keeps the report`() {
        val rows = nullishRows("interface SF { text: string } class N { getSF(): SF | undefined { return undefined } m(sf?: SF) { if (!sf) { sf = this.getSF(); } return sf.text } }")
        assert(rows == listOf("1:138 TS18048: 'sf' is possibly 'undefined'."))
    }

    @Test
    fun `t09 a function expression this is not the class`() {
        val rows = nullishRows("interface SF { text: string } class N { getSF(): SF { return null! } m(sf?: SF) { function h(this: any) { if (!sf) sf = this.getSF(); return sf.text } return h } }")
        assert(rows == listOf("1:142 TS18048: 'sf' is possibly 'undefined'."))
    }
}
