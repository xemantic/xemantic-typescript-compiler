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
 * (CHK.196) stage-3 residues (P18.262): an anonymous construct type (`abstract new () => T`,
 * `new () => St`) is a construct SOURCE like a class constructor type — at the argument gate and
 * for the missing-static TS2741 head ([ClassConstructorTypes.isConstructSource]); a generic class
 * infers `T` from a `() => T` constructor parameter's argument; `cond ? A : B` is subtype-reduced
 * like an array literal; and a class constructor mismatch elaborates through its construct
 * signature rather than through `prototype`. Every expectation is tsgo 7.0.2's output over the
 * same source (`build/bench/p18262-agent/pins`).
 */
class ConstructSourceResiduesTest {

    private val es2022 = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(source: String): List<String> =
        diagnose(source, es2022).flatMap { d ->
            listOf("${d.line}:${d.character} TS${d.code} ${d.message}") + d.messageChain.map { "  | ${it.trim()}" }
        }

    /** cells t05 / a04 / a05 / a06: an anonymous construct type (incl. an instantiated `abstract new () => T` member) reaches the argument relation. */
    @Test
    fun `an anonymous construct type is checked against a constructor-typed parameter`() {
        assert(rows("""
            abstract class Ab { x = 1 }
            class Co { x = 1 }
            class Ot { y = 1 }
            class H<T> { c!: abstract new () => T }
            declare const h: H<Co>;
            declare function take(c: new () => Co): void;
            take(h.c);
            declare const o: new () => Ot;
            take(o);
            declare const s: new (x: string) => Co;
            declare function take1(c: new (x: number) => Co): void;
            take1(s);
            declare const two: new (x: string, y: number) => Co;
            take(two);
        """.trimIndent()) == listOf(
            "7:6 TS2345 Argument of type 'abstract new () => Co' is not assignable to parameter of type 'new () => Co'.",
            "  | Cannot assign an abstract constructor type to a non-abstract constructor type.",
            "9:6 TS2345 Argument of type 'new () => Ot' is not assignable to parameter of type 'new () => Co'.",
            "  | Property 'x' is missing in type 'Ot' but required in type 'Co'.",
            "12:7 TS2345 Argument of type 'new (x: string) => Co' is not assignable to parameter of type 'new (x: number) => Co'.",
            "  | Types of parameters 'x' and 'x' are incompatible.",
            "  | Type 'number' is not assignable to type 'string'.",
            "14:6 TS2345 Argument of type 'new (x: string, y: number) => Co' is not assignable to parameter of type 'new () => Co'.",
            "  | Target signature provides too few arguments. Expected 2 or more, but got 0.",
        ))
    }

    /** controls: a matching construct type, one with an extra static, an abstract target, and a GENERIC source signature (tsgo instantiates it; admitting it read a false TS2345). */
    @Test
    fun `legal construct-type arguments stay silent - including a generic construct signature`() {
        assert(rows("""
            class Co { x = 1 }
            declare function take(c: new () => Co): void;
            declare const c: new () => Co;
            take(c);
            declare const st: { new (): Co; s: number };
            take(st);
            declare const g: new <T>() => T;
            take(g);
            declare const ab: abstract new () => Co;
            declare function takeAb(c: abstract new () => Co): void;
            takeAb(ab);
            takeAb(c);
        """.trimIndent()).isEmpty())
    }

    /** cells c13 / k05 / k12 / k10: `constructor(public c: () => T)` infers `T` from the argument's return type. */
    @Test
    fun `a generic class infers its type argument from a callback constructor parameter`() {
        assert(rows("""
            class Co { x = 1 }
            class H<T> { constructor(public c: () => T) {} }
            const h = new H(() => new Co());
            const n: number = h.c;
            const h2 = new H(() => 1);
            const s: string = h2.c;
            const h3 = new H(() => { return new Co() });
            const n3: number = h3;
            const h4 = new H(() => "a");
            const n4: number = h4;
        """.trimIndent()) == listOf(
            "4:7 TS2322 Type '() => Co' is not assignable to type 'number'.",
            "6:7 TS2322 Type '() => number' is not assignable to type 'string'.",
            "8:7 TS2322 Type 'H<Co>' is not assignable to type 'number'.",
            "10:7 TS2322 Type 'H<string>' is not assignable to type 'number'.",
        ))
    }

    /** controls for c13: an explicit `H<Co>` and an annotated target agree with the inference. */
    @Test
    fun `an annotated or explicitly instantiated callback class stays silent`() {
        assert(rows("""
            class Co { x = 1 }
            class H<T> { constructor(public c: () => T) {} }
            const h: H<Co> = new H(() => new Co());
            h.c().x;
            const h2 = new H<Co>(() => new Co());
            h2.c().x;
        """.trimIndent()).isEmpty())
    }

