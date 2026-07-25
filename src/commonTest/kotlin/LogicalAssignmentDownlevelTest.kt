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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Emit-parity family #3 (self-compile emit diff, 2026-07-12): tsc downlevels the
 * ES2021 logical-assignment operators `||=`/`&&=`/`??=` to the short-circuiting
 * read/write form below ES2021 (`a ||= b` -> `a || (a = b)`), capturing a
 * side-effecting property/element receiver into a temp so it is evaluated once.
 * xtsc previously emitted the operators verbatim at `target: es2020`
 * (~284 sites in the tsc `compiler` self-compile profile). The corpus has ZERO
 * files exercising these operators, so this invariant is pinned only here.
 */
class LogicalAssignmentDownlevelTest {

    private fun js(@Language("typescript") source: String, target: String = "es2020"): String =
        TypeScriptCompiler().compile("// @target: $target\n$source").javascript
            ?: error("no javascript output")

    @Test
    fun `identifier or-assign downlevels to short-circuit form at es2020`() {
        val out = js("let a: number | undefined; a ||= 1;")
        assert("a || (a = 1)" in out)
        assert(!("||=" in out))
    }

    @Test
    fun `identifier and-assign downlevels to short-circuit form`() {
        val out = js("let a: number | undefined; a &&= 2;")
        assert("a && (a = 2)" in out)
        assert(!("&&=" in out))
    }

    @Test
    fun `identifier nullish-assign downlevels keeping native nullish coalescing at es2020`() {
        val out = js("let a: number | undefined; a ??= 3;")
        assert("a ?? (a = 3)" in out)
        assert(!("??=" in out))
    }

    @Test
    fun `property access with simple this receiver is not captured`() {
        val out = js(
            """
            class C {
              x: number | undefined;
              m() { this.x ||= 4; }
            }
            """.trimIndent(),
        )
        assert("this.x || (this.x = 4)" in out)
    }

    @Test
    fun `property access with side-effecting receiver captures the receiver once`() {
        val out = js(
            """
            declare function f(): { x: number | undefined };
            f().x ??= 5;
            """.trimIndent(),
        )
        assert("(_a = f()).x ?? (_a.x = 5)" in out)
    }

    @Test
    fun `element access captures receiver and key once without parenthesizing the key`() {
        val out = js(
            """
            declare function obj(): Record<string, number>;
            declare function key(): string;
            obj()[key()] ||= 6;
            """.trimIndent(),
        )
        assert("(_a = obj())[_b = key()] || (_a[_b] = 6)" in out)
    }

    @Test
    fun `es2021 target keeps the operator verbatim`() {
        val out = js("let a: number | undefined; a ??= 7;", target = "es2021")
        assert("a ??= 7" in out)
    }
}
