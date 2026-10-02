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
 * (CHK.196) (P18.258) — the residues of the `new` port: a constructor-less class's INHERITED
 * construct signature instantiated through the heritage type arguments
 * ([ClassConstructorTypes.inheritedConstructSignatures]); the zero-argument default of an
 * extending class whose base constructs with none; TS2511 owned by the type-based check for
 * every callee (a union of constructor types included), the name-based walker kept as the
 * fallback; the TS2339 `new` receiver through any callee, a class expression and an anonymous
 * `export default class` import ([NameResolver.anonymousDefaultClassSymbol]). Every
 * expectation is tsgo 7.0.2's output over the same source (cells under
 * `build/bench/p18258-agent/cells`).
 */
class ConstructorSideResiduesTest {

    private val es2022 = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(source: String, directives: String = es2022): List<String> =
        diagnose(source, directives).map {
            "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} TS${it.code} ${it.message}"
        }

    /** cell n05 / q1: the base's `v: T` is `number` through `extends G<number>`. */
    @Test
    fun `a constructor-less class extending a generic base checks its arguments against the instantiated parameter`() {
        assert(rows("""
            class G<T> { constructor(v: T) {} }
            class H extends G<number> {}
            new H("s");
            new H();
            const c = H;
            new c("s");
        """) == listOf(
            "t.ts 3:7 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "t.ts 4:1 TS2554 Expected 1 arguments, but got 0.",
            "t.ts 6:7 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
    }

    /** cell q2: the mapper composes down the chain and through a generic intermediate. */
    @Test
    fun `the inherited parameter is instantiated through a multi-level chain and explicit type arguments`() {
        assert(rows("""
            class G<T> { constructor(v: T, w?: string) {} }
            class H extends G<number> {}
            class I extends H {}
            new I("s");
            new I(1, 2);
            class K<U> extends G<U> {}
            new K<string>(1);
            class L extends K<boolean> {}
            new L(1);
            class R<T> { constructor(...xs: T[]) {} }
            class S extends R<number> {}
            new S(1, "a");
        """) == listOf(
            "t.ts 4:7 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "t.ts 5:10 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            "t.ts 7:15 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            "t.ts 9:7 TS2345 Argument of type 'number' is not assignable to parameter of type 'boolean'.",
            "t.ts 12:10 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
    }

    /** Controls: a legal call, and a generic subclass without explicit type arguments (tsgo
     *  infers `U`; this checker does not, so it must stay silent rather than guess). */
    @Test
    fun `negative control - legal and uninferred calls through an inherited generic constructor are silent`() {
        assert(rows("""
            class G<T> { constructor(v: T) {} }
            class H extends G<number> {}
            new H(1);
            class K<U> extends G<U> {}
            new K("s");
            new K(1);
        """).isEmpty())
    }

    /** cell q3: an OVERLOADED inherited constructor stays with B264 — reported once each (the
     *  ordinary check draws `b: number`'s row first, which B264 used to redraw). */
    @Test
    fun `an overloaded inherited generic constructor is reported once per call`() {
        assert(rows("""
            class M<T> { constructor(a: T); constructor(a: T, b: number); constructor(a: any, b?: any) {} }
            class N extends M<string> {}
            new N(1);
            new N("s", "t");
        """).sorted() == listOf(
            "t.ts 3:7 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            "t.ts 4:12 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
    }

    /** cell d4 (census g7): tsgo's `getDefaultConstructSignatures` over a base constructor
     *  type with no construct signature, and over a constructor-less class base. */
    @Test
    fun `an extending class whose base constructs with no arguments takes none`() {
        assert(rows("""
            declare const Base: any;
            class D extends Base {}
            const d = D;
            new d(1, 2);
            new (D)(1, 2);
            new D(1);
            class B0 {}
            class G extends B0 {}
            const g = G;
            new g(1);
            const e2 = class extends B0 {};
            new e2(1);
            class H extends D {}
            new H(1);
        """) == listOf(
            "t.ts 4:7 TS2554 Expected 0 arguments, but got 2.",
            "t.ts 5:9 TS2554 Expected 0 arguments, but got 2.",
            "t.ts 6:7 TS2554 Expected 0 arguments, but got 1.",
            "t.ts 10:7 TS2554 Expected 0 arguments, but got 1.",
            "t.ts 12:8 TS2554 Expected 0 arguments, but got 1.",
            "t.ts 14:7 TS2554 Expected 0 arguments, but got 1.",
        ))
    }

    /** A base typed by a construct signature keeps its own arity. (tsgo also counts an
     *  `any`-typed PARAMETER base — `function f(Base: any) { class In extends Base {} new In(1) }`
     *  is TS2554 there — which this checker leaves silent: only a FILE-LEVEL `any`-annotated
     *  variable is trusted.) */
    @Test
    fun `negative control - an extending class whose base has a construct signature is not zero-argument`() {
        assert(rows("""
            interface IC { new (a: string): { z: number } }
            declare const ic: IC;
            class J extends ic {}
            new J("s");
        """).isEmpty())
    }

    /** cell a1: the type-based check owns a bare identifier, so the arguments of an abstract
     *  `new` are not checked (tsgo `resolveErrorCall`). */
    @Test
    fun `an abstract class new through an identifier reports TS2511 alone`() {
        assert(rows("""
            abstract class Ab { constructor(x: string) {} }
            new Ab(1);
            const c = Ab;
            new c(1);
            function f(t: typeof Ab) { new t(); }
        """) == listOf(
            "t.ts 2:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 4:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 5:28 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    /** `abstractClassUnionInstantiation`: a union of constructor types with an abstract one. */
    @Test
    fun `a union of constructor types carrying an abstract class is TS2511`() {
        assert(rows("""
            class ConcreteA {}
            abstract class AbstractA { a = "" }
            declare const u: typeof ConcreteA | typeof AbstractA;
            new u();
            declare const w: typeof ConcreteA;
            new w();
            [ConcreteA, AbstractA].map(cls => new cls());
        """) == listOf(
            "t.ts 4:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 7:35 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    /** cells a2c / a2d: callees the name-based walker cannot see — a variable initialized by
     *  a call, a property of a union of constructor types — reach TS2511 through the type. */
    @Test
    fun `TS2511 is decided from the callee type where no name says abstract`() {
        assert(rows("""
            abstract class Ab { constructor(x: string) {} }
            class Co { constructor(x: string) {} }
            declare function mk(): typeof Ab;
            const k = mk();
            new k(1);
            declare function pick(): typeof Co | typeof Ab;
            const k2 = pick();
            new k2(1);
            declare const o: { c: typeof Co | typeof Ab; d: typeof Co };
            new o.c("s");
            new o.d("s");
        """) == listOf(
            "t.ts 5:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 8:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 10:1 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    /** The walker stays for a callee no type reaches: a block-scoped class (B83.5). */
    @Test
    fun `a block-scoped abstract class is still TS2511`() {
        assert(rows("""
            (() => {
                abstract class A {}
                class B extends A {}
                new A();
                new B();
            })()
        """) == listOf("t.ts 4:5 TS2511 Cannot create an instance of an abstract class."))
    }

    /** cell r1: a `new` receiver's class through any callee and a class expression. */
    @Test
    fun `a missing member of an instance built through a property element or class expression callee is TS2339`() {
        assert(rows("""
            class A { x = 1 }
            const o = { A };
            new o.A().nope;
            const arr = [A];
            new arr[0]().nope;
            const e = class { y = 1 };
            new e().nope;
            const e2 = class Named { y = 1 };
            new e2().nope;
        """) == listOf(
            "t.ts 3:11 TS2339 Property 'nope' does not exist on type 'A'.",
            "t.ts 5:14 TS2339 Property 'nope' does not exist on type 'A'.",
            "t.ts 7:9 TS2339 Property 'nope' does not exist on type 'e'.",
            "t.ts 9:10 TS2339 Property 'nope' does not exist on type 'Named'.",
        ))
    }

    /** cell r5: inherited members, statics (TS2576) and a generic class expression. */
    @Test
    fun `a class expression receiver reads its base and its statics`() {
        assert(rows("""
            class Base { b = 1; m() {} }
            const e = class extends Base { y = 1 };
            new e().b; new e().m(); new e().y; new e().toString();
            const ix = class { [k: string]: number };
            new ix().anything;
            const st = class { static s = 1; i = 1 };
            new st().s;
            const g = class<T> { constructor(public v: T) {} };
            new g(1).v;
        """) == listOf(
            "t.ts 7:10 TS2576 Property 's' does not exist on type 'st'. Did you mean to access the static member 'st.s' instead?",
        ))
    }

    /** cell r4: tsgo prints the instantiated receiver, `unknown` only where nothing inferred. */
    @Test
    fun `a generic new receiver names its instantiation`() {
        assert(rows("""
            class Bx<T> { constructor(public v: T) {} }
            new Bx(1).nope;
            new Bx<string>("s").nope;
            class By<T> { v!: T }
            new By().nope;
        """) == listOf(
            "t.ts 2:11 TS2339 Property 'nope' does not exist on type 'Bx<number>'.",
            "t.ts 3:21 TS2339 Property 'nope' does not exist on type 'Bx<string>'.",
            "t.ts 5:10 TS2339 Property 'nope' does not exist on type 'By<unknown>'.",
        ))
    }

    /** cells n19 / r2 / r3: an anonymous `export default class` import was `any`. */
    @Test
    fun `an anonymous default-exported class is imported as the class named default`() {
        val r = rows("""
            // @Filename: a.ts
            export default class { y = 1 }
            // @Filename: t.ts
            import D from "./a";
            new D().nope;
            const s: string = new D().y;
            const c: string = D;
            new D(1);
        """)
        assert(r == listOf(
            "t.ts 2:9 TS2339 Property 'nope' does not exist on type 'default'.",
            "t.ts 3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 4:7 TS2322 Type 'typeof default' is not assignable to type 'string'.",
            "t.ts 5:7 TS2554 Expected 0 arguments, but got 1.",
        ))
    }

    /** `newAbstractInstance2` through the type, a generic default with a base, and a
     *  `export { default } from` re-export. */
    @Test
    fun `an anonymous default class reaches abstractness generics a base and a re-export`() {
        val r = rows("""
            // @Filename: a.ts
            export default abstract class { y = 1 }
            // @Filename: b.ts
            export class Base { b = 1 }
            export default class<T> extends Base { constructor(public v: T) { super(); } }
            // @Filename: c.ts
            export { default } from "./b";
            // @Filename: t.ts
            import A from "./a";
            import B from "./b";
            import C from "./c";
            new A();
            const s1: string = new B(1).v;
            const s2: string = new B(1).b;
            new B(1).nope;
            const s3: string = new C(1).v;
        """)
        assert(r == listOf(
            "t.ts 4:1 TS2511 Cannot create an instance of an abstract class.",
            "t.ts 5:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 6:7 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 7:10 TS2339 Property 'nope' does not exist on type 'default<number>'.",
            "t.ts 8:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** b14: the right operand of `instanceof` reads the constructor side; narrowing is
     *  unchanged. */
    @Test
    fun `instanceof narrowing through a class operand is unchanged`() {
        assert(rows("""
            class A { a = 1 } class B { b = "" }
            declare const x: A | B;
            if (x instanceof A) { const s: string = x.a; } else { const n: number = x.b; }
            abstract class Ab { z = 1 }
            declare const y: Ab | string;
            if (y instanceof Ab) { const s4: string = y.z; }
            const r = A;
            if (x instanceof r) { const s5: string = x.a; }
        """) == listOf(
            "t.ts 3:29 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 3:61 TS2322 Type 'string' is not assignable to type 'number'.",
            "t.ts 6:30 TS2322 Type 'number' is not assignable to type 'string'.",
            "t.ts 8:29 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }
}
