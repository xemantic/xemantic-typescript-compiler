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
 * (P18.284) (CHK.225)(a). Every expected row is tsgo 7.0.2's (cells under
 * `build/bench/p18284-agent/{m,m2,m3}`, heads only, 1-based column).
 *
 * The relation's same-target shortcut compared the type arguments of `X<A>` vs `X<B>`
 * COVARIANTLY for a generic with no `in` / `out`, so a parameter used only contravariantly
 * (a function-typed member) or bivariantly (a method parameter) was a false positive on
 * legal code. tsgo relates such a pair by MEASURED variance; here a failed covariant check
 * on an unannotated parameter falls through to the structural comparison of the two
 * instantiations (`Relater.unannotatedFallbackAllowed`), which can only accept.
 *
 * That fallback needs tsgo's CALLBACK rule in the method-parameter bivariance
 * (`Relater.isCallbackParameterPair`): a parameter that is itself a single-signature callback
 * relates only through the callbacks' signatures, which is what keeps `Promise<T>`, `Set<T>`
 * and an `Obs<T> { subscribe(cb: (x: T) => void) }` COVARIANT. A generic method's own type
 * parameters are erased in that callback comparison (`Relater.erasedCallbacksRelated`),
 * since tsgo instantiates the source in the target's context first.
 */
class UnannotatedVarianceFallbackTest {

    private val prelude = "interface Animal { name: string }\ninterface Dog extends Animal { bark(): void }\n\n"

