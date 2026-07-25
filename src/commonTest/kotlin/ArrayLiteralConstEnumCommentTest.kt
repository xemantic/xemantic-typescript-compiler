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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EP.2a (round 674): an inlined const-enum member inside an ARRAY LITERAL must
 * carry its explanatory comment EXACTLY ONCE.
 *
 * The bug: `emitArrayLiteral` re-emits each element's same-line trailing
 * comments after `emitExpression(element)`, guarded by `element !is
 * NumericLiteralNode` because a numeric literal already emits its own. A
 * StringLiteralNode emits its own too but was NOT excluded, so a STRING-valued
 * const enum printed its label twice — 128 occurrences on the tsc compiler
 * profile, all `Extension.*`, which is why only string-valued enums showed it.
 * Both array branches (single-line and multiline) carried the same pair.
 *
 * Counting is the assertion here: a substring check would pass on doubled
 * output, which is exactly how this survived until the emit-diff gate exposed
 * it.
 */
class ArrayLiteralConstEnumCommentTest {

    private fun occurrences(haystack: String, needle: String): Int {
        var n = 0; var i = haystack.indexOf(needle)
        while (i >= 0) { n++; i = haystack.indexOf(needle, i + needle.length) }
        return n
    }

    private fun emit(body: String): String {
        val source = """
            // @module: commonjs
            // @filename: e.ts
            export const enum Ext { Cts = ".cts", Cjs = ".cjs" }
            export const enum Num { A = 1, B = 2 }
            // @filename: m.ts
            $body
        """.trimIndent()
        return TypeScriptCompiler().compile(source, "m.ts").jsOutputs.joinToString("\n") { it.second }
    }

    @Test
    fun `a string-valued const enum in a SINGLE-LINE array is commented once`() {
        val js = emit(
            """
            import { Ext } from "./e";
            export const arr = [Ext.Cts, Ext.Cjs];
            """.trimIndent()
        )
        assertEquals(1, occurrences(js, "/* Ext.Cts */"), "comment must appear once, got:\n$js")
        assertEquals(1, occurrences(js, "/* Ext.Cjs */"), "comment must appear once, got:\n$js")
        assertTrue("\".cts\" /* Ext.Cts */" in js)
    }

    @Test
    fun `a string-valued const enum in a MULTILINE array is commented once`() {
        val js = emit(
            """
            import { Ext } from "./e";
            export const arr = [
                Ext.Cts,
                Ext.Cjs,
            ];
            """.trimIndent()
        )
        assertEquals(1, occurrences(js, "/* Ext.Cts */"), "comment must appear once, got:\n$js")
        assertEquals(1, occurrences(js, "/* Ext.Cjs */"), "comment must appear once, got:\n$js")
    }

    @Test
    fun `a NUMERIC const enum in an array is still commented once`() {
        val js = emit(
            """
            import { Num } from "./e";
            export const arr = [Num.A, Num.B];
            """.trimIndent()
        )
        assertEquals(1, occurrences(js, "/* Num.A */"), "comment must appear once, got:\n$js")
        assertEquals(1, occurrences(js, "/* Num.B */"), "comment must appear once, got:\n$js")
    }

    @Test
    fun `a const enum OUTSIDE an array is unaffected`() {
        val js = emit(
            """
            import { Ext } from "./e";
            export const one = Ext.Cts;
            """.trimIndent()
        )
        assertEquals(1, occurrences(js, "/* Ext.Cts */"))
    }

    @Test
    fun `nested arrays and call arguments each comment once`() {
        val js = emit(
            """
            import { Ext } from "./e";
            declare function take(x: unknown): void;
            export const nested = [[Ext.Cts], [Ext.Cjs]];
            take([Ext.Cts]);
            """.trimIndent()
        )
        assertEquals(2, occurrences(js, "/* Ext.Cts */"), "two distinct sites, once each, got:\n$js")
        assertEquals(1, occurrences(js, "/* Ext.Cjs */"))
    }

    @Test
    fun `a genuine source trailing comment on an array element is still emitted`() {
        // Negative control for the guard: the fix must not silence REAL comments
        // that only the array loop would emit.
        val js = emit(
            """
            export const arr = ["a" /* keep me */, "b"];
            """.trimIndent()
        )
        assertEquals(1, occurrences(js, "/* keep me */"), "source comment must survive, got:\n$js")
    }
}
