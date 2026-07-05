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
import kotlin.test.assertTrue

/**
 * M1.12 (self-compile burn-down): TS2394 ("This overload signature is not compatible with its
 * implementation signature.") must NOT fire when the OVERLOAD's return type is `void` — tsc's
 * `isImplementationCompatibleWithOverload` short-circuits `targetReturnType === voidType`, because
 * a caller of a void-returning overload ignores the returned value. tsc's own emitter.ts
 * `writeTokenText(…): void;` / `writeTokenText(…): number;` (impl `: number`) relies on it.
 */
class OverloadVoidReturnTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile(source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `void overload return with non-void impl return - no TS2394`() {
        // The exact emitter.ts writeTokenText shape.
        val d = diags(
            """
            function writeTokenText(token: number, writer: (s: string) => void): void;
            function writeTokenText(token: number, writer: (s: string) => void, pos: number): number;
            function writeTokenText(token: number, writer: (s: string) => void, pos?: number): number {
                return pos ?? 0;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2394 },
            "a `void` overload return must be compatible with a `number` implementation return; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `void overload return over a value-returning impl - no TS2394`() {
        val d = diags(
            """
            function f(x: string): void;
            function f(x: string): string {
                return x;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2394 },
            "a `void` overload return must accept a `string` implementation return; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `unrelated non-void overload return still fires TS2394 - negative control`() {
        // overload returns C, impl returns E; C and E are unrelated → genuine incompatibility.
        val d = diags(
            """
            class C { c!: number; }
            class E { e!: string; }
            function g(x: number): C;
            function g(x: number): E {
                return new E();
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2394 },
            "an overload returning an unrelated class must still fire TS2394; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
