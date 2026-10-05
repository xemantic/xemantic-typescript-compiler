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
 * (P18.301) Three union rules tsgo applies and this checker did not, all three reached by type-fest:
 *
 * 1. A conditional whose check type is a WRITTEN union (`A | B extends X ? …`) is NOT distributive —
 *    only a naked type parameter distributes. type-fest `Sum<-999, PositiveInfinity>` answered
 *    `number | Infinity`.
 * 2. `keyof (A | B)` is the keys COMMON to every constituent (it answered `string`). type-fest
 *    `KeyAsString<{1; foo} | {1; bar}>` is `'1'`.
 * 3. A HOMOMORPHIC mapped type `[K in keyof T]` with `T` bound to a union of object types distributes
 *    over it (`Partial<A | B>` is `Partial<A> | Partial<B>`). Rule 2 needs it: without it the common
 *    keys drop every non-shared member of `Partial<A | B>` / `Mutable<A | B>`.
 *
 * 4. Rule 3 makes tsc's own `let node: Mutable<A | B>; node = makeA(); node.onlyOnA` (parser.ts:6741)
 *    real, so a CALL right-hand side of `=` must reduce a union-declared reference to the members its
 *    return relates to (tsgo `getAssignmentReducedType`) — it kept the whole declared union.
 *
 * Every expected row is tsgo 7.0.2's row, full text.
 */
class UnionKeyofCheckTypeTest {

    private fun rows(d: List<Diagnostic>): List<String> = d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val prelude = """
        declare function expectType<T>(v: T): void
        type PI = 1e999; type NI = -1e999;
    """.trimIndent() + "\n"

    @Test
    fun `a written union check type is evaluated as a whole`() {
        val d = diagnose(
            prelude + """
            type S1<A extends number, B extends number> = A | B extends PI | NI ? 'yes' : 'no';
            expectType<'no'>({} as S1<-999, PI>);
            type S1p<A extends number, B extends number> = (A | B) extends PI | NI ? 'yes' : 'no';
            expectType<'no'>({} as S1p<-999, PI>);
            expectType<'yes'>({} as S1<NI, PI>);
            """.trimIndent(),
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a whole-union check that fails reports the false branch alone`() {
        val d = diagnose(
            prelude + """
            type S1<A extends number, B extends number> = A | B extends PI | NI ? 'yes' : 'no';
            expectType<'yes'>({} as S1<-999, PI>);
            """.trimIndent(),
        )
        assert(rows(d) == listOf("4:19 TS2345 Argument of type '\"no\"' is not assignable to parameter of type '\"yes\"'."))
    }

    @Test
    fun `control - a naked type parameter still distributes`() {
        val d = diagnose(
            prelude + """
            type D<T> = T extends PI ? 'yes' : 'no';
            expectType<'no'>({} as D<-999 | PI>);
            """.trimIndent(),
        )
        assert(rows(d) == listOf("4:18 TS2345 Argument of type '\"no\" | \"yes\"' is not assignable to parameter of type '\"no\"'."))
    }

    @Test
    fun `keyof a union is the keys common to every constituent`() {
        val d = diagnose(
            prelude + """
            type K1 = keyof ({ a: 1; foo: 1 } | { a: 1; bar: 1 });
            expectType<'a'>({} as K1);
            type K2 = keyof ({ foo: 1 } | { bar: 1 });
            expectType<never>({} as K2);
            type K3 = keyof ({ [k: string]: 1 } | { a: 1 });
            expectType<'a'>({} as K3);
            type KS<B> = `${'$'}{Extract<keyof B, string>}`;
            expectType<'a'>({} as KS<{ a: 1; foo: 1 } | { a: 1; bar: 1 }>);
            """.trimIndent(),
        )
        assert(d.isEmpty())
    }

    @Test
    fun `keyof a union rejects a key one constituent lacks`() {
        val d = diagnose(
            prelude + """
            type K1 = keyof ({ a: 1; foo: 1 } | { a: 1; bar: 1 });
            expectType<'foo'>({} as K1);
            """.trimIndent(),
        )
        assert(rows(d) == listOf("4:19 TS2345 Argument of type '\"a\"' is not assignable to parameter of type '\"foo\"'."))
    }

    private val objects = """
        interface A { readonly kind: 1; readonly a: string }
        interface B { readonly kind: 2; readonly b: number }
        type Opt<T> = { [K in keyof T]?: T[K] };
        type Mutable<T> = { -readonly [K in keyof T]: T[K] };
    """.trimIndent() + "\n"

    @Test
    fun `a homomorphic mapped type distributes over a union of objects`() {
        val d = diagnose(
            objects + """
            const p1: Opt<A | B> = { a: "s" };
            const p2: Opt<A | B> = { b: 1 };
            declare const m: Mutable<A | B>;
            if (m.kind === 1) { const s: string = m.a; }
            m.kind = 1;
            """.trimIndent(),
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a distributed mapped type still rejects a key no constituent has`() {
        val d = diagnose(
            objects + """
            const p3: Opt<A | B> = { c: 1 };
            """.trimIndent(),
        )
        assert(rows(d) == listOf("5:26 TS2353 Object literal may only specify known properties, and 'c' does not exist in type 'Opt<A | B>'."))
    }

    private val calls = """
        interface SP { readonly kind: 1; equalsToken?: string }
        interface PA { readonly kind: 2; initializer: number }
        type Mutable<T extends object> = { -readonly [K in keyof T]: T[K] };
        declare function mkSP(): SP;
        declare function mkPA(): PA;
        declare const c: boolean;
    """.trimIndent() + "\n"

    @Test
    fun `a call assigned to a union-declared local narrows it to the related member`() {
        val d = diagnose(
            calls + """
            function f() {
              let node: Mutable<SP | PA>;
              if (c) { node = mkSP(); node.equalsToken = "x"; }
              else { node = mkPA(); node.initializer = 1; }
            }
            """.trimIndent(),
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the reduced member still rejects the other constituent's property`() {
        val d = diagnose(
            calls + """
            function f() {
              let u: SP | PA;
              if (c) { u = mkSP(); u.equalsToken = "y"; }
              u = mkSP();
              u.initializer = 1;
            }
            """.trimIndent(),
        )
        assert(rows(d) == listOf("11:5 TS2339 Property 'initializer' does not exist on type 'SP'."))
    }
}
