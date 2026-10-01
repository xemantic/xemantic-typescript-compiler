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
 * (CHK.196) (P18.256) — a `new` callee is an ordinary VALUE read: a class identifier answers
 * the class's constructor side ([ClassConstructorTypes]) like every other read of it, and the
 * `new`-expression readers take the construct signatures — and the class they construct —
 * off that constructor type (`Checker.getReturnTypeOfNewExpression`,
 * `constructSignaturesForNewCtx`, `checkSingleNewExpressionTypesCore`,
 * `inferSimpleReturnTypeFromBody`). Every expectation is tsgo 7.0.2's output over the same
 * source (matrix cells under `build/bench/p18256-agent/cells`).
 */
class NewExpressionConstructorSideTest {

    private val es2022 = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(source: String, directives: String = es2022): List<String> =
        diagnose(source, directives).map {
            "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} TS${it.code} ${it.message}"
        }

    /** cells n04 / n14: the implicit zero-argument constructor of a heritage-free class. */
    @Test
    fun `a constructor-less class takes no arguments through a variable a parameter and parentheses`() {
        assert(rows("""
            class C { x = 1 }
            new C(1);
            const c = C;
            new c(1);
            function f(t: typeof C) { new t(1); }
            new (C)(1);
        """) == listOf(
            "t.ts 2:7 TS2554 Expected 0 arguments, but got 1.",
            "t.ts 4:7 TS2554 Expected 0 arguments, but got 1.",
            "t.ts 5:33 TS2554 Expected 0 arguments, but got 1.",
            "t.ts 6:9 TS2554 Expected 0 arguments, but got 1.",
        ))
    }

    /** A class that extends something inherits the base's constructor, not a zero-argument
     *  default. */
    @Test
    fun `negative control - a constructor-less derived class takes its base's arguments`() {
        assert(rows("""
            class B0 { constructor(a: number) {} }
            class D extends B0 {}
            const d = D;
            new d(1);
            new (D)(1);
        """).isEmpty())
    }

    /** cells n06 / n18 / census c25: TS2673 was not modelled at all. */
    @Test
    fun `a private constructor used outside its class is TS2673`() {
        assert(rows("""
            class P { private constructor() {} static make() { return new P(); } }
            new P();
            const c = P;
            new c();
        """) == listOf(
            "t.ts 2:1 TS2673 Constructor of class 'P' is private and only accessible within the class declaration.",
            "t.ts 4:1 TS2673 Constructor of class 'P' is private and only accessible within the class declaration.",
        ))
    }

    /** cell n18: a module file's class is in no `globals`; it is read off the constructor side. */
    @Test
    fun `a private or protected constructor is checked in a module file`() {
        assert(rows("""
            export {};
            class P { private constructor() {} }
            new P();
            const c = P;
            new c();
            class Q { protected constructor() {} }
            function f(t: typeof Q) { return new t(); }
        """) == listOf(
            "t.ts 3:1 TS2673 Constructor of class 'P' is private and only accessible within the class declaration.",
            "t.ts 5:1 TS2673 Constructor of class 'P' is private and only accessible within the class declaration.",
            "t.ts 7:34 TS2674 Constructor of class 'Q' is protected and only accessible within the class declaration.",
        ))
    }

    /** cells n07 / c31 / n19: a missing member on a `new <variable>()` receiver. */
    @Test
    fun `a missing member of an instance built through a variable or parameter is TS2339 on the class`() {
        assert(rows("""
            class A { x = 1; static s = 1 }
            const c = A;
            new c().nope;
            function f(t: typeof A) { new t().nope; }
            new A().nope;
        """) == listOf(
            "t.ts 3:9 TS2339 Property 'nope' does not exist on type 'A'.",
            "t.ts 4:35 TS2339 Property 'nope' does not exist on type 'A'.",
            "t.ts 5:9 TS2339 Property 'nope' does not exist on type 'A'.",
        ))
    }

