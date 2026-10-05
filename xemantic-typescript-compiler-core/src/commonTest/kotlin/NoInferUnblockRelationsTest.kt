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
 * (P18.302) Five relation / evaluation rules tsgo applies and this checker did not, each one a
 * mechanism behind the type-fest rows that evaluating `NoInfer<T>` exposes:
 *
 * 1. A target member that only the global `Object` interface supplies (`toString`, `valueOf`, …) is
 *    compared against `Object`'s declaration when the source lacks it — `{ x: 1 }` does NOT relate to
 *    `Boolean` (`valueOf(): boolean`). It was skipped outright, so type-fest's `Jsonify<{…}>` took its
 *    `T extends Boolean ? boolean` branch.
 * 2. `Lowercase<string>` / `Uppercase<string>` / `Capitalize<string>` / `Uncapitalize<string>` are types
 *    of their own (tsgo's `StringMappingType`): a literal relates when the mapping leaves it unchanged,
 *    `string` and a template do not, and one may be a template placeholder. type-fest `IsLowercase`.
 * 3. A tuple pattern of OPTIONAL slots before a trailing rest (`[(infer F)?, ...infer R]`) infers
 *    position by position. type-fest `CollapseRestElement` / `AllExtend` answered `any`.
 * 4. `void extends undefined` is false inside a conditional type (type-fest `IsOptional<void>`).
 * 5. A homomorphic mapped member is readonly exactly when its SOURCE property is: an intersection's only
 *    when every constituent's is, and a `Readonly<X>` member although its declaration carries no
 *    modifier (type-fest `IsReadonlyKeyOf`).
 *
 * Every expected row is tsgo 7.0.2's row, full text; `realLibs` because the embedded lib declares none
 * of the string-mapping intrinsics, `Extract` or `Readonly`.
 */
class NoInferUnblockRelationsTest {

    private fun rows(d: List<Diagnostic>): List<String> = d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @lib: esnext"

    private val expect = "declare function expectType<T>(v: T): void;\n"

    @Test
    fun `an Object-prototype member the source lacks is compared against Object's declaration`() {
        val d = diagnose(
            expect + """
            type IsBoolean<T> = T extends Boolean ? 1 : 0;
            expectType<IsBoolean<{ x: 1 }>>(0);
            expectType<IsBoolean<{}>>(0);
            expectType<IsBoolean<{ valueOf(): boolean }>>(1);
            const b: Boolean = {};
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("6:7 TS2322 Type '{}' is not assignable to type 'Boolean'."))
    }

    @Test
    fun `negative control - an Object-prototype member whose inherited type relates is still satisfied`() {
        val d = diagnose(
            """
            interface Named { toString(): string }
            declare const src: { a: number };
            const named: Named = src;
            const o: Object = src;
            const n: { valueOf(): Object } = src;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a string literal relates to a string mapping over string when the mapping leaves it unchanged`() {
        val d = diagnose(
            expect + """
            expectType<'a' extends Lowercase<string> ? 1 : 2>(1);
            expectType<'A' extends Lowercase<string> ? 1 : 2>(2);
            expectType<'A' extends Uppercase<string> ? 1 : 2>(1);
            expectType<'Ab' extends Capitalize<string> ? 1 : 2>(1);
            expectType<'aB' extends Capitalize<string> ? 1 : 2>(2);
            expectType<string extends Lowercase<string> ? 1 : 2>(2);
            expectType<`on${'$'}{string}` extends Lowercase<string> ? 1 : 2>(2);
            type IsLower<S extends string> = S extends Lowercase<string> ? true : false;
            expectType<IsLower<'1'>>(true);
            expectType<IsLower<'a' | 'B'>>(null! as boolean);
            const wrong: 'x' = null! as Lowercase<string>;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("12:7 TS2322 Type 'Lowercase<string>' is not assignable to type '\"x\"'."))
    }

    @Test
    fun `a string mapping is a template literal placeholder`() {
        val d = diagnose(
            expect + """
            expectType<'aB' extends `${'$'}{string}${'$'}{Uppercase<string>}${'$'}{string}` ? 1 : 2>(1);
            expectType<'ab' extends `${'$'}{string}${'$'}{Uppercase<string>}${'$'}{string}` ? 1 : 2>(2);
            expectType<'ab' extends `${'$'}{string}${'$'}{Uppercase<string>}${'$'}{string}` ? 1 : 2>(1);
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("4:75 TS2345 Argument of type '1' is not assignable to parameter of type '2'."))
    }

    @Test
    fun `optional slots before a trailing rest infer position by position`() {
        val d = diagnose(
            """
            type P1<T> = T extends readonly [(infer F)?, ...infer R] ? [F, R] : 0;
            type P2<T> = T extends readonly [(infer A)?, (infer B)?, ...infer R] ? [A, B, R] : 0;
            const a: 'x' = null! as P1<[true, true]>;
            const b: 'x' = null! as P1<[1?, 2?]>;
            const c: 'x' = null! as P1<[]>;
            const d: 'x' = null! as P1<number[]>;
            const e: 'x' = null! as P1<[1, 2, ...number[], 3]>;
            const f: 'x' = null! as P1<[...string[], 1]>;
            const g: 'x' = null! as P2<[1]>;
            const h: 'x' = null! as P2<[1, ...number[], 3]>;
            type P3<T> = T extends [(infer A)?, ...infer R] ? [A, R] : 0;
            const i: 'x' = null! as P3<readonly [1]>;
            """.trimIndent(),
        )
        assert(
            rows(d) == listOf(
                "3:7 TS2322 Type '[true, [true]]' is not assignable to type '\"x\"'.",
                "4:7 TS2322 Type '[1, [(2 | undefined)?]]' is not assignable to type '\"x\"'.",
                "5:7 TS2322 Type '[unknown, unknown[]]' is not assignable to type '\"x\"'.",
                "6:7 TS2322 Type '[number, number[]]' is not assignable to type '\"x\"'.",
                "7:7 TS2322 Type '[1, [2, ...number[], 3]]' is not assignable to type '\"x\"'.",
                "8:7 TS2322 Type '[unknown, unknown[]]' is not assignable to type '\"x\"'.",
                "9:7 TS2322 Type '[1, unknown, unknown[]]' is not assignable to type '\"x\"'.",
                "10:7 TS2322 Type '[1, unknown, unknown[]]' is not assignable to type '\"x\"'.",
                "12:7 TS2322 Type '0' is not assignable to type '\"x\"'.",
            ),
        )
    }

    @Test
    fun `void does not extend undefined inside a conditional type`() {
        val d = diagnose(
            expect + """
            type IsUndef<T> = T extends undefined ? true : false;
            expectType<IsUndef<void>>(false);
            expectType<void extends undefined ? 1 : 2>(2);
            expectType<Extract<void, undefined>>(null! as never);
            expectType<IsUndef<undefined>>(true);
            expectType<undefined extends void ? 1 : 2>(1);
            """.trimIndent(),
            directives = realLibs,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a homomorphic mapped member is readonly exactly when its source property is`() {
        val d = diagnose(
            expect + """
            type IsEqual<A, B> = [A] extends [B] ? [B] extends [A] ? (<G>() => G extends A & G | G ? 1 : 2) extends (<G>() => G extends B & G | G ? 1 : 2) ? true : false : false : false;
            type IsReadonlyKey<T extends object, K extends keyof T> = K extends unknown ? IsEqual<{ [P in K]: T[K] }, { readonly [P in K]: T[K] }> : never;
            type F = { readonly a: string } & { a: string };
            expectType<IsReadonlyKey<F, 'a'>>(false);
            type FF = { readonly a: string } & { readonly a: string };
            expectType<IsReadonlyKey<FF, 'a'>>(true);
            type I = Readonly<{ a: number; b: string }>;
            expectType<IsReadonlyKey<I, 'a'>>(true);
            type A = { a: string; readonly b: number };
            expectType<IsReadonlyKey<A, 'a'>>(false);
            expectType<IsReadonlyKey<A, 'b'>>(true);
            type M<T> = { [K in keyof T]: T[K] };
            declare const m: M<F>;
            m.a = 'ok';
            declare const mi: M<I>;
            mi.a = 1;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf("17:4 TS2540 Cannot assign to 'a' because it is a read-only property."))
    }
}
