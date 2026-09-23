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
 * (CHK.154)(a), round P18.185 — when one signature is RELATED to another, a trailing run
 * of required parameters whose type accepts `void` does not count towards the source's
 * minimum argument count (tsgo 7.0.2 `getMinArgumentCount`, relater.go
 * `getMinArgumentCountEx`), so `(value: void | PromiseLike<void>) => void` relates to
 * `() => void` — a Promise's `resolve` passed as an observer's `complete` (rxjs
 * `Observable.ts:307`, an ours-only TS2769).
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18185/cells`.
 * The rule is exactly the `void` FLAG, on the type or on a union constituent: `undefined`,
 * `any`, `unknown`, `never` and a type parameter (even one constrained to `void`) do NOT
 * count, and the run stops at the first parameter that is not void-accepting.
 *
 * NOT PINNED, and unchanged by this round: CALL arity (`g()` for `g(x: void)` still
 * reports TS2554 here where tsgo is silent — a separate reader), and a derived class's
 * construct signatures carrying the base constructor ((CHK.154)(b)). A binding-pattern
 * parameter makes the positions untrustworthy ([Checker.relationMinArgumentCount] then
 * keeps the raw count), which leaves one pre-existing ours-only row for
 * `({ a }: X, b: void, c?: number)` against `(x: X) => void`.
 */
class TrailingVoidParameterRelationTest {

    /** Every row as `line:column code message`, sorted. */
    private fun rows(source: String, directives: String = "// @strict: true"): List<String> =
        diagnose(source.trimIndent(), directives = directives)
            .map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    private fun notAssignable(line: Int, col: Int, source: String, target: String) =
        "$line:$col 2322 Type '$source' is not assignable to type '$target'."

    private val kinds = """
        declare const a1: (v: void) => void;
        declare const a2: (v: void | PromiseLike<void>) => void;
        declare const a3: (v: void | number) => void;
        declare const a4: (v: undefined) => void;
        declare const a5: (v: any) => void;
        declare const a6: (v: unknown) => void;
        declare const a7: (v: PromiseLike<void>) => void;
        declare const a8: (v: number) => void;
        declare const a9: (v: void | undefined) => void;
        declare const a10: (v: never) => void;
        const c1: () => void = a1;
        const c2: () => void = a2;
        const c3: () => void = a3;
        const c4: () => void = a4;
        const c5: () => void = a5;
        const c6: () => void = a6;
        const c7: () => void = a7;
        const c8: () => void = a8;
        const c9: () => void = a9;
        const c10: () => void = a10;
    """

    private val kindsExpected = listOf(
        notAssignable(14, 7, "(v: undefined) => void", "() => void"),
        notAssignable(15, 7, "(v: any) => void", "() => void"),
        notAssignable(16, 7, "(v: unknown) => void", "() => void"),
        notAssignable(17, 7, "(v: PromiseLike<void>) => void", "() => void"),
        notAssignable(18, 7, "(v: number) => void", "() => void"),
        notAssignable(20, 7, "(v: never) => void", "() => void"),
    ).sorted()

    @Test
    fun `exactly a void-accepting parameter is optional in the relation`() {
        val rows = rows(kinds)
        assert(rows == kindsExpected)
    }

    @Test
    fun `strictNullChecks off does not change the rule`() {
        val rows = rows(kinds, directives = "// @strict: false")
        assert(rows == kindsExpected)
    }

    @Test
    fun `only a trailing run of void parameters is dropped`() {
        val rows = rows("""
            declare const b1: (a: number, b: void) => void;
            declare const b2: (a: void, b: number) => void;
            declare const b3: (a: void, b: void) => void;
            declare const b4: (a: void, b?: number) => void;
            declare const b5: (a: void, ...r: number[]) => void;
            declare const b6: (a: number, b: void, c: void) => void;
            const c1: (x: number) => void = b1;
            const c1b: () => void = b1;
            const c2: () => void = b2;
            const c2b: (x: any) => void = b2;
            const c3: () => void = b3;
            const c4: () => void = b4;
            const c5: () => void = b5;
            const c6: (x: number) => void = b6;
            const c6b: (x: number, y: void) => void = b6;
        """)
        assert(rows == listOf(
            notAssignable(8, 7, "(a: number, b: void) => void", "() => void"),
            notAssignable(9, 7, "(a: void, b: number) => void", "() => void"),
            notAssignable(10, 7, "(a: void, b: number) => void", "(x: any) => void"),
        ).sorted())
    }

    @Test
    fun `a type parameter is not void-accepting even when constrained to void`() {
        val rows = rows("""
            export function f<T>(r: (v: T) => void) { const c: () => void = r; }
            export function g<T extends void>(r: (v: T) => void) { const c: () => void = r; }
        """)
        assert(rows == listOf(
            notAssignable(1, 49, "(v: T) => void", "() => void"),
            notAssignable(2, 62, "(v: T) => void", "() => void"),
        ).sorted())
    }

    @Test
    fun `an argument and an assignment relate the same way`() {
        val rows = rows("""
            declare const r: (value: void | PromiseLike<void>) => void;
            declare const n: (value: number) => void;
            declare function take(cb: () => void): void;
            take(r);
            take(n);
            function ret(): () => void { return r; }
            function ret2(): () => void { return n; }
            let x: () => void = () => {};
            x = r;
            x = n;
        """)
        assert(rows == listOf(
            "5:6 2345 Argument of type '(value: number) => void' is not assignable to parameter of type '() => void'.",
            notAssignable(7, 31, "(value: number) => void", "() => void"),
            notAssignable(10, 1, "(value: number) => void", "() => void"),
        ).sorted())
    }

    @Test
    fun `an interface method with a void parameter relates to one with none`() {
        val rows = rows("""
            interface Src { m(v: void): void }
            interface Tgt { m(): void }
            declare const s: Src;
            const t: Tgt = s;
            interface Src2 { m(v: number): void }
            declare const s2: Src2;
            const t2: Tgt = s2;
        """)
        assert(rows == listOf(notAssignable(7, 7, "Src2", "Tgt")))
    }

    @Test
    fun `a this pseudo-parameter does not count as a position`() {
        val rows = rows("""
            declare const g2: (this: Date, v: void) => void;
            declare const g5: (this: Date, a: number, v: void) => void;
            const c2: () => void = g2;
            const c6: (x: number) => void = g5;
            const c7: () => void = g5;
        """)
        assert(rows == listOf(
            notAssignable(5, 7, "(this: Date, a: number, v: void) => void", "() => void"),
        ))
    }

    @Test
    fun `a promise resolve is an observer complete through an overloaded derived constructor`() {
        // The embedded test lib declares no `Partial`, so the fixture carries its own.
        val rows = rows("""
            type Partial<T> = { [P in keyof T]?: T[P] };
            interface Obs<T> { next: (value: T) => void; error: (err: any) => void; complete: () => void; }
            class Base<T> { constructor(d?: Base<any> | Obs<any>) {} }
            class Sub<T> extends Base<T> { constructor(o?: Partial<Obs<T>> | ((value: T) => void) | null, e?: ((e?: any) => void) | null) { super(); } }
            declare const resolve: (value: void | PromiseLike<void>) => void;
            export function f<T>() {
              const s = new Sub<T>({ next: (value) => {}, complete: resolve });
            }
            const p: Partial<Obs<number>> = { complete: resolve };
        """)
        assert(rows.isEmpty())
    }
}
