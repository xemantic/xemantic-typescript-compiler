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
 * (CHK.196) stage 2 — a VALUE read of a class (`const c = A`, `A.s`, `A.prototype`, `N.C`,
 * a renaming import, `return A`, a class expression) answers the class's CONSTRUCTOR SIDE
 * ([ClassConstructorTypes]) instead of its instance type. Every expectation is tsgo 7.0.2's
 * own output over the same source (census cells under `build/scratch-p18252-census/cells`,
 * probes under `build/bench/p18254-agent/p`).
 *
 * Deliberately NOT in this stage, and pinned as such only where the old answer is still
 * correct: a direct `new` callee keeps reading the instance (its typing is keyed on it), a
 * heritage expression and the right operand of `instanceof` too; a mixin class expression
 * keeps `any`.
 */
class ClassValueReadConstructorTypeTest {

    private val es2022 = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(source: String, directives: String = es2022): List<String> =
        diagnose(source, directives).map {
            "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} TS${it.code} ${it.message}"
        }

    /** census cell c01: a class-valued variable displays as `typeof A`. */
    @Test
    fun `a class assigned to a variable displays as typeof A`() {
        assert(rows("""
            class A { x = 1 }
            const c = A;
            const n: number = c;
            const m: number = A;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'typeof A' is not assignable to type 'number'.",
            "t.ts 4:7 TS2322 Type 'typeof A' is not assignable to type 'number'.",
        ))
    }

    /** census cell c18: a generic class's constructor side is not a foreign type parameter. */
    @Test
    fun `a generic class read as a value displays as typeof G`() {
        assert(rows("""
            class G<T> { v!: T }
            const m: number = G;
        """) == listOf(
            "t.ts 2:7 TS2322 Type 'typeof G' is not assignable to type 'number'.",
        ))
    }

    /** census cells c22, c32, h15: statics through a class-valued variable, no TS2576. */
    @Test
    fun `a class-valued variable reads statics and not instance members`() {
        assert(rows("""
            class A { x = 1; static s = 1; static m(x: string) {} }
            class B extends A { static t = 2 }
            const c = A;
            const p: string = c.s;
            const q: string = c.x;
            c.m(1);
            const k = B;
            const r: string = k.s;
            const u: string = k.t;
        """) == listOf(
            "t.ts 4:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 5:21 TS2339 Property 'x' does not exist on type 'typeof A'.",
            "t.ts 6:5 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            "t.ts 8:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 9:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cell c10: `A.prototype` is the instance. */
    @Test
    fun `A dot prototype reads as the instance type`() {
        assert(rows("""
            class A { x = 1 }
            const p: string = A.prototype;
            const q: string = A.prototype.x;
        """) == listOf(
            "t.ts 2:7 TS2322 Type 'A' is not assignable to type 'string'.",
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cell c14: a merged namespace's value through a class-valued variable. */
    @Test
    fun `a merged namespace value reads through a class-valued variable`() {
        assert(rows("""
            class A { x = 1 }
            namespace A { export const k = 1 }
            const p: string = A.k;
            const c = A;
            const q: string = c.k;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 5:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cells c03, h11: construction through a class-valued variable. */
    @Test
    fun `new through a class-valued variable checks arguments and reads the instance`() {
        assert(rows("""
            class A { constructor(public x: number) {} }
            const c = A;
            new c("s");
            class P { x = 1 }
            class Q { x = 2 }
            declare const cond: boolean;
            const k = cond ? P : Q;
            const p: string = new k().x;
        """) == listOf(
            "t.ts 3:7 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "t.ts 8:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** census cell h02: one class's constructor side against another's. */
    @Test
    fun `a class value missing a static of the target constructor type is TS2741`() {
        assert(rows("""
            class A { static s = 1 }
            class C {}
            var r: typeof A = C;
        """) == listOf(
            "t.ts 3:5 TS2741 Property 's' is missing in type 'typeof C' but required in type 'typeof A'.",
        ))
    }

    /** census cell c30: a constructor side against a call-only target is decidable. */
    @Test
    fun `a class value against a call-only function type is not assignable`() {
        val r = rows("""
            class A { x = 1 }
            const c: new () => A = A;
            const e: () => A = A;
        """)
        assert(r.size == 1)
        assert(r[0] == "t.ts 3:7 TS2322 Type 'typeof A' is not assignable to type '() => A'.")
    }

    /** census cell h18: a namespace-qualified class read as a value. */
    @Test
    fun `a namespace-qualified class read as a value is its constructor side`() {
        assert(rows("""
            namespace N { export class C { static s = 1; x = 1 } }
            const k = N.C;
            const p: string = k.s;
            const n: number = k;
            const m: number = N.C;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 4:7 TS2322 Type 'typeof C' is not assignable to type 'number'.",
            "t.ts 5:7 TS2322 Type 'typeof C' is not assignable to type 'number'.",
        ))
    }

    /** probe h04y: a renaming import and a named default-exported class. */
    @Test
    fun `a class read through a renaming import is its constructor side`() {
        assert(rows("""
            // @Filename: /proj/a.ts
            export default class Foo { static s = 1; x = 1 }
            export class Named { static s = 1 }
            // @Filename: /proj/t.ts
            import D from './a';
            import { Named as R } from './a';
            const p: string = D.s;
            const n: number = D;
            const q: string = R.s;
            const m: number = R;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 4:7 TS2322 Type 'typeof Foo' is not assignable to type 'number'.",
            "t.ts 5:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 6:7 TS2322 Type 'typeof Named' is not assignable to type 'number'.",
        ))
    }

    /** census cell h17: `return A` infers the constructor side. */
    @Test
    fun `a function returning a class infers its constructor side`() {
        assert(rows("""
            class A { static s = 1; x = 1 }
            function g() { return A }
            const p: string = g().s;
            const q: number = g();
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 4:7 TS2322 Type 'typeof A' is not assignable to type 'number'.",
        ))
    }

    /** census cells c15, h10: a class expression's value; a qualified heritage base reads its instance. */
    @Test
    fun `a class expression's value is its constructor side`() {
        assert(rows("""
            const C = class { static s = 1; x = "a" };
            const p: string = C.s;
            const q: number = new C().x;
            const D = class Named { static s = 1 };
            const r: string = D.s;
            const n: number = D;
            namespace N { export class B { static t = 1 } }
            const E = class extends N.B {};
            const w: string = E.t;
        """) == listOf(
            "t.ts 2:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 3:7 TS2322 Type 'string' is not assignable to type 'number'.",
            "t.ts 5:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 6:7 TS2322 Type 'typeof Named' is not assignable to type 'number'.",
            "t.ts 9:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** corpus `exportClassExtendingIntersection` shape: a mixin keeps its `any` (tsgo: silent). */
    @Test
    fun `negative control - a mixin class expression over a type-parameter base is not checked`() {
        assert(rows("""
            type Constructor<T> = new (...args: any[]) => T;
            class MyBaseClass<T> { baseProperty!: string; constructor(value: T) {} }
            interface MyMixin { mixinProperty: string }
            export function MyMixin<T extends Constructor<MyBaseClass<any>>>(base: T): T & Constructor<MyMixin> {
                return class extends base { mixinProperty!: string };
            }
        """).isEmpty())
    }

    /** an INSTANCE-typed value named like its class (a shadowing parameter / local) keeps the instance type. */
    @Test
    fun `negative control - an instance value still reads instance members`() {
        assert(rows("""
            class A { x = 1; static s = 1 }
            declare const a: A;
            const p: string = a.x;
            function f(A: A) { const q: string = A.x; }
            function g() { const A: A = null!; const r: string = A.x; }
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 4:26 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 5:42 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** a direct `new`, a heritage clause and `instanceof` keep reading the instance. */
    @Test
    fun `negative control - new, extends and instanceof are unchanged`() {
        assert(rows("""
            class Box<T> { constructor(public v: T) {} }
            const B = Box;
            const s: number = new B<string>("x").v;
            const s2: number = new Box<string>("x").v;
            class A { x = 1 }
            class C extends A { y = 2 }
            const z: string = new C().x;
            declare const u: A | string;
            if (u instanceof A) { const w: string = u.x; }
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'string' is not assignable to type 'number'.",
            "t.ts 4:7 TS2322 Type 'string' is not assignable to type 'number'.",
            "t.ts 7:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 9:29 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }
}
