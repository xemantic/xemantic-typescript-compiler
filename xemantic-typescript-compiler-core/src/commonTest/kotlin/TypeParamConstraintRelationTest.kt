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
 * (CHK.174), round P18.202 — a type-parameter SOURCE is related to a target through its
 * CONSTRAINT, as tsgo's type-variable leg does (`relater.go`, `structuredTypeRelatedToWorker`:
 * `constraint := r.c.getConstraintOfType(source)` … `r.isRelatedTo(constraint, target, …)`,
 * reached after the union decomposition for an instantiable source).
 *
 * `Checker.canUseTypeEngine`'s B60.8 rule admits a constrained type parameter against a
 * primitive target, and the relation had nothing that could ACCEPT that pair — so
 * `function g<T extends number>(k: T): number { return k }` was a TS2322 on legal code at the
 * declaration, return and object-literal-member readers (the assignment and argument readers
 * decide it elsewhere). Likewise a union target (`T extends Foo` against `Foo | undefined`).
 *
 * Two boundaries: an UNCONSTRAINED or CIRCULARLY constrained type parameter stays unrelated
 * (tsgo's `getConstraintOfTypeParameter` answers nil for `T extends T`, and the corpus's
 * `typeParameterHasSelfAsConstraint` keeps its TS2322); and B57.1b's alias type-argument guard
 * keeps the old verdict through `CheckerState.aliasGuardRelation` ((INC.30) owns it).
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN HEAD ROW over the identical text
 * (`build/scratch-p18202/cells`, `strict`, `target: es2022`, `module: esnext`). Known residues,
 * deliberately not pinned: our declaration/return readers print no `Type 'string' is not
 * assignable to type 'number'.` chain line under the head, and for a UNION or literal-union
 * constraint tsgo names the constraint (`Type 'string | number' …`) where we name `T`.
 */
class TypeParamConstraintRelationTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(code: String): List<String> =
        diagnose(code.trim() + "\nexport {}", directives = directives, fileName = "a.ts").map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}"
        }.sorted()

    @Test
    fun `a number-constrained parameter relates to number at the declaration reader`() {
        val actual = rows("function g<T extends number>(k: T) { const n: number = k }")
        assert(actual.isEmpty())
    }

    @Test
    fun `a number-constrained parameter relates to number at the return reader`() {
        val actual = rows("function g<T extends number>(k: T): number { return k }")
        assert(actual.isEmpty())
    }

    @Test
    fun `a boolean-constrained parameter relates to boolean`() {
        val actual = rows("function g<T extends boolean>(k: T) { const n: boolean = k }")
        assert(actual.isEmpty())
    }

    @Test
    fun `a literal-union constraint relates to its primitive`() {
        val actual = rows("""
function g<T extends "a" | "b">(k: T) { const n: string = k }
function h<T extends "a" | "b">(k: T): string { return k }
        """)
        assert(actual.isEmpty())
    }

    @Test
    fun `a literal constraint relates to its primitive`() {
        val actual = rows("function g<T extends 1 | 2>(k: T) { const n: number = k }")
        assert(actual.isEmpty())
    }

    @Test
    fun `a union constraint relates to the same union target`() {
        val actual = rows("""
function g<T extends number | string>(k: T) { const n: number | string = k }
function h<T extends number | string>(k: T): number | string { return k }
        """)
        assert(actual.isEmpty())
    }

    @Test
    fun `a constraint chain is followed`() {
        val actual = rows("""
function g<U extends number, T extends U>(k: T) { const n: number = k }
function h<U extends number, T extends U>(k: T): number { return k }
        """)
        assert(actual.isEmpty())
    }

    @Test
    fun `a keyof constraint relates to the property-key union and to string`() {
        val actual = rows("""
function g<O, K extends keyof O>(k: K) { const n: string | number | symbol = k }
function h<O, K extends keyof O & string>(k: K) { const n: string = k }
        """)
        assert(actual.isEmpty())
    }

    @Test
    fun `an object constraint relates to a union target carrying it`() {
        val actual = rows("""
interface Foo { a: number }
function g<T extends Foo>(k: T) { const n: Foo | undefined = k }
        """)
        assert(actual.isEmpty())
    }

    @Test
    fun `a class method and an arrow return relate through the class and own constraints`() {
        val actual = rows("""
class C<T extends number> { m(k: T): number { return k } }
const f = <T extends number>(k: T): number => k
        """)
        assert(actual.isEmpty())
    }

    @Test
    fun `an object-literal member relates through the constraint`() {
        val actual = rows("function g<T extends number>(k: T) { const o: { v: number } = { v: k } }")
        assert(actual.isEmpty())
    }

    @Test
    fun `control - a mismatched constraint still reports at the declaration reader`() {
        val actual = rows("function g<T extends string>(k: T) { const n: number = k }")
        assert(actual == listOf("1:44 2322 Type 'T' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a mismatched constraint still reports at the return reader`() {
        val actual = rows("function g<T extends string>(k: T): number { return k }")
        assert(actual == listOf("1:46 2322 Type 'T' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a mismatched object constraint still reports against a union target`() {
        val actual = rows("""
interface Foo { a: number }
function g<T extends Foo>(k: T) { const n: string | undefined = k }
        """)
        assert(actual == listOf("2:41 2322 Type 'T' is not assignable to type 'string | undefined'."))
    }

    @Test
    fun `control - a mismatched constraint still reports at the argument reader`() {
        val actual = rows("""
declare function pn(n: number): void
function g<T extends string>(k: T) { pn(k) }
        """)
        assert(actual == listOf("2:41 2345 Argument of type 'T' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `control - a circular constraint relates to nothing`() {
        val actual = rows("function foo<T extends T>(x: T): number { return x }")
        assert(actual == listOf(
            "1:24 2313 Type parameter 'T' has a circular constraint.",
            "1:43 2322 Type 'T' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `control - a mutually circular constraint relates to nothing`() {
        val actual = rows("function foo<T extends U, U extends T>(x: T): number { return x }")
        // Only the TS2322 row is this class's subject: our two TS2313 rows name the type
        // parameters the wrong way round (a pre-existing display residue).
        val relationRows = actual.filter { " 2322 " in it }
        assert(relationRows == listOf("1:56 2322 Type 'T' is not assignable to type 'number'."))
    }

    @Test
    fun `control - an any constraint is no constraint against a union target`() {
        // tsgo relates an `any`-constrained type parameter as an unconstrained one (`{}`).
        val actual = rows("function g<T extends any>(k: T) { const n: number | string = k }")
        assert(actual == listOf("1:41 2322 Type 'T' is not assignable to type 'string | number'."))
    }

    @Test
    fun `control - an unknown constraint relates to nothing`() {
        val actual = rows("function h<T extends unknown>(k: T): number { return k }")
        assert(actual == listOf("1:47 2322 Type 'T' is not assignable to type 'number'."))
    }

    @Test
    fun `the alias type-argument guard keeps its old verdict inside a self-referential alias`() {
        // The corpus's `excessPropertyCheckIntersectionWithRecursiveType` shape: with the new
        // rule reaching B57.1b's guard, `Prepend<any, I>` stops degrading, `BuildTree` expands
        // one level further and the literal gains a TS2322 tsgo does not report. The TS2353 is
        // tsgo's own row (as in the corpus baseline).
        val actual = rows("""
type Length<T extends any[]> = T["length"];
type Prepend<V, T extends any[]> = ((head: V, ...args: T) => void) extends (...args: infer R) => void ? R : any;
type BuildTree<T, N extends number = -1, I extends any[] = []> = {
  1: T;
  0: T & { children: BuildTree<T, N, Prepend<any, I>>[] };
}[Length<I> extends N ? 1 : 0];
interface User { name: string }
type GrandUser = BuildTree<User, 2>;
const grandUser: GrandUser = {
  name: "a",
  children: [{ name: "b", children: [{ name: "c", children: [{ name: "d", children: [{ name: "e" }] }] }] }],
};
        """)
        assert(actual == listOf(
            "11:51 2353 Object literal may only specify known properties, and 'children' does not exist in type 'User'.",
        ))
    }
}
