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
 * (LIBS.3) round (P18.310): `Omit` / `Pick` / `Readonly` materialization made complete enough
 * to TRUST (a private / protected member is never carried — `keyof` has none; an index
 * signature is kept; the reference is displayed as `Omit<O, "c">`), mapped and utility
 * constituents of an intersection trusted exactly as far as their SOURCE table is (and never
 * when that table was copied while in flight — ky's `InitOptions`), and TS2339 for a
 * TYPE-level indexed access `X['k']` / `X[0]` on a non-generic object or intersection type.
 * Every expected row is tsgo 7.0.2's own output for the same source (cells under
 * `build/bench/p18310-agent/m1/` and `m3/`; 1-based columns).
 */
class OmitPickMaterializationTest {

    private val strict = "// @strict: true\n// @target: es2020\n// @useRealLibs: true"

    private fun rows(source: String, directives: String = strict): List<String> =
        diagnose(source, directives = directives, fileName = "t.ts")
            .map { d -> "${d.line}:${d.character} TS${d.code} ${d.message}" + d.messageChain.joinToString("") { " | ${it.trim()}" } }
            .sorted()

    private fun missing(line: Int, col: Int, name: String, type: String) =
        "$line:$col TS2339 Property '$name' does not exist on type '$type'."

    @Test
    fun `a materialized Omit keeps methods, accessors, optional and readonly members and displays as the reference`() {
        val r = rows(
            """
            interface O { a: number; b?: string; readonly r: boolean; m(x: number): string; get g(): number; set s(v: number); c: 1 }
            declare const x: Omit<O,'c'>;
            export const v: symbol[] = [x.a, x.b, x.r, x.m, x.g, x.s, x.c, x.zz];
            x.r = true;
            """,
        )
        assert(
            r == listOf(
                missing(3, 61, "c", "Omit<O, \"c\">"),
                missing(3, 66, "zz", "Omit<O, \"c\">"),
                "4:3 TS2540 Cannot assign to 'r' because it is a read-only property.",
            ),
        )
    }

    @Test
    fun `a materialized Omit carries a base interface's members`() {
        val r = rows(
            """
            interface B { b: string; bm(): void } interface O extends B { a: number; c: 1 }
            declare const x: Omit<O,'c'>;
            export const v: symbol[] = [x.a, x.b, x.bm, x.c, x.zz];
            """,
        )
        assert(r == listOf(missing(3, 47, "c", "Omit<O, \"c\">"), missing(3, 52, "zz", "Omit<O, \"c\">")))
    }

    @Test
    fun `Omit of a class leaves its private member out, as keyof does`() {
        val r = rows(
            """
            class K { a = 1; private p = 2; m() { return 1 } c = 1 }
            declare const x: Omit<K,'c'> & { d: 1 };
            export const v: symbol[] = [x.a, x.m, x.d, x.p, x.c, x.zz];
            """,
        )
        val t = "Omit<K, \"c\"> & { d: 1; }"
        assert(r == listOf(missing(3, 46, "p", t), missing(3, 51, "c", t), missing(3, 56, "zz", t)))
    }

    @Test
    fun `Omit over a string or numeric index signature keeps the index signature`() {
        val r = rows(
            """
            interface O { [k: string]: number; a: 1; c: 2 }
            declare const x: Omit<O,'c'>;
            export const q: symbol = x.a;
            export const q2: symbol = x;
            interface P { [k: number]: string; a: 1; c: 2 }
            declare const y: Omit<P,'c'>;
            export const q3: symbol = y;
            export const q4: symbol = y[3];
            """,
        )
        assert(
            r == listOf(
                "3:14 TS2322 Type 'number' is not assignable to type 'symbol'.",
                "4:14 TS2322 Type 'Omit<O, \"c\">' is not assignable to type 'symbol'.",
                "7:14 TS2322 Type 'Omit<P, \"c\">' is not assignable to type 'symbol'.",
                "8:14 TS2322 Type 'string' is not assignable to type 'symbol'.",
            ),
        )
    }