    /** cells d03 / d06 / a08 / a09: `missingRequiredStatic` reaches non-identifier construct sources at a declaration and an argument. */
    @Test
    fun `a construct type missing one required static is the TS2741 head`() {
        assert(rows("""
            class Co { x = 1 }
            class St { static s = 1; x = 1 }
            class G { static make(): void {} }
            declare const c: new () => St;
            const x: typeof St = c;
            declare const g: new () => G;
            const y: typeof G = g;
            declare function take(c: typeof St): void;
            take(c);
            declare const co: new () => Co;
            declare function takeS(c: { new (): Co; s: number }): void;
            takeS(co);
        """.trimIndent()) == listOf(
            "5:7 TS2741 Property 's' is missing in type 'new () => St' but required in type 'typeof St'.",
            "7:7 TS2741 Property 'make' is missing in type 'new () => G' but required in type 'typeof G'.",
            "9:6 TS2741 Property 's' is missing in type 'new () => St' but required in type 'typeof St'.",
            "12:7 TS2741 Property 's' is missing in type 'new () => Co' but required in type '{ new (): Co; s: number; }'.",
        ))
    }

    /** tsgo builds `cond ? A : B` with `UnionReductionSubtype`: a strict constructor subtype is dropped, unrelated ones stay. */
    @Test
    fun `a conditional expression of constructor types is subtype-reduced`() {
        assert(rows("""
            class A { constructor(x?: number) {} }
            class C extends A { c = 1 }
            abstract class Ab { }
            class Co { }
            class O { constructor(x?: number) {} }
            class X { x = 1 }
            class Y { y = 1 }
            declare const cond: boolean;
            const u1 = cond ? C : A; const p1: number = u1;
            const u2 = cond ? Co : Ab; const p2: number = u2;
            const u3 = cond ? O : Co; const p3: number = u3;
            const u4 = cond ? C : cond ? A : Co; const p4: number = u4;
            const u5 = cond ? X : Y; const p5: number = u5;
            const u6 = cond ? Ab : Co; new u6();
        """.trimIndent()) == listOf(
            "9:32 TS2322 Type 'typeof A' is not assignable to type 'number'.",
            "10:34 TS2322 Type 'typeof Ab' is not assignable to type 'number'.",
            "11:33 TS2322 Type 'typeof O' is not assignable to type 'number'.",
            "12:44 TS2322 Type 'typeof A' is not assignable to type 'number'.",
            "13:32 TS2322 Type 'typeof X | typeof Y' is not assignable to type 'number'.",
            "  | Type 'typeof X' is not assignable to type 'number'.",
            "14:28 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    /** cells t06 / p02 / p04 / p06-p10: the binder-made `prototype` is never compared; the instance chain prints under the head (a lone missing-member line flattened). */
    @Test
    fun `a class constructor mismatch elaborates through the construct signature and not through prototype`() {
        assert(rows("""
            class Co { x = 1 }
            class Ot { y = 1 }
            class Q { x = "s" }
            class R { x = 1; y = 1; z = 1 }
            declare function take(c: typeof Co): void;
            take(Ot);
            function f(): typeof Co { return Ot }
            declare const o: { c: typeof Co };
            o.c = Ot;
            declare let t: typeof Co;
            t = Q;
            declare let r: typeof R;
            r = Co;
            class H<T> { c!: T }
            declare const h: H<typeof Co>;
            h.c = Ot;
        """.trimIndent()) == listOf(
            "6:6 TS2345 Argument of type 'typeof Ot' is not assignable to parameter of type 'typeof Co'.",
            "  | Property 'x' is missing in type 'Ot' but required in type 'Co'.",
            "7:27 TS2322 Type 'typeof Ot' is not assignable to type 'typeof Co'.",
            "  | Property 'x' is missing in type 'Ot' but required in type 'Co'.",
            "9:1 TS2322 Type 'typeof Ot' is not assignable to type 'typeof Co'.",
            "  | Property 'x' is missing in type 'Ot' but required in type 'Co'.",
            "11:1 TS2322 Type 'typeof Q' is not assignable to type 'typeof Co'.",
            "  | Type 'Q' is not assignable to type 'Co'.",
            "  | Types of property 'x' are incompatible.",
            "  | Type 'string' is not assignable to type 'number'.",
            "13:1 TS2322 Type 'typeof Co' is not assignable to type 'typeof R'.",
            "  | Type 'Co' is missing the following properties from type 'R': y, z",
            "16:1 TS2322 Type 'typeof Ot' is not assignable to type 'typeof Co'.",
            "  | Property 'x' is missing in type 'Ot' but required in type 'Co'.",
        ))
    }

    /**
     * Controls for the widened TS2741 head: tsgo reports two missing statics as TS2739 and a
     * required member spelled like a `Function` apparent member (`length`) as a member-type
     * mismatch, so neither may become a single-missing-static TS2741.
     */
    @Test
    fun `two missing statics or a Function-named member are never a single missing static`() {
        val codes = diagnose("""
            class S2 { static a = 1; static b = 1; x = 1 }
            declare const c: new () => S2;
            const x: typeof S2 = c;
            class Nm { static override = 1; x = 1 }
            declare const d: new () => Nm;
            const y: { new (): Nm; length: string } = d;
        """.trimIndent(), es2022).map { it.line to it.code }
        assert(codes.size == 2 && codes.none { it.second == 2741 })
    }
}
