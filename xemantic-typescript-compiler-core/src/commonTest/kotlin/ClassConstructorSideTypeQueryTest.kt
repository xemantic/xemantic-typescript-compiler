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

/**
 * (CHK.196) stage 1 — `typeof A` for a class is its CONSTRUCTOR SIDE ([ClassConstructorTypes]):
 * the statics (inherited ones too), the values of a merged namespace, every visible construct
 * signature (the base's when the class declares none, a zero-argument default otherwise),
 * abstract-ness — and it prints `typeof A`. Every expectation is tsgo 7.0.2's own output over
 * the same source (the census cells under `build/scratch-p18252-census/cells` and `build/bench/p18253-agent/m`).
 *
 * An identifier READ of a class (`const c = A`) is stage 2's, pinned in
 * [ClassValueReadConstructorTypeTest].
 */
class ClassConstructorSideTypeQueryTest {

    private val es2022 = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(source: String, directives: String = es2022): List<String> =
        diagnose(source, directives).map {
            "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} TS${it.code} ${it.message}"
        }

    /** census cell c06: statics through an annotated `typeof A`, and the display of the type. */
    @Test
    fun `a typeof A variable reads the class statics and prints typeof A`() {
        assert(rows("""
            class A { static s = true; x = 1 }
            const t: typeof A = A;
            const p: number = t.s;
            t.nope;
            t.x;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'boolean' is not assignable to type 'number'.",
            "t.ts 4:3 TS2339 Property 'nope' does not exist on type 'typeof A'.",
            "t.ts 5:3 TS2339 Property 'x' does not exist on type 'typeof A'.",
        ))
    }

    /** census cell c21: a type alias of `typeof A` carries the static property and method. */
    @Test
    fun `a type alias of typeof A carries static members`() {
        assert(rows("""
            class A { static s = true; static m() { return 1 } }
            type T = typeof A;
            declare const t: T;
            const p: string = t.s;
            const q: string = t.m();
        """) == listOf(
            "t.ts 4:7 TS2322 Type 'boolean' is not assignable to type 'string'.",
            "t.ts 5:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cell c31: a parameter annotated `typeof A`. */
    @Test
    fun `a typeof A parameter reads the class statics`() {
        assert(rows("""
            class A { static s = 1 }
            function f(k: typeof A) { const p: string = k.s; }
        """) == listOf(
            "t.ts 2:33 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cell c12: a generic class constructed through `typeof Box` infers its argument. */
    @Test
    fun `new through a typeof Box variable infers the class type argument`() {
        assert(rows("""
            class Box<T> { constructor(public v: T) {} }
            const t: typeof Box = Box;
            const r: string = new t(1).v;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cell c16: the module-object carrier `m.Cls` and a variable copied from it. */
    @Test
    fun `a class read off a module namespace object carries its statics`() {
        assert(rows("""
            // @Filename: /proj/m.ts
            export class Cls { static s = 1; x = 1 }
            // @Filename: /proj/t.ts
            import * as m from './m';
            const p: string = m.Cls.s;
            const c = m.Cls;
            const q: string = c.s;
        """) == listOf(
            "t.ts 2:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 4:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cell h03, the `typeof A` half: an imported class's statics through `typeof A`. */
    @Test
    fun `typeof an imported class carries its statics`() {
        assert(rows("""
            // @Filename: /proj/a.ts
            export class A { static s = 1; x = 1 }
            // @Filename: /proj/t.ts
            import { A } from './a';
            const t: typeof A = A;
            const q: string = t.s;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** cell m/k1: the VALUE exports of a merged namespace are members of `typeof A`. */
    @Test
    fun `typeof A carries the values of a merged namespace`() {
        assert(rows("""
            class A { x = 1 }
            namespace A { export const k = 1 }
            declare const t: typeof A;
            const p: string = t.k;
        """) == listOf(
            "t.ts 4:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** cell m/k2: every constructor OVERLOAD is a construct signature of `typeof O`. */
    @Test
    fun `typeof O carries every constructor overload`() {
        assert(rows("""
            class O {
              constructor(a: string);
              constructor(a: number, b: number);
              constructor(a: any, b?: any) {}
            }
            declare const t: typeof O;
            new t(1, 2);
            new t("s");
            new t(true);
        """) == listOf(
            "t.ts 9:7 TS2345 Argument of type 'boolean' is not assignable to parameter of type 'string'.",
        ))
    }

    /** cell m/k4: a class without a constructor takes its base's signatures and inherits statics. */
    @Test
    fun `typeof a derived class without a constructor takes the base signatures and statics`() {
        assert(rows("""
            class Base { constructor(public v: string) {} static make() { return 1 } }
            class Sub extends Base {}
            declare const t: typeof Sub;
            new t();
            const q: string = t.make();
        """) == listOf(
            "t.ts 4:1 TS2554 Expected 1 arguments, but got 0.",
            "t.ts 5:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** cell m/k5: a generic class's statics through `typeof Box`. */
    @Test
    fun `typeof a generic class carries its statics`() {
        assert(rows("""
            class Box<T> { constructor(public v: T) {} static zero = 0 }
            declare const t: typeof Box;
            const z: string = t.zero;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** cell m/k6: the type itself prints `typeof A`, not its construct signature. */
    @Test
    fun `typeof A prints as typeof A in a relation error`() {
        assert(rows("""
            class A { static s = 1 }
            declare const t: typeof A;
            const n: number = t;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'typeof A' is not assignable to type 'number'.",
        ))
    }

    /** cells c11 and m/k7: an abstract class stays non-constructable through `typeof`, statics read. */
    @Test
    fun `typeof an abstract class is abstract and carries statics`() {
        assert(rows("""
            abstract class Ab { static s = "x" }
            declare const t: typeof Ab;
            new t();
            const p: number = t.s;
        """) == listOf(
            "t.ts 3:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 4:7 TS2322 Type 'string' is not assignable to type 'number'.",
        ))
    }

    /** cell m/k3: a class without any constructor stays constructable through `typeof`. */
    @Test
    fun `typeof a class without a constructor is constructable`() {
        assert(rows("""
            class Plain { x = 1 }
            declare const t: typeof Plain;
            const p: string = new t().x;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /**
     * The corpus's `classSideInheritance3`, its legal line: the constructor side carries NO
     * `prototype` member yet, because the identifier `C` is still instance-typed (stage 2) — a
     * `prototype` on the `typeof A` target alone is a false TS2741 here (measured by the census).
     */
    @Test
    fun `a derived class with a compatible constructor is assignable to typeof its base`() {
        assert(rows("""
            class A { constructor(public x: string) {} }
            class C extends A { constructor(x: string) { super(x); } }
            var r3: typeof A = C;
        """).isEmpty())
    }

    /**
     * The corpus's `typeofClass`: the missing-member row on a `typeof K` receiver prints the type
     * once — the old `"typeof $rawTypeName"` prefix on a class-symbol receiver must not double it.
     */
    @Test
    fun `a missing member on typeof K prints typeof K once`() {
        assert(rows("""
            class K { foo = 1; static bar = "" }
            declare var k1: K;
            k1.bar;
            declare var k2: typeof K;
            k2.foo;
            k2.bar;
        """) == listOf(
            "t.ts 3:4 TS2576 Property 'bar' does not exist on type 'K'. Did you mean to access the static member 'K.bar' instead?",
            "t.ts 5:4 TS2339 Property 'foo' does not exist on type 'typeof K'.",
        ))
    }

    /**
     * cell m/k8: a member-less class's constructor side still RELATES — to a construct-signature
     * target and inside a lib utility. Measured: an empty-but-non-null member table there
     * silently dropped both rows (each was reported before this round).
     */
    @Test
    fun `typeof a class without statics relates against a construct signature target`() {
        assert(rows("""
            class Plain { x = 1 }
            declare const t: typeof Plain;
            const h: new () => string = t;
            const o: { new (): Plain } = t;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'typeof Plain' is not assignable to type 'new () => string'.",
        ))
    }

    /**
     * cell m/k9: a static of the SOURCE constructor side satisfies a property of the target —
     * the instance-side "a static does not count" rule (B175) must not read it. tsgo reports the
     * incompatible property (TS2322); with the rule applied the row was a false TS2741 `missing`.
     */
    @Test
    fun `a static of typeof S satisfies a target property`() {
        assert(rows("""
            class S { static s = 1; x = 1 }
            declare const t: typeof S;
            const o2: { new (): S; s: string } = t;
            const o3: { s: number } = t;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'typeof S' is not assignable to type '{ new (): S; s: string; }'.",
        ))
    }

    /** cell m/k10: two constructor sides relate by their statics, a union of them reads one. */
    @Test
    fun `constructor sides relate by their statics and a union of them reads the shared static`() {
        assert(rows("""
            class A { static s = 1; x = 1 }
            class B { static s = 1; static q = ""; y = 1 }
            declare const a: typeof A;
            const x1: typeof B = a;
            declare const u: typeof A | typeof B;
            const us: string = u.s;
            function g<T extends typeof A>(c: T) { const v: string = c.s; }
        """) == listOf(
            "t.ts 4:7 TS2741 Property 'q' is missing in type 'typeof A' but required in type 'typeof B'.",
            "t.ts 6:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 7:46 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }
}
