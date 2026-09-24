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
 * (CHK.171) R1, round P18.201 — at the DECLARATION (cta) reader, a constructor or setter body
 * sees its class's type parameters, and a nested function or class sees the enclosing
 * function's.
 *
 * The statement anchor installs `frame.fnTpScope ?: <resting scope>`, and the resting scope is
 * null, so a frame without its own scope resolved `T` to `any` — which relates to nearly
 * everything and silently hides errors. Two frame kinds lost it: (d) constructor / setter
 * frames, built without checkFunctionBody, never built a scope at all, and their parameter and
 * `this.$prop` seeds were resolved with none ((CHK.161)(a)); (e) a nested function's scope was
 * seeded from the RESTING scope instead of the enclosing frame's, so it lost every outer type
 * parameter. tsgo has no frames: `resolveNameHelper` (`binder/nameresolver.go`) walks
 * `location.Parent` and finds a type parameter in whichever container declares it, innermost
 * first — which is what the shadowing cases pin.
 *
 * A bare `T` is useless as a probe here — this engine is blind for a type-parameter SOURCE at
 * the declaration reader, and wrong for a primitive-constrained one — so every probe is either
 * `T[]`-shaped or a member read through a constraint (`k.b: number` against `string`), which
 * reports only when `T` resolves to the RIGHT declaration: `any` and the shadowed outer
 * parameter both leave it silent.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW over the identical text (`build/scratch-p18201/pins`,
 * `strict`, `target: es2022`, `module: esnext`, the same three-line prelude). Block, clause and
 * narrowing frames (`if`, `for`, `case`, ...) still drop the scope — (CHK.171) R4 — so every
 * probe here sits at a body's TOP level.
 */
class CtaFrameTypeParamScopeTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private val prelude = "interface A { a: number }\ninterface B { b: number }\ndeclare const ss: string[];\n"

    private fun rows(code: String): List<String> =
        diagnose(prelude + code.trim(), directives = directives, fileName = "a.ts").map { d ->
            "${d.fileName?.substringAfterLast('/')} ${d.line}:${d.character} ${d.code} ${d.message}"
        }.sorted()

    @Test
    fun `a constructor parameter is typed by the class type parameter`() {
        val actual = rows("""
class C<T extends B> { constructor(k: T) { const s: string = k.b; } }
        """)
        assert(actual == listOf(
            "a.ts 4:50 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `a setter parameter is typed by the class type parameter`() {
        val actual = rows("""
class C<T extends B> { set p(k: T) { const s: string = k.b; } }
        """)
        assert(actual == listOf(
            "a.ts 4:44 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `a constructor this member seed is typed by the class type parameter`() {
        val actual = rows("""
class C<T extends B> { v!: T; constructor() { const s: string = this.v.b; } }
        """)
        assert(actual == listOf(
            "a.ts 4:53 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `a setter this member seed is typed by the class type parameter`() {
        val actual = rows("""
class C<T extends B> { v!: T; set p(x: number) { const s: string = this.v.b; } }
        """)
        assert(actual == listOf(
            "a.ts 4:56 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `a constructor body annotation sees the class type parameter`() {
        val actual = rows("""
class C<T> { constructor() { const y: T[] = ss; } }
        """)
        assert(actual == listOf(
            "a.ts 4:36 2322 Type 'string[]' is not assignable to type 'T[]'.",
        ))
    }

    @Test
    fun `a setter body annotation sees the class type parameter`() {
        val actual = rows("""
class C<T> { set p(x: number) { const y: T[] = ss; } }
        """)
        assert(actual == listOf(
            "a.ts 4:39 2322 Type 'string[]' is not assignable to type 'T[]'.",
        ))
    }

    @Test
    fun `a constructor array parameter keeps its element type parameter`() {
        val actual = rows("""
class C<T> { constructor(v: T[]) { const q: string[] = v; } }
        """)
        assert(actual == listOf(
            "a.ts 4:42 2322 Type 'T[]' is not assignable to type 'string[]'.",
        ))
    }

    @Test
    fun `a constructor this array member keeps its element type parameter`() {
        val actual = rows("""
class C<T> { v!: T[]; constructor() { const q: string[] = this.v; } }
        """)
        assert(actual == listOf(
            "a.ts 4:45 2322 Type 'T[]' is not assignable to type 'string[]'.",
        ))
    }

    @Test
    fun `a nested function with its own type parameters still sees the outer ones`() {
        val actual = rows("""
function f<T>(a: T[]) { function g<U>(b: U[]) { const z: T[] = ss; const w: T[] = a; } }
        """)
        assert(actual == listOf(
            "a.ts 4:55 2322 Type 'string[]' is not assignable to type 'T[]'.",
        ))
    }

    @Test
    fun `a nested function without type parameters sees the outer ones`() {
        val actual = rows("""
function f<T>() { function g() { const z: T[] = ss; } }
        """)
        assert(actual == listOf(
            "a.ts 4:40 2322 Type 'string[]' is not assignable to type 'T[]'.",
        ))
    }

    @Test
    fun `control - a nested generic arrow already saw the outer type parameters`() {
        val actual = rows("""
function f<T>() { const g = <U,>(u: U) => { const z: T[] = ss; }; }
        """)
        assert(actual == listOf(
            "a.ts 4:51 2322 Type 'string[]' is not assignable to type 'T[]'.",
        ))
    }

    @Test
    fun `a method of a class declared in a generic function sees the function type parameters`() {
        val actual = rows("""
function f<T>() { class K { m(): void { const z: T[] = ss; } } }
        """)
        assert(actual == listOf(
            "a.ts 4:47 2322 Type 'string[]' is not assignable to type 'T[]'.",
        ))
    }

    @Test
    fun `a constructor of a class declared in a generic function sees the function type parameters`() {
        val actual = rows("""
function f<T>() { class K { constructor() { const z: T[] = ss; } } }
        """)
        assert(actual == listOf(
            "a.ts 4:51 2322 Type 'string[]' is not assignable to type 'T[]'.",
        ))
    }

    @Test
    fun `an outer constrained type parameter is read through its constraint in a nested function`() {
        val actual = rows("""
function f<T extends B>() { function g<U extends A>(k: T) { const s: string = k.b; } }
        """)
        assert(actual == listOf(
            "a.ts 4:67 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `an outer constrained type parameter is read through its constraint in a nested class constructor`() {
        val actual = rows("""
function f<T extends B>() { class K { constructor(k: T) { const s: string = k.b; } } }
        """)
        assert(actual == listOf(
            "a.ts 4:65 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `shadowing - a nested function own type parameter wins over the outer one`() {
        val actual = rows("""
function f<T extends A>() { function g<T extends B>(k: T) { const s: string = k.b; } }
        """)
        assert(actual == listOf(
            "a.ts 4:67 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `shadowing - a class type parameter wins over the enclosing function one in a method`() {
        val actual = rows("""
function f<T extends A>() { class K<T extends B> { m(k: T) { const s: string = k.b; } } }
        """)
        assert(actual == listOf(
            "a.ts 4:68 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `shadowing - a class type parameter wins over the enclosing function one in a constructor`() {
        val actual = rows("""
function f<T extends A>() { class K<T extends B> { constructor(k: T) { const s: string = k.b; } } }
        """)
        assert(actual == listOf(
            "a.ts 4:78 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `shadowing - a method own type parameter wins over the class one`() {
        val actual = rows("""
class C<T extends A> { m<T extends B>(k: T) { const s: string = k.b; } }
        """)
        assert(actual == listOf(
            "a.ts 4:53 2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `a static setter renders the class type parameter as tsgo does`() {
        val actual = rows("""
class C<T> { static set p(v: number) { const x: T = null!; const probe: number = { v: x }; } }
        """)
        assert(actual == listOf(
            "a.ts 4:49 2302 Static members cannot reference class type parameters.",
            "a.ts 4:66 2322 Type '{ v: T; }' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `control - a top-level generic function body always reported`() {
        val actual = rows("""
function f<T>(v: T[]) { const q: string[] = v; }
        """)
        assert(actual == listOf(
            "a.ts 4:31 2322 Type 'T[]' is not assignable to type 'string[]'.",
        ))
    }

    @Test
    fun `control - a type parameter does not leak into a sibling nested function`() {
        val actual = rows("""
function f() { function a<T>() {} function b() { const z: T[] = ss; } }
        """)
        assert(actual == listOf(
            "a.ts 4:59 2304 Cannot find name 'T'.",
        ))
    }

    @Test
    fun `control - a type parameter does not leak into a sibling top-level function`() {
        val actual = rows("""
function a<T>() {}
function b() { const z: T[] = ss; }
        """)
        assert(actual == listOf(
            "a.ts 5:25 2304 Cannot find name 'T'.",
        ))
    }
}
