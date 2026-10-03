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
 * (CHK.206) discriminant and literal-union narrowing by the comparand's TYPE, not its syntax.
 *
 * tsgo narrows `u.kind === Code.a` whenever the comparand's type is a unit literal type,
 * whatever expression produced it — an `as const` member, a mapped type of literals, a
 * body-local `const`, a `typeof X.a` binding — and a UNION comparand narrows the TRUE branch
 * only (a `switch` case narrows its clause and never subtracts in `default:`). Every expected
 * row below is tsgo 7.0.2's own output for the same source (less the harness directive line),
 * read out with a deliberate mis-assignment so the narrowed type is in the message.
 */
class ComparandTypeNarrowingTest {

    @Test
    fun `as const object member narrows a discriminated union`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            const Code = { a: "a", b: "b" } as const;
            export function f(u: U) { if (u.kind === Code.a) { const p: boolean = u; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("6,58: TS2322 Type 'A' is not assignable to type 'boolean'."))
    }

    @Test
    fun `mapped type of literals member narrows a discriminated union`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            declare const Code: { [k in "a" | "b" | "c"]: k };
            export function f(u: U) { if (u.kind === Code.a) { const p: boolean = u; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("6,58: TS2322 Type 'A' is not assignable to type 'boolean'."))
    }

