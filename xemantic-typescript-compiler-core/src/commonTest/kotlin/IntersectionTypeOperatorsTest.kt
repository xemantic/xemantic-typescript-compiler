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
 * (CHK.215) (CHK.226) The type operators over an INTERSECTION — `keyof (A & B)`,
 * `(A & B)[K]`, a property of an intersection (which `Required` / `Partial` / `Pick` / `Omit` read
 * through) — and the `infer` / alias-display pieces that `KeysOfUnion` needs. Every expected row
 * is tsgo 7.0.2's, full text, measured in `build/bench/p18282-agent/pin` (the `ab` prelude ends
 * in a blank line, so its fixtures read one line lower). Before the round `keyof (A & B)` was
 * `string`, `(A & B)["p"]` was `any`, `Pick<A & B, K>` dropped `?` and
 * `Required<A & B>` never stripped `undefined`.
 */
class IntersectionTypeOperatorsTest {

    private val ab = """
        type A = { a: number; c: string };
        type B = { b: boolean; c: string };

    """.trimIndent()

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.code} ${it.message}" }

    @Test
    fun `keyof an intersection is the union of the constituent keys`() {
        val r = rows(ab + """
            export const k1: keyof (A & B) = 'a';
            export const k2: keyof (A & B) = 'b';
            export const k3: keyof (A & B) = 'c';
            export const k4: keyof (A & B) = 'd';
        """)
        assert(r == listOf("7:2322 Type '\"d\"' is not assignable to type '\"a\" | \"b\" | \"c\"'."))
    }

    @Test
    fun `keyof an intersection with a string index signature member is open`() {
        val r = rows(ab + """
            type S = { [k: string]: unknown };
            export const k1: keyof (A & S) = 'zz';
            export const k2: keyof (A & S) = 5;
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - keyof an intersection with a type parameter stays open`() {
        val r = rows(ab + """
            export function f<T>(t: T, k: keyof (A & T)) {
              const z: 'a' = k;
              return [t, z];
            }
        """)
        assert(r == listOf("5:2322 Type 'string | number | symbol' is not assignable to type '\"a\"'."))
    }

    @Test
    fun `KeysOfUnion evaluates through UnionToIntersection and each instantiation prints its own keys`() {
        val r = rows("""
            type Record<K extends keyof any, T> = { [P in K]: T };
            type U2I<U> = (U extends unknown ? (d: U) => void : never) extends ((m: infer I) => void) ? I & U : never;
            type KeysOfUnion<O> = keyof U2I<O extends unknown ? Record<keyof O, never> : never>;
            declare const one: KeysOfUnion<{ a: 1 }>;
            declare const two: KeysOfUnion<{ c: 1; a: 1 } | { c: 1; b: 1 }>;
            export const s1: 'a' = one;
            export const s2: 'b' = one;
            export const s3: 'a' | 'b' | 'c' = two;
            export const s4: 'a' = two;
        """)
        assert(r == listOf(
            "7:2322 Type '\"a\"' is not assignable to type '\"b\"'.",
            "9:2322 Type '\"a\" | \"b\" | \"c\"' is not assignable to type '\"a\"'.",
        ))
    }

    @Test
    fun `keyof an intersection over a discriminated union drops the member a disjoint discriminant reduces`() {
        val fixture = """
            enum ABC { A, B }
            type Gen<T extends ABC> = { v: T } & ({ v: ABC.A; a: string } | { v: ABC.B; b: string });
            type Gen2<T extends ABC> = { [P in keyof Gen<T>]: string };
            export const g1: Gen2<ABC.A> = { v: 'x', a: '' };
            export const g2: Gen2<ABC.A> = { v: 'x' };
        """
        val r = rows(fixture)
        assert(r == listOf("5:2741 Property 'a' is missing in type '{ v: string; }' but required in type 'Gen2<ABC.A>'."))
        // The related row points at `a`'s declaration in the SURVIVING union member, which the
        // property lookup reaches only through the reduced intersection (corpus:
        // `mappedTypeNotMistakenlyHomomorphic`'s `related TS2728`).
        val related = diagnose(fixture).single().relatedInformation
            .map { "${it.line}:${it.code} ${it.message}" }
        assert(related == listOf("2:2728 'a' is declared here."))
    }

    @Test
    fun `a conditional as clause remaps the keys of a mapped type`() {
        val r = rows("""
            type Filter<T> = { [K in keyof T as T[K] extends string ? K : never]: T[K] };
            export const r1: keyof Filter<{ s: string; n: number }> = 's';
            export const r2: keyof Filter<{ s: string; n: number }> = 'n';
        """)
        assert(r == listOf("3:2322 Type '\"n\"' is not assignable to type '\"s\"'."))
    }

    @Test
    fun `an indexed access on an intersection reads the constituents that have the key`() {
        val r = rows("""
            type IO = { x: number; both: string } & { retry: { limit: number }; both: 'lit' };
            declare const r: IO['retry'];
            export const p1: boolean = r;
            declare const b: IO['both'];
            export const p2: 'lit' = b;
            export const p3: 'other' = b;
        """)
        assert(r == listOf(
            "3:2322 Type '{ limit: number; }' is not assignable to type 'boolean'.",
            "6:2322 Type '\"lit\"' is not assignable to type '\"other\"'.",
        ))
    }

    @Test
    fun `an indexed access on an intersection reads a generic constituent through its instantiation`() {
        val r = rows("""
            interface W<T> { v: T }
            type I = W<string> & { x: 1 };
            declare const v: I['v'];
            export const q1: boolean = v;
        """)
        assert(r == listOf("4:2322 Type 'string' is not assignable to type 'boolean'."))
    }

    @Test
    fun `Required strips undefined and Partial adds it over an intersection`() {
        val r = rows("""
            type Req<T> = { [P in keyof T]-?: T[P] };
            type Part<T> = { [P in keyof T]?: T[P] };
            type A = { a?: number | undefined; b: string };
            type B = { c?: boolean };
            declare const rq: Req<A & B>;
            export const q1: number = rq.a;
            export const q2: boolean = rq.c;
            declare const pp: Part<A & B>;
            export const q3: string = pp.b;
        """)
        assert(r == listOf("9:2322 Type 'string | undefined' is not assignable to type 'string'."))
    }

    @Test
    fun `Pick and Omit over an intersection keep the source modifiers`() {
        val r = rows("""
            type A = { a?: number | undefined; b: string };
            type B = { c?: boolean };
            export const q1: Pick<A & B, 'a' | 'c'> = {};
            declare const pk: Pick<A & B, 'a' | 'c'>;
            export const q2: number = pk.a;
            export const q3: Omit<A & B, 'b'> = { b: 'x' };
        """)
        assert(r == listOf(
            "5:2322 Type 'number | undefined' is not assignable to type 'number'.",
            "6:2353 Object literal may only specify known properties, and 'b' does not exist in type 'Omit<A & B, \"b\">'.",
        ))
    }

    @Test
    fun `a tuple matches Array of infer through its array base and the result is not renamed`() {
        val r = rows("""
            type Tu<T> = T extends Array<infer U> ? U : 'no';
            declare const t1: Tu<[1, 'a']>;
            export const q1: 1 | 'a' = t1;
            export const q2: 1 = t1;
            type Deep<T> = T extends Array<infer U> ? Array<Deep<U>> : T extends number ? number : T;
            declare const d: Deep<[1, 2]>;
            declare const plain: number[];
            export const q3: boolean = plain;
            export const q4: boolean = d;
        """)
        assert(r == listOf(
            "4:2322 Type '\"a\" | 1' is not assignable to type '1'.",
            "8:2322 Type 'number[]' is not assignable to type 'boolean'.",
            "9:2322 Type 'number[]' is not assignable to type 'boolean'.",
        ))
    }

    @Test
    fun `an anonymous union member of an intersection prints parenthesized`() {
        val r = rows("""
            type X = { x: 1 };
            type Y = { y: 1 };
            type Z = { z: 1 };
            declare const s: X & (Y | Z);
            export const q1: boolean = s;
        """)
        assert(r == listOf("5:2322 Type 'X & (Y | Z)' is not assignable to type 'boolean'."))
    }
}
