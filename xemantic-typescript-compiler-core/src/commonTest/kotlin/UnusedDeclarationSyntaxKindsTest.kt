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
 * (P18.271) (CHK.204) — the unused-declaration check (TS6133 / TS6196 / TS6205) counted a name
 * as used only where a hand-written collector walked its syntax kind, so a reference in any
 * other position read as "declared but never used" where tsgo is silent: 97 rows across hono,
 * ky, zod and type-fest. One test per position class, each with the CONTROL tsgo still reports
 * in the same fixture — every new reference position can only turn a row into a silence, so
 * the controls are what keep a real row from being swallowed.
 *
 * Every expectation is tsgo 7.0.2's full output for the fixture's unused-family rows
 * (`tools/tsgo-7.0.2/lib/tsc`, `noUnusedLocals` + `noUnusedParameters`, `strict`).
 */
class UnusedDeclarationSyntaxKindsTest {

    private val directives =
        "// @strict: true\n// @noUnusedLocals: true\n// @noUnusedParameters: true\n// @target: es2022"

    private val unusedCodes = setOf(6133, 6138, 6192, 6196, 6198, 6199, 6205)

    private fun unusedRows(source: String): List<String> =
        diagnose(source, directives = directives)
            .filter { it.code in unusedCodes }
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    /** A template-literal TYPE's `${…}` placeholders reference names (the parser keeps only the
     *  raw slice, so no collector saw them). Controls: a name appearing only in a NESTED
     *  template's literal text or in a quoted string is not a reference, and a name an
     *  `infer R` re-declares inside the template leaves the outer `R` unused. */
    @Test
    fun `a reference inside a template literal type counts as a use`() {
        val rows = unusedRows(
            """
            type Trim<T extends string> = T extends `/${'$'}{infer R}` ? Trim<R> : T;
            export type A<P extends string> = `${'$'}{Trim<P>}`;
            type Kind = 'home' | 'work';
            type Pfx<T extends string> = `${'$'}{T} ` | '';
            export type B = `${'$'}{Pfx<Kind>}${'$'}{number}`;
            type Unq = 'u';
            export type C = `${'$'}{`Unq`}x${'$'}{'Unq'}`;
            export type D<R> = string extends `${'$'}{infer R}` ? R : 2;
            """,
        )
        // tsgo also reports `'R' is declared but never used.` at 8:15 — the outer R is shadowed by
        // the inferred one. This checker's collectors are name-based and miss that (pre-existing,
        // the same as any shadowing type parameter); the row is absent before and after.
        assert(rows == listOf("6:6 TS6196 'Unq' is declared but never used."))
    }

    /** An index signature's value type in an interface and a class. Control: the index
     *  signature's PARAMETER name is not a reference to a same-named type. */
    @Test
    fun `an index signature value type counts as a use`() {
        val rows = unusedRows(
            """
            interface H1 { v: string }
            interface Hs { [name: string]: H1[] }
            export const h: Hs = {};
            interface H2 { v: string }
            export class C2 { [k: string]: H2 | number }
            type k = 1;
            export interface I3 { [k: string]: number }
            """,
        )
        assert(rows == listOf("6:6 TS6196 'k' is declared but never used."))
    }

    /** A type predicate's TYPE is a reference (`e is Array<T>`, `asserts _ is T`). Control: the
     *  predicate's PARAMETER name is not a read of that parameter — tsgo reports `y`. */
    @Test
    fun `a type predicate's type counts as a use and its parameter name does not`() {
        val rows = unusedRows(
            """
            export function g1<T>(e: unknown): e is Array<T> { return !!e; }
            interface E2 { x: 1 }
            export function g2(e: unknown): e is E2 { return !!e; }
            export function g3<T>(_: any): asserts _ is T {}
            export function g4(x: unknown, y: unknown): y is string { return x === 1; }
            """,
        )
        assert(rows == listOf("5:32 TS6133 'y' is declared but its value is never read."))
    }

    /** A reference in a type parameter's constraint or default — a sibling's, its own, an
     *  interface call signature's, a generic arrow's — is a use. Control: a generic arrow's own
     *  `<Q,>` SHADOWS an outer `type Q`, which tsgo reports (this checker newly agrees). */
    @Test
    fun `a reference in a type parameter constraint or default counts as a use`() {
        val rows = unusedRows(
            """
            export type B1<T, U, _V extends [T, U]> = any;
            export interface I2<T = unknown, U = any, _W = Map<T, U>> { w: _W }
            export interface I3<E> { <E2 = E>(h: E2): void }
            type Tgt<M> = M extends 'get' ? 1 : 2;
            export const v4 = <M extends string, U extends Tgt<M>>(u: U) => u;
            export type S5<T extends Array<T>> = 1;
            type Q = 1;
            export const f6 = <Q,>(x: Q) => x;
            export class C7<T, _U extends T[]> { x = 1 }
            """,
        )
        assert(rows == listOf("7:6 TS6196 'Q' is declared but never used."))
    }

    /** A type written inside a parameter DEFAULT (`m = (i) => i as U`). Control: the same
     *  function without the cast leaves `U` unused. */
    @Test
    fun `a type in a parameter default counts as a use`() {
        val rows = unusedRows(
            """
            export function fmt<T, U>(x: T, m = (i: number) => i as U) { return [x, m]; }
            export function fmt2<T, U>(x: T, m = (i: number) => i) { return [x, m]; }
            """,
        )
        assert(rows == listOf("2:25 TS6196 'U' is declared but never used."))
    }

    /** A computed member name is a value read — a class accessor, an interface property, an
     *  object-literal method. Control: a plain member NAME spelling the const is not. */
    @Test
    fun `a computed member name counts as a use`() {
        val rows = unusedRows(
            """
            const KEY = 'k';
            export class C1 { get [KEY]() { return 1 } }
            const KEY2 = 'k2';
            export interface I2 { [KEY2]: number }
            const KEY3 = 'k3';
            export const o3 = { [KEY3]() { return 1 } };
            const K = 'k';
            export class C4 { K = 1 }
            """,
        )
        assert(rows == listOf("7:7 TS6133 'K' is declared but its value is never read."))
    }

    /** Nothing under a `declare` statement is reported — tsgo's `reportUnused` drops a node in
     *  an ambient context: a module augmentation's interface, a `declare class`'s type
     *  parameter, a `declare namespace` member's. Control: a non-ambient namespace's unused
     *  interface is still reported. */
    @Test
    fun `nothing in an ambient context is reported`() {
        val rows = unusedRows(
            """
            export {};
            declare module './other' {
              interface Aug1 { x: 1 }
            }
            export declare class DC2<T> { x: number }
            export declare namespace DN3 { type U<T> = number; export const z: U<1>; }
            export namespace N4 { interface I4 { a: 1 } }
            """,
        )
        assert(rows == listOf("7:33 TS6196 'I4' is declared but never used."))
    }

    /** A `this` parameter is never reported, and neither is an `infer _`. Controls: an ordinary
     *  unused parameter beside `this`, and an unused `infer Z`. */
    @Test
    fun `a this parameter and an underscore infer are never reported`() {
        val rows = unusedRows(
            """
            export function f1(this: Date): void {}
            export function f2(this: Date, a: number, b: number) { return a; }
            export type H1<I> = I extends { param: infer _ } ? 1 : 2;
            export type H2<I> = I extends [infer Z] ? 1 : 2;
            """,
        )
        assert(
            rows == listOf(
                "2:43 TS6133 'b' is declared but its value is never read.",
                "4:38 TS6196 'Z' is declared but never used.",
            ),
        )
    }
}