    @Test
    fun `an Omit, a Pick and an Omit of an Omit constituent decide a missing member on an intersection`() {
        val a = rows(
            """
            interface O { a: number; b?: string; m(): void; get g(): number; c: 1 }
            declare const x: Omit<O,'c'> & { d: 1 };
            export const v: symbol[] = [x.a, x.b, x.m, x.g, x.d, x.c, x.zz];
            """,
        )
        assert(a == listOf(missing(3, 56, "c", "Omit<O, \"c\"> & { d: 1; }"), missing(3, 61, "zz", "Omit<O, \"c\"> & { d: 1; }")))
        val b = rows(
            """
            interface B { b: string } interface O extends B { a: number; m(): void; c: 1 }
            declare const x: Pick<O,'a'|'b'|'m'> & { d: 1 };
            export const v: symbol[] = [x.a, x.b, x.m, x.d, x.c, x.zz];
            """,
        )
        val pt = "Pick<O, \"a\" | \"b\" | \"m\"> & { d: 1; }"
        assert(b == listOf(missing(3, 51, "c", pt), missing(3, 56, "zz", pt)))
        val c = rows(
            """
            interface O { a: number; b: string; c: 1 }
            declare const x: Omit<Omit<O,'c'>,'b'> & { d: 1 };
            export const v: symbol[] = [x.a, x.d, x.b, x.c];
            """,
        )
        val ot = "Omit<Omit<O, \"c\">, \"b\"> & { d: 1; }"
        assert(c == listOf(missing(3, 41, "b", ot), missing(3, 46, "c", ot)))
    }

    @Test
    fun `lib-typed Omit and Pick constituents`() {
        val a = rows(
            """
            type I = Omit<RequestInit,'body'> & { json?: unknown };
            declare const x: I;
            export const v: symbol[] = [x.method, x.headers, x.signal, x.json, x.body, x.zz];
            """,
        )
        assert(a == listOf(missing(3, 70, "body", "I"), missing(3, 78, "zz", "I")))
        val b = rows(
            """
            declare const x: Pick<Array<number>,'length'|'push'> & { d: 1 };
            export const v: symbol[] = [x.length, x.push, x.d, x.pop];
            """,
        )
        assert(b == listOf(missing(2, 54, "pop", "Pick<number[], \"length\" | \"push\"> & { d: 1; }")))
    }

    @Test
    fun `Partial, Required, Readonly, a literal-key mapped type and Record constituents`() {
        assert(
            rows(
                """
                interface B { b: string } interface O extends B { a: number; m(): void; get g(): number }
                declare const x: Partial<O> & { d: 1 };
                export const v: symbol[] = [x.a, x.b, x.m, x.g, x.d, x.zz];
                """,
            ) == listOf(missing(3, 56, "zz", "Partial<O> & { d: 1; }")),
        )
        assert(
            rows(
                """
                interface O { a?: number; b: string }
                declare const x: Required<O> & Readonly<O> & { d: 1 };
                export const v: symbol[] = [x.a, x.b, x.d, x.zz];
                """,
            ) == listOf(missing(3, 46, "zz", "Required<O> & Readonly<O> & { d: 1; }")),
        )
        assert(
            rows(
                """
                type M = { [K in 'a' | 'b']: number } & { c: string };
                declare const x: M;
                export const v: symbol[] = [x.a, x.c, x.d];
                """,
            ) == listOf(missing(3, 41, "d", "M")),
        )
        assert(
            rows(
                """
                declare const x: Record<'a'|'b', number> & { c: 1 };
                export const v: symbol[] = [x.a, x.c, x.d];
                """,
            ) == listOf(missing(2, 41, "d", "Record<\"a\" | \"b\", number> & { c: 1; }")),
        )
    }

