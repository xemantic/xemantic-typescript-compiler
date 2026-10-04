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
 * (P18.288) (CHK.216) part 2 — `infer` in a TUPLE pattern and in a TEMPLATE LITERAL pattern
 * ([ConditionalInferPatterns], [TemplateLiteralTypes.matchInferPattern]), plus the normalisations
 * the matrix exposed (tuple spreads, `NonNullable<undefined>`, numeric literal values, declared
 * tuples not widened). Every expectation is tsgo 7.0.2's full output for the fixture
 * (`tools/tsgo-7.0.2/lib/tsc`, `strict`, `target: es2022`), row for row — probes are ARGUMENT
 * probes because the variable-declaration reader still displays a conditional alias by name.
 */
class ConditionalInferPatternsTest {

    private fun rows(source: String): List<String> =
        diagnose(source, "// @strict: true\n// @target: es2022")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    /** `[infer H, ...infer R]`, `[...infer I, infer L]` and `[infer A, infer B, ...infer R]` over concrete tuples; the tail keeps its labels. */
    @Test
    fun `tuple-rest infer binds the head the tail the last and the init`() {
        val r = rows(
            """
            declare function is<T>(x: T): void;
            type Head<T> = T extends [infer H, ...infer R] ? H : "none";
            type Tail<T> = T extends [infer H, ...infer R] ? R : "none";
            type Last<T> = T extends [...infer I, infer L] ? L : "none";
            type Init<T> = T extends [...infer I, infer L] ? I : "none";
            type Two<T> = T extends [infer A, infer B, ...infer R] ? [B, A, R] : "none";
            is<Head<[1, 2, 3]>>("z");
            is<Tail<[1, 2, 3]>>("z");
            is<Last<[1, 2, 3]>>("z");
            is<Init<[1, 2, 3]>>("z");
            is<Two<[1, 2, 3, 4]>>("z");
            is<Two<[1, 2]>>("z");
            is<Tail<[a: 1, b: 2]>>("z");
            """,
        )
        assert(r == listOf(
            "10:21 TS2345 Argument of type 'string' is not assignable to parameter of type '[1, 2]'.",
            "11:23 TS2345 Argument of type 'string' is not assignable to parameter of type '[2, 1, [3, 4]]'.",
            "12:17 TS2345 Argument of type 'string' is not assignable to parameter of type '[2, 1, []]'.",
            "13:24 TS2345 Argument of type 'string' is not assignable to parameter of type '[b: 2]'.",
            "7:21 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '1'.",
            "8:21 TS2345 Argument of type 'string' is not assignable to parameter of type '[2, 3]'.",
            "9:21 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '3'.",
        ).sorted())
    }

    /** too short, empty, an array, a readonly source against a mutable pattern, an optional element in a required slot, a trailing rest against a fixed suffix, a primitive and `unknown`. */
    @Test
    fun `a tuple pattern that cannot match takes the false branch`() {
        val r = rows(
            """
            declare function is<T>(x: T): void;
            type Head<T> = T extends [infer H, ...infer R] ? H : "none";
            type Last<T> = T extends [...infer I, infer L] ? L : "none";
            type Two<T> = T extends [infer A, infer B, ...infer R] ? A : "none";
            type Exact<T> = T extends [infer A, infer B] ? A : "none";
            is<Two<[1]>>("z");
            is<Head<[]>>("z");
            is<Head<string[]>>("z");
            is<Head<readonly [1, 2]>>("z");
            is<Head<[1?, 2?]>>("z");
            is<Last<[1, 2?]>>("z");
            is<Last<[1, ...string[]]>>("z");
            is<Exact<[1, 2, 3]>>("z");
            is<Exact<[1, ...number[]]>>("z");
            is<Head<string>>("z");
            is<Head<unknown>>("z");
            """,
        )
        assert(r == listOf(
            "10:20 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "11:19 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "12:28 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "13:22 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "14:29 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "15:18 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "16:19 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "6:14 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "7:14 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "8:20 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "9:27 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
        ).sorted())
    }

