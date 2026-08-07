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
 * Local corner-case tests for the parentheses around an erased type assertion.
 *
 * Erasing the cast from `(x + 1 as number) * 3` and dropping the parentheses with it
 * emits `x + 1 * 3` — the `*` re-associates into the sum, so the program MEANS something
 * else. This is the one class of emit difference that is not cosmetic, which is why the
 * conformance case carries the comment "Must emit as (x + 1) * 3".
 *
 * The controls pin the other direction: parentheses that were only holding the cast must
 * still go, or every erased assertion would accumulate them.
 */
class TypeAssertionParenErasureTest {

    private fun emit(source: String): String =
        TypeScriptCompiler()
            .compile("// @strict: false\n" + source.trimIndent(), "t.ts")
            .javascript ?: ""

    @Test
    fun `a BINARY operand keeps its parentheses when the cast is erased`() {
        val js = emit(
            """
            declare var x;
            (x + 1 as number) * 3;
            """
        )
        assert(js.contains("(x + 1) * 3"))
    }

    @Test
    fun `a CONDITIONAL operand keeps its parentheses`() {
        val js = emit(
            """
            declare var c, a, b;
            (c ? a : b as number) + 1;
            """
        )
        assert(js.contains("(c ? a : b) + 1"))
    }

    @Test
    fun `control - a simple operand drops them`() {
        val js = emit(
            """
            declare var x;
            (x as any).y;
            """
        )
        assert(js.contains("x.y"))
        assert(!js.contains("(x).y"))
    }

    @Test
    fun `control - a call under new keeps them`() {
        // `new (x())` is not `new x()`: dropping these parentheses changes which
        // expression is constructed.
        val js = emit(
            """
            declare var x;
            new (x() as any);
            """
        )
        assert(js.contains("new (x())"))
    }
}