    @Test
    fun `control - an Omit copied from an interface whose heritage base was in flight is not trusted`() {
        // ky's shape ((CHK.219)(e)): `Options`' member table is planted while `KyOptions` is still
        // resolving and rebuilt later, but `InitOptions`' `Omit<Options, 'hooks'>` copied the
        // provisional table. tsgo reports only `hooks` and `zz` here (3,74) and (3,84); the reads of
        // members that DO exist must never be reported.
        val r = diagnose(
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
            import type {InitOptions} from './options';
            declare const io: InitOptions;
            export const v = [io.json, io.baseUrl, io.cache, io.method, io.extra, io.hooks, io.zz];
            """,
            directives = strict,
        )
        val legal = listOf("json", "baseUrl", "cache", "method", "extra")
        assert(r.none { d -> d.code == 2339 && legal.any { d.message.startsWith("Property '$it' ") } })
    }

    @Test
    fun `a type-level indexed access with a missing literal key is TS2339 on objects and intersections`() {
        val r = rows(
            """
            type A0 = { a: number };
            export type X0 = A0['zz'];
            type A = { a: number } & { b: string };
            export type X1 = A['a'];
            export type X2 = A['zz'];
            interface O { a: number; c: 1 }
            type B = Omit<O,'c'> & { d: 1 };
            export type X3 = B['c'];
            type M = { [K in 'a'|'b']: number } & { c: 1 };
            export type X4 = M['zz'];
            interface I { a: number } interface J { b: string }
            export type X5 = (I & J)['zz'];
            export type X6 = (I & J)['a' | 'zz'];
            type N = { [k: number]: number } & { a: 1 };
            export type X7 = N['zz'];
            export type X8 = N[5];
            """,
        )
        assert(
            r == listOf(
                missing(2, 21, "zz", "A0"),
                missing(5, 20, "zz", "A"),
                missing(8, 20, "c", "B"),
                missing(10, 20, "zz", "M"),
                missing(12, 26, "zz", "I & J"),
                missing(13, 26, "zz", "I & J"),
                missing(15, 20, "zz", "N"),
            ).sorted(),
        )
    }

    @Test
    fun `fixed-length-array - a numeric key and a removed method at type level, a numeric key written at value level`() {
        val r = rows(
            """
            type Keys = 'splice' | 'push' | 'pop' | 'shift' | 'unshift';
            type Filter<K, E> = K extends E ? never : K;
            type Except<T, K extends keyof T> = { [P in keyof T as Filter<P, K>]: T[P] };
            type F = Except<[string, string, string], Keys | number | 'length'> & { readonly length: 3 };
            export type A0 = F[0];
            export type A3 = F[3];
            export type L = F['length'];
            export type S = F['splice'];
            export type P = F['push'];
            declare const f: F;
            f[0] = 'a';
            f[3] = 'd';
            """,
        )
        assert(
            r == listOf(
                missing(6, 20, "3", "F"),
                missing(8, 19, "splice", "F"),
                missing(9, 19, "push", "F"),
                "12:1 TS7053 Element implicitly has an 'any' type because expression of type '3' can't be used to index type 'F'. | Property '3' does not exist on type 'F'.",
            ).sorted(),
        )
    }

    @Test
    fun `control - a type-level access where a type parameter may be in scope, or through a string index, stays silent`() {
        // tsgo reports TS2536 for the first three (a generic access, not a missing property); this
        // checker does not model that yet, and must never answer TS2339 for them.
        val r = rows(
            """
            export type G<Node> = (Node & { a: 1 })['zz'];
            export type H<Text> = Text extends string ? (Text & { a: 1 })['zz'] : never;
            export type M = { [Event in 'x']: (Event & { a: 1 })['zz'] };
            export type S = { [k: string]: number } & { a: 1 };
            export type S1 = S['anything'];
            export interface I { a: number }
            export type L = (I & { b: 1 })['toString'];
            """,
        )
        assert(r.none { it.contains("TS2339") })
    }

    @Test
    fun `a branded string read with a numeric key stays silent as tsgo - tsc's own scriptInfo path`() {
        // tsgo: `p[0]` and `p.length` are clean; `p["x"]` is TS7015, which is not modelled, so silent here.
        val r = rows(
            """
            type Path = string & { __normalizedPathTag: any };
            declare const p: Path;
            export const c = p[0];
            export const d = p["x"];
            export const n = p.length;
            """,
        )
        assert(r.isEmpty())
    }
}
