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
 */

package com.xemantic.typescript.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 436c: `return <literal>` against an annotation whose top-level union
 * syntactically contains that literal member is ALWAYS legal. The engine
 * relation passed but did not early-return for non-nullish sources, so control
 * fell to the STRING fallback, which re-widened the literal ('boolean'/'string')
 * and FP'd against the union display — tsc parser.ts's `return false;` against
 * `JSDocTypeTag | … | false` ×4, and the completely unpinned general shape
 * `function f(): "a" | "b" { return "a"; }`.
 */
class LiteralReturnVsLiteralUnionTest {

    private fun ts2322s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2322 }

    @Test fun stringLiteralReturnAgainstItsUnionIsLegal() {
        val diags = ts2322s("""function g(): "a" | "b" { return "a"; }""")
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** The tsc parser.ts shape: `return false` vs an interface union with `| false`. */
    @Test fun returnFalseAgainstUnionWithFalseIsLegal() {
        val diags = ts2322s(
            """
            interface TagA { kind: "a"; x: number }
            interface TagB { kind: "b"; y: number }
            declare function tryParse(): TagA | TagB | false;
            function f(c: boolean): TagA | TagB | false {
                if (c) { return tryParse(); }
                return false;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    @Test fun numericLiteralAndNegativeFormsMatch() {
        val diags = ts2322s(
            """
            function f(c: boolean): 0 | -1 | "x" {
                if (c) { return 0; }
                return -1;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** Alias form: the union body lives behind a type alias. */
    @Test fun literalReturnAgainstAliasUnionIsLegal() {
        val diags = ts2322s(
            """
            type Mode = "read" | "write" | false;
            function f(c: boolean): Mode {
                if (c) { return "read"; }
                return false;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: a literal NOT in the union still fires. */
    @Test fun nonMemberLiteralReturnStillFires() {
        val diags = ts2322s("""function g(): "a" | "b" { return "c"; }""")
        assertTrue(diags.isNotEmpty(), "expected TS2322 for '\"c\"' vs '\"a\" | \"b\"'")
    }

    /** NEGATIVE control: `return true` when only `false` is in the union still fires. */
    @Test fun trueAgainstFalseOnlyUnionStillFires() {
        val diags = ts2322s(
            """
            interface TagA { kind: "a" }
            function g(): TagA | false { return true; }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for 'true' vs 'TagA | false'")
    }
}