    /** a lone source rest is its array, a sliced readonly tuple is mutable, an optional element stays optional. */
    @Test
    fun `the variadic slice keeps rests optionality and becomes mutable`() {
        val r = rows(
            """
            declare function is<T>(x: T): void;
            type Tail<T> = T extends [infer H, ...infer R] ? R : "none";
            type Init<T> = T extends [...infer I, infer L] ? I : "none";
            type Mid<T> = T extends [infer A, ...infer M, infer Z] ? M : "none";
            type RTail<T> = T extends readonly [infer H, ...infer R] ? R : "none";
            type RestOnly<T> = T extends [...infer R] ? R : "none";
            type RO<T> = T extends readonly [...infer R] ? R : "none";
            is<Tail<[1, ...string[]]>>("z");
            is<Init<[...string[], 1]>>("z");
            is<Init<[1, ...string[], 2]>>(0);
            is<Mid<[1, 2, 3, 4]>>("z");
            is<Mid<[1, ...string[], 4]>>("z");
            is<Mid<[1, 4]>>("z");
            is<RTail<readonly [1, 2]>>("z");
            is<RestOnly<string[]>>("z");
            is<RestOnly<readonly string[]>>("z");
            is<RO<readonly string[]>>("z");
            is<Tail<[1, 2?]>>("z");
            """,
        )
        assert(r == listOf(
            "10:31 TS2345 Argument of type 'number' is not assignable to parameter of type '[1, ...string[]]'.",
            "11:23 TS2345 Argument of type 'string' is not assignable to parameter of type '[2, 3]'.",
            "12:30 TS2345 Argument of type 'string' is not assignable to parameter of type 'string[]'.",
            "13:17 TS2345 Argument of type 'string' is not assignable to parameter of type '[]'.",
            "14:28 TS2345 Argument of type 'string' is not assignable to parameter of type '[2]'.",
            "15:24 TS2345 Argument of type 'string' is not assignable to parameter of type 'string[]'.",
            "16:33 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "17:27 TS2345 Argument of type 'string' is not assignable to parameter of type 'string[]'.",
            "18:19 TS2345 Argument of type 'string' is not assignable to parameter of type '[(2 | undefined)?]'.",
            "8:28 TS2345 Argument of type 'string' is not assignable to parameter of type 'string[]'.",
            "9:28 TS2345 Argument of type 'string' is not assignable to parameter of type 'string[]'.",
        ).sorted())
    }

    /** a union distributes, a constrained `infer` refuses, `Rev` and a template join recurse through the tail. */
    @Test
    fun `distribution constraints and recursion through the tail`() {
        val r = rows(
            """
            declare function is<T>(x: T): void;
            type Head<T> = T extends [infer H, ...infer R] ? H : "none";
            type C<T> = T extends [infer H extends string, ...infer R] ? H : "no";
            type Rev<T extends unknown[]> = T extends [infer F, ...infer R] ? [...Rev<R>, F] : [];
            type J<I extends readonly unknown[]> = I extends readonly [] ? "" : I extends readonly [infer F extends string, ...infer T extends readonly string[]] ? `${'$'}{F}.${'$'}{J<T>}` : string;
            is<Head<[1, 2] | ["a"]>>("z");
            is<C<["a", 1]>>("z");
            is<C<[1, 1]>>("z");
            is<Rev<[1, 2, 3]>>("z");
            is<J<["a", "b"]>>("z");
            is<J<["a", "b"]>>("a.b.");
            """,
        )
        assert(r == listOf(
            "10:19 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"a.b.\"'.",
            "6:26 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"a\" | 1'.",
            "7:17 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"a\"'.",
            "8:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"no\"'.",
            "9:20 TS2345 Argument of type 'string' is not assignable to parameter of type '[3, 2, 1]'.",
        ).sorted())
    }

    /** tsgo `createNormalizedTupleType`, `ReadonlyArray<infer I>` over arrays and tuples, `ReadonlySet` / `ReadonlyMap` over the mutable lib type. */
    @Test
    fun `tuple spreads normalise and array or collection patterns infer`() {
        val r = rows(
            """
            declare function is<T>(x: T): void;
            type E<T> = T extends readonly [] | readonly [...never[]] ? "empty" : "no";
            type It<T> = T extends ReadonlyArray<infer I> ? I : "none";
            type MIt<T> = T extends (infer I)[] ? I : "none";
            type SetItem<S> = S extends ReadonlySet<infer I> ? I : "none";
            type MapKey<S> = S extends ReadonlyMap<infer K, infer V> ? [K, V] : "none";
            is<[...[1, 2], 3]>("z");
            is<[...string[]]>("z");
            is<readonly [...string[]]>("z");
            is<[1, ...(readonly string[])]>(0);
            is<E<string[]>>("z");
            is<It<string[]>>(0);
            is<It<[1, "a"]>>(true);
            is<MIt<readonly number[]>>("z");
            is<SetItem<Set<string>>>(0);
            is<SetItem<string[]>>("z");
            is<MapKey<Map<string, number>>>(0);
            """,
        )
        assert(r == listOf(
            "10:33 TS2345 Argument of type 'number' is not assignable to parameter of type '[1, ...string[]]'.",
            "11:17 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"no\"'.",
            "12:18 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            "13:18 TS2345 Argument of type 'true' is not assignable to parameter of type '\"a\" | 1'.",
            "14:28 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "15:26 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
            "16:23 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "17:33 TS2345 Argument of type 'number' is not assignable to parameter of type '[string, number]'.",
            "7:20 TS2345 Argument of type 'string' is not assignable to parameter of type '[1, 2, 3]'.",
            "8:19 TS2345 Argument of type 'string' is not assignable to parameter of type 'string[]'.",
            "9:28 TS2345 Argument of type 'string' is not assignable to parameter of type 'readonly string[]'.",
        ).sorted())
    }

