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
 * (P18.292) EXPANDO — an ANNOTATED `const` bound to an arrow / function expression is an
 * expando host in tsgo's binder too: `get.raw = …` declares `raw` on the FUNCTION's type,
 * which is the source related to the annotation, so `const get: SG = () => …; get.raw = …`
 * relates `{ (): R; raw: … }` to `SG` (zod's `ShapeGetter`). A `let` is not a host, and
 * tsgo's source display carries the planted members. Rows measured against tsgo 7.0.2.
 */
class AnnotatedExpandoHostTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private val sg = "interface SG { (): number; raw: string }\n"

    @Test
    fun `a body-local annotated const with a later member write relates`() {
        val r = rows("""
            interface SG { (): Record<PropertyKey, any>; raw: Record<PropertyKey, any> }
            export function f(sh: Record<string, any>) {
              const get: SG = () => {
                const n = { ...sh }
                get.raw = n
                return n
              }
              get.raw = sh
              return get
            }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `a file-level annotated const and a write in a nested block`() {
        val r = rows(sg + """
            export const get: SG = () => 1;
            get.raw = "x";
            export function f() { const g: SG = () => 1; if (Math.random()) { g.raw = "x" } return g }
        """.trimIndent())
        assert(r.isEmpty())
    }

    @Test
    fun `a wrongly typed write shows the planted member in the source`() {
        val r = rows(sg + """
            export function f() { const get: SG = () => 1; get.raw = 1; return get }
        """.trimIndent())
        assert(r == listOf(
            "2:29 TS2322 Type '{ (): number; raw: number; }' is not assignable to type 'SG'.",
            "2:48 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `a still-missing member is named and not the written one`() {
        val r = rows("""
            interface SG { (): number; raw: string; other: number }
            export function f() { const get: SG = function () { return 1 }; get.raw = "x"; return get }
        """)
        assert(r == listOf(
            "2:29 TS2741 Property 'other' is missing in type '{ (): number; raw: string; }' but required in type 'SG'.",
        ))
    }

    @Test
    fun `negative control - a let is not an expando host`() {
        val r = rows(sg + """
            export function f() { let get: SG = () => 1; get.raw = "x"; return get }
        """.trimIndent())
        assert(r.size == 1 && r[0].startsWith("2:27 TS2"))
    }

    @Test
    fun `negative control - with no write the annotation still rejects the arrow`() {
        val r = rows(sg + """
            export function f() { const get: SG = () => 1; return get }
        """.trimIndent())
        assert(r.size == 1 && r[0].startsWith("2:29 TS2"))
    }
}
