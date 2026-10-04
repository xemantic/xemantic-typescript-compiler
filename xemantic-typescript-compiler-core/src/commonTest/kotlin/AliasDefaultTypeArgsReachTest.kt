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
 * (P18.293) a reference to a generic type alias that OMITS a defaulted argument
 * ([AliasDefaultTypeArgs]) — and the three older defects that making such a reference
 * evaluate REACHED on real libraries (superstruct, hono). Every expected row is tsgo
 * 7.0.2's, measured on the same source.
 */
class AliasDefaultTypeArgsReachTest {

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @lib: esnext"

    private val isNeverPrelude = """
        type IsNever<T> = [T] extends [never] ? true : false;
        type A<T, Op = {}> = IsNever<T>;
    """.trimIndent() + "\n"

    // --- 1. the alias-default fill, at both reader sites ----------------------

    @Test
    fun `an omitted defaulted alias argument keeps the explicit ones at a variable annotation`() {
        val d = diagnose(isNeverPrelude + """
            const c: A<[]> = "s";
        """.trimIndent())
        assert(rows(d) == listOf("3:7 TS2322 Type '\"s\"' is not assignable to type 'false'."))
    }

    @Test
    fun `an omitted defaulted alias argument keeps the explicit ones at a call argument`() {
        val d = diagnose(isNeverPrelude + """
            declare function expectType<T>(v: T): void;
            expectType<A<[]>>(true);
        """.trimIndent())
        assert(rows(d) == listOf("4:19 TS2345 Argument of type 'true' is not assignable to parameter of type 'false'."))
    }

    @Test
    fun `a fully applied and a default-completed reference answer the same type`() {
        val d = diagnose(isNeverPrelude + """
            const e: A<[], {}> = "s";
            const ok: A<[]> = false;
        """.trimIndent())
        assert(rows(d) == listOf("3:7 TS2322 Type '\"s\"' is not assignable to type 'false'."))
    }

    // --- 2. keyof unknown (hono client.ts:103) -------------------------------

    @Test
    fun `keyof unknown is never in a conditional over a defaulted parameter`() {
        val d = diagnose(
            """
            type O<T = unknown> = keyof T extends never ? "y" : "n";
            const ok: O = "y";
            const bad: O = "n";
            type K = keyof unknown;
            const k: K = "z";
            """,
        )
        val codes = d.map { "${it.line}:${it.character} TS${it.code}" }
        assert(codes == listOf("3:7 TS2322", "5:7 TS2322"))
        assert(d.last().message == "Type 'string' is not assignable to type 'never'.")
    }

    // --- 3. await of a union (hono middleware/cache:287) ----------------------

    @Test
    fun `await distributes over a union containing a Promise`() {
        val d = diagnose(
            """
            interface Resp { ok: boolean }
            declare const hook: () => Resp | false | Promise<Resp | false>;
            export async function a() {
              const m = await hook();
              if (!m) return;
              const x: Resp = m;
              return x;
            }
            export async function b() {
              const m = await hook();
              const x: Resp | false = m;
              const y: string = m;
              return x;
            }
            declare const gen: () => Promise<string> | string;
            export async function c() {
              let key = "k";
              key = await gen();
              const z: number = await gen();
            }
            """,
        )
        assert(rows(d) == listOf(
            "12:9 TS2322 Type 'false | Resp' is not assignable to type 'string'.",
            "19:9 TS2322 Type 'string' is not assignable to type 'number'.",
        ))
    }

    // --- 4. a MODULE lib file contributes only its `declare global` (superstruct) ---

