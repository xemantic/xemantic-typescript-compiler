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
 * (CHK.150) rung 3, round P18.182 — an argument is contextually typed by the overload
 * the call SELECTS, and a generic class method's `Partial<Observer<T>>` parameter is
 * read with the receiver's type arguments substituted into its callback members.
 *
 * Overloads: tsgo's `chooseOverload` (`checker.go`) filters candidates by
 * `hasCorrectArity`, then checks each in turn with the arguments contextually typed by
 * THAT candidate, instantiated through its own inference context. Our contextual-argument
 * core adopted a definitive winner on the call side only when it was NOT the first
 * overload (a round-481 byte-parity guard); a first-overload win fell to a fallback that
 * answers only when every overload is function-shaped at the position, and with no
 * mapper — so `subscribe(s: Subscriber<T>)` beside `subscribe(s, extra)` handed
 * `createOp<T>` no contextual return and its callback read `unknown` (X1).
 *
 * Property bag: `Partial<Observer<T>>` resolves to an anonymous object whose members
 * are `((value: T) => void) | undefined`; the method-parameter instantiation mapped them
 * with the plain rule, which no-ops a function-shaped constituent, so `X<string>` kept
 * the declaration's `T` in every callback member (rxjs's `Observable.subscribe`).
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW SET, measured over `build/scratch-p18182/cells`
 * (the harness's embedded lib has no `Partial`, so the prelude declares tsc's own).
 * Probes are ARGUMENTS at a `number`/`string` parameter, whose message names the
 * callback parameter's type.
 *
 * NOT PINNED (wrong today; a countdown): `r(cb: (x: string) => string)` beside `r(cb:
 * (x: number) => number)` called as `r((x) => { ps(x); return 1; })` — tsgo FIXES the
 * arrow's parameter type from the first candidate and reports TS2769 against the last;
 * we pick the second overload and report its body.
 */
class OverloadContextualArgumentTest {

    private val prelude = """
        type Partial<T> = { [P in keyof T]?: T[P] };
        interface Observer<T> { next: (value: T) => void; error: (err: any) => void; complete: () => void; }
        declare class Subscription {}
        declare class Subscriber<T> extends Subscription implements Observer<T> {
          next(value: T): void; error(err: any): void; complete(): void;
        }
        declare function createOp<T>(dest: Subscriber<any>, onNext?: (value: T) => void): Subscriber<T>;
        declare function pn(n: number): void;
        declare function ps(s: string): void;
    """.trimIndent() + "\n"

    private fun arg(type: String, param: String) =
        "2345 Argument of type '$type' is not assignable to parameter of type '$param'."

    /** Every row, as `code message`, sorted. */
    private fun rows(source: String): List<String> =
        diagnose(prelude + source).map { "${it.code} ${it.message}" }.sorted()

