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
 * (P18.290) (LIBS.3) CURRIED — the RETURNED arrow of a curried arrow is contextually typed
 * by the contextual signature's RETURN type (tsgo `getContextualReturnType` for an arrow's
 * expression body), never by the outer arrow's own contextual type.
 *
 * Before: `const a: F = (x) => (o) => o.buffer` reported TS2322 `'(x: number) => (o: number)
 * => any'` (the ambient `contextualType` leaked into the body, so `o` took the OUTER
 * parameter list), and as a call argument the inner arrow got no context at all (TS7006).
 * Every expectation is `tools/tsgo-7.0.2/lib/tsc`'s row for the same fixture, in tsgo's
 * coordinates (`Diagnostic.line` is 0-based and the directive line shifts it by one).
 */
class CurriedArrowContextualReturnTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val prelude = """
        type Opts = { buffer: string }
        type F = (x: number) => (o: Opts) => string
    """.trimIndent() + "\n"

    @Test
    fun `a curried arrow against an annotation types the inner arrow from the return type`() {
        assert(rows(prelude + """
            export const a1: F = (x) => (o) => o.buffer
            export const a2: F = (x) => ({ buffer }) => buffer
            export const a3: (x: number, y: string) => (o: Opts) => string = (x, y) => (o) => o.buffer + y
            export const a4: (x: number) => (y: string) => (o: Opts) => string = (x) => (y) => (o) => o.buffer + y
            export const a5: F = (x) => { return (o) => o.buffer }
            export const a6: F | undefined = (x) => (o) => o.buffer
            export const a7: F = (x: number) => (o) => o.buffer
            export const a8: F = function (x) { return (o) => o.buffer }
        """.trimIndent()).isEmpty())
    }

    @Test
    fun `a curried arrow passed as an argument gives the inner arrow a context`() {
        assert(rows(prelude + """
            declare function take(f: F): void
            take((x) => (o) => o.buffer)
            declare function takeG<T>(v: T, f: (x: T) => (o: Opts) => string): void
            takeG(1, (x) => (o) => o.buffer)
        """.trimIndent()).isEmpty())
    }

    @Test
    fun `a wrong-typed read inside the inner arrow reports`() {
        assert(rows(prelude + """
            export const b2: F = (x) => (o) => o.nope
            declare function take(f: F): void
            take((x) => (o) => { const bad: number = o.buffer; return "" })
            export const b4: F = (x) => (o) => { const bad: string = x; return "" }
        """.trimIndent()) == listOf(
            "3:38 TS2339 Property 'nope' does not exist on type 'Opts'.",
            "5:28 TS2322 Type 'string' is not assignable to type 'number'.",
            "6:44 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `an annotation's inner block body sees the inner parameter type`() {
        // tsgo additionally anchors a whole-arrow TS2322 at the inner arrow (13:29); ours
        // anchors it at the declaration — the pre-existing arrow-return elaboration residue.
        val r = rows(prelude + """
            export const b1: F = (x) => (o) => { const bad: number = o.buffer; return bad }
        """.trimIndent())
        assert("3:44 TS2322 Type 'string' is not assignable to type 'number'." in r)
    }

    @Test
    fun `an overloaded contextual type folds its applicable signatures`() {
        assert(rows("""
            type Opts = { buffer: string }
            interface Ov { (x: number): (o: Opts) => string; (x: string): (o: Opts) => string }
            export const c1: Ov = (x) => (o) => o.buffer
            type Ctx<E> = { env: E; path: string }
            type Handler<E> = (c: Ctx<E>, next: () => Promise<void>) => Promise<void>
            interface Mw {
              <E extends object = {}>(gen: (c: Ctx<E>) => string[]): Handler<E>
              <E extends object = {}>(params: string[]): Handler<E>
            }
            export const mw: Mw = (params) => async (c, next) => {
              const bad: number = c.path
              await next()
            }
        """) == listOf(
            "11:9 TS2322 Type 'string' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `control - a curried arrow returning the wrong type still reports`() {
        val r = rows(prelude + """
            export const d1: F = (x) => (o) => x
        """.trimIndent())
        assert(r.size == 1)
        assert(r.single().contains("TS2322"))
    }
}
