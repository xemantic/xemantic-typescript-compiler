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
 * (CHK.196) stage 3, part 1 (P18.259) — a union of class CONSTRUCTOR types. The array-literal
 * builder merged two distinct constructor types through the TS2403 identity test, which reads
 * no construct signature and keys its cycle stack by the class symbol a constructor type shares
 * with its own `prototype`, so `[A, B]` kept `typeof A`; it now applies tsgo's subtype reduction
 * to the constructor types instead ([ClassConstructorTypes.reduceConstructorSubtypes]). The
 * relation refuses an abstract construct signature against a non-abstract one, the argument
 * gate admits a constructor type against a constructor-typed parameter, and a missing required
 * static is the TS2741 head. Every expectation is tsgo 7.0.2's output over the same source
 * (cells under `build/bench/p18259-agent`).
 */
class ConstructorUnionReductionTest {

    private val es2022 = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(source: String): List<String> =
        diagnose(source, es2022).flatMap { d ->
            listOf("${d.line}:${d.character} TS${d.code} ${d.message}") + d.messageChain.map { "  | ${it.trim()}" }
        }

    private val classes = """
        class Co { }
        abstract class Ab { }
        class A1 { constructor(x: number) {} }
        class B1 { constructor(s: string) {} }

    """.trimIndent()

    /** cells e1 / p02: construct signatures that differ keep both members; `new` combines them. */
    @Test
    fun `an array literal of two classes whose constructors differ keeps both constructor types`() {
        assert(rows(classes + """
            const arr = [A1, B1];
            const z: string = arr;
            new arr[0](1);
        """.trimIndent()) == listOf(
            "6:7 TS2322 Type '(typeof A1 | typeof B1)[]' is not assignable to type 'string'.",
            "7:12 TS2345 Argument of type '1' is not assignable to parameter of type 'never'.",
        ))
    }

    /** cells d03 / d06 / d05: a strict subtype is dropped, unrelated ones stay. */
    @Test
    fun `the element union drops a constructor type that is a strict subtype of another`() {
        assert(rows("""
            class A { constructor(x?: number) {} }
            class C extends A { c = 1 }
            class D extends A { static sd = 1 }
            class Co { }
            abstract class Ab { }
            const p1: number = [C, A];
            const p2: number = [Co, Ab];
            const p3: number = [D, C];
        """) == listOf(
            "6:7 TS2322 Type '(typeof A)[]' is not assignable to type 'number'.",
            "7:7 TS2322 Type '(typeof Ab)[]' is not assignable to type 'number'.",
            "8:7 TS2322 Type '(typeof C | typeof D)[]' is not assignable to type 'number'.",
        ))
    }

    /** cells r14 / r15: STRICT arity — the constructor with more parameters is the supertype in both orders. */
    @Test
    fun `the strict-arity rule keeps the constructor with more parameters in both orders`() {
        assert(rows("""
            class O { constructor(x?: number) {} }
            class Co { }
            const p1: number = [O, Co];
            const p2: number = [Co, O];
        """) == listOf(
            "3:7 TS2322 Type '(typeof O)[]' is not assignable to type 'number'.",
            "4:7 TS2322 Type '(typeof O)[]' is not assignable to type 'number'.",
        ))
        // Declared the other way round (cell s1), which is what separates the arity rule from
        // union order: the two are mutually assignable, so without it the later one is dropped.
        assert(rows("""
            class Co { }
            class O { constructor(x?: number) {} }
            const p1: number = [O, Co];
            const p2: number = [Co, O];
        """) == listOf(
            "3:7 TS2322 Type '(typeof O)[]' is not assignable to type 'number'.",
            "4:7 TS2322 Type '(typeof O)[]' is not assignable to type 'number'.",
        ))
    }

