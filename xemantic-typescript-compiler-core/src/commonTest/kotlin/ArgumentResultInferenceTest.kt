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
 * (CHK.159) step 1, round P18.191 — a call's RESULT type is inferred from its ARGUMENTS where the
 * shape-gated `tryInferSingleTypeParamFromArgs` answered nothing (`Checker.argInferResultTypeArguments`,
 * reusing the contextual-return leg's structural walk `ctxReturnInferInto`). Before this such a call
 * returned the RAW `sig.resolvedReturnType`, which `typeContainsForeignTypeParam` then hid by NAME:
 * silent where the names differ, a false positive where they collide with different shapes (rxjs
 * `race.ts:52`), a false negative where they collide with the same shape.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18191/pins` on the identical
 * text less the three `// @` directive lines the harness strips, so the prelude is line 1 and a
 * one-line fixture is on line 2. Probes are ARGUMENT probes
 * (`pn(v)`), which print the inferred type; an assignment probe is blind for a bare type parameter.
 *
 * NOT PINNED (residues, measured): a generic function argument whose own type parameter would leak
 * (`f(g)` with `g<U>`) — tsgo binds `unknown`, this leg abandons and stays silent; a no-candidate type
 * parameter (tsgo `unknown`); a context-sensitive OBJECT literal whose other members would bind (tsgo's
 * first pass reads them); a single-signature constraint failure (tsgo substitutes the constraint, this
 * leg keeps the raw return); inference through base types / index signatures / reverse-mapped types.
 */
class ArgumentResultInferenceTest {

    private val prelude = """
        // @useRealLibs: true
        // @strict: true
        // @target: es2020
        declare function pn(n: number): void;

    """.trimIndent()

    /** Every row as `line:column code message`, sorted; a one-line fixture is on line 2. */
    private fun rows(source: String): List<String> =
        diagnose(prelude + source.trimIndent(), directives = "")
            .map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    @Test
    fun `a T or array-of-T parameter binds T from a number argument`() {
        val actual = rows(
            """
            declare function f<T>(x: T | T[]): T[];
            declare const n: number;
            pn(f(n));
            """
        )
        val expected = listOf(
            "4:4 2345 Argument of type 'number[]' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a declared function argument binds a callback parameter type`() {
        val actual = rows(
            """
            declare function f<T>(cb: (x: T) => void): T[];
            declare function take(x: number): void;
            pn(f(take));
            """
        )
        val expected = listOf(
            "4:4 2345 Argument of type 'number[]' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    /** the name-collision FALSE NEGATIVE (c19/c20): before this the raw `T[]` was accepted as the caller's `T[]`, and renamed to `U` it was silent too. */
    @Test
    fun `a raw callee T no longer passes as the caller own T of the same name`() {
        val actual = rows(
            """
            declare function f<T>(x: T | T[]): T[];
            export function g<T>(xs: T[], n: number) {
              xs = f(n);
            }
            export function h<U>(xs: U[], n: number) {
              xs = f(n);
            }
            """
        )
        val expected = listOf(
            "4:3 2322 Type 'number[]' is not assignable to type 'T[]'.",
            "7:3 2322 Type 'number[]' is not assignable to type 'U[]'.",
        )
        assert(actual == expected)
    }

    /** c38: the argument is the caller's own `v: T`, so the inferred `T[]` is the caller's and the assignment is legal. */
    @Test
    fun `negative control - a candidate that IS the caller type parameter relates to it`() {
        val actual = rows(
            """
            declare function f<T>(x: T | T[]): T[];
            export function g<T>(xs: T[], v: T) {
              xs = f(v);
            }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    /** tsc `parseDelimitedList`: `() => Nd | undefined` fails overload 1's `T extends Nd`, so overload 2 answers `NA<NN<Nd | undefined>> | undefined` — and `NN<U> = U & {}` over a union reduces to `Nd`. */
    @Test
    fun `a constraint failure falls through to the next generic overload`() {
        val actual = rows(
            """
            type NN<T> = T & {};
            interface Nd { kind: number }
            interface NA<T> extends ReadonlyArray<T> { pos: number }
            declare function pl<T extends Nd>(k: number, el: () => T): NA<T>;
            declare function pl<T extends Nd | undefined>(k: number, el: () => T): NA<NN<T>> | undefined;
            declare function el1(): Nd | undefined;
            declare function el2(): Nd;
            pn(pl(1, () => el1()));
            pn(pl(1, () => el2()));
            """
        )
        val expected = listOf(
            "10:4 2345 Argument of type 'NA<Nd>' is not assignable to parameter of type 'number'.",
            "9:4 2345 Argument of type 'NA<Nd> | undefined' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    /** rxjs `zip.ts`: `map(() => false)` binds `boolean`. Line 7 is the control: the contextual `Op<number, true>` keeps `true`, so it is silent in tsgo. */
    @Test
    fun `a literal a function expression returns is widened unless the call context keeps it`() {
        val actual = rows(
            """
            interface Op<T, R> { t: T; r: R }
            declare function mp<T, R>(p: (v: T) => R): Op<T, R>;
            declare function th<R>(cb: () => R): R[];
            pn(th(() => false));
            pn(mp((v: number) => false));
            const z: Op<number, true> = mp((v: number) => true);
            """
        )
        val expected = listOf(
            "5:4 2345 Argument of type 'boolean[]' is not assignable to parameter of type 'number'.",
            "6:4 2345 Argument of type 'Op<number, boolean>' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a literal candidate is kept under a primitive constraint and widened otherwise`() {
        val actual = rows(
            """
            declare function f<T extends string>(x: T | T[]): T[];
            declare function h<T>(x: T | T[]): T[];
            const k = "k";
            pn(f(k));
            pn(h(k));
            """
        )
        val expected = listOf(
            "5:4 2345 Argument of type '\"k\"[]' is not assignable to parameter of type 'number'.",
            "6:4 2345 Argument of type 'string[]' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    /** tsc `checker.ts` `forEachNodeRecursively`: before this `U` bound `T | "skip"` and line 4 was an ours-only TS2322. */
    @Test
    fun `identical literal constituents are matched by value`() {
        val actual = rows(
            """
            declare function each<T>(cb: (n: number) => T | "skip" | undefined): T | undefined;
            export function outer<T>(cb: (n: number) => T | "skip" | undefined): T | undefined {
              return each(cb);
            }
            declare const c2: (n: number) => string | "skip" | undefined;
            pn(each(c2));
            """
        )
        val expected = listOf(
            "7:4 2345 Argument of type 'string | undefined' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    /** rxjs `combineLatest.ts:30`: `pipe(cl(...args), mo(rs))` — the spread call leaves `cl`'s own `T` raw; binding the rest and leaving pipe's `T` raw collided by name with the caller's `T` and read as a TS2322 tsgo does not report. */
    @Test
    fun `negative control - a nested call that leaks its own type parameter abandons the inference`() {
        val actual = rows(
            """
            interface Obs<T> { v: T }
            interface UF<T, R> { (source: T): R }
            interface OF<T, R> extends UF<Obs<T>, Obs<R>> {}
            declare function pipe<T, A>(fn1: UF<T, A>): UF<T, A>;
            declare function pipe<T, A, B>(fn1: UF<T, A>, fn2: UF<A, B>): UF<T, B>;
            declare function mo<T, R>(fn: (...values: T[]) => R): OF<T | T[], R>;
            export function cl<T, A extends readonly unknown[], R>(...sources: A): OF<T, [T, ...A]>;
            export function cl<T, R>(...args: any[]): OF<T, unknown>;
            export function cl<T, R>(...args: any[]): OF<T, unknown> {
              const rs: ((...values: any[]) => R) | undefined = undefined as any;
              return rs ? pipe(cl(...(args as Array<number>)), mo(rs)) : (null as any);
            }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    /** Every `setTextRange(range, …)` inside a `<T extends TextRange>` caller failed this relation's constraint check and substituted the constraint: +13 rows per profile. */
    @Test
    fun `negative control - a caller type parameter candidate is not checked against the constraint`() {
        val actual = rows(
            """
            interface TR { pos: number }
            declare function st<T extends TR>(r: T | T[], loc: TR): T[];
            export function f<T extends TR>(r: T): T[] {
              return st(r, r);
            }
            declare function st2<T extends TR>(r: T | T[], loc: TR): T[];
            declare function st2<T>(r: T | T[], loc: string): string;
            export function f2<T extends TR>(r: T): T[] {
              return st2(r, r);
            }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    /** Needs the depth-6 walk: at depth 4 the structural match failed and the naked `T` took the whole `Promise<string>`. */
    @Test
    fun `a Promise argument binds T through PromiseLike`() {
        val actual = rows(
            """
            declare function w<T>(x: T | PromiseLike<T>): T[];
            declare const p: Promise<string>;
            pn(w(p));
            """
        )
        val expected = listOf(
            "4:4 2345 Argument of type 'string[]' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    /** Lines 7 and 8 were ours-only TS2322s; line 10 is the display control — the reduced `Nd` must not be renamed `NonNullable<Nd | undefined>` program-wide. */
    @Test
    fun `NonNullable of a union reduces to its non-nullish member and keeps its display`() {
        val actual = rows(
            """
            interface Nd { kind: number }
            interface NA<T> extends ReadonlyArray<T> { pos: number }
            declare const b: NonNullable<Nd | undefined>;
            declare const n: NA<NonNullable<Nd | undefined>>;
            declare const q: Nd;
            const y1: Nd = b;
            const y2: NA<Nd> = n;
            const y3: boolean = b;
            const y4: boolean = q;
            """
        )
        val expected = listOf(
            "10:7 2322 Type 'Nd' is not assignable to type 'boolean'.",
            "9:7 2322 Type 'Nd' is not assignable to type 'boolean'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a context-sensitive callback argument contributes nothing and the other argument still binds`() {
        val actual = rows(
            """
            declare function f<T>(x: T | T[], cb: (v: T) => void): T[];
            pn(f(1, v => {}));
            """
        )
        val expected = listOf(
            "3:4 2345 Argument of type 'number[]' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an interface extending a generic function interface binds through its call signature`() {
        val actual = rows(
            """
            interface Obs<T> { v: T }
            interface UF<T, R> { (source: T): R }
            interface OF<T, R> extends UF<Obs<T>, Obs<R>> {}
            declare function pipe<T, A>(fn1: UF<T, A>): UF<T, A>;
            declare function pipe<T, A, B>(fn1: UF<T, A>, fn2: UF<A, B>): UF<T, B>;
            declare const o1: OF<number, string>;
            declare const o2: OF<string, boolean>;
            pn(pipe(o1, o2));
            """
        )
        val expected = listOf(
            "9:4 2345 Argument of type 'UF<Obs<number>, Obs<boolean>>' is not assignable to parameter of type 'number'.",
        )
        assert(actual == expected)
    }

    /** A type parameter the arguments do not bind leaves the WHOLE return raw: bound partially,
     *  `Pair<A, number>` kept the callee's raw `A`, which collides by name with the caller's `A`
     *  and read as a TS2322 tsgo does not report (tsgo binds `A` from the contextual return). */
    @Test
    fun `negative control - an unbound type parameter leaves the whole return raw`() {
        val actual = rows(
            """
            interface Box<T> { v: T }
            interface Pair<A, B> { a: A; b: B }
            declare function pick<A, B>(b: B | B[]): Pair<A, B>;
            export function g<A>(): Pair<Box<A>, number> {
              return pick(1);
            }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }
}
