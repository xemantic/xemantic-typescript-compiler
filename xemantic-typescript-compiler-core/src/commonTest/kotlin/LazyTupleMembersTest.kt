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
 * (CHK.233) A tuple's member table is deferred (`Type.Object.lazyMembers`): an accumulator
 * recursion run to tsgo's 1,000-level tail budget mints one tuple per level, and the eager table
 * minted k element symbols and k global `symbolTypes` entries for every k-element tuple — about
 * n^2/2 live entries for n levels (type-fest's `if-not-any-or-never.ts`: 6.3 s and 1.77 GB). The
 * pins assert a COUNT ([TupleMemberCensus]), never a time; the eager binary mints ~405,000
 * element symbols for the nine-hundred-level fixture. `length` is minted with the tuple and
 * answered without forcing the table; the number index has its own thunk; a spread reads the
 * optional flags the tuple was minted with. Plus (CHK.233)'s second residue: the lazy
 * recursive-alias cycle-break for an INTERSECTION body of object literals.
 *
 * Every full-text row is tsgo 7.0.2's row.
 */
class LazyTupleMembersTest {

    private fun rows(d: List<Diagnostic>): List<String> = d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    /** The census counters are process-global: count a delta over one compile. */
    private fun <T> census(block: () -> T): Triple<T, Int, Int> {
        val t0 = TupleMemberCensus.tables
        val e0 = TupleMemberCensus.elementSymbols
        val r = block()
        return Triple(r, TupleMemberCensus.tables - t0, TupleMemberCensus.elementSymbols - e0)
    }

    @Test
    fun `an accumulator recursion reading the length at every level stays linear`() {
        val (d, _, elements) = census {
            diagnose(
                """
                type Count<T, Acc extends unknown[] = []> = Acc["length"] extends 900 ? Acc : Count<T, [...Acc, T]>;
                const bad: Count<string>["length"] = 899;
                """,
            )
        }
        assert(rows(d) == listOf("2:7 TS2322 Type '899' is not assignable to type '900'."))
        assert(elements < 2_000)
    }

    @Test
    fun `an accumulator recursion reading the element union at every level stays linear`() {
        val (d, _, elements) = census {
            diagnose(
                """
                type V<N extends number, Acc extends number[] = [0]> = Acc['length'] extends N ? Acc : V<N, [...Acc, Acc[number]]>;
                const v: V<300>['length'] = 1;
                const v2: V<300>[299] = 1;
                """,
            )
        }
        assert(
            rows(d) == listOf(
                "2:7 TS2322 Type '1' is not assignable to type '300'.",
                "3:7 TS2322 Type '1' is not assignable to type '0'.",
            ),
        )
        // `v2`'s element read forces the one 300-slot table; the eager binary mints ~45,000
        assert(elements < 2_000)
    }

    @Test
    fun `an element read on the result still sees the element type`() {
        val (d, tables, elements) = census {
            diagnose(
                """
                type Count<T, Acc extends unknown[] = []> = Acc["length"] extends 900 ? Acc : Count<T, [...Acc, T]>;
                type C900 = Count<string>;
                const e0: C900[0] = 1;
                const e899: C900[899] = "s";
                """,
            )
        }
        assert(rows(d) == listOf("3:7 TS2322 Type 'number' is not assignable to type 'string'."))
        // the one table the element read forces — positive control that the census counts
        assert(tables >= 1)
        assert(elements in 900..2_000)
    }

    @Test
    fun `a spread of a nine hundred element tuple keeps a trailing optional slot`() {
        val d = diagnose(
            """
            type Big<Acc extends unknown[] = []> = Acc["length"] extends 900 ? Acc : Big<[...Acc, 1]>;
            type BigOpt = [...Big, 2?];
            const b1: BigOpt["length"] = 902;
            type Opt = [string, number?];
            type Sp = [boolean, ...Opt];
            const s1: Sp = [true, "a"];
            const s2: Sp = [true, "a", 1];
            type W = [1, 2?];
            type Sp3 = [0, ...W];
            const w1: Sp3 = [0, 1];
            """,
        )
        assert(rows(d) == listOf("3:7 TS2322 Type '902' is not assignable to type '900 | 901'."))
    }

    @Test
    fun `tuple members read through the deferred table answer as before`() {
        val d = diagnose(
            """
            type L = [1, 2];
            declare const l: L;
            const ll: string = l.length;
            declare const ro: readonly [1, 2];
            ro.length = 3;
            ro[0] = 1;
            type Rest = [string, ...number[]];
            declare const rs: Rest;
            const p5: boolean = rs.length;
            const p6: boolean = rs[3];
            """,
        )
        assert(
            rows(d) == listOf(
                "3:7 TS2322 Type 'number' is not assignable to type 'string'.",
                "5:4 TS2540 Cannot assign to 'length' because it is a read-only property.",
                "6:4 TS2540 Cannot assign to '0' because it is a read-only property.",
                "9:7 TS2322 Type 'number' is not assignable to type 'boolean'.",
                "10:7 TS2322 Type 'number' is not assignable to type 'boolean'.",
            ),
        )
    }

    @Test
    fun `an intersection body whose recursion sits in literal members does not report TS2589`() {
        val d = diagnose(
            """
            type Wide<T> = { a(): Wide<T | 1>; b(): Wide<T | 2>; c(): Wide<T | 3> } & { tag: T };
            declare const w: Wide<0>;
            const ws: string = w;
            """,
        )
        assert(rows(d) == listOf("3:7 TS2322 Type 'Wide<0>' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - a plain type literal body with the same fan-out`() {
        val d = diagnose(
            """
            type Wide2<T> = { a(): Wide2<T | 1>; b(): Wide2<T | 2>; c(): Wide2<T | 3> };
            declare const w2: Wide2<0>;
            const ws2: string = w2;
            """,
        )
        assert(rows(d) == listOf("3:7 TS2322 Type 'Wide2<0>' is not assignable to type 'string'."))
    }
}
