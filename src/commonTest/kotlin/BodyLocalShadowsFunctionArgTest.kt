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
 * Round 428d (M3.1): the call-types walker registers an anyType SHADOW for a
 * body-local declaration whose name collides with an outer/file-level FUNCTION —
 * otherwise a bare-identifier ARG resolves through the merged globals to the
 * function and FPs TS2345 (tsc checker.ts's `const symbolName = …;
 * canUsePropertyAccess(symbolName, …)` shadowing utilitiesPublic's
 * `function symbolName(symbol: Symbol): string`, ×~40 self-compile).
 * Mirrors M1.11's shadowNestedFunctionNames (suppression-only).
 */
class BodyLocalShadowsFunctionArgTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `body-local const shadowing a file-level function does not FP as arg`() {
        val d = diags(
            """
            interface Sym { name: string; }
            function symbolName(symbol: Sym): string { return symbol.name; }
            declare function canUse(name: string, version: number): boolean;
            export function f(sym: Sym): boolean {
                const symbolName = sym.name + "!";
                return canUse(symbolName, 5);
            }
            """
        )
        assertTrue(d.none { it.code == 2345 }, "expected no TS2345, got: $d")
    }

    @Test
    fun `negative control - non-shadowing wrong-typed param still fires`() {
        // (A non-callable BODY local isn't typed by this pass at all — the baseline
        // capability is param-args — so the control uses a param.)
        val d = diags(
            """
            declare function canUse(name: string, version: number): boolean;
            export function f(count: number): boolean {
                return canUse(count, 5);
            }
            """
        )
        assertTrue(d.any { it.code == 2345 }, "expected TS2345 for number vs string, got: $d")
    }

    @Test
    fun `negative control - passing the actual function still fires`() {
        // No local shadow in scope: the outer function passed by name where a
        // string is required must keep firing.
        val d = diags(
            """
            interface Sym { name: string; }
            function symbolName(symbol: Sym): string { return symbol.name; }
            declare function canUse(name: string, version: number): boolean;
            export function f(): boolean {
                return canUse(symbolName, 5);
            }
            """
        )
        assertTrue(d.any { it.code == 2345 }, "expected TS2345 for fn vs string, got: $d")
    }
}
