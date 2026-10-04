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
 * (CHK.228) a conditional type over `any`, after tsgo's `getConditionalType`: `X extends any` /
 * `X extends unknown` is the true branch for every `X`, a GENUINE `any` check type takes both
 * branches, distributing over `never` yields `never`, and type-fest's
 * `IsAny<T> = 0 extends 1 & NoInfer<T> ? true : false` decides `any`. Only a genuine `any` (the
 * keyword, or an alias argument bound to one — [GenuineAnyProvenance]) is evaluated: this checker
 * also answers `any` for types it cannot resolve, and the controls pin that such a wash stays
 * silent. Every expected row is tsgo 7.0.2's, full text, measured in `build/bench/p18286-agent/pin` (whose files carry the
 * three directive lines, so their rows read three lines lower).
 */
class ConditionalAnyEvaluationTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private fun rows(source: String): List<String> =
        diagnose(source, directives).map { "${it.line}:${it.character} ${it.code} ${it.message}" }

    @Test
    fun `extends any and extends unknown are the true branch for every check type`() {
        val r = rows("""
            declare function take<T>(v: T): void;
            type C1 = string extends any ? "T" : "F";
            type C3 = any extends any ? 1 : 2;
            type C4 = unknown extends any ? 1 : 2;
            type C15 = any extends unknown ? 1 : 2;
            type C17 = { a: 1 } extends unknown ? "T" : "F";
            take<C1>("F");
            take<C3>(2);
            take<C4>(2);
            take<C15>(2);
            take<C17>("F");
            export {};
        """)
        assert(r == listOf(
            "7:10 2345 Argument of type '\"F\"' is not assignable to parameter of type '\"T\"'.",
            "8:10 2345 Argument of type '2' is not assignable to parameter of type '1'.",
            "9:10 2345 Argument of type '2' is not assignable to parameter of type '1'.",
            "10:11 2345 Argument of type '2' is not assignable to parameter of type '1'.",
            "11:11 2345 Argument of type '\"F\"' is not assignable to parameter of type '\"T\"'.",
        ))
    }

    @Test
    fun `a genuine any check type takes both branches through alias arguments`() {
        val r = rows("""
            declare function take<T>(v: T): void;
            type C2 = any extends string ? 1 : 2;
            type DA<X> = X extends string ? 1 : 2;
            type OuterZ<Y> = Z<Y>;
            type Z<T> = 0 extends 1 & T ? true : false;
            take<C2>(3);
            take<DA<any>>(3);
            take<OuterZ<any>>(false);
            take<Z<any>>(false);
            take<Z<number>>(true);
            take<DA<"s">>(2);
            export {};
        """)
        assert(r == listOf(
            "6:10 2345 Argument of type '3' is not assignable to parameter of type '1 | 2'.",
            "7:15 2345 Argument of type '3' is not assignable to parameter of type '1 | 2'.",
            "8:19 2345 Argument of type 'false' is not assignable to parameter of type 'true'.",
            "9:14 2345 Argument of type 'false' is not assignable to parameter of type 'true'.",
            "10:17 2345 Argument of type 'true' is not assignable to parameter of type 'false'.",
            "11:15 2345 Argument of type '2' is not assignable to parameter of type '1'.",
        ))
    }

    @Test
    fun `distributing over never yields never and a wrapped never does not distribute`() {
        val r = rows("""
            declare function take<T>(v: T): void;
            type D<X> = X extends any ? [X] : 0;
            type ND<X> = [X] extends [never] ? 1 : 2;
            take<D<never>>(0);
            take<D<"a">>(["a"]);
            take<ND<never>>(2);
            take<never extends string ? 1 : 2>(2);
            export {};
        """)
        assert(r == listOf(
            "4:16 2345 Argument of type '0' is not assignable to parameter of type 'never'.",
            "6:17 2345 Argument of type '2' is not assignable to parameter of type '1'.",
            "7:36 2345 Argument of type '2' is not assignable to parameter of type '1'.",
        ))
    }

    @Test
    fun `type-fest IsAny decides any through NoInfer`() {
        val r = rows("""
            declare function take<T>(v: T): void;
            type IsAny<T> = 0 extends 1 & NoInfer<T> ? true : false;
            type Wrap<U> = IsAny<U>;
            take<IsAny<any>>(false);
            take<Wrap<any>>(false);
            export {};
        """)
        assert(r == listOf(
            "4:18 2345 Argument of type 'false' is not assignable to parameter of type 'true'.",
            "5:17 2345 Argument of type 'false' is not assignable to parameter of type 'true'.",
        ))
    }

    @Test
    fun `negative control - an any this checker could not resolve is not evaluated`() {
        val r = rows("""
            type M<A extends unknown[]> = A extends [infer F, ...infer R] ? R : never;
            type CW<A extends unknown[]> = M<A>[number] extends number ? 1 : 2;
            declare const cw: CW<[1, 2, 3]>;
            export const c: 1 = cw;
            type DW<A extends unknown[]> = M<A>[number] extends unknown ? 1 : 2;
            declare const dw: DW<[1, 2, 3]>;
            export const d: 1 = dw;
            type N2<A extends unknown[]> = number extends M<A>[number] ? 1 : 2;
            export const n2: N2<[1, 2, 3]> = 2;
        """)
        assert(r.isEmpty())
    }

    /** A `never` this checker produced by evaluating a conditional it should have deferred is not
     *  distributed over (the corpus's `conditionalTypeAssignabilityWhenDeferred`, `o6`). */
    @Test
    fun `negative control - a never from an undeferred conditional does not distribute`() {
        val r = rows("""
            type Distributive<T> = T extends { a: number } ? { a: number; b: number } : boolean;
            export function f<T>(o: { a: number; b: number }) {
              const o6: Distributive<[T] extends [never] ? { a: number } : never> = o;
              return o6;
            }
        """)
        assert(r.isEmpty())
    }

    /** The genuine `any` must be part of the instantiation-cache context (ablated, the washed
     *  `DA` is served the genuine `1 | 2` — reachable when a same-named alias lives in another
     *  module, as in `build/bench/p18286-agent/f5`). */
    @Test
    fun `negative control - a genuine any instantiation is not served to a washed one`() {
        val r = rows("""
            // @Filename: b.ts
            type DA<X> = X extends string ? 1 : 2;
            export {};
            // @Filename: f.ts
            declare function take<T>(v: T): void;
            type M<A extends unknown[]> = A extends [infer F, ...infer R] ? R : never;
            type DA<X> = X extends string ? 1 : 2;
            take<DA<any>>(3);
            declare const w: DA<M<[1, 2, 3]>[number]>;
            export const c: 2 = w;
        """)
        assert(r == listOf("4:15 2345 Argument of type '3' is not assignable to parameter of type '1 | 2'."))
    }
}
