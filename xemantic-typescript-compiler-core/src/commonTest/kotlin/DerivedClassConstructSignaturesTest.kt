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
 * (CHK.154)(b), round P18.186 — a class that DECLARES a constructor has exactly its OWN
 * construct signatures; only a class WITHOUT one inherits its base's. tsgo 7.0.2
 * `resolveDeclaredMembers` (checker.go): `getSignaturesOfSymbol(symbol.Members["__constructor"])`,
 * falling back to `getDefaultConstructSignatures` only when that list is empty.
 * `MemberResolver` used to put the base constructor(s) BESIDE the own ones, so
 * `new Sub("x")` against `constructor(o?: number)` was ACCEPTED by the base's
 * `(d?: string)` — a false negative — and an arity failure also produced an ours-only
 * TS2345 against the base signature. An INTERFACE still concatenates its bases' construct
 * signatures, as tsc does.
 *
 * A second, smaller half: tsgo `resolveNewExpression` returns `resolveErrorCall` when
 * `isConstructorAccessible` fails, so an inaccessible PRIVATE or PROTECTED constructor's
 * arguments are never checked. And the relation must then skip construct signatures on a
 * generic class INSTANCE (`Type.Reference`) as it already did on a `Type.Interface` one.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18186/cells`.
 *
 * NOT PINNED (pre-existing, unchanged): TS2673 for a private constructor is not modelled;
 * `class D extends B<string> {}` does not check `new D(1)` against the instantiated
 * inherited signature (nor does `new B<string>` without explicit type arguments on the
 * base — a class type parameter in a constructor parameter); a class EXPRESSION held in
 * a `const` is not argument-checked at all; a mixin base without an own constructor.
 */
class DerivedClassConstructSignaturesTest {

    /** Every row as `line:column code message`, sorted. */
    private fun rows(source: String): List<String> =
        diagnose(source.trimIndent())
            .map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    private fun arg(line: Int, col: Int, source: String, target: String) =
        "$line:$col 2345 Argument of type '$source' is not assignable to parameter of type '$target'."

    @Test
    fun `an own constructor rejects an argument only the base constructor accepts`() {
        val actual = rows(
            """
            class Base { constructor(d?: string) {} }
            class Sub extends Base { constructor(o?: number) { super(); } }
            new Sub("x");
            new Sub(1);
            """
        )
        assert(actual == listOf(arg(3, 9, "string", "number")))
    }

    @Test
    fun `control - a class without a constructor inherits the base constructor`() {
        val actual = rows(
            """
            class Base { constructor(d?: string) {} }
            class Sub extends Base { m() {} }
            new Sub(1);
            new Sub("x");
            """
        )
        assert(actual == listOf(arg(3, 9, "number", "string")))
    }

    @Test
    fun `a grandchild without a constructor inherits the middle class's own constructor only`() {
        val actual = rows(
            """
            class A { constructor(d?: string) {} }
            class B extends A { constructor(o?: number) { super(); } }
            class C extends B {}
            new C("x");
            new C(1);
            """
        )
        assert(actual == listOf(arg(4, 7, "string", "number")))
    }

    @Test
    fun `overloaded own constructors are the only candidates`() {
        val actual = rows(
            """
            class Base { constructor(d?: string) {} }
            class Sub extends Base {
              constructor(a: number);
              constructor(a: boolean, b: number);
              constructor(a: any, b?: any) { super(); }
            }
            new Sub("x");
            new Sub(1);
            new Sub(true, 2);
            """
        )
        assert(actual == listOf(arg(7, 9, "string", "number")))
    }

    @Test
    fun `an arity failure against the own constructor reports no argument error against the base`() {
        val actual = rows(
            """
            class Base { constructor(a: string, b: string) {} }
            class Sub extends Base { constructor(o: number) { super("a", "b"); } }
            new Sub(1, 2);
            new Sub();
            class Sub3 extends Base {}
            new Sub3("a");
            """
        )
        assert(
            actual == listOf(
                "3:12 2554 Expected 1 arguments, but got 2.",
                "4:1 2554 Expected 1 arguments, but got 0.",
                "6:1 2554 Expected 2 arguments, but got 1.",
            )
        )
    }

    @Test
    fun `ambient abstract and generic bases follow the same rule`() {
        val actual = rows(
            """
            declare class Amb { constructor(d?: string); }
            declare class AmbSub extends Amb { constructor(o?: number); }
            new AmbSub("x");
            abstract class Abs { constructor(d?: string) {} }
            class AbsSub extends Abs { constructor(o?: number) { super(); } }
            new AbsSub("x");
            class Gen<T> { constructor(x: T) {} }
            class GenSub extends Gen<string> { constructor(n: number) { super("s"); } }
            new GenSub("s");
            new GenSub(1);
            """
        )
        assert(
            actual == listOf(
                arg(3, 12, "string", "number"),
                arg(6, 12, "string", "number"),
                arg(9, 12, "string", "number"),
            )
        )
    }

    @Test
    fun `control - super arguments are checked against the base constructor`() {
        val actual = rows(
            """
            class Base { constructor(d: string) {} }
            class Sub extends Base { constructor(o: number) { super(o); } }
            class Sub2 extends Base { constructor(o: number) { super("ok"); } }
            new Sub(1);
            """
        )
        assert(actual == listOf(arg(2, 57, "number", "string")))
    }

    @Test
    fun `control - a callback argument is contextually typed by the own constructor`() {
        val actual = rows(
            """
            class Base { constructor(cb: (n: number) => void) {} }
            class Sub extends Base { constructor(cb: (s: string) => void) { super(() => {}); } }
            declare function pn(n: number): void;
            new Sub((v) => pn(v));
            class Sub2 extends Base {}
            new Sub2((v) => pn(v));
            """
        )
        assert(actual == listOf(arg(4, 19, "string", "number")))
    }

    @Test
    fun `control - the class value relates through its own construct signature`() {
        val actual = rows(
            """
            class Base { constructor(d?: string) {} }
            class Sub extends Base { constructor(o?: number) { super(); } }
            const a: new (o?: number) => Sub = Sub;
            const b: new (d: string) => Sub = Sub;
            """
        )
        assert(actual.map { it.substringBefore(" Type") } == listOf("4:7 2322"))
    }

    @Test
    fun `control - an interface still inherits its bases' construct signatures`() {
        val actual = rows(
            """
            interface I { new (x: string): object }
            interface J extends I { new (x: number): object }
            declare const j: J;
            new j("s");
            new j(1);
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `an inaccessible constructor's arguments are not checked`() {
        val actual = rows(
            """
            class A { private constructor(x: number) {} static make() { return new A(1); } }
            new A("s");
            class B { protected constructor(x: number) {} }
            class C extends B { constructor() { super(1); } m() { return new C(); } }
            new B("s");
            class P { private constructor(x: number) {} static make() { return new P("s"); } }
            """
        )
        // tsgo also reports `2:1 TS2673` (private), which is not modelled here; the pin is
        // that line 2's ARGUMENT is not checked, not that the row set is complete.
        val argRowsOnLine2 = actual.filter { it.startsWith("2:") && " 2345 " in it }
        assert(argRowsOnLine2.isEmpty())
        assert(actual.filterNot { it.startsWith("2:") } == listOf(
            "5:1 2674 Constructor of class 'B' is protected and only accessible within the class declaration.",
            arg(6, 74, "string", "number"),
        ))
    }

    /**
     * A generic class INSTANCE is a `Type.Reference`; the relation must not compare the
     * construct signatures this checker hangs on an instance type ((CHK.73)), or a
     * derived instance with its own constructor stops relating to its base instance
     * (rxjs `SafeSubscriber<T>` against `Subscriber<T>`).
     */
    @Test
    fun `a derived generic instance with its own constructor still relates to the base instance`() {
        val actual = rows(
            """
            class Base<T> { constructor(d?: string) {} v?: T }
            class Sub<T> extends Base<T> { constructor(o?: number) { super(); } }
            declare const s: Sub<number>;
            const b: Base<number> = s;
            class A<T> { m(): Base<T> { return new Base<T>(); } }
            class B<T> extends A<T> { m(): Sub<T> { return new Sub<T>(); } }
            const bad: Base<number> = new Base<string>();
            """
        )
        assert(actual == listOf("7:7 2322 Type 'Base<string>' is not assignable to type 'Base<number>'."))
    }

    /**
     * The construct-signature comparison on a generic class instance used to reject an
     * interface source by accident (the source has no constructor); without it the
     * relation needs tsgo's own rule — exactly one side `private`, different
     * declarations, unrelated (`propertyRelatedTo`, relater.go). Non-generic and
     * non-constructor shapes were missing it before as well.
     */
    @Test
    fun `a private target member is not satisfied by a public source member`() {
        val actual = diagnose(
            """
            interface Pub<T> { one: T }
            declare const p: Pub<number>;
            class CP<T> { constructor(private one: T) {} }
            let x: CP<number> = new CP(1);
            x = p;
            interface Pub2 { one: number }
            declare const p2: Pub2;
            class CP2 { constructor(private one: number) {} }
            let y: CP2 = new CP2(1);
            y = p2;
            class CQ<T> { private one!: T }
            let z: CQ<number> = new CQ<number>();
            z = p;
            class S { private one = 1 }
            const q: Pub2 = new S();
            class T2 { private one = 1 }
            class U extends T2 {}
            const r: T2 = new U();
            """.trimIndent()
        ).map { d -> "${d.line}:${d.character} ${d.code} ${d.message}" + d.messageChain.joinToString("") { " /$it" } }
            .sorted()
        assert(
            actual == listOf(
                "10:1 2322 Type 'Pub2' is not assignable to type 'CP2'. /  Property 'one' is private in type 'CP2' but not in type 'Pub2'.",
                "13:1 2322 Type 'Pub<number>' is not assignable to type 'CQ<number>'. /  Property 'one' is private in type 'CQ<number>' but not in type 'Pub<number>'.",
                "15:7 2322 Type 'S' is not assignable to type 'Pub2'. /  Property 'one' is private in type 'S' but not in type 'Pub2'.",
                "5:1 2322 Type 'Pub<number>' is not assignable to type 'CP<number>'. /  Property 'one' is private in type 'CP<number>' but not in type 'Pub<number>'.",
            )
        )
    }
}