    @Test
    fun `reversed operands and loose equality narrow too`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            declare const Code: { [k in "a" | "b" | "c"]: k };
            export function f(u: U) {
              if (Code.b === u.kind) { const p: boolean = u; }
              if (u.kind == Code.c) { const q: boolean = u; }
            }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("7,34: TS2322 Type 'B' is not assignable to type 'boolean'.", "8,33: TS2322 Type 'Cc' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a unit comparand narrows the false branch`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            const Code = { a: "a", b: "b" } as const;
            export function f(u: U) { if (u.kind !== Code.a) { const p: boolean = u; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("6,58: TS2322 Type 'B | Cc' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a body local const comparand narrows`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            export function f(u: U) { const L = "b"; if (u.kind === L) { const p: boolean = u; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("5,68: TS2322 Type 'B' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a typeof member and a number unit comparand narrow`() {
        val rows = diagnose(
            """
            interface N1 { n: 1; a: 1 }
            interface N2 { n: 2; b: 1 }
            const C = { one: 1 } as const;
            declare const t: typeof C.one;
            export function f(u: N1 | N2) { if (u.n === t) { const p: boolean = u; } else { const q: boolean = u; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("5,56: TS2322 Type 'N1' is not assignable to type 'boolean'.", "5,87: TS2322 Type 'N2' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a union comparand narrows only the true branch`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            declare const ab: "a" | "b";
            export function f(u: U) {
              if (u.kind === ab) { const p: boolean = u; } else { const q: boolean = u; }
              if (u.kind !== ab) { const r: boolean = u; }
            }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("7,30: TS2322 Type 'A | B' is not assignable to type 'boolean'.", "7,61: TS2322 Type 'U' is not assignable to type 'boolean'.", "8,30: TS2322 Type 'U' is not assignable to type 'boolean'."))
    }

    @Test
    fun `negative control - a string comparand does not narrow`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            declare const s: string;
            export function f(u: U) { if (u.kind === s) { const p: boolean = u; } else { const q: boolean = u; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("6,53: TS2322 Type 'U' is not assignable to type 'boolean'.", "6,84: TS2322 Type 'U' is not assignable to type 'boolean'."))
    }

    @Test
    fun `nested discriminant narrows the inner reference`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            const Code = { a: "a" } as const;
            export function f(w: { inner: U }) { if (w.inner.kind === Code.a) { const p: boolean = w.inner; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("6,75: TS2322 Type 'A' is not assignable to type 'boolean'."))
    }

    @Test
    fun `switch cases on a mapped type exhaust to never`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            declare const Code: { [k in "a" | "b" | "c"]: k };
            export function f(u: U) {
              switch (u.kind) {
                case Code.a: { const pa: boolean = u; break; }
                case Code.b: { const pb: boolean = u; break; }
                case Code.c: return u.rc;
                default: { const n: never = u; return n; }
              }
            }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("8,26: TS2322 Type 'A' is not assignable to type 'boolean'.", "9,26: TS2322 Type 'B' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a union case narrows its clause and leaves the default whole`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            declare const ab: "a" | "b";
            export function f(u: U) {
              switch (u.kind) {
                case ab: { const p: boolean = u; break; }
                default: { const d: boolean = u; }
              }
            }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("8,22: TS2322 Type 'A | B' is not assignable to type 'boolean'.", "9,22: TS2322 Type 'U' is not assignable to type 'boolean'."))
    }

    @Test
    fun `negative control - a string case narrows nothing`() {
        val rows = diagnose(
            """
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            interface Cc { kind: "c"; rc: boolean }
            type U = A | B | Cc;
            declare const s: string;
            export function f(u: U) {
              switch (u.kind) {
                case s: { const p: boolean = u; break; }
              }
            }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("8,21: TS2322 Type 'U' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a direct literal union subject narrows by a unit comparand`() {
        val rows = diagnose(
            """
            type K = "a" | "b" | "c";
            const C = { a: "a" } as const;
            export function f(k: K) { if (k === C.a) { const p: "zz" = k; } else { const q: "zz" = k; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("3,50: TS2322 Type '\"a\"' is not assignable to type '\"zz\"'.", "3,78: TS2322 Type '\"b\" | \"c\"' is not assignable to type '\"zz\"'."))
    }

    @Test
    fun `a direct literal union subject narrows by a union comparand`() {
        val rows = diagnose(
            """
            type K = "a" | "b" | "c";
            declare const ab: "a" | "b";
            export function f(k: K) { if (k === ab) { const p: "zz" = k; } else { const q: "zz" = k; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("3,49: TS2322 Type '\"a\" | \"b\"' is not assignable to type '\"zz\"'.", "3,77: TS2322 Type 'K' is not assignable to type '\"zz\"'."))
    }

    @Test
    fun `a direct switch on a literal union exhausts to never`() {
        val rows = diagnose(
            """
            type K = "a" | "b" | "c";
            declare const C: { [k in K]: k };
            export function f(k: K) {
              switch (k) {
                case C.a: { const p: "zz" = k; break; }
                case C.b: case C.c: { const q: "zz" = k; break; }
                default: { const n: never = k; }
              }
            }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("5,23: TS2322 Type '\"a\"' is not assignable to type '\"zz\"'.", "6,33: TS2322 Type '\"b\" | \"c\"' is not assignable to type '\"zz\"'."))
    }

    @Test
    fun `control - an enum member comparand still narrows`() {
        val rows = diagnose(
            """
            enum E { a = "a", b = "b" }
            interface EA { kind: E.a; ra: number }
            interface EB { kind: E.b; rb: string }
            export function f(u: EA | EB) { if (u.kind === E.a) { const p: boolean = u; } }
            """
        ).map { "${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("4,61: TS2322 Type 'EA' is not assignable to type 'boolean'."))
    }
    @Test
    fun `a comparand imported from another module narrows`() {
        val rows = diagnose(
            """
            // @filename: other.ts
            export const OC = { a: "a", b: "b" } as const;
            // @filename: t.ts
            import { OC } from "./other";
            interface A { kind: "a"; ra: number }
            interface B { kind: "b"; rb: string }
            export function f(u: A | B) { if (u.kind === OC.b) { const p: boolean = u; } }
            """
        ).map { "${it.fileName?.substringAfterLast('/')}:${it.line},${it.character}: TS${it.code} ${it.message}" }
        assert(rows == listOf("t.ts:4,60: TS2322 Type 'B' is not assignable to type 'boolean'."))
    }
}
