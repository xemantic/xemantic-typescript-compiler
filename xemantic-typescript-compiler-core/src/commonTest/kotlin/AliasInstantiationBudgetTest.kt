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
 * (P18.304) The generic-alias substitution budget ([AliasInstantiationBudget]) at tsgo's limits:
 * a non-tail depth of 100, a tail-recursion count of 1,000, a per-outermost-substitution count
 * of 2,000 that bounds a fan-out, and a tuple of 10,000 elements evaluating to an error. The old
 * budget was a depth of 10, which bailed every one of the deep-but-finite evaluations below with a
 * false TS2589. Plus the both-branch self-reference walker, which no longer reports a conditional
 * that tsgo defers.
 *
 * Every expected row is tsgo 7.0.2's row, full text.
 */
class AliasInstantiationBudgetTest {

    private fun rows(d: List<Diagnostic>): List<String> = d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `a non-tail recursion forty levels deep evaluates`() {
        val d = diagnose(
            """
            type Cnt<S extends string> = S extends `${'$'}{string}${'$'}{infer R}` ? [...Cnt<R>, 0] : [];
            const c40: Cnt<'abcdefghijklmnopqrstuvwxyz0123456789abcd'>['length'] = 1;
            """,
        )
        assert(rows(d) == listOf("2:7 TS2322 Type '1' is not assignable to type '40'."))
    }

    @Test
    fun `a tail recursion three hundred levels deep evaluates`() {
        val d = diagnose(
            """
            type TupleOf<T, N extends number, Acc extends unknown[] = []> = Acc['length'] extends N ? Acc : TupleOf<T, N, [T, ...Acc]>;
            const to: TupleOf<0, 300>['length'] = 1;
            """,
        )
        assert(rows(d) == listOf("2:7 TS2322 Type '1' is not assignable to type '300'."))
    }

    @Test
    fun `a tail recursion through nested conditional branches evaluates`() {
        val d = diagnose(
            """
            type Strip<S extends string> = S extends `-${'$'}{infer T}` ? Strip<T> : S extends `+${'$'}{infer T}` ? Strip<T> : S;
            type Dashes = '-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+x';
            const s: Strip<Dashes> extends 'x' ? 1 : 0 = 0;
            """,
        )
        assert(rows(d) == listOf("3:7 TS2322 Type '0' is not assignable to type '1'."))
    }

    @Test
    fun `a genuinely infinite tail recursion still reports TS2589`() {
        val d = diagnose(
            """
            type Loop<T> = T extends string ? Loop<T> : never;
            type L1 = Loop<'a'>;
            """,
        )
        assert(rows(d) == listOf("2:11 TS2589 Type instantiation is excessively deep and possibly infinite."))
    }

    @Test
    fun `a body with three recursive members is bounded by the count`() {
        val d = diagnose(
            """
            type Wide<T> = { a(): Wide<T | 1>; b(): Wide<T | 2>; c(): Wide<T | 3> } & { tag: T };
            declare const w: Wide<0>;
            const ws: string = w;
            """,
        )
        // The TS2589 this checker also reports at the annotation (2:18) is a pre-existing ours-only
        // row — tsgo resolves the members lazily and never expands them — and not what is measured
        // here: without the count, the 3^depth expansion does not terminate.
        assert("3:7 TS2322 Type 'Wide<0>' is not assignable to type 'string'." in rows(d))
    }

    @Test
    fun `a doubling tuple recursion reports TS2799 rather than materializing the tuple`() {
        val d = diagnose(
            """
            type Build<L extends number, T extends any[] = [any]> = T['length'] extends L ? T : Build<L, [...T, ...T]>;
            type BT = Build<3>;
            """,
        )
        assert(rows(d) == listOf("2:11 TS2799 Type produces a tuple type that is too large to represent."))
    }

    @Test
    fun `a both-branch recursion under a generic check type is deferred`() {
        val d = diagnose(
            """
            type TupleMax<A extends number[], Result extends number = 0> = number extends A[number]
              ? never
              : A extends [infer F extends number, ...infer R extends number[]]
                ? F extends Result ? TupleMax<R, F> : TupleMax<R, Result>
                : Result;
            """,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a both-branch recursion under a non-generic check type reports TS2589`() {
        val d = diagnose(
            """
            type Foo<T> = T extends unknown ? unknown extends `${'$'}{infer R}` ? Foo<T> : Foo<unknown> : unknown;
            """,
        )
        assert(rows(d) == listOf("1:75 TS2589 Type instantiation is excessively deep and possibly infinite."))
    }
}
