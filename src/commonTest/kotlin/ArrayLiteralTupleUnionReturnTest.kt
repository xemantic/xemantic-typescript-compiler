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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 446: an array-literal `return [msg, ...args]` matching a variadic-TUPLE member
 * of the target — a bare tuple, or a union/alias containing one (tsc's
 * `DiagnosticOrDiagnosticAndArguments = DiagnosticMessage | [message: DiagnosticMessage,
 * ...args: (string|number)[]]`). The relation engine skips array→tuple and `getTupleType`
 * collapses the rest slot, so both the engine and the string fallback FP'd. The fix
 * AST-matches the array literal against the tuple. Suppression-only — a genuine element
 * mismatch keeps failing.
 */
class ArrayLiteralTupleUnionReturnTest {

    @Test
    fun `direct return of a variadic-tuple-in-union array literal is legal`() {
        diagnose(
            """
            type Msg = string | [head: string, ...args: (string | number)[]];
            function f(): Msg { return ["a", 1, "b", 2]; }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `head-only array literal (empty rest) matches the variadic tuple`() {
        diagnose(
            """
            type Msg = string | [head: string, ...args: number[]];
            function f(): Msg { return ["only"]; }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `conditional return with array-literal branches matching the tuple union is legal`() {
        diagnose(
            """
            type Msg = string | [head: string, ...args: (string | number)[]];
            function f(c: boolean): Msg { return c ? ["x", 1] : ["y"]; }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `variadic tuple in a union with undefined is legal`() {
        diagnose(
            """
            type Msg = string | [head: string, ...args: (string | number)[]];
            function f(c: boolean): Msg | undefined {
                if (c) { return ["a", 1]; }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `fixed tuple (no rest) in a union matches an array literal`() {
        diagnose(
            """
            type P = number | [string, string];
            function g(): P { return ["a", "b"]; }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `direct tuple target (no union) matches an array literal return`() {
        diagnose(
            """
            type Q = [head: string, ...args: number[]];
            function h(): Q { return ["a", 1, 2]; }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - wrong fixed-prefix element type still fires`() {
        diagnose(
            """
            type Msg = number | [head: string, ...args: number[]];
            function f(): Msg { return [true, 1]; }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - wrong rest element type still fires`() {
        diagnose(
            """
            type Msg = string | [head: string, ...args: number[]];
            function f(): Msg { return ["a", true]; }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
