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
 * (CHK.152) step 1, round P18.183 — a NAMED-OBJECT argument is RELATED to a NAMED-OBJECT
 * parameter. `z(q)` with `q: Q` (`vv: number`) against `(p: S)` (`vv: string`) is TS2345
 * with the member chain in tsgo 7.0.2 and was SILENT here, while `const s: S = q` reported:
 * no `allow*` gate of `caasNonSimpleParamChecks`' firewall claimed the pair, and the tail's
 * member elaboration excluded `Type.Interface`/`Type.Reference`.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, head AND chain, measured over the cell matrix in
 * `build/scratch-p18183/cells{,2}` (argument row and declaration twin per cell).
 *
 * The silent controls are the half that matters most: a non-fresh source with an extra
 * member, method bivariance, a required-to-optional member, a structurally compatible class,
 * a subtype, a flow-narrowed argument — each would become a false positive if the relation
 * were wrong, and each stays silent in tsgo.
 *
 * NOT PINNED (wrong today; countdowns for the census's later steps): a REST position
 * (`z(...p: S[])`, tsgo reports `Q` against the ELEMENT `S`), a union or nullable parameter
 * (`S | undefined`), a union argument, an argument carrying a free type parameter, a generic
 * callee whose instantiation carries the mismatch, a class with a `private` member against
 * an identical class, and an array parameter.
 */
class NamedObjectArgumentAssignabilityTest {

    private val prelude = """
        interface S { vv: string }
        interface Q { vv: number }
        declare const q: Q;
    """.trimIndent() + "\n"

    /** Every row as `code message` followed by its chain lines, sorted by row. */
    private fun rows(source: String): List<List<String>> =
        diagnose(source).map { listOf("${it.code} ${it.message}") + it.messageChain }
            .sortedBy { it.first() }

    private val vvChain = listOf(
        "  Types of property 'vv' are incompatible.",
        "    Type 'number' is not assignable to type 'string'.",
    )

    private fun arg(type: String, param: String, chain: List<String>) =
        listOf("2345 Argument of type '$type' is not assignable to parameter of type '$param'.") + chain

    @Test
    fun `an interface argument is related to an interface parameter with the member chain`() {
        val rows = rows(prelude + """
            declare function z(p: S): void;
            z(q);
        """.trimIndent())
        assert(rows == listOf(arg("Q", "S", vvChain)))
    }

    @Test
    fun `an aliased object type parameter is related`() {
        val rows = rows("""
            type S = { vv: string };
            interface Q { vv: number }
            declare const q: Q;
            declare function z(p: S): void;
            z(q);
        """.trimIndent())
        assert(rows == listOf(arg("Q", "S", vvChain)))
    }

    @Test
    fun `a class instance argument is related to a class instance parameter`() {
        val rows = rows("""
            class S { vv = '' }
            class Q { vv = 0 }
            declare const q: Q;
            declare function z(p: S): void;
            z(q);
        """.trimIndent())
        assert(rows == listOf(arg("Q", "S", vvChain)))
    }

    @Test
    fun `a generic interface instantiation is related to another instantiation`() {
        val rows = rows("""
            interface G<T> { v: T }
            declare const q: G<number>;
            declare function z(p: G<string>): void;
            z(q);
        """.trimIndent())
        assert(rows == listOf(arg("G<number>", "G<string>",
            listOf("  Type 'number' is not assignable to type 'string'."))))
    }

    @Test
    fun `an intersection argument elaborates through its members`() {
        val rows = rows(prelude + """
            interface R { w: 1 }
            declare const qr: Q & R;
            declare function z(p: S): void;
            z(qr);
        """.trimIndent())
        assert(rows == listOf(arg("Q & R", "S", vvChain)))
    }

    @Test
    fun `a nested member mismatch elaborates with the dotted path`() {
        val rows = rows("""
            interface S { o: { vv: string } }
            interface Q { o: { vv: number } }
            declare const q: Q;
            declare function z(p: S): void;
            z(q);
        """.trimIndent())
        assert(rows == listOf(arg("Q", "S", listOf(
            "  The types of 'o.vv' are incompatible between these types.",
            "    Type 'number' is not assignable to type 'string'.",
        ))))
    }

    @Test
    fun `an optional source member against a required one elaborates the undefined`() {
        val rows = rows("""
            interface S { vv: string }
            interface Q { vv?: string }
            declare const q: Q;
            declare function z(p: S): void;
            z(q);
        """.trimIndent())
        assert(rows == listOf(arg("Q", "S", listOf(
            "  Types of property 'vv' are incompatible.",
            "    Type 'string | undefined' is not assignable to type 'string'.",
            "      Type 'undefined' is not assignable to type 'string'.",
        ))))
    }

    @Test
    fun `a parameter source a method call and a constructor call all relate`() {
        val rows = rows(prelude + """
            declare function z(p: S): void;
            declare const o: { m(p: S): void };
            declare class K { constructor(p: S) }
            function f(qp: Q) { z(qp); }
            o.m(q);
            new K(q);
        """.trimIndent())
        assert(rows == List(3) { arg("Q", "S", vvChain) })
    }

    @Test
    fun `only the first failing argument of a call reports, here the second`() {
        val rows = rows(prelude + """
            declare const s: S;
            declare function z(a: S, p: S): void;
            z(s, q);
        """.trimIndent())
        assert(rows == listOf(arg("Q", "S", vvChain)))
    }

    @Test
    fun `negative control - a wrong arity reports TS2554 alone`() {
        val rows = rows(prelude + """
            declare function z(p: S): void;
            z(q, 1);
        """.trimIndent())
        assert(rows == listOf(listOf("2554 Expected 1 arguments, but got 2.")))
    }

    @Test
    fun `negative control - a rest position never compares the argument against the array`() {
        val rows = rows(prelude + """
            declare function z(...p: S[]): void;
            z(q);
        """.trimIndent())
        assert(rows.none { r -> r.any { "'S[]'" in it } })
    }

    @Test
    fun `negative control - a branded primitive argument is not reported as a bare missing property`() {
        // tsgo: TS2345 whose chain names the APPARENT `String & { __b: 1; }`. This reader
        // would print a bare TS2741 at the argument, so the pair stays outside the gate
        // (a countdown: the row itself is still missing here).
        val rows = rows(prelude + """
            type Brand = string & { __b: 1 };
            declare const b: Brand;
            declare function z(p: S): void;
            z(b);
        """.trimIndent())
        assert(rows.none { it.first().startsWith("2741 ") })
    }

    @Test
    fun `negative control - compatible named objects stay silent`() {
        val rows = rows("""
            interface S { vv: string }
            interface Extra { vv: string; w: number }
            interface Opt { vv?: string; w?: number }
            interface A { x: number }
            interface B extends A { y: number }
            interface M { m(a: A): void }
            interface MB { m(b: B): void }
            interface U { vv: unknown }
            interface G<T> { v: T }
            class C1 { vv = '' }
            class C2 { vv = ''; m() {} }
            class Base { vv = '' }
            class Derived extends Base { w = 1 }
            declare const extra: Extra;
            declare const s: S;
            declare const mb: MB;
            declare const c2: C2;
            declare const d: Derived;
            declare const ga: G<'a'>;
            declare function zs(p: S): void;
            declare function zo(p: Opt): void;
            declare function zm(p: M): void;
            declare function zu(p: U): void;
            declare function zc(p: C1): void;
            declare function zb(p: Base): void;
            declare function zg(p: G<string>): void;
            zs(extra);
            zo(extra);
            zm(mb);
            zu(s);
            zc(c2);
            zs(c2);
            zb(d);
            zg(ga);
        """.trimIndent())
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a flow-narrowed argument is judged by its narrowed type`() {
        val rows = rows("""
            interface S { vv: string }
            interface A { vv: string | number }
            interface B extends A { vv: string }
            declare function isB(x: A): x is B;
            declare function assertB(x: A): asserts x is B;
            declare function z(p: S): void;
            declare const o: { a: A };
            function f1(x: A) { if (isB(x)) { z(x); } }
            function f2(x: A) { if (!isB(x)) return; z(x); }
            function f3(x: A) { return isB(x) && z(x); }
            function f4(x: A) { return isB(x) ? z(x) : 0; }
            function f5() { if (!isB(o.a)) return; z(o.a); }
            function f6(x: A) { assertB(x); z(x); }
        """.trimIndent())
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a union parameter against a narrowed receiver's member stays silent`() {
        // tsgo is silent: `p.parent` is `A | B` once `p` is narrowed to `E`. This reader
        // types the member off the DECLARED `N`, so admitting a union parameter alone reported
        // `Argument of type 'N' is not assignable to parameter of type 'A | B'` three times
        // (the shape of the 3 ours-only rows it added on tsc's harness sources). Since (CHK.152)
        // step 3 (P18.198) the parameter IS admitted and the narrowed-receiver re-read
        // (`Checker.argMemberRereadFromNarrowedReceiver`) is what keeps these legal.
        val rows = rows("""
            interface N { k: number; parent: N }
            interface A extends N { a: 1 }
            interface B extends N { b: 1 }
            interface E extends N { e: 1; parent: A | B }
            declare function isE(n: N): n is E;
            declare function zab(p: A | B): void;
            function h1(p: N) { return isE(p) && zab(p.parent); }
            function h2(p: N) { return isE(p) ? zab(p.parent) : 0; }
            function h3(p: N) { if (!isE(p)) return; zab(p.parent); }
        """.trimIndent())
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - an argument carrying a constrained type parameter stays silent`() {
        // `G<T>` with `T extends string` IS assignable to `G<string>` (tsgo is silent);
        // deciding it needs a type-parameter-to-concrete relation rule this engine lacks.
        val rows = rows("""
            interface G<T> { v: T }
            interface H<T> { f(x: T): void; w: T[] }
            declare function z(p: G<string>): void;
            declare function zh(p: H<string>): void;
            function f1<T extends string>(x: G<T>) { z(x); }
            function f2<T extends 'a' | 'b'>(x: G<T>) { z(x); }
            function f3<T extends string>(x: H<T>) { zh(x); }
        """.trimIndent())
        assert(rows.isEmpty())
    }

    @Test
    fun `negative control - a parameter carrying the callee's own type parameter is not related`() {
        // tsgo reports only `Argument of type 'string' is not assignable to parameter of
        // type 'number'.` at `"x"` (a countdown here: inference picks `T` differently); the
        // un-instantiated `G<T>` must never be a relation target.
        val rows = rows("""
            interface G<T> { v: T }
            declare const gn: G<number>;
            declare function z<T>(a: T, p: G<T>): void;
            z("x", gn);
        """.trimIndent())
        assert(rows.none { r -> r.any { "'G<T>'" in it } })
    }
}
