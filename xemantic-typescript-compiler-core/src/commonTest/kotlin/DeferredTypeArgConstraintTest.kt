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
 * (CHK.213) A type-argument constraint is instantiated with ALL the reference's type
 * arguments (tsgo `checkTypeArgumentConstraints`), not resolved once against a fresh
 * parameter's constraint. Every expected row below is tsgo 7.0.2's, byte for byte.
 */
class DeferredTypeArgConstraintTest {

    private fun rows(source: String): List<String> =
        diagnose(source).filter { it.code == 2344 || it.code == 2741 || it.code == 2322 }
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `K extends keyof O is checked against the argument for O`() {
        val r = rows(
            """
            type Ex = { a: number; b: string };
            type P2<O, K extends keyof O> = O;
            export type A1 = P2<Ex, 'c'>;
            export type A2 = P2<Ex, 'a'>;
            export type A3 = P2<{ a: 1 }, 'c'>;
            interface I2<O, K extends keyof O> { o: O }
            export type A4 = I2<Ex, 'c'>;
            """
        )
        assert(r == listOf(
            "3:25 TS2344 Type '\"c\"' does not satisfy the constraint 'keyof Ex'.",
            "5:31 TS2344 Type '\"c\"' does not satisfy the constraint '\"a\"'.",
            "7:25 TS2344 Type '\"c\"' does not satisfy the constraint 'keyof Ex'.",
        ))
    }

    @Test
    fun `an indexed-access constraint over a sibling is instantiated - not judged against its constraint`() {
        val r = rows(
            """
            interface Def { check: string; abort?: boolean; when?: () => boolean }
            interface Internals<T> { def: Def; v?: T }
            interface Check<T = never> { _zod: Internals<T> }
            interface FmtDef extends Def { format: string }
            interface FmtInternals extends Internals<string> { def: FmtDef }
            interface Fmt extends Check<string> { _zod: FmtInternals }
            type Ex<T, U> = T extends U ? never : T;
            type P<T extends Check<any>, K extends Ex<keyof T["_zod"]["def"], "check"> = never> = K;
            export type A = P<Fmt, "format">;
            type V<O, X extends O[keyof O]> = O;
            export type B = V<{ a: number; b: string }, boolean>;
            """
        )
        assert(r == listOf(
            "11:45 TS2344 Type 'boolean' does not satisfy the constraint 'string | number'.",
        ))
    }

    @Test
    fun `siblings defaults and conditionals in a constraint`() {
        val r = rows(
            """
            type D<T, U extends T = T> = U;
            export type A1 = D<string>;
            export type A2 = D<string, number>;
            type L<A extends B, B> = A;
            export type A3 = L<'x', number>;
            export type A4 = L<'x', string>;
            type Cnd<T, U extends (T extends string ? number : boolean)> = U;
            export type A5 = Cnd<string, true>;
            export type A6 = Cnd<number, true>;
            interface Cmp<T extends Cmp<T>> { c(o: T): number }
            interface Good { c(o: Good): number }
            export type A7 = Cmp<Good>;
            """
        )
        assert(r == listOf(
            "3:28 TS2344 Type 'number' does not satisfy the constraint 'string'.",
            "5:20 TS2344 Type 'string' does not satisfy the constraint 'number'.",
            "8:30 TS2344 Type 'boolean' does not satisfy the constraint 'number'.",
        ))
    }

    @Test
    fun `a tuple argument is checked against an array constraint`() {
        val r = rows(
            """
            type AndAll<T extends readonly boolean[]> = T[number];
            export type A1 = AndAll<[1, 0]>;
            export type A2 = AndAll<[true, false]>;
            type SA<T extends string[]> = T;
            export type A3 = SA<['x']>;
            """
        )
        assert(r == listOf(
            "2:25 TS2344 Type '[1, 0]' does not satisfy the constraint 'readonly boolean[]'.",
        ))
    }

    @Test
    fun `a type-parameter argument whose own constraint names a sibling satisfies the instantiated constraint`() {
        val r = rows(
            """
            interface Def { check: string; error?: string; abort?: boolean }
            interface Check { _zod: { def: Def } }
            type Ex<T, U> = T extends U ? never : T;
            type Params<T extends Check, OmitKeys extends keyof T["_zod"]["def"] = never> = T;
            export type CP<T extends Check = Check, A extends Ex<keyof T["_zod"]["def"], "check"> = never> =
                Params<T, "check" | A>;
            export type CP2<T extends Check = Check, A extends keyof T["_zod"]["def"] = never> = Params<T, A>;
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `call and new type arguments instantiate the constraint`() {
        val r = rows(
            """
            type Ex = { a: number; b: string };
            declare function f<O, K extends keyof O>(o: O, k: K): void;
            f<Ex, 'c'>({ a: 1, b: '' }, 'c');
            f<Ex, 'a'>({ a: 1, b: '' }, 'a');
            declare class K3<O, K extends keyof O> { constructor(o: O, k: K) }
            new K3<Ex, 'c'>({ a: 1, b: '' }, 'c');
            """
        )
        assert(r == listOf(
            "3:7 TS2344 Type '\"c\"' does not satisfy the constraint 'keyof Ex'.",
            "6:12 TS2344 Type '\"c\"' does not satisfy the constraint 'keyof Ex'.",
        ))
    }

    @Test
    fun `a type reference written as a call type argument is checked`() {
        val r = rows(
            """
            type Ex = { a: number; b: string };
            type P2<O, K extends keyof O> = O;
            declare function e<T>(x?: T): void;
            e<P2<Ex, 'c'>>();
            e<P2<Ex, 'a'>>();
            """
        )
        assert(r == listOf(
            "4:10 TS2344 Type '\"c\"' does not satisfy the constraint 'keyof Ex'.",
        ))
    }

    @Test
    fun `keyof an index-signature or symbol-keyed object keeps its open keys`() {
        val r = rows(
            """
            declare const sym: unique symbol;
            type B = { [k: string]: number };
            type H = { [sym]: number; readonly x: string };
            type P2<O, K extends keyof O> = O;
            export type A1 = P2<B, 'anything'>;
            export type A2 = P2<B, string>;
            export type A3 = P2<H, typeof sym>;
            export const b1: keyof B = 1;
            export const b2: keyof B = true;
            """
        )
        assert(r == listOf(
            "9:14 TS2322 Type 'boolean' is not assignable to type 'string | number'.",
        ))
    }

    @Test
    fun `a constraint through a distributive conditional alias displays the resolved union`() {
        val r = rows(
            """
            type KeysOfUnion<O> = O extends unknown ? keyof O : never;
            type DO<O, K extends KeysOfUnion<O>> = O;
            type U2 = { a: number; b: string } | { a: number; c: boolean };
            export type X1 = DO<U2, 'd'>;
            export type X2 = DO<U2, 'c'>;
            """
        )
        assert(r == listOf(
            "4:25 TS2344 Type '\"d\"' does not satisfy the constraint '\"a\" | \"b\" | \"c\"'.",
        ))
    }

    @Test
    fun `an element access keyed by a primitive key union reads through the index signatures`() {
        val r = rows(
            """
            interface Ix { a: string; b: number; [k: string]: string | number }
            declare const ix: Ix;
            declare const k: keyof Ix;
            export const p1: boolean = ix[k];
            declare const k2: string | number;
            export const p2: boolean = ix[k2];
            """
        )
        assert(r == listOf(
            "4:14 TS2322 Type 'string | number' is not assignable to type 'boolean'.",
            "6:14 TS2322 Type 'string | number' is not assignable to type 'boolean'.",
        ))
    }
}