    /** cell p01: the reduced element is abstract, so `new` of an element is TS2511. */
    @Test
    fun `new of an element of a reduced array of an abstract and a concrete class is TS2511`() {
        assert(rows(classes + """
            const arr = [Co, Ab];
            new arr[0]();
            const ok = [Co, A1];
            new ok[0](1);
        """.trimIndent()) == listOf(
            "6:1 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    /** cell p10: two identical classes reduce to the one declared first. */
    @Test
    fun `two structurally identical classes reduce to the first declared`() {
        assert(rows("""
            class Sx { static s = 1 }
            class Sy { static s = 1 }
            const p: number = [Sy, Sx];
        """) == listOf(
            "3:7 TS2322 Type '(typeof Sx)[]' is not assignable to type 'number'.",
        ))
    }

    /** cells r02 / p07 / p05: the abstract-to-non-abstract rule, at a declaration and through an alias. */
    @Test
    fun `an abstract constructor type is not assignable to a non-abstract one`() {
        assert(rows(classes + """
            const x: typeof Co = Ab;
            const y: new () => Co = Ab;
            type Ctor<T> = new () => T;
            const k: Ctor<Ab> = Ab;
        """.trimIndent()) == listOf(
            "5:7 TS2322 Type 'typeof Ab' is not assignable to type 'typeof Co'.",
            "  | Cannot assign an abstract constructor type to a non-abstract constructor type.",
            "6:7 TS2322 Type 'typeof Ab' is not assignable to type 'new () => Co'.",
            "  | Cannot assign an abstract constructor type to a non-abstract constructor type.",
            "8:7 TS2322 Type 'typeof Ab' is not assignable to type 'Ctor<Ab>'.",
            "  | Cannot assign an abstract constructor type to a non-abstract constructor type.",
        ))
    }

    /** cells p03 / p04 / c05: `abstract new` accepts both — through a generic and an instantiated alias. */
    @Test
    fun `negative control - an abstract construct signature target accepts abstract and concrete classes`() {
        assert(rows(classes + """
            function g<T>(c: abstract new () => T) {}
            g(Ab); g(Co);
            type Ctor<T> = abstract new () => T;
            const k: Ctor<Ab> = Ab;
            const k2: Ctor<Co> = Co;
            const z: abstract new () => Ab = Co;
        """.trimIndent()).isEmpty())
    }

    /** cells s2 / s7: instantiating a generic member keeps its construct signature abstract
     *  (`TypeInstantiator`), so an abstract class still satisfies it. */
    @Test
    fun `negative control - an instantiated generic abstract construct signature stays abstract`() {
        assert(rows("""
            abstract class Ab { x = 1 }
            class Co { x = 1 }
            interface F<T> { make: abstract new () => T }
            const f: F<Ab> = { make: Ab };
            const f2: F<Co> = { make: Ab };
            interface G<T> { make(c: abstract new () => T): void }
            declare const g: G<Ab>;
            g.make(Ab);
        """).isEmpty())
    }

    /** cells c04 / c05 / p06 / p11: a class value against a constructor-typed parameter. */
    @Test
    fun `a class value is checked against a constructor-typed parameter`() {
        val d = diagnose(classes + """
            class A { a = 1 }
            class B { b = 1 }
            function f(c: new () => A) {}
            f(B); f(Ab); f(A);
            function k(c: typeof Co) {}
            k(Ab); k(A1);
        """.trimIndent(), es2022)
        assert(rows(classes + """
            class A { a = 1 }
            class B { b = 1 }
            function f(c: new () => A) {}
            f(B); f(Ab); f(A);
            function k(c: typeof Co) {}
            k(Ab); k(A1);
        """.trimIndent()) == listOf(
            "8:3 TS2345 Argument of type 'typeof B' is not assignable to parameter of type 'new () => A'.",
            "  | Property 'a' is missing in type 'B' but required in type 'A'.",
            "8:9 TS2345 Argument of type 'typeof Ab' is not assignable to parameter of type 'new () => A'.",
            "  | Cannot assign an abstract constructor type to a non-abstract constructor type.",
            "10:3 TS2345 Argument of type 'typeof Ab' is not assignable to parameter of type 'typeof Co'.",
            "  | Cannot assign an abstract constructor type to a non-abstract constructor type.",
            "10:10 TS2345 Argument of type 'typeof A1' is not assignable to parameter of type 'typeof Co'.",
            "  | Target signature provides too few arguments. Expected 1 or more, but got 0.",
        ))
        // tsgo's `elaborateDidYouMeanToCallOrConstruct`: `new B()` does not satisfy a constructor parameter.
        val related = d.flatMap { it.relatedInformation }.map { it.code }
        assert(6213 !in related)
    }

    /** cells b01 / c03: a missing required static is the TS2741 head, before any construct signature. */
    @Test
    fun `a class value missing a required static is TS2741 at a declaration and an argument`() {
        assert(rows("""
            class A { a = 1; static sa = 1; constructor(x?: number) {} }
            class B { b = 1; static sb = 1; constructor(x?: number) {} }
            const x: typeof A = B;
            function f(c: typeof A) {}
            f(B);
        """) == listOf(
            "3:7 TS2741 Property 'sa' is missing in type 'typeof B' but required in type 'typeof A'.",
            "5:3 TS2741 Property 'sa' is missing in type 'typeof B' but required in type 'typeof A'.",
        ))
    }

    /** cell b04: a baseless source instance's missing member is the chain line itself (a derived
     *  source, r07, keeps tsgo's intermediate line but not its third — a residue, not pinned). */
    @Test
    fun `a construct signature return mismatch elaborates the missing member of a baseless instance`() {
        assert(rows("""
            class A { constructor(x?: number) {} }
            class C extends A { c = 1 }
            const x: typeof C = A;
        """) == listOf(
            "3:7 TS2322 Type 'typeof A' is not assignable to type 'typeof C'.",
            "  | Property 'c' is missing in type 'A' but required in type 'C'.",
        ))
    }

    /** cell e3 (rxjs `Action<T> extends Subscription`): statics are inherited through a GENERIC
     *  base, so the derived constructor type relates and the element union keeps the base. */
    @Test
    fun `statics inherited through a generic base keep a derived constructor type assignable`() {
        assert(rows("""
            class Sub { static EMPTY = 1; x = 1 }
            class Act<T> extends Sub { constructor(s: number) { super(); } }
            class AsyncAct<T> extends Act<T> { constructor(s: number) { super(s); } }
            const q: typeof Act = AsyncAct;
            function f(c: typeof Act) {} f(AsyncAct);
            const n: number = AsyncAct.EMPTY;
            const p: number = [AsyncAct, Act];
        """) == listOf(
            "7:7 TS2322 Type '(typeof Act)[]' is not assignable to type 'number'.",
        ))
    }

    /** cell e4 (rxjs `VirtualAction` against `typeof AsyncAction`): a CONSTRUCTOR-declared target
     *  compares parameters bivariantly; a construct signature TYPE stays strict. */
    @Test
    fun `constructor declared parameters compare bivariantly and a constructor type literal strictly`() {
        assert(rows("""
            class AS2 { a = 1 }
            class VSched extends AS2 { v = 1 }
            class AAct { constructor(scheduler: AS2, n: number) {} }
            class VAct extends AAct { constructor(scheduler: VSched, n: number) { super(scheduler, n); } }
            function f(c: typeof AAct) {} f(VAct);
            const k: typeof AAct = VAct;
            const z: new (s: AS2, n: number) => AAct = VAct;
        """).map { it.substringBefore(" Type '") } == listOf(
            "7:7 TS2322",
            "  | Types of parameters 'scheduler' and 's' are incompatible.",
            "  |",
        ))
    }
}