    private fun rows(source: String, directives: String = "// @strict: true\n// @target: es2022"): List<String> =
        diagnose(prelude + source.trimIndent(), directives = directives)
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a function-typed member makes the parameter contravariant`() {
        val r = rows(
            """
            interface Fn<T> { f: (x: T) => void }
            declare const fnA: Fn<Animal>;
            export const c1: Fn<Dog> = fnA;
            function takeFn(x: Fn<Dog>) {}
            takeFn(fnA);
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a method parameter is bivariant`() {
        val r = rows(
            """
            interface M<T> { m(x: T): void }
            declare const ms: M<string>; declare const ma: M<"a">;
            export const c3: M<"a"> = ms;
            export const c4: M<string> = ma;
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a nested function-typed generic and an unused parameter relate`() {
        val r = rows(
            """
            interface Fn<T> { f: (x: T) => void }
            interface Outer<T> { inner: Fn<T> }
            declare const oA: Outer<Animal>;
            export const c13: Outer<Dog> = oA;
            interface Ph<T> { k: number }
            declare const phS: Ph<string>;
            export const c29: Ph<number> = phS;
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a covariant property readonly property private member and recursive interface still report`() {
        val r = rows(
            """
            interface Box<T> { v: T }
            declare const bA: Box<Animal>; declare const bD: Box<Dog>;
            export const c5: Box<Animal> = bD;
            export const c6: Box<Dog> = bA;
            interface RO<T> { readonly v: T }
            declare const rA: RO<Animal>;
            export const c8: RO<Dog> = rA;
            class P<T> { private p!: T; q!: T }
            declare const pA: P<Animal>; declare const pD: P<Dog>;
            export const c11: P<Animal> = pD;
            export const c12: P<Dog> = pA;
            interface Node1<T> { value: T; next: Node1<T> | undefined }
            declare const nA: Node1<Animal>; declare const nD: Node1<Dog>;
            export const c15: Node1<Animal> = nD;
            export const c16: Node1<Dog> = nA;
            """
        )
        assert(r == listOf(
            "10:14 TS2322 Type 'RO<Animal>' is not assignable to type 'RO<Dog>'.",
            "14:14 TS2322 Type 'P<Animal>' is not assignable to type 'P<Dog>'.",
            "18:14 TS2322 Type 'Node1<Animal>' is not assignable to type 'Node1<Dog>'.",
            "7:14 TS2322 Type 'Box<Animal>' is not assignable to type 'Box<Dog>'.",
        ))
    }

    @Test
    fun `an invariant use still reports in the direction the covariant check refuses`() {
        val r = rows(
            """
            interface Inv<T> { get(): T; set: (x: T) => void }
            declare const iA: Inv<Animal>;
            export const c10: Inv<Dog> = iA;
            interface NodeF<T> { cb: (x: T) => void; next: NodeF<T> }
            declare const nfD: NodeF<Dog>;
            export const c18: NodeF<Animal> = nfD;
            """
        )
        assert(r == listOf(
            "6:14 TS2322 Type 'Inv<Animal>' is not assignable to type 'Inv<Dog>'.",
            "9:14 TS2322 Type 'NodeF<Dog>' is not assignable to type 'NodeF<Animal>'.",
        ))
    }

    @Test
    fun `a callback parameter of a method keeps the parameter covariant`() {
        val r = rows(
            """
            interface Obs<T> { subscribe(cb: (x: T) => void): void }
            declare const oA: Obs<Animal>; declare const oD: Obs<Dog>;
            export const c1: Obs<Animal> = oD;
            export const c2: Obs<Dog> = oA;
            interface G<T> { m<U>(f: (x: T) => U): U }
            declare const gA: G<Animal>; declare const gD: G<Dog>;
            export const c13: G<Animal> = gD;
            export const c14: G<Dog> = gA;
            """
        )
        assert(r == listOf(
            "11:14 TS2322 Type 'G<Animal>' is not assignable to type 'G<Dog>'.",
            "7:14 TS2322 Type 'Obs<Animal>' is not assignable to type 'Obs<Dog>'.",
        ))
    }

    @Test
    fun `a method parameter that is an object of methods stays bivariant`() {
        val r = rows(
            """
            interface ObsM<T> { subscribe(o: { next(x: T): void }): void }
            declare const omA: ObsM<Animal>; declare const omD: ObsM<Dog>;
            export const c3: ObsM<Dog> = omA;
            export const c4: ObsM<Animal> = omD;
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a generic method with a callback relates to its interface declaration`() {
        val r = rows(
            """
            interface N { k: number }
            interface SF { forEachChild<T>(cbNode: (node: N) => T | undefined): T | undefined }
            class SFO { forEachChild<T>(cbNode: (node: N) => T): T | undefined { return undefined } }
            declare const s: SFO;
            export const a: SF = s;
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `an emitter indexed by a method type parameter stays covariant`() {
        val r = rows(
            """
            interface E1<E> { on<K extends keyof E>(k: K, f: (e: E[K]) => void): void }
            declare const x1: E1<{ a: Animal }>;
            export const a1: E1<{ a: Dog }> = x1;
            interface E4<E> { on<K extends keyof E>(f: (e: E[K]) => void): void }
            declare const x4: E4<{ a: Animal }>;
            export const a4: E4<{ a: Dog }> = x4;
            """
        )
        assert(r == listOf(
            "6:14 TS2322 Type 'E1<{ a: Animal; }>' is not assignable to type 'E1<{ a: Dog; }>'.",
            "9:14 TS2322 Type 'E4<{ a: Animal; }>' is not assignable to type 'E4<{ a: Dog; }>'.",
        ))
    }

    @Test
    fun `lib containers stay covariant and a method-only key is bivariant`() {
        val r = rows(
            """
            declare const prA: Promise<Animal>; declare const prD: Promise<Dog>;
            export const c23: Promise<Animal> = prD;
            export const c24: Promise<Dog> = prA;
            declare const sA: Set<Animal>;
            export const c8: Set<Dog> = sA;
            declare const arA: Array<Animal>;
            export const c20: Array<Dog> = arA;
            declare const wm: WeakMap<object, any>;
            export const w: WeakMap<Animal, unknown[]> = wm;
            """,
            directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true",
        )
        assert(r == listOf(
            "10:14 TS2322 Type 'Animal[]' is not assignable to type 'Dog[]'.",
            "6:14 TS2322 Type 'Promise<Animal>' is not assignable to type 'Promise<Dog>'.",
            "8:14 TS2322 Type 'Set<Animal>' is not assignable to type 'Set<Dog>'.",
        ))
    }

    @Test
    fun `declared in and out variance is unchanged`() {
        val r = rows(
            """
            interface In<in T> { f: (x: T) => void }
            declare const inA: In<Animal>; declare const inD: In<Dog>;
            export const c25: In<Dog> = inA;
            export const c26: In<Animal> = inD;
            interface Out<out T> { v: T }
            declare const outA: Out<Animal>; declare const outD: Out<Dog>;
            export const c27: Out<Animal> = outD;
            export const c28: Out<Dog> = outA;
            """
        )
        assert(r == listOf(
            "11:14 TS2322 Type 'Out<Animal>' is not assignable to type 'Out<Dog>'.",
            "7:14 TS2322 Type 'In<Dog>' is not assignable to type 'In<Animal>'.",
        ))
    }
}