    @Test
    fun `a missing member of an instance built through a renaming import names the class`() {
        val r = diagnose("""
            // @Filename: a.ts
            export class A { x = 1 }
            // @Filename: t.ts
            import { A as B } from "./a";
            new B().nope;
        """, es2022).map { "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(r == listOf("t.ts 2:9 TS2339 Property 'nope' does not exist on type 'A'."))
    }

    /** cell n17: TS2511 off the constructor type's abstract construct signature. */
    @Test
    fun `an abstract class reached through a qualified property element or parenthesized callee is TS2511`() {
        assert(rows("""
            abstract class Ab { x = 1 }
            namespace N { export abstract class Ab2 {} }
            new N.Ab2();
            const o = { Ab };
            new o.Ab();
            const arr = [Ab];
            new arr[0]();
            declare const k: typeof Ab | undefined;
            new k!();
            new (Ab)();
        """) == listOf(
            "t.ts 3:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 5:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 7:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 9:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 10:1 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    /** The abstract flag sits on a DECLARED constructor's signature too, and tsgo stops at
     *  TS2511 without checking the arguments. */
    @Test
    fun `an abstract class with a constructor is TS2511 and its arguments are not checked`() {
        assert(rows("""
            abstract class Ac { constructor(n: number) {} }
            const o = { Ac };
            new o.Ac(1);
            new (Ac)("s");
        """) == listOf(
            "t.ts 3:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 4:1 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    @Test
    fun `negative control - a concrete class through the same callees is not TS2511`() {
        assert(rows("""
            class Co { x = 1 }
            namespace N { export class Co2 {} }
            new N.Co2();
            const o = { Co };
            new o.Co();
            const arr = [Co];
            new arr[0]();
            new (Co)();
        """).isEmpty())
    }

    /** cell n22: a union of class INSTANCES at a `new` is unconstructable — B60.15's class
     *  refusal (a class value used to type as its instance) suppressed both rows. */
    @Test
    fun `a union of class instances at a new is TS2351 with the constituent chain`() {
        val r = diagnose("""
            class A { x = 1 }
            class B { y = 1 }
            declare const u: A | B;
            new u();
            declare const v: A | (new () => B);
            new v();
        """, es2022).map { "${it.line}:${it.character} TS${it.code} ${it.message} ${it.messageChain}" }
        assert(r == listOf(
            "4:5 TS2351 This expression is not constructable. [  No constituent of type 'A | B' is constructable.]",
            "6:5 TS2351 This expression is not constructable. [  Not all constituents of type 'A | (new () => B)' are constructable.,     Type 'A' has no construct signatures.]",
        ))
    }

    @Test
    fun `negative control - a union of classes read as values constructs`() {
        assert(rows("""
            class A { x = 1 }
            class B { y = 1 }
            const arr = [A, B];
            for (const c of arr) new c();
            declare const w: typeof A | typeof B;
            new w();
        """).isEmpty())
    }

    /** cell n20: the class's type parameters reach the contextual argument types. */
    @Test
    fun `a constructor-typed callee contextually types a callback through the class's type parameters`() {
        assert(rows("""
            class Box<T> { constructor(public v: T, cb: (x: T) => void) {} }
            const B = Box;
            new B(1, (x) => { const s: string = x; });
            new Box(1, (x) => { const s: string = x; });
            function f(t: typeof Box) { new t<number>(1, (x) => { const s: string = x; }); }
        """) == listOf(
            "t.ts 3:25 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 4:27 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 5:61 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** cells n03 / n16: inference, explicit type arguments and the uninferred default. */
    @Test
    fun `new through a class identifier and a class-valued variable instantiates the class`() {
        assert(rows("""
            class Box<T> { constructor(public v: T) {} }
            const s1: string = new Box(1).v;
            const s2: string = new Box<number>(1).v;
            const b = Box;
            const s3: string = new b(1).v;
            class D<T = number> { constructor() {} v!: T }
            const s4: string = new D().v;
        """) == listOf(
            "t.ts 2:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 5:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 7:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** cell n21 / corpus `genericCloneReturnTypes2`: a member returning `new L<T>()` of its
     *  own generic class infers `L<T>`, instantiated through the receiver. */
    @Test
    fun `a generic method returning a new instance of its own class infers the instantiation`() {
        assert(rows("""
            class L<T> { m() { return new L<T>(); } v!: T }
            declare var l: L<string>;
            const z: number = l.m();
            const w: number = l.m().v;
        """) == listOf(
            "t.ts 3:7 TS2322 Type 'L<string>' is not assignable to type 'number'.",
            "t.ts 4:7 TS2322 Type 'string' is not assignable to type 'number'.",
        ))
    }
}
