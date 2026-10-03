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

import kotlin.test.Test
import com.xemantic.kotlin.test.assert

/**
 * (CHK.219) the inference gaps that blocked zod's `util.arrayToEnum` (61 rows of zod's
 * `ZodError.ts` family): (a) a generic call returning a MAPPED type over its own type
 * parameter answered `any` (the declared return resolved to `any`, and instantiating that
 * lost the call's binding); (b) a type parameter with a PRIMITIVE constraint widened its
 * literal candidates (tsgo's `getCovariantInference` keeps them), and a TUPLE-constrained
 * one inferred an array; (c) a generic ARROW's annotated parameter types were never
 * persisted, so `const f = <T>(x: T) => …` inferred nothing at any call; (d) an explicit
 * type argument failing its constraint still had its call's arguments checked; (e) an
 * interface whose heritage base was IN FLIGHT on a resolution cycle froze a member table
 * without that base. Every expected row is tsgo 7.0.2's own output for the same source,
 * read out with a deliberate mis-assignment so the inferred type is in the message.
 */
class GenericMappedReturnInferenceTest {

    private fun rows(source: String, directives: String = "// @strict: true") =
        diagnose(source, directives).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }

    @Test
    fun `explicit type argument instantiates a mapped return type`() {
        assert(rows(
            """
            declare function mr<U extends string>(x: U): { [k in U]: k };
            const v = mr<"a">("a");
            v.zz;
            """
        ) == listOf("3,3: TS2339 Property 'zz' does not exist on type '{ a: \"a\"; }'."))
    }

    @Test
    fun `inferred type argument instantiates a mapped return type`() {
        assert(rows(
            """
            declare function mr<U extends string>(x: U): { [k in U]: k };
            const v = mr("b");
            const p: "zz" = v.b;
            v.zz;
            """
        ) == listOf(
            "3,7: TS2322 Type '\"b\"' is not assignable to type '\"zz\"'.",
            "4,3: TS2339 Property 'zz' does not exist on type '{ b: \"b\"; }'.",
        ))
    }

    @Test
    fun `a mapped type is not widened by a const initializer`() {
        assert(rows(
            """
            type M = { [k in "a"]: k };
            declare const m: M;
            const m2 = m;
            m2.zz;
            """
        ) == listOf("4,4: TS2339 Property 'zz' does not exist on type 'M'."))
    }

    @Test
    fun `negative control - an object literal initializer still widens`() {
        assert(rows(
            """
            const o = { a: "a" };
            o.zz;
            """
        ) == listOf("2,3: TS2339 Property 'zz' does not exist on type '{ a: string; }'."))
    }

    @Test
    fun `a primitive-constrained type parameter keeps a literal argument`() {
        assert(rows(
            """
            declare function id<T extends string>(x: T): T;
            const q: "zz" = id("a");
            declare function num<N extends number>(x: N): N;
            const q2: "zz" = num(1);
            declare function lit<K extends "a" | "b">(x: K): K;
            const q3: "zz" = lit("a");
            """
        ) == listOf(
            "2,7: TS2322 Type '\"a\"' is not assignable to type '\"zz\"'.",
            "4,7: TS2322 Type '1' is not assignable to type '\"zz\"'.",
            "6,7: TS2322 Type '\"a\"' is not assignable to type '\"zz\"'.",
        ))
    }

    @Test
    fun `two literal candidates infer their union`() {
        val r = diagnose(
            """
            declare function pair<T extends string>(x: T, y: T): T;
            const q: "zz" = pair("a", "b");
            declare function arr<T extends string>(x: T[]): T;
            const q2: "zz" = arr(["a", "b"]);
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message} | ${it.messageChain}" }
        assert(r == listOf(
            "2,7: TS2322 Type '\"a\" | \"b\"' is not assignable to type '\"zz\"'. | [  Type '\"a\"' is not assignable to type '\"zz\"'.]",
            "4,7: TS2322 Type '\"a\" | \"b\"' is not assignable to type '\"zz\"'. | [  Type '\"a\"' is not assignable to type '\"zz\"'.]",
        ))
    }

    @Test
    fun `a literal inferred through a primitive constraint survives a let`() {
        assert(rows(
            """
            declare function id<T extends string>(x: T): T;
            let l = id("a");
            const q: "zz" = l;
            """
        ) == listOf("3,7: TS2322 Type '\"a\"' is not assignable to type '\"zz\"'."))
    }

    @Test
    fun `negative control - an object-constrained type parameter still widens`() {
        assert(rows(
            """
            declare function wide<T extends object>(x: T): T;
            const q: "zz" = wide({ a: "a" }).a;
            """
        ) == listOf("2,7: TS2322 Type 'string' is not assignable to type '\"zz\"'."))
    }

    @Test
    fun `a tuple constraint infers a tuple and keeps literals only under a primitive-constrained element`() {
        assert(rows(
            """
            declare function ate<T extends string, U extends [T, ...T[]]>(items: U): U;
            const q: "zz" = ate(["a", "b"]);
            declare function tup<U extends [string, ...string[]]>(x: U): U;
            const q2: "zz" = tup(["a", "b"]);
            """
        ) == listOf(
            "2,7: TS2322 Type '[\"a\", \"b\"]' is not assignable to type '\"zz\"'.",
            "4,7: TS2322 Type '[string, string]' is not assignable to type '\"zz\"'.",
        ))
    }

    @Test
    fun `a generic arrow's annotated parameters take part in inference`() {
        assert(rows(
            """
            const id2 = <T extends string>(x: T): T => x;
            const q: "zz" = id2("a");
            const first = <T,>(x: T[]): T => x[0];
            const q2: "zz" = first([1]);
            """
        ) == listOf(
            "2,7: TS2322 Type '\"a\"' is not assignable to type '\"zz\"'.",
            "4,7: TS2322 Type 'number' is not assignable to type '\"zz\"'.",
        ))
    }

    @Test
    fun `zod arrayToEnum - a generic arrow returning a mapped type over a tuple constraint`() {
        assert(rows(
            """
            export namespace util {
              export const arrayToEnum = <T extends string, U extends [T, ...T[]]>(items: U): { [k in U[number]]: k } => {
                const obj: any = {};
                return obj;
              };
            }
            const Code = util.arrayToEnum(["a", "b"]);
            Code.zz;
            const q: "zz" = Code.a;
            """
        ) == listOf(
            "8,6: TS2339 Property 'zz' does not exist on type '{ a: \"a\"; b: \"b\"; }'.",
            "9,7: TS2322 Type '\"a\"' is not assignable to type '\"zz\"'.",
        ))
    }

    @Test
    fun `an explicit type argument failing its constraint reports only the type argument`() {
        assert(rows(
            """
            interface ObjA { y?: string }
            interface ObjB { [key: string]: any }
            interface Opts<A, B> { a: A, b: B }
            declare function fn<A extends ObjA, B extends ObjB = ObjB>(opts: Opts<A, B>): string;
            interface MyObjA { x: string }
            fn<MyObjA>({ a: { x: 'X', y: 'Y' }, b: {} });
            """
        ) == listOf("6,4: TS2559 Type 'MyObjA' has no properties in common with type 'ObjA'."))
    }

    @Test
    fun `an interface whose heritage base is in flight on an import cycle keeps that base's members`() {
        assert(rows(
            """
            // @Filename: hooks.ts
            import type {InitOptions, NormalizedOptions} from './options';
            export type InitHook = (options: InitOptions) => void;
            export type BeforeRequestHook = (state: { options: Readonly<NormalizedOptions> }) => void;
            export type Hooks = { init?: InitHook[]; beforeRequest?: BeforeRequestHook[] };

            // @Filename: options.ts
            import type {Hooks} from './hooks';
            interface Req { cache?: string; body?: string; headers?: string }
            export type KyOptions = { json?: unknown; baseUrl?: string; hooks?: Hooks | undefined };
            type RequestOptions = { [Key in Exclude<keyof Req, 'headers'>]?: Req[Key] | undefined };
            export interface Options extends KyOptions, RequestOptions { method?: string | undefined }
            export type InitOptions = Omit<Options, 'hooks'> & { extra?: number };
            export interface NormalizedOptions extends Readonly<Req> { readonly baseUrl?: Options['baseUrl'] }

            // @Filename: t.ts
            import type {Options} from './options';
            declare const o: Options;
            export const p1: number = o.json;
            export const o2: Options = { json: 1 };
            """,
            "// @strict: true\n// @useRealLibs: true",
        ) == listOf("3,14: TS2322 Type 'unknown' is not assignable to type 'number'."))
    }
}
