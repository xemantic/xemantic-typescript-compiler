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
 * (P18.311) (LIBS.4) — false positives on ordinary application code in real libraries
 * (zod, hono, ky, superstruct, immer), each reduced to a fixture. Every expected list is
 * tsgo 7.0.2's output on the identical source (the harness strips the `// @` directive
 * lines, so each expected line is tsgo's minus the directive count), and each fixture
 * carries the row-producing CONTROL beside the silenced shape. Two residues are NOT pinned
 * here because they predate this round: `o.name` on `T extends SomeClass` (tsgo TS2339,
 * ours silent) and a `never` receiver left by an `instanceof` of a generic base (tsgo
 * TS2339 on `never`, ours silent).
 */
class RealLibraryFalsePositiveSweepTest {

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `an any operand pairs with a bigint operand and two anys stay number`() {
        val d = diagnose(
            """
            declare const d: any; declare const b: bigint; declare const n: number;
            const k1 = d % b; const s1: string = k1;
            const k2 = b % d; const s2: string = k2;
            const k3 = d >> 16n; const s3: string = k3;
            const k4 = d & 1n; const s4: string = k4;
            const k7 = d % n; const s7: string = k7;
            const k8 = d % d; const s8: string = k8;
            export const r = d % b !== BigInt(0);
            """,
            "// @strict: true\n// @useRealLibs: true\n// @lib: esnext",
        )
        assert(
            rows(d) == listOf(
                "2:25 TS2322 Type 'bigint' is not assignable to type 'string'.",
                "3:25 TS2322 Type 'bigint' is not assignable to type 'string'.",
                "4:28 TS2322 Type 'bigint' is not assignable to type 'string'.",
                "5:26 TS2322 Type 'bigint' is not assignable to type 'string'.",
                "6:25 TS2322 Type 'number' is not assignable to type 'string'.",
                "7:25 TS2322 Type 'number' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `a bitwise operator over a bigint body local and a bigint literal is bigint`() {
        val d = diagnose(
            """
            declare function parse(): bigint
            export function g(sections: number[]) {
              const ipv4 = parse()
              sections.push(Number((ipv4 >> 16n) & 0xffffn), Number(ipv4 & 0xffffn))
              const t: string = (ipv4 >> 16n) & 0xffffn
            }
            """,
            "// @strict: true\n// @useRealLibs: true\n// @lib: esnext",
        )
        assert(
            rows(d) == listOf(
                "5:9 TS2322 Type 'bigint' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `an annotated const host's expando writes are members of the weak-check source`() {
        val d = diagnose(
            """
            interface Weak { stop?: symbol; retry?: number }
            declare const stop: symbol;
            export const mk = () => {
              const ky: Weak = (x: string) => 1;
              ky.stop = stop;
            };
            export const mk3 = () => {
              const ky: Weak = (x: string) => 1;
            };
            export const top2: Weak = (x: string) => 1;
            top2.other = 1;
            """,
            "// @strict: true",
        )
        assert(
            rows(d) == listOf(
                "8:9 TS2559 Type '(x: string) => number' has no properties in common with type 'Weak'.",
                "10:14 TS2559 Type '{ (x: string): number; other: number; }' has no properties in common with type 'Weak'.",
                "11:6 TS2339 Property 'other' does not exist on type 'Weak'.",
            )
        )
    }

    @Test
    fun `a spread operand is the flow narrowed type`() {
        val d = diagnose(
            """
            type RetryOptions = { limit?: number; methods?: string[] };
            export const clone = (retry: RetryOptions | number): RetryOptions | number => {
              if (retry === null || typeof retry !== 'object' || Array.isArray(retry)) {
                return retry as RetryOptions | number;
              }
              return { ...retry };
            };
            export const clone4 = (retry: RetryOptions | number) => {
              if (typeof retry === 'object') return 1;
              return { ...retry };
            };
            export function f(result: string | boolean | { message?: string }) {
              if (result === true) return
              else if (result === false) result = {}
              else if (typeof result === 'string') result = { message: result }
              return { ...result }
            }
            """,
            "// @strict: true",
        )
        assert(
            rows(d) == listOf(
                "10:12 TS2698 Spread types may only be created from object types.",
            )
        )
    }

    @Test
    fun `a type parameter constrained to a class constructor has the Function members`() {
        val d = diagnose(
            """
            class Klass { constructor(..._a: any[]) {} static s = 1; x = 1 }
            export function i1<T extends typeof Klass>(cls: T) {
              cls.name; cls.length; cls.prototype; cls.apply; cls.s;
              cls.nope; cls.x;
            }
            """,
            "// @strict: true",
        )
        assert(
            rows(d) == listOf(
                "4:7 TS2339 Property 'nope' does not exist on type 'T'.",
                "4:17 TS2339 Property 'x' does not exist on type 'T'.",
            )
        )
    }

    @Test
    fun `a negative instanceof branch keeps a generic instance that does not derive from the class`() {
        val d = diagnose(
            """
            abstract class ZT<O = any, D = any> { _def!: D; _o!: O; parse(x: unknown): O { return null! } }
            export class ZUndef extends ZT<undefined, {}> {}
            export class ZOpt<T extends ZT<any, any>> extends ZT<T | undefined, { inner: T }> { unwrap(): T { return this._def.inner } }
            export const getD = <T extends ZT<any, any>>(type: T): number => {
              if (type instanceof ZUndef) { return 1 }
              else if (type instanceof ZOpt) { type.unwrap(); }
              return 0;
            };
            export function g2(type: ZT<any, any>) {
              if (type instanceof ZUndef) { return 1 }
              else if (type instanceof ZOpt) { type.unwrap(); }
              else { const n: number = type; }
            }
            export function g4(type: ZUndef | ZOpt<any>) {
              if (type instanceof ZUndef) { return 1 }
              else { type.unwrap(); }
            }
            """,
            "// @strict: true",
        )
        assert(
            rows(d) == listOf(
                "12:16 TS2322 Type 'ZT<any, any>' is not assignable to type 'number'.",
            )
        )
    }

    @Test
    fun `a class extending a lib constructor value with a non-generic construct signature owes no TS2314`() {
        val d = diagnose(
            """
            export function enable() {
              class DM extends Map { k = 1 }
              class DSet extends Set { k = 1 }
              return [DM, DSet]
            }
            export const X = class extends Map {};
            export class F extends Array {}
            class C<T> { t!: T }
            export class D extends C {}
            """,
            "// @strict: true\n// @useRealLibs: true\n// @lib: esnext",
        )
        assert(
            rows(d) == listOf(
                "9:24 TS2314 Generic type 'C<T>' requires 1 type argument(s).",
            )
        )
    }

    @Test
    fun `an element access this assignment in the constructor definitely assigns the computed member`() {
        val d = diagnose(
            """
            export const DS: unique symbol = Symbol.for("x")
            export class H {
              a: number; ["b"]: number; [DS]: number; c: number; d: number;
              constructor() { this["a"] = 1; this["b"] = 2; this[DS] = 3; }
            }
            export class K { [DS]: number; e: number; constructor() { this.e = 1 } }
            """,
            "// @strict: true",
        )
        assert(
            rows(d) == listOf(
                "3:43 TS2564 Property 'c' has no initializer and is not definitely assigned in the constructor.",
                "3:54 TS2564 Property 'd' has no initializer and is not definitely assigned in the constructor.",
                "6:18 TS2564 Property '[DS]' has no initializer and is not definitely assigned in the constructor.",
            )
        )
    }

    @Test
    fun `a readonly assignment target is typed by the receiver's own local declaration`() {
        val d = diagnose(
            """
            type Inst = { readonly stop: symbol; readonly n: number };
            type Mutable<T> = { -readonly [P in keyof T]: T[P] };
            declare const s: symbol;
            const make = (): Inst => {
              const ky: Partial<Mutable<Inst>> = {};
              ky.stop = s;
              return ky as Inst;
            };
            const ky = make();
            export default ky;
            export function f() { const o: { readonly a: number } = { a: 1 }; o.a = 2; }
            export function g() { const ky: Inst = null!; ky.n = 2; }
            export function h(ky: Partial<Mutable<Inst>>) { ky.n = 2; }
            export function k() { { const ky: Partial<Mutable<Inst>> = {}; ky.n = 3; } }
            export function m() { const { ky } = { ky: null! as Partial<Mutable<Inst>> }; ky.n = 4; }
            ky.n = 5;
            """,
            "// @strict: true",
        )
        assert(
            rows(d) == listOf(
                "11:69 TS2540 Cannot assign to 'a' because it is a read-only property.",
                "12:50 TS2540 Cannot assign to 'n' because it is a read-only property.",
                "16:4 TS2540 Cannot assign to 'n' because it is a read-only property.",
            )
        )
    }
}