    @Test
    fun `a module lib file does not merge its local Iterator into the global one`() {
        val d = diagnose(
            """
            declare const it: IterableIterator<number>;
            const i2: Iterator<number> = it;
            declare const m: Set<any>;
            const m2: Iterable<any> = m;
            declare const mm: Map<any, any>;
            const m3 = new Map(mm);
            """,
            directives = realLibs,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the declare global block of a module lib file is merged`() {
        val d = diagnose(
            """
            declare const s: IteratorObject<number, any, any>;
            const a: string = s.map;
            declare const i: Iterator<number>;
            const n: number = i.map;
            """,
            directives = realLibs,
        )
        assert(d.map { "${it.line}:${it.character} TS${it.code}" } == listOf("2:7 TS2322", "4:21 TS2339"))
        assert(d.first().message == "Type '<U>(callbackfn: (value: number, index: number) => U) => " +
            "IteratorObject<U, undefined, unknown>' is not assignable to type 'string'.")
    }

    // --- 5. the string-mapping intrinsics ([StringMappingTypes]) ---------------

    @Test
    fun `a string mapping intrinsic over a literal evaluates`() {
        val d = diagnose(
            """
            const a: Uppercase<"ab"> = "AB";
            const b: Capitalize<"ab" | "cd"> = "Cd";
            const c: Uncapitalize<"AB"> = "aB";
            const e: Lowercase<"AB"> = "x";
            """,
            directives = realLibs,
        )
        assert(d.map { "${it.line}:${it.character} TS${it.code}" } == listOf("4:7 TS2322"))
    }

    // --- 6. the three guards that keep a COMPLETED reference on its old answer ----

    /** hono `helper/ssg/middleware.ts:43`: `E extends Env` fails the argument guard (it has no
     *  "type parameter via its constraint" rule), and a completed reference must not collapse
     *  to `errorType` there — that loses the contextual parameter types. */
    @Test
    fun `a completed reference whose argument fails the constraint guard keeps its contextual type`() {
        val d = diagnose(
            """
            interface Env { v?: string }
            type MH<E extends Env = any, P extends string = string> = (c: E, next: () => void) => void;
            interface Mw { <E extends Env = Env>(p: number): MH<E> }
            export const mw: Mw = (p) => (c, next) => {};
            """,
        )
        assert(d.isEmpty())
    }

    /** type-fest's accumulator parameters: an omitted `Acc = []` is the accumulator of a recursion
     *  our depth budget (10) cannot finish where tsgo's (1,000 tail-recursive) does. */
    @Test
    fun `a completed reference that exhausts the depth budget does not report TS2589`() {
        val d = diagnose(
            """
            type Rep<S extends string, Acc extends unknown[] = []> =
              S extends `${'$'}{infer H}${'$'}{infer R}` ? Rep<R, [...Acc, H]> : Acc['length'];
            const n: Rep<"abcdefghijklmnopqrst"> = 20;
            """,
        )
        assert(d.isEmpty())
    }

    /** type-fest `CamelCase`: a template whose span is a type this checker did not compute
     *  (an `any` span, so the template is imprecise) relates as that `any`, not as `string`. */
    @Test
    fun `an imprecise template with an uncomputed span is not reported against a literal`() {
        val d = diagnose(
            """
            type Rev<S extends string> = S extends `${'$'}{infer H}${'$'}{infer R}` ? `${'$'}{Rev<R>}${'$'}{H}` : '';
            type C<S extends string, O extends { p?: boolean } = {}> = `${'$'}{O['p'] extends true ? '_' : ''}${'$'}{Rev<S>}`;
            declare function expectType<T>(v: T): void;
            const c: C<'abcdefghijklmnopqrstuvwxyz'> = 'zyxwvutsrqponmlkjihgfedcba';
            expectType<'zyxwvutsrqponmlkjihgfedcba'>(c);
            """,
        )
        assert(d.isEmpty())
    }

    // --- 7. conditional evaluation reached by tsc's own `MatchingKeys` (8 profiles) ----

    private val matchingKeysPrelude = """
        type MK<R, M, K extends keyof R = keyof R> = K extends (R[K] extends M ? K : never) ? K : never;
        interface IT { y: number }
        interface A { flags: number; c1?: IT; c2?: IT; s?: string }
    """.trimIndent() + "\n"

    /** A distributive conditional instantiates its EXTENDS type per constituent when the
     *  extends type names the check type parameter. */
    @Test
    fun `a distributive conditional re-evaluates an extends type naming the check parameter`() {
        val d = diagnose(matchingKeysPrelude + """
            const k: "zz" = null! as MK<A, IT | undefined, keyof A>;
        """.trimIndent())
        assert(rows(d) == listOf("4:7 TS2322 Type '\"c1\" | \"c2\"' is not assignable to type '\"zz\"'."))
    }

    /** Only a NAKED type parameter distributes: `R[K] = string | undefined` is tested whole. */
    @Test
    fun `an indexed access check type is not distributed over its union`() {
        val d = diagnose(matchingKeysPrelude + """
            declare const key: MK<A, IT | undefined>;
            declare const r: A;
            r[key] = { y: 1 };
            type IA<T extends { a?: string }> = T['a'] extends string ? 1 : 2;
            const two: IA<{ a?: string }> = 2;
        """.trimIndent())
        assert(d.isEmpty())
    }
}
