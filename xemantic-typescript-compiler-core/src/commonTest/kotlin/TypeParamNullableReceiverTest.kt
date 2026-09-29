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
 * (CHK.173) Round B4 (G3a) — a receiver whose declared type is a TYPE PARAMETER (or a
 * union naming one) with a nullable constraint: `function f<T extends string | null>(x: T)
 * { x.length }` is tsgo 7.0.2's TS18047, because `checkNonNullType` reads an instantiable
 * type's facts through its BASE constraint.
 *
 * Two halves: the property-access (cpa) frames now carry a type-parameter scope
 * (`Checker.cpaTpScope` for the spine frames — function, method / constructor / accessor
 * with the class's type parameters except in a STATIC member, arrow, function expression;
 * `Checker.cpaWithOwnTps` for the function-likes the legacy walker reaches inside an
 * anchored statement), so a `T`-annotated parameter is registered as `T` instead of
 * reading `any`; and the identifier / element / member TS1804x arms read the declared type
 * with every type parameter replaced by its base constraint
 * (`Checker.typeParamsToBaseConstraints`; an unconstrained one reports nothing).
 *
 * Fixtures are the (P18.215) census's g3 cells and the round's own matrix
 * (`build/bench/p18221-agent/cells/p18221_tp*`); every expectation is tsgo's row list
 * restricted to the codes this family emits. RESIDUES, not pinned (tsgo reports, we do
 * not yet): TS2339 on a `T` whose constraint lacks the member (`T extends object`, `void`,
 * a union constraint, `T[K]`, `T extends unknown`), TS2551 / TS2322 on the constraint
 * (`x.lenght`, `x.a = "s"`), `x = v as T` re-narrowing (tsgo still reports), a body
 * local typed `T` (`const y = x`, `let y: T = x` — G1), a `for…of` over `T[]`, an
 * object-literal method body, a `this: T` parameter, TS2721 for a `T`-typed callee, and
 * `x: T | null` / `x: T | undefined`, where a TS2339 on the union PRE-EXISTS beside the
 * right TS1804x row.
 */
class TypeParamNullableReceiverTest {
    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private val CODES = setOf(18047, 18048, 18049, 2531, 2532, 2533, 2339, 2551, 2322, 2721, 2722, 2723)

