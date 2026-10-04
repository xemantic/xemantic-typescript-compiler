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
 * (LIBS.3) TS2536 on `vals[i]` where the receiver and the index are both declared as a generic
 * CONDITIONAL alias over one enclosing type parameter — date-fns `buildLocalizeFn`'s
 * `valuesArray[index]`, the library's last ours-only row (`DeferredConditionalIndexAccess.kt`).
 * Reported only when tsgo's target-side, default-constraint, distributive-constraint and
 * number-index routes all provably fail; the controls pin the silence where one of them holds.
 *
 * Every expectation is tsgo 7.0.2's output (`build/bench/p18291-agent/m`), 1-based columns.
 */
class DeferredConditionalIndexAccessTest {

    private val prelude = """
        type Unit = "am" | "pm" | 0 | 1
        type Rec<K extends string, T> = { [P in K]: T }
        type Vals<V extends Unit> = V extends "am" | "pm" ? Rec<"am" | "pm", string> : V extends 0 | 1 ? readonly [string, string] : never
        type Idx<V extends Unit | number> = V extends Unit ? keyof Vals<V> : number
        type IdxN<V extends Unit> = V extends Unit ? keyof Vals<V> : never
        type Tup<V extends 0 | 1> = V extends 0 ? readonly [string, string] : readonly [string, string, string]
        type TIdx<V extends 0 | 1> = V extends 0 ? 0 | 1 : number
        type Lit<V extends "a" | "b"> = V extends "a" ? { a: 1; b: 2 } : { a: 3; b: 4 }
        type LIdx<V extends "a" | "b"> = V extends "a" ? "a" : "b"
        type LIdxS<V extends "a" | "b"> = V extends "a" ? "a" : string
        type SRec<V extends "a" | "b"> = V extends "a" ? { [k: string]: number } : { [k: string]: string }
        interface IR { a: 1; b: 2 }
        type IRec<V extends "a" | "b"> = V extends "a" ? IR : { a: 3; b: 4 }

    """.trimIndent()

    private fun rows(source: String): List<String> =
        diagnose(prelude + "\n" + source.trimIndent(), directives = "// @strict: true\n// @target: es2022")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `a deferred conditional key that cannot index a deferred conditional receiver is TS2536`() {
        val r = rows(
            """
            export function c01<V extends Unit>(vals: Vals<V>, i: Idx<V>) { return vals[i] }
            export function c11<V extends "a" | "b">(vals: Lit<V>, i: LIdxS<V>) { return vals[i] }
            export const c24 = <V extends "a" | "b">(vals: Lit<V>, i: LIdxS<V>) => vals[i]
            export class C25<V extends "a" | "b"> { m(vals: Lit<V>, i: LIdxS<V>) { return vals[i] } }
            export function c27<V extends Unit>(vals: Vals<V>, i: Idx<V>) { return vals?.[i] }
            export function c22<V extends "a" | "b">(vals: IRec<V>, i: LIdxS<V>) { return vals[i] }
            """
        )
        assert(
            r == listOf(
                "15:72 TS2536 Type 'Idx<V>' cannot be used to index type 'Vals<V>'.",
                "16:78 TS2536 Type 'LIdxS<V>' cannot be used to index type 'Lit<V>'.",
                "17:72 TS2536 Type 'LIdxS<V>' cannot be used to index type 'Lit<V>'.",
                "18:79 TS2536 Type 'LIdxS<V>' cannot be used to index type 'Lit<V>'.",
                "19:72 TS2536 Type 'Idx<V>' cannot be used to index type 'Vals<V>'.",
                "20:79 TS2536 Type 'LIdxS<V>' cannot be used to index type 'IRec<V>'.",
            )
        )
    }

    @Test
    fun `the date-fns shape - an asserted key and an annotated let - reports and satisfies its expect-error`() {
        val r = rows(
            """
            export function f<V extends Unit>(args: { values: Vals<V> }, value: V, cb?: (v: V) => Idx<V>) {
              let valuesArray: Vals<V>;
              valuesArray = args.values as Vals<V>;
              const index = (cb ? cb(value as V) : value) as Idx<V>;
              const direct = valuesArray[index];
              // @ts-expect-error
              return [direct, valuesArray[index]];
            }
            """
        )
        assert(r == listOf("19:18 TS2536 Type 'Idx<V>' cannot be used to index type 'Vals<V>'."))
    }

    @Test
    fun `a write through the access is checked like a read`() {
        val r = rows(
            """
            export function c23<V extends "a" | "b">(vals: Lit<V>, i: LIdxS<V>, r: any) { vals[i] = r }
            """
        )
        assert(r == listOf("15:79 TS2536 Type 'LIdxS<V>' cannot be used to index type 'Lit<V>'."))
    }

    @Test
    fun `negative control - a route tsgo accepts by is silent`() {
        val r = rows(
            """
            export function c02<V extends Unit>(vals: Vals<V>, i: keyof Vals<V>) { return vals[i] }
            export function c03<V extends Unit, K extends keyof Vals<V>>(vals: Vals<V>, i: K) { return vals[i] }
            export function c04<V extends Unit>(vals: Vals<V>, i: IdxN<V>) { return vals[i] }
            export function c09<V extends 0 | 1>(vals: Tup<V>, i: TIdx<V>) { return vals[i] }
            export function c10<V extends "a" | "b">(vals: Lit<V>, i: LIdx<V>) { return vals[i] }
            export function c17<V extends "a" | "b">(vals: SRec<V>, i: LIdxS<V>) { return vals[i] }
            export function c08<V extends 0 | 1>(vals: Tup<V>, i: number) { return vals[i] }
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - an undecidable shape stays silent`() {
        val r = rows(
            """
            export function u1<V extends string>(vals: Lit<V & "a">, i: LIdxS<V & "a">) { return vals[i] }
            export function u2<V extends Unit>(vals: Vals<V>, i: Idx<V>) { { const i = 0 as any; return vals[i] } }
            export function u3<V extends Unit>(vals: Vals<V>, i: Idx<V>) { return (vals as any)[i] }
            """
        )
        assert(r.none { "TS2536" in it })
    }
    @Test
    fun `the distributive constraint decides when the default constraint fails`() {
        val r = rows(
            """
            type U2<V extends "a" | "b"> = V extends "a" | "b" ? "a" : string
            type U3<V extends "a" | "b"> = V extends "a" | "b" ? "c" : "a"
            export function e1<V extends "a" | "b">(vals: Lit<V>, i: U2<V>) { return vals[i] }
            export function e2<V extends "a" | "b">(vals: Lit<V>, i: U3<V>) { return vals[i] }
            """
        )
        assert(r == listOf("18:74 TS2536 Type 'U3<V>' cannot be used to index type 'Lit<V>'."))
    }
    @Test
    fun `a tuple branch has its slots and its Array members as keys and nothing else`() {
        val r = rows(
            """
            type TL<V extends "x" | 0> = V extends "x" ? { a: 1 } : readonly [string]
            type TLI<V extends "x" | 0> = V extends "x" ? "a" : "a"
            type TM<V extends "x" | 0> = V extends "x" ? { map: 1; length: 2 } : readonly [string]
            type TMI<V extends "x" | 0> = V extends "x" ? "map" : "length"
            export function e3<V extends "x" | 0>(vals: TL<V>, i: TLI<V>) { return vals[i] }
            export function e4<V extends "x" | 0>(vals: TM<V>, i: TMI<V>) { return vals[i] }
            """
        )
        assert(r == listOf("19:72 TS2536 Type 'TLI<V>' cannot be used to index type 'TL<V>'."))
    }
}
