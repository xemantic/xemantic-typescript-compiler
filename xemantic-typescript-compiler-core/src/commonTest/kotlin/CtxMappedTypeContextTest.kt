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
 * (CHK.214) Contextual typing through a MAPPED type whose key domain is `string` /
 * `number` (`Record<string, C>`), which the checker resolves to `any`
 * (`Checker.ctxMappedIndexType` mints the contextual index signature), plus the two
 * key-remap defects (`string & K` and the PRE-remap key in the template) and the
 * numeric member key. Every expectation is tsgo 7.0.2's full row; each positive pin
 * reads a WRONG-TYPED USE of a parameter, so it proves the parameter is TYPED rather
 * than merely silenced (CLAUDE.md's (CHK.30) grading rule).
 */
class CtxMappedTypeContextTest {

    private val directives = "// @strict: true\n// @useRealLibs: true"
    private val prelude = "type C = (agg: { n: number }, def: string) => void;\n"

    // Directive lines are not counted (tsgo reports these rows two lines lower).
    private fun rows(source: String): List<String> =
        diagnose(prelude + source, directives).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val num = "TS2322 Type 'number' is not assignable to type 'boolean'."
    private val str = "TS2322 Type 'string' is not assignable to type 'boolean'."

    @Test
    fun `Record of string types both callback parameters`() {
        val r = rows("export const a: Record<string, C> = { g: (agg, def) => { const x: boolean = agg.n; const y: boolean = def; } };")
        assert(r == listOf("2:64 $num", "2:90 $str"))
    }

    @Test
    fun `a nested Record recurses on the argument node`() {
        val r = rows("export const b: Record<string, Record<string, C>> = { o: { g: (agg) => { const x: boolean = agg.n; } } };")
        assert(r == listOf("2:80 $num"))
    }

    @Test
    fun `a mapped type over string types a method shorthand member`() {
        val r = rows("export const c: { [K in string]: C } = { g(agg) { const x: boolean = agg.n; } };")
        assert(r == listOf("2:57 $num"))
    }

    @Test
    fun `Record of number types a numeric member key`() {
        val r = rows("export const d: Record<number, C> = { 1: (agg) => { const x: boolean = agg.n; } };")
        assert(r == listOf("2:59 $num"))
    }

    @Test
    fun `a string-and-K key remap reads the source key in its template`() {
        val r = rows(
            "type Remap<T> = { [K in keyof T as `on\${string & K}`]: (v: T[K]) => void };\n" +
                "export const e: Remap<{ x: number; y: string }> = { onx: (v) => { const x: boolean = v; }, ony: (v) => { const x: boolean = v; } };",
        )
        assert(r == listOf("3:73 $num", "3:112 $str"))
    }

    @Test
    fun `a return position annotated Record types the callback`() {
        val r = rows("export function f(): Record<string, C> { return { g: (agg) => { const x: boolean = agg.n; } }; }")
        assert(r == listOf("2:71 $num"))
    }

    @Test
    fun `a union of Record and undefined types the callback`() {
        val r = rows("export const g: Record<string, C> | undefined = { g: (agg) => { const x: boolean = agg.n; } };")
        assert(r == listOf("2:71 $num"))
    }

    @Test
    fun `negative control - legal uses under every shape report nothing`() {
        val r = rows(
            """
            export const a2: Record<string, C> = { g: (agg, def) => { const n: number = agg.n; const s: string = def; }, h(agg) { const n: number = agg.n; } };
            export const b2: Record<string, Record<string, C>> = { o: { g: (agg) => { const n: number = agg.n; } } };
            type Remap2<T> = { [K in keyof T as `on${'$'}{string & K}`]: (v: T[K]) => void };
            export const e2: Remap2<{ x: number }> = { onx: (v) => { const n: number = v; } };
            export const d2: Record<number, C> = { 1: (agg) => { const n: number = agg.n; } };
            export function f2(): Record<string, C> { return { g: (agg) => { const n: number = agg.n; } }; }
            """.trimIndent(),
        )
        assert(r.isEmpty())
    }

    @Test
    fun `control - a literal-key Record and an index signature were already typed`() {
        val r = rows(
            "export const p: Record<\"a\", C> = { a: (agg) => { const x: boolean = agg.n; } };\n" +
                "export const q: { [k: string]: C } = { g: (agg) => { const x: boolean = agg.n; } };",
        )
        assert(r == listOf("2:56 $num", "3:60 $num"))
    }
}