    @Test
    fun `an overload pair decided by arity types the argument by the first overload - X1`() {
        val rows = rows("""
            declare class X1<T> {
              subscribe(s: Subscriber<T>): Subscription;
              subscribe(s: Subscriber<T>, extra: number): Subscription;
            }
            function x1<T>(src: X1<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(arg("T", "number")))
    }

    @Test
    fun `the arity pair with a concrete receiver substitutes the receiver's argument`() {
        val rows = rows("""
            declare class X1<T> {
              subscribe(s: Subscriber<T>): Subscription;
              subscribe(s: Subscriber<T>, extra: number): Subscription;
            }
            function x1(src: X1<string>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(arg("string", "number")))
    }

    @Test
    fun `control - the arity pair declared the other way round already agreed`() {
        val rows = rows("""
            declare class X1<T> {
              subscribe(s: Subscriber<T>, extra: number): Subscription;
              subscribe(s: Subscriber<T>): Subscription;
            }
            function x1<T>(src: X1<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(arg("T", "number")))
    }

    @Test
    fun `control - a single signature is unaffected - X4`() {
        val rows = rows("""
            declare class X4<T> { subscribe(s: Subscriber<T>): Subscription; }
            function x4<T>(src: X4<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(arg("T", "number")))
    }

    @Test
    fun `control - two function-shaped overloads type the callback by the first`() {
        val rows = rows("""
            declare function g(cb: (x: string) => void): void;
            declare function g(cb: (x: number) => void, n?: number): void;
            g((x) => { pn(x); });
        """.trimIndent())
        assert(rows == listOf(arg("string", "number")))
    }

    @Test
    fun `control - the first overload failing on another argument selects the second`() {
        val rows = rows("""
            declare function q(n: number, cb: (x: number) => void): void;
            declare function q(s: string, cb: (x: string) => void): void;
            q("a", (x) => { pn(x); });
        """.trimIndent())
        assert(rows == listOf(arg("string", "number")))
    }

    @Test
    fun `a generic first overload instantiates its own type parameter from the other argument`() {
        val rows = rows("""
            declare function u<U>(cb: (x: U) => void, seed: U): void;
            declare function u(cb: (x: string) => void): void;
            u((x) => { ps(x); }, 1);
        """.trimIndent())
        assert(rows == listOf(arg("number", "string")))
    }

    @Test
    fun `a generic first overload beside a longer one is decided by arity and instantiated`() {
        val rows = rows("""
            declare function u<U>(seed: U, cb: (x: U) => void): void;
            declare function u(seed: string, cb: (x: string) => void, extra: number): void;
            u(1, (x) => { ps(x); });
        """.trimIndent())
        assert(rows == listOf(arg("number", "string")))
    }

    @Test
    fun `a mixed function and object overload set types the callback by the first`() {
        val rows = rows("""
            declare function m(cb: (x: string) => void): void;
            declare function m(o: { next: (x: string) => void }): void;
            m((x) => { pn(x); });
        """.trimIndent())
        assert(rows == listOf(arg("string", "number")))
    }

    @Test
    fun `control - a method with its own type parameter in an overload pair`() {
        val rows = rows("""
            declare class Y<T> {
              pipe<U>(f: (x: T) => U): U;
              pipe<U>(f: (x: T) => U, g: number): U;
            }
            function y(src: Y<string>) { src.pipe((x) => { pn(x); return 1; }); }
        """.trimIndent())
        assert(rows == listOf(arg("string", "number")))
    }

    @Test
    fun `control - a callback parameter through two function-shaped overloads is not implicitly any`() {
        val rows = rows("""
            declare function k(cb: (x: string) => void): void;
            declare function k(cb: (x: string) => void, n: number): void;
            k((x) => { pn(x); });
            k(function (y) { pn(y); });
        """.trimIndent())
        assert(rows == listOf(arg("string", "number"), arg("string", "number")))
    }

    @Test
    fun `control - an arrow with no contextual type keeps its TS7006`() {
        val rows = rows("""
            declare function k(cb: (x: string) => void, n: number): void;
            declare function k(o: { a: 1 }, n: number, z: string): void;
            const f = (x) => { pn(x); };
        """.trimIndent())
        assert(rows == listOf("7006 Parameter 'x' implicitly has an 'any' type."))
    }

    @Test
    fun `an object literal through an arity pair of Partial observers`() {
        val rows = rows("""
            declare class X1<T> {
              subscribe(o: Partial<Observer<T>>): Subscription;
              subscribe(o: Partial<Observer<T>>, extra: number): Subscription;
            }
            function x1<T>(src: X1<T>) { src.subscribe({ next: (v) => { pn(v); } }); }
        """.trimIndent())
        assert(rows == listOf(arg("T", "number")))
    }

    @Test
    fun `a Partial observer or function parameter of a generic class method binds the return type`() {
        val rows = rows("""
            declare class X<T> { subscribe(o?: Partial<Observer<T>> | ((value: T) => void)): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(arg("T", "number")))
    }

    @Test
    fun `rxjs's overloaded Observable subscribe binds the operator's type parameter`() {
        val rows = rows("""
            declare class Observable<T> {
              subscribe(observerOrNext?: Partial<Observer<T>> | ((value: T) => void)): Subscription;
              subscribe(next?: ((value: T) => void) | null, error?: ((error: any) => void) | null, complete?: (() => void) | null): Subscription;
            }
            function f<T>(src: Observable<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(arg("T", "number")))
    }

    @Test
    fun `rxjs's overloaded subscribe types a bare callback and an observer literal`() {
        val rows = rows("""
            declare class Observable<T> {
              subscribe(observerOrNext?: Partial<Observer<T>> | ((value: T) => void)): Subscription;
              subscribe(next?: ((value: T) => void) | null, error?: ((error: any) => void) | null, complete?: (() => void) | null): Subscription;
            }
            function f(src: Observable<string>) { src.subscribe((v) => { pn(v); }); src.subscribe({ next: (w) => { pn(w); } }); }
        """.trimIndent())
        assert(rows == listOf(arg("string", "number"), arg("string", "number")))
    }

    @Test
    fun `a Partial observer member mismatching the receiver's argument is related`() {
        val rows = rows("""
            declare class X<T> { s(o: Partial<Observer<T>>): void; }
            function f(src: X<string>) { src.s({ next: (w: number) => { pn(w); } }); }
        """.trimIndent())
        assert(rows == listOf("2322 Type '(w: number) => void' is not assignable to type '(value: string) => void'."))
    }

    @Test
    fun `a Partial bag's plain and callback members both follow the receiver`() {
        val rows = rows("""
            interface Box<T> { v: T; w: (x: T) => void; }
            declare class X<T> { s(o: Partial<Box<T>>): void; }
            function f(src: X<string>) { src.s({ v: 1 }); src.s({ w: (x) => { pn(x); } }); }
        """.trimIndent())
        assert(rows == listOf(arg("string", "number"), "2322 Type 'number' is not assignable to type 'string'.").sorted())
    }

    @Test
    fun `a Partial bag parameter of a method with its own type parameter`() {
        val rows = rows("""
            declare class X<T> { s<U>(o: Partial<Observer<T>>, u: U): U; }
            function f(src: X<string>) { const r: string = src.s({ next: (w) => { pn(w); } }, 1); }
        """.trimIndent())
        assert(rows == listOf(arg("string", "number"), "2322 Type 'number' is not assignable to type 'string'.").sorted())
    }
}