    /** tsgo `inferToTemplateLiteralType`: one character per empty delimiter, literal prefix / suffix, number / bigint / boolean constraints, no match for `string`. */
    @Test
    fun `infer inside a template literal pattern`() {
        val r = rows(
            """
            declare function is<T>(x: T): void;
            type First<S> = S extends `${'$'}{infer H}${'$'}{infer R}` ? H : "none";
            type Rest<S> = S extends `${'$'}{infer H}${'$'}{infer R}` ? R : "none";
            type DropPre<S> = S extends `pre-${'$'}{infer R}` ? R : "none";
            type DropSuf<S> = S extends `${'$'}{infer R}-suf` ? R : "none";
            type Num<S> = S extends `${'$'}{infer N extends number}` ? N : "none";
            type Big<S> = S extends `${'$'}{infer N extends bigint}` ? N : "none";
            type Bool<S> = S extends `${'$'}{infer B extends boolean}` ? B : "none";
            type Split<S> = S extends `${'$'}{infer A}.${'$'}{infer B}` ? [A, B] : "none";
            type Cap<S> = S extends `${'$'}{infer A extends "a" | "b"}${'$'}{infer R}` ? A : "none";
            is<First<"abc">>("z");
            is<Rest<"abc">>("z");
            is<First<"">>("z");
            is<Rest<"a">>("z");
            is<DropPre<"pre-x">>("z");
            is<DropPre<"post-x">>("z");
            is<DropSuf<"x-suf">>("z");
            is<Num<"5">>("z");
            is<Num<"05">>("z");
            is<Num<"x">>("z");
            is<Num<"1e3">>("z");
            is<Big<"12">>("z");
            is<Bool<"true">>("z");
            is<Split<"a.b.c">>("z");
            is<Split<"abc">>("z");
            is<First<string>>("z");
            is<First<"ab" | "cd">>("z");
            is<Cap<"ax">>("z");
            is<Cap<"cx">>("z");
            is<DropPre<`pre-${'$'}{number}`>>("z");
            type Trim<S extends string> = S extends ` ${'$'}{infer R}` ? Trim<R> : S;
            is<Trim<"   x">>("z");
            type Rv<S extends string> = S extends `${'$'}{infer H}${'$'}{infer R}` ? `${'$'}{Rv<R>}${'$'}{H}` : "";
            is<Rv<"abc">>("z");
            """,
        )
        assert(r == listOf(
            "11:18 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"a\"'.",
            "12:17 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"bc\"'.",
            "13:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "14:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"\"'.",
            "15:22 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"x\"'.",
            "16:23 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "17:22 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"x\"'.",
            "18:14 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '5'.",
            "19:15 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "20:14 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "21:16 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "22:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '12n'.",
            "23:18 TS2345 Argument of type '\"z\"' is not assignable to parameter of type 'true'.",
            "24:20 TS2345 Argument of type 'string' is not assignable to parameter of type '[\"a\", \"b.c\"]'.",
            "25:18 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "26:19 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "27:24 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"a\" | \"c\"'.",
            "28:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"a\"'.",
            "29:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"none\"'.",
            "30:30 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '`\${number}`'.",
            "32:18 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"x\"'.",
            "34:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"cba\"'.",
        ).sorted())
    }

    /** `NonNullable<undefined>` is `never`, a numeric literal type reads separators and radixes, a bigint is normalised, a tuple written as a type is not widened, `[1, 2?]` keeps its marker inside type arguments. */
    @Test
    fun `nullish NonNullable numeric separators and declared tuples`() {
        val r = rows(
            """
            declare function is<T>(x: T): void;
            type NN<T> = T & {}; is<NN<undefined>>("z");
            is<null & {}>("z");
            is<`${'$'}{NN<undefined> | ""}x`>("z");
            is<1_000>(1000);
            is<1_000>(999);
            is<0x10>(16);
            is<0b11>(2);
            is<-1_000n>(-1000n);
            is<0xFFn>(255n);
            is<0xFFn>("z");
            const d = { t: ["foo"] as ["foo"], s: [1] as [...string[], number] };
            is<["foo"]>(d.t);
            is<[...string[], number]>(d.s);
            is<[1, 2?]>("z");
            type Id<T> = T;
            is<Id<[1, 2?]>>("z");
            """,
        )
        assert(r == listOf(
            "11:11 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '255n'.",
            "15:13 TS2345 Argument of type 'string' is not assignable to parameter of type '[1, (2 | undefined)?]'.",
            "17:17 TS2345 Argument of type 'string' is not assignable to parameter of type '[1, (2 | undefined)?]'.",
            "2:40 TS2345 Argument of type '\"z\"' is not assignable to parameter of type 'never'.",
            "3:15 TS2345 Argument of type '\"z\"' is not assignable to parameter of type 'never'.",
            "4:30 TS2345 Argument of type '\"z\"' is not assignable to parameter of type '\"x\"'.",
            "6:11 TS2345 Argument of type '999' is not assignable to parameter of type '1000'.",
            "8:10 TS2345 Argument of type '2' is not assignable to parameter of type '3'.",
        ).sorted())
    }
}
