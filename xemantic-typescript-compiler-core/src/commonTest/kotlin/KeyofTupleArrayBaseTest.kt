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
 * (P18.300) `keyof` a TUPLE includes its `Array` / `ReadonlyArray` base's keys (tsgo: `keyof [1, 2]`
 * has `"push"`, `"map"`, `number`, the well-known symbols, `"0" | "1"`, `"length"`). The tuple's own
 * member table holds only its slots and `length`, so `keyof` answered a CLOSED subset: a false TS2322 /
 * TS2344 on every legal array key, and type-fest's `Except<TupleOf<3, E>, 'push' | …>` failed its
 * `K extends keyof T` constraint and resolved to `any` (`FixedLengthArray`).
 *
 * Residue, shared with `keyof string[]` and NOT changed here: a member keyed by a well-known symbol is
 * named `"[Symbol.iterator]"` in a member table and `keyof` reads that NAME as a string key (tsgo: a
 * `symbol` key, so `Symbol.iterator` is accepted and the string rejected). Answering `symbol` was built
 * and REFUSED: a mapped type cannot enumerate a non-literal key, so `Readonly<ReadonlyMap<infer K, infer
 * V>>` became `any` and every type-fest `ReadonlyDeep<T>` with it.
 *
 * Every expected row is tsgo 7.0.2's row (code, line, column, source type); the TARGET display differs
 * in FORM — tsgo prints `keyof [1, 2]`, this checker the expanded key union — so the pins stop at the
 * source type.
 */
class KeyofTupleArrayBaseTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message.substringBefore(" is not assignable").substringBefore(" does not satisfy")}" }

    @Test
    fun `keyof a tuple accepts the keys of its Array base`() {
        val d = diagnose(
            """
            const a1: keyof [1, 2] = 'push';
            const a2: keyof [1, 2] = 'map';
            const a3: keyof [1, 2] = '1';
            const a4: keyof [1, 2] = 'length';
            const a5: keyof [1, 2] = 0;
            """,
            realLibs,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a readonly tuple takes the ReadonlyArray base so mutating methods stay out`() {
        val d = diagnose(
            """
            const b1: keyof readonly [1] = 'map';
            const b2: keyof readonly [1] = 'push';
            """,
            realLibs,
        )
        assert(rows(d) == listOf("2:7 TS2322 Type '\"push\"'"))
    }

    @Test
    fun `keys outside the tuple and its base are still rejected`() {
        val d = diagnose(
            """
            const c1: keyof [1, 2] = 'nope';
            const c2: keyof [1, 2] = '2';
            """,
            realLibs,
        )
        assert(rows(d) == listOf("1:7 TS2322 Type '\"nope\"'", "2:7 TS2322 Type '\"2\"'"))
    }

    @Test
    fun `a constraint K extends keyof T accepts an Array method name for a tuple T`() {
        val d = diagnose(
            """
            type Get<O, K extends keyof O> = O[K];
            declare const g1: Get<[1, 2], 'map'>;
            declare const g2: Get<readonly [1, 2], 'push'>;
            """,
            realLibs,
        )
        assert(rows(d) == listOf("3:40 TS2344 Type '\"push\"'"))
    }
}
