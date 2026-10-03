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
 * (LIBS.2) GSIG — the `IsEqual` generic-signature identity trick of every type-test library,
 * `(<G>() => G extends A ? 1 : 2) extends (<G>() => G extends B ? 1 : 2) ? true : false`, which
 * answered TRUE for every pair (the inner conditional over the free `G` resolved to `any`). Every
 * expected row is tsgo 7.0.2's, full text, measured in `build/bench/p18285-agent/{m1,m2,m4,pin}`.
 */
class GenericSignatureIsEqualTest {

    private val eq = "type Eq<A, B> = (<G>() => G extends A ? 1 : 2) extends (<G>() => G extends B ? 1 : 2) ? true : false;\n"

    private val typeFest = """
        type IsEqual<A, B> = [A] extends [B] ? [B] extends [A] ? _IsEqual<A, B> : false : false;
        type _IsEqual<A, B> = (<G>() => G extends A & G | G ? 1 : 2) extends (<G>() => G extends B & G | G ? 1 : 2) ? true : false;
        declare function expectType<T>(value: T): void;

    """.trimIndent()

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} ${it.code} ${it.message}" }

    @Test
    fun `the plain idiom decides every pair as tsgo does`() {
        val r = rows(eq + """
            export const c01: Eq<number, number> = false;
            export const c02: Eq<number, string> = false;
            export const c03: Eq<any, unknown> = false;
            export const c04: Eq<never, never> = false;
            export const c05: Eq<never, any> = false;
            export const c06: Eq<1, number> = false;
            export const c07: Eq<{ a: 1 }, { a: 1 }> = false;
            export const c08: Eq<{ readonly a: 1 }, { a: 1 }> = false;
            export const c09: Eq<{ a?: 1 }, { a: 1 }> = false;
            export const c10: Eq<1 | 2, 2 | 1> = false;
            export const c11: Eq<1 | 2, 1> = false;
            export const c12: Eq<any, any> = false;
            export const t02: Eq<number, string> = true;
            export const t03: Eq<any, unknown> = true;
            export const t08: Eq<{ readonly a: 1 }, { a: 1 }> = true;
        """.trimIndent())
        val f = "Type 'false' is not assignable to type 'true'."
        val t = "Type 'true' is not assignable to type 'false'."
        assert(r == listOf(
            "2:14 2322 $f", "5:14 2322 $f", "8:14 2322 $f", "11:14 2322 $f", "13:14 2322 $f",
            "14:14 2322 $t", "15:14 2322 $t", "16:14 2322 $t",
        ))
    }

    @Test
    fun `the type-fest form separates any never unknown readonly and duplicated constituents`() {
        val r = rows(typeFest + """
            expectType<false>({} as IsEqual<any, number>);
            expectType<false>({} as IsEqual<any, never>);
            expectType<false>({} as IsEqual<never, any>);
            expectType<false>({} as IsEqual<any, unknown>);
            expectType<false>({} as IsEqual<[any], [never]>);
            expectType<false>({} as IsEqual<{a: 1}, {readonly a: 1}>);
            expectType<true>({} as IsEqual<{a: 1}, {readonly a: 1}>);
            expectType<true>({} as IsEqual<{a: 1} | {a: 1}, {a: 1}>);
            expectType<true>({} as IsEqual<{a: 1} & {a: 1}, {a: 1}>);
            expectType<true>({} as IsEqual<{a: 1} | {b: 2}, {b: 2} | {a: 1}>);
            expectType<true>({} as IsEqual<any, any>);
            expectType<true>({} as IsEqual<never, never>);
            // @ts-expect-error
            expectType<true>({} as IsEqual<number, string>);
            // @ts-expect-error
            expectType<false>({} as IsEqual<1, 1>);
        """.trimIndent())
        assert(r == listOf("10:18 2345 Argument of type 'false' is not assignable to parameter of type 'true'."))
    }

    @Test
    fun `a key filter built on IsEqual keeps the readonly keys`() {
        val r = rows(typeFest + """
            type IsReadonlyKeyOf<T extends object, K extends keyof T> = K extends unknown ? IsEqual<{[Q in K]: T[K]}, {readonly [Q in K]: T[K]}> : never;
            type ReadonlyKeys<T extends object> = keyof {[K in keyof T as IsReadonlyKeyOf<T, K> extends false ? never : K]: never};
            type Opt = {readonly a?: string; b: number; readonly c: boolean};
            expectType<"a" | "c">({} as ReadonlyKeys<Opt>);
            expectType<"b">({} as ReadonlyKeys<Opt>);
            export const r1: IsReadonlyKeyOf<Opt, "a"> = true;
            export const r2: IsReadonlyKeyOf<Opt, "b"> = false;
            export const r3: IsReadonlyKeyOf<Opt, "b"> = true;
        """.trimIndent())
        assert(r == listOf(
            "8:17 2345 Argument of type '\"a\" | \"c\"' is not assignable to parameter of type '\"b\"'.",
            "11:14 2322 Type 'true' is not assignable to type 'false'.",
        ))
    }

    @Test
    fun `a mapped type over K extends keyof T keeps the source modifiers`() {
        val r = rows("""
            type O = { readonly a?: string; b: number; readonly c: boolean };
            type MyPick<T, K extends keyof T> = { [Q in K]: T[Q] };
            declare const p2: MyPick<O, "a" | "c">;
            p2.c = true;
            export const q2: MyPick<O, "a" | "c"> = { c: true };
            type NoKeyof<T, K extends string> = { [Q in K]: Q };
            declare const p3: NoKeyof<O, "a">;
            p3.a = "a";
            export const q3: NoKeyof<O, "a"> = {};
        """.trimIndent())
        assert(r == listOf(
            "4:4 2540 Cannot assign to 'c' because it is a read-only property.",
            "9:14 2741 Property 'a' is missing in type '{}' but required in type 'NoKeyof<O, \"a\">'.",
        ))
    }

    @Test
    fun `identical constraints on the unified parameter still decide`() {
        val r = rows("""
            type EqC<A, B> = (<T extends string>() => T extends A ? 1 : 2) extends (<T extends string>() => T extends B ? 1 : 2) ? true : false;
            export const c1: EqC<number, string> = false;
            export const c2: EqC<number, string> = true;
        """.trimIndent())
        assert(r == listOf("3:14 2322 Type 'true' is not assignable to type 'false'."))
    }

    @Test
    fun `the source default constraint relates to a target conditional that skips an untaken branch`() {
        val r = rows("""
            type D1<A, B> = (<G>() => G extends A ? 1 : 1) extends (<G>() => G extends B ? 1 : 2) ? true : false;
            export const d1: D1<number, unknown> = false;
            export const d2: D1<number, string> = true;
            export const d3: D1<number, any> = false;
            type D2<A, B> = (<G>() => G extends A ? 1 : 1) extends (<G>() => G extends B ? 1 : 1) ? true : false;
            export const d4: D2<number, string> = false;
            type D3<A, B> = (<G>() => G extends A ? 1 : 1) extends (<G>() => G extends B ? G : 1) ? true : false;
            export const d5: D3<number, string> = true;
        """.trimIndent())
        val f = "Type 'false' is not assignable to type 'true'."
        val t = "Type 'true' is not assignable to type 'false'."
        assert(r == listOf("2:14 2322 $f", "3:14 2322 $t", "4:14 2322 $f", "6:14 2322 $f", "8:14 2322 $t"))
    }

    @Test
    fun `control - a side this checker resolves to any against a structured one keeps the old answer`() {
        // tsgo: true. Under the real lib `Record<string, number>` reads `any` here, so the pair is
        // undecidable and must not turn into a false `false`.
        val r = diagnose(eq + """
            export const s6: Eq<Record<string, number>, { [k: string]: number }> = true;
            export const s7: Eq<boolean, true | false> = true;
            export const s5: Eq<() => void, () => void> = true;
        """.trimIndent(), "// @strict: true\n// @useRealLibs: true")
        assert(r.isEmpty())
    }
}