    private fun rows(source: String): List<String> =
        diagnose(source + "\nexport {}\n", STRICT)
            .filter { it.code in CODES }
            .map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }
            .sorted()

    @Test
    fun `G3a a01 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { x.length = 1 }")
        assert(rows == listOf(
            "1:45 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a02 reports`() {
        val rows = rows("function f<T extends { m(): void } | null>(x: T) { x.m() }")
        assert(rows == listOf(
            "1:52 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a03 reports`() {
        val rows = rows("function f<T extends string | null>(x?: T) { return x.length }")
        assert(rows == listOf(
            "1:53 TS18049: 'x' is possibly 'null' or 'undefined'.",
        ))
    }

    @Test
    fun `G3a a04 reports`() {
        val rows = rows("function f<T extends string>(x?: T) { return x.length }")
        assert(rows == listOf(
            "1:46 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a a05 reports`() {
        val rows = rows("function f<T extends string | null>(x: T = null!) { return x.length }")
        assert(rows == listOf(
            "1:60 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a06 reports`() {
        val rows = rows("class C { m<T extends string | null>(x: T) { return x.length } }")
        assert(rows == listOf(
            "1:53 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a07 reports`() {
        val rows = rows("class C<T extends string | undefined> { constructor(x: T) { x.length } }")
        assert(rows == listOf(
            "1:61 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a a08 reports`() {
        val rows = rows("class C<T extends string | undefined> { set p(x: T) { x.length } }")
        assert(rows == listOf(
            "1:55 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a a09 is silent`() {
        val rows = rows("class C<T extends string | undefined> { static s(x: T) { return x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a a10 reports`() {
        val rows = rows("function o<T extends string | null>() { function i(x: T) { return x.length } return i }")
        assert(rows == listOf(
            "1:67 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a11 reports`() {
        val rows = rows("function o<T extends string | null>() { return function <U extends T>(x: U) { return x.length } }")
        assert(rows == listOf(
            "1:86 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a12 reports`() {
        val rows = rows("const f = <T extends string | null>(x: T) => x.length")
        assert(rows == listOf(
            "1:46 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a13 reports`() {
        val rows = rows("const f = function <T extends string | null>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:61 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a15 reports`() {
        val rows = rows("function f<T extends { p: string | null }>(o: T) { return o.p.length }")
        assert(rows == listOf(
            "1:59 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a16 is silent`() {
        val rows = rows("function f<T extends string>(x: T) { return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a a18 is silent`() {
        val rows = rows("function f<T extends string | number>(x: T) { return x.toString() }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a a19 reports`() {
        val rows = rows("function f<T>(x: T) { return x.nope }")
        assert(rows == listOf(
            "1:32 TS2339: Property 'nope' does not exist on type 'T'.",
        ))
    }

    @Test
    fun `G3a a21 reports`() {
        val rows = rows("function f<T extends { a: number }>(x: T) { return x.b }")
        assert(rows == listOf(
            "1:54 TS2339: Property 'b' does not exist on type 'T'.",
        ))
    }

    @Test
    fun `G3a a22 is silent`() {
        val rows = rows("function f<T extends { a: number }>(x: T) { return x.a }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a a23 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (x === null) return; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a a24 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { return x ? x.length : 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a a26 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { const g = function () { return x.length }; return g }")
        assert(rows == listOf(
            "1:76 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a27 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { return [1].map(() => x.length) }")
        assert(rows == listOf(
            "1:66 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a28 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { try { return x.length } catch { return 0 } }")
        assert(rows == listOf(
            "1:58 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a29 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (Math.random()) { return x.length } }")
        assert(rows == listOf(
            "1:73 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a31 reports`() {
        val rows = rows("function f<T extends string | null>(x: T[]) { return x[0].length }")
        assert(rows == listOf(
            "1:54 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a33 reports`() {
        val rows = rows("function f<T extends null>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:43 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a34 reports`() {
        val rows = rows("function f<T extends undefined>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:48 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a a36 reports`() {
        val rows = rows("function f<T extends any>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:44 TS2339: Property 'length' does not exist on type 'T'.",
        ))
    }

    @Test
    fun `G3a a37 reports`() {
        val rows = rows("function f<T extends string | null, U extends T | undefined>(x: U) { return x.length }")
        assert(rows == listOf(
            "1:77 TS18049: 'x' is possibly 'null' or 'undefined'.",
        ))
    }

    @Test
    fun `G3a a38 reports`() {
        val rows = rows("type N = string | null; function f<T extends N>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:64 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a39 reports`() {
        val rows = rows("interface I { a: number } function f<T extends I | null>(x: T) { return x.a }")
        assert(rows == listOf(
            "1:73 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a42 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { function g() { return x.length } return g }")
        assert(rows == listOf(
            "1:67 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a43 reports`() {
        val rows = rows("namespace N { export function f<T extends string | null>(x: T) { return x.length } }")
        assert(rows == listOf(
            "1:73 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a44 reports`() {
        val rows = rows("function f<T extends { a: number } | null>(x: T) { return x[\"a\"] }")
        assert(rows == listOf(
            "1:59 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a45 reports`() {
        val rows = rows("function f<T extends readonly string[] | null>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:63 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a46 reports`() {
        val rows = rows("class C<T extends string | null> { x!: T; m() { return this.x.length } }")
        assert(rows == listOf(
            "1:56 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a a48 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { switch (1) { case 1: return x.length } }")
        assert(rows == listOf(
            "1:73 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c02 reports`() {
        val rows = rows("const C = class <T extends string | null> { m(x: T) { return x.length } }")
        assert(rows == listOf(
            "1:62 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c03 reports`() {
        val rows = rows("function o<T extends string | null>() { class C { m(x: T) { return x.length } } return C }")
        assert(rows == listOf(
            "1:68 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c04 reports`() {
        val rows = rows("class C<T extends string> { m<T extends string | null>(x: T) { return x.length } }")
        assert(rows == listOf(
            "1:71 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c05 reports`() {
        val rows = rows("class C<T extends string | null> { m() { return (x: T) => x.length } }")
        assert(rows == listOf(
            "1:59 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c06 reports`() {
        val rows = rows("class C<T extends string | null> { m() { function g<U extends T>(x: U) { return x.length } return g } }")
        assert(rows == listOf(
            "1:81 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c07 reports`() {
        val rows = rows("class C<T extends string | null> { get g() { return (x: T) => x.length } }")
        assert(rows == listOf(
            "1:63 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c08 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (x) { return x.length } return x.length }")
        assert(rows == listOf(
            "1:79 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c09 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (!x) throw 0; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a c10 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { return x != null ? x.length : 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a c11 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { for (let i = 0; i < 2; i++) { if (x) x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a c12 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T): number { return x?.length ?? 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a c13 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { return typeof x === \"string\" && x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a c14 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { x!.length; return x.length }")
        assert(rows == listOf(
            "1:63 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c15 reports`() {
        val rows = rows("function f<T extends { a: { b: number } | null }>(x: T) { return x.a.b }")
        assert(rows == listOf(
            "1:66 TS18047: 'x.a' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c16 reports`() {
        val rows = rows("function f<T extends string | null>(...xs: T[]) { return xs[0].length }")
        assert(rows == listOf(
            "1:58 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c17 reports`() {
        val rows = rows("function f<T extends string | null>({ a }: { a: T }) { return a.length }")
        assert(rows == listOf(
            "1:63 TS18047: 'a' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c19 reports`() {
        val rows = rows("declare function g<T extends string | null>(x: T): void; function f<T extends string | null>(x: T) { g(x); return x.length }")
        assert(rows == listOf(
            "1:115 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c20 reports`() {
        val rows = rows("function f<T extends string | null, K extends T>(x: K | undefined) { return x.length }")
        assert(rows == listOf(
            "1:77 TS18049: 'x' is possibly 'null' or 'undefined'.",
        ))
    }

    @Test
    fun `G3a c22 reports`() {
        val rows = rows("function f<T extends string[] | null>(x: T) { return x[0] }")
        assert(rows == listOf(
            "1:54 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a c24 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { x.length; x.length }")
        assert(rows == listOf(
            "1:45 TS18047: 'x' is possibly 'null'.",
            "1:55 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a e01 reports`() {
        val rows = rows("const h = () => { function g<T extends string | null>(x: T) { return x.length } return g }")
        assert(rows == listOf(
            "1:70 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a e02 reports`() {
        val rows = rows("const C = class { m<T extends string | undefined>(x: T) { return x.length } }")
        assert(rows == listOf(
            "1:66 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a e03 reports`() {
        val rows = rows("const h = () => { class D<T extends string | null> { m(x: T) { return x.length } } return D }")
        assert(rows == listOf(
            "1:71 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a e04 is silent`() {
        val rows = rows("class C<T extends string | null> { static s<T extends string>(x: T) { return x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a e05 is silent`() {
        val rows = rows("const C = class <T extends string | null> { static s(x: T) { return x.length } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a t01 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:52 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a t02 reports`() {
        val rows = rows("function f<T extends string | undefined>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:57 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a t03 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (x) return x.length; return 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a t04 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (x !== null) return x.length; return 0 }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a t05 reports`() {
        val rows = rows("function f<T>(x: T) { return x.toString() }")
        assert(rows == listOf(
            "1:32 TS2339: Property 'toString' does not exist on type 'T'.",
        ))
    }

    @Test
    fun `G3a t07 reports`() {
        val rows = rows("function f<T extends { n: number } | null>(x: T) { return x.n }")
        assert(rows == listOf(
            "1:59 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a t08 reports`() {
        val rows = rows("function f<T extends { n: number } | null | undefined>(x: T) { return x.n }")
        assert(rows == listOf(
            "1:71 TS18049: 'x' is possibly 'null' or 'undefined'.",
        ))
    }

    @Test
    fun `G3a t09 reports`() {
        val rows = rows("class C<T extends string | null> { constructor(public x: T) {} m() { return this.x.length } }")
        assert(rows == listOf(
            "1:77 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a t10 reports`() {
        val rows = rows("function f<T extends string | null, U extends T>(x: U) { return x.length }")
        assert(rows == listOf(
            "1:65 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a t11 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { return x?.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a t13 reports`() {
        val rows = rows("function f<T extends {} | null>(x: T) { return x.toString() }")
        assert(rows == listOf(
            "1:48 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a t15 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { return x!.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u01 reports`() {
        val rows = rows("function f<T extends string | null>(x: T) { const g = () => x.length; return g }")
        assert(rows == listOf(
            "1:61 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a u02 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (!x) return; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u03 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (x == null) return; return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u04 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { return x && x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u05 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { if (typeof x === 'string') return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u06 is silent`() {
        val rows = rows("class C<T extends { n: number } | undefined> { m(x: T) { if (x) return x.n } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u07 reports`() {
        val rows = rows("class C<T extends { n: number } | undefined> { m(x: T) { return x.n } }")
        assert(rows == listOf(
            "1:65 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a u09 is silent`() {
        val rows = rows("function f<T extends string | null>(x: T) { while (x) { x.length; break } }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u10 is silent`() {
        val rows = rows("function f<T extends object | null>(x: T) { if (x) return x.toString() }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u11 is silent`() {
        val rows = rows("function f<T extends string>(x: T | null) { if (x) return x.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `G3a u12 reports`() {
        val rows = rows("function f<T extends string | undefined = string>(x: T) { return x.length }")
        assert(rows == listOf(
            "1:66 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G3a f03 reports - a generic arrow's closure`() {
        val rows = rows("const f = <T extends string | null>(x: T) => { const g = () => x.length; return g }")
        assert(rows == listOf(
            "1:64 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G3a f04 reports - a generic function expression argument`() {
        val rows = rows("foo(function <T extends string | null>(x: T) { return x.length }); declare function foo(a: unknown): void")
        assert(rows == listOf(
            "1:55 TS18047: 'x' is possibly 'null'.",
        ))
    }

    /**
     * A constraint naming a type parameter again inside a union is a TS2313 cycle; the base
     * constraint walk must stay bounded (an unbounded one overflowed the stack, surfacing as
     * the boundary guard's TS2589 and wiping every other row of the program). tsgo's TS2313 /
     * TS2339 rows here are a pre-existing residue; what is pinned is no overflow and no
     * TS1804x.
     */
    @Test
    fun `G3a f02 - a circular constraint through a union stays bounded`() {
        val all = diagnose("function f<T extends U | null, U extends T>(x: T) { return x.length }\nexport {}\n", STRICT)
        val overflow = all.any { it.code == 2589 }
        val nullish = all.any { it.code == 18047 || it.code == 18048 || it.code == 18049 }
        assert(!overflow)
        assert(!nullish)
    }

    private val guardPrelude = """
        interface Node { readonly tag: string }
        interface StringLiteral extends Node { text: string }
        declare function isOne(node: Node): node is StringLiteral

    """.trimIndent()

    /**
     * A type guard on a `T` receiver narrows it to `T & StringLiteral` (round 744); now that
     * the property-access family types `input` as `T`, its flow suppression must find the
     * candidate's member through the intersection (tsgo: no row).
     */
    @Test
    fun `G3a guard - a guarded T receiver resolves the candidate's member`() {
        val all = diagnose(guardPrelude + """
            export function f<T extends Node>(input: T): string {
                if (isOne(input)) { return input.tag + input.text; }
                return input.tag;
            }
            """, STRICT)
        assert(all.none { it.code == 2339 })
    }

    /** Negative control for the guard: outside it the candidate's member is missing (tsgo 6:18). */
    @Test
    fun `G3a guard - an unguarded T receiver still misses the candidate's member`() {
        val src = "interface Node { readonly tag: string }\n" +
            "interface StringLiteral extends Node { text: string }\n" +
            "declare function isOne(node: Node): node is StringLiteral\n" +
            "export function f<T extends Node>(input: T): string {\n" +
            "    if (isOne(input)) { return input.text; }\n" +
            "    return input.text;\n" +
            "}\n"
        val all = diagnose(src, STRICT).filter { it.code == 2339 }.map { "${it.line}:${it.character} ${it.message}" }
        assert(all == listOf("6:18 Property 'text' does not exist on type 'T'."))
    }

    /**
     * A self-recursive alias constraint is tracked by the type-parameter-ops walker AND is
     * now typed by the property-access family: one row, as tsgo (2:35), not two.
     */
    @Test
    fun `G3a tpo - a self-recursive alias constraint reports its missing member once`() {
        val all = diagnose("type R = { next: R };\nfunction f<T extends R>(t: T) { t.foo; }\nexport {}\n", STRICT)
            .filter { it.code == 2339 }.map { "${it.line}:${it.character} ${it.message}" }
        assert(all == listOf("2:35 Property 'foo' does not exist on type 'T'."))
    }

    /** The walker keeps an UNCONSTRAINED `T` (the property-access family reads `any` there). */
    @Test
    fun `G3a tpo - an unconstrained T still reports its missing member once`() {
        val all = diagnose("function f<T>(t: T) { t.foo; }\nexport {}\n", STRICT)
            .filter { it.code == 2339 }.map { "${it.line}:${it.character} ${it.message}" }
        assert(all == listOf("1:25 Property 'foo' does not exist on type 'T'."))
    }
}
