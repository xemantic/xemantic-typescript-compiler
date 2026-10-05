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
 * (P18.306) landing `NoInfer<T>` evaluation and the alias-constraint fix, plus the five mechanisms
 * whose rows they would otherwise have added on type-fest:
 *
 * 1. `NoInfer<X>` evaluates to `X` (it was left un-evaluated, i.e. an `any`).
 * 2. An alias type parameter's constraint is resolved with the alias's OWN parameters bound to the
 *    reference's arguments — a same-named parameter of the CALLER leaked in before — and a
 *    constraint naming its own alias (`Shared<I, D extends Shared<I, D>>`) is a cycle, not a stack
 *    overflow.
 * 3. `any` does not relate to `never` inside a conditional type (`IsNever<any>` is `false`).
 * 4. A homomorphic mapped type over an intersection-of-union maps the distributed union, as tsgo
 *    (whose intersections are normalized at construction) does: `typeof foo.a` then narrows.
 * 5. An `IsEqual`-shaped conditional over two `any` WASHES (here a mapped type over a `unique symbol`
 *    key, which this checker cannot enumerate) answers `any`, not a confident `true`.
 * 6. `string[] & ['x']` relates to an array target through its array constituent; and a target that
 *    re-declares an `Object.prototype` member (`Boolean`'s `valueOf(): boolean`) is not satisfied by
 *    an intersection's inherited one.
 *
 * Every expected row is tsgo 7.0.2's row, full text.
 */
class NoInferAliasConstraintLandingTest {

    private fun rows(d: List<Diagnostic>): List<String> = d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @lib: esnext"

    @Test
    fun `NoInfer of a concrete type is that type`() {
        val d = diagnose(
            """
            const ni: NoInfer<string> = 1;
            const ok: NoInfer<number> = 1;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("1:7 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `an alias constraint is resolved with its own parameters bound, not the caller's`() {
        val d = diagnose(
            """
            type C<Options extends object, D extends {[P in keyof Options]?: never}> = [Options, D];
            type Outer<Options extends object> = C<{}, {a: 1}>;
            type Outer2<O extends object> = C<{}, {a: 1}>;
            declare const x: Outer<{a: 1}>;
            const n1: number = x;
            declare const y: Outer2<{a: 1}>;
            const n2: number = y;
            type Inner<T extends Options, Options> = T;
            type Outer3<Options> = Inner<string, string>;
            declare const z: Outer3<number>;
            const n3: number = z;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "5:7 TS2322 Type 'Outer<{ a: 1; }>' is not assignable to type 'number'.",
            "7:7 TS2322 Type 'Outer2<{ a: 1; }>' is not assignable to type 'number'.",
            "11:7 TS2322 Type 'string' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `a constraint naming its own alias is a cycle, not a stack overflow`() {
        val d = diagnose(
            """
            type Shared<I, D extends Shared<I, D>> = {[P in keyof I & keyof D]?: D[P]};
            type Use<I, D extends Shared<I, D>> = D;
            declare const u: Use<{a: 1}, {a: 1}>;
            const sh: number = u;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("4:7 TS2322 Type '{ a: 1; }' is not assignable to type 'number'."))
    }

    @Test
    fun `any does not extend never in a conditional type`() {
        val d = diagnose(
            """
            type IsNever<T> = [T] extends [never] ? true : false;
            const an1: IsNever<any> = 0 as unknown as "probe";
            const an2: IsNever<never> = 0 as unknown as "probe";
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "2:7 TS2322 Type '\"probe\"' is not assignable to type 'false'.",
            "3:7 TS2322 Type '\"probe\"' is not assignable to type 'true'.",
        ))
    }

    @Test
    fun `negative control - any still assigns to never-free targets outside a conditional`() {
        val d = diagnose(
            """
            declare const a: any;
            const s: string = a;
            const t: [string] = [a];
            """.trimIndent(),
            directives = realLibs,
        )
        assert(d.isEmpty())
    }

    private val mapped = """
        type Pk<T, K extends keyof T> = {[P in K]: T[P]};
        type Rq<T> = {[P in keyof T]-?: T[P]};
        type Pt<T> = {[P in keyof T]?: T[P]};
        type Rc<K extends keyof any, T> = {[P in K]: T};
        type Ex<T, U> = T extends U ? never : T;
        type Om<T, K extends keyof any> = Pk<T, Ex<keyof T, K>>;
        type S<T> = {[K in keyof T]: T[K]} & {};
        """.trimIndent() + "\n"

    @Test
    fun `a homomorphic mapped type over an intersection of a union maps each member`() {
        val d = diagnose(
            mapped + """
            function n2(foo: S<({a: string; b?: never} | {a?: never; b: string}) & {c: string}>): void {
                if (typeof foo.a === 'string') return;
                const q: number = foo.b;
            }
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("10:11 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `a union member that is itself an intersection is distributed too`() {
        val d = diagnose(
            mapped + """
            type RE<O, K extends keyof O> = {[P in K]: Rq<Pk<O, P>> & Pt<Rc<Ex<K, P>, never>>}[K] & Om<O, K>;
            function n1(foo: S<RE<{a: string; b: string; c: string}, 'a' | 'b'>>): void {
                if (typeof foo.a === 'string') return;
                const q: number = foo.b;
            }
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("11:11 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `an IsEqual over two any washes is not a confident true`() {
        val d = diagnose(
            """
            declare const sym: unique symbol;
            type H = {[sym]: number; readonly x: string};
            type Eq<A, B> = (<G>() => G extends A & G | G ? 1 : 2) extends (<G>() => G extends B & G | G ? 1 : 2) ? true : false;
            declare function exp<T>(v: T): void;
            exp<Eq<{[K in typeof sym]: H[K]}, {readonly [K in typeof sym]: H[K]}>>(false);
            exp<Eq<{a: 1}, {a: 1}>>(false);
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("6:25 TS2345 Argument of type 'false' is not assignable to parameter of type 'true'."))
    }

    @Test
    fun `an intersection with an array constituent relates to an array target`() {
        val d = diagnose(
            """
            type X = string[] & ['some value'];
            declare const x: X;
            const ar1: readonly unknown[] = x;
            const ar2: unknown[] = x;
            type C1 = X extends unknown[] ? 1 : 2;
            const ar3: C1 = 0 as unknown as "probe";
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("6:7 TS2322 Type '\"probe\"' is not assignable to type '1'."))
    }

    @Test
    fun `an intersection does not satisfy a re-declared Object-prototype member it only inherits`() {
        val d = diagnose(
            """
            const bo1: Boolean = {} as {k: 1} & {j: 2};
            const bo2: Boolean = {} as {k: 1} & {valueOf(): boolean};
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("1:7 TS2322 Type '{ k: 1; } & { j: 2; }' is not assignable to type 'Boolean'."))
    }
}
