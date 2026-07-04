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
 * A nested `let`/`var` declaration SHADOWS an enclosing `const` of the same name within its
 * block, so reassigning the inner binding must NOT fire TS2588 "Cannot assign to 'c' because
 * it is a constant." The const-assignment walker inherits the enclosing block's const set into
 * nested blocks (a copy), but only collected `const` declarations — a nested `let c` did not
 * REMOVE `c` from the inherited set, so the inner reassignment saw the outer const → FP. This
 * was 4 self-compile FPs (tsc's `compareTypes` in checker.ts: `const c = compareSymbols(...)`
 * enclosing `else { let c = compareNodes(...); c = compareTypeMappers(...); }`, and
 * moduleNameResolver's `let resolved` shadowing an enclosing `const resolved`).
 */
class ConstShadowedByNestedLetTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(body.trimIndent(), "t.ts").diagnostics

    @Test
    fun `nested let shadowing an enclosing const - reassign does NOT fire TS2588`() {
        val d = diags(
            """
            function f(a: number): number {
                if (a & 1) {
                    const c = 1;
                    if (c !== 0) return c;
                    if (a & 2) {
                        let c = 2;
                        c = 3;
                        return c;
                    }
                }
                return 0;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2588 },
            "reassigning a nested let that shadows an enclosing const must NOT be TS2588; got: " +
                d.joinToString { "TS${it.code}@${it.line}:${it.character}" },
        )
    }

    @Test
    fun `nested var (compound assign) shadowing an enclosing const - no TS2588`() {
        // moduleNameResolver's `let resolved = …; resolved ??= …` shape shadowing an outer const.
        val d = diags(
            """
            function g(x: boolean, y: number | undefined): number {
                const resolved = 1;
                if (resolved !== 0) {
                    if (x) {
                        let resolved = y;
                        resolved ??= 5;
                        return resolved;
                    }
                }
                return resolved;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2588 },
            "reassigning a nested let that shadows an enclosing const must NOT be TS2588; got: " +
                d.joinToString { "TS${it.code}@${it.line}:${it.character}" },
        )
    }

    @Test
    fun `a genuine const reassignment STILL fires TS2588 (negative control)`() {
        val d = diags(
            """
            function h(): number {
                const c = 1;
                c = 2;
                return c;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2588 && it.message.contains("'c'") },
            "reassigning a real const must still be TS2588; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `an enclosing const reassigned before a nested let still fires on the outer one`() {
        // The outer `c = 2` reassigns the const (TS2588); the inner `let c; c = 4` is fine.
        val d = diags(
            """
            function k(a: number): number {
                const c = 1;
                c = 2;
                if (a) { let c = 3; c = 4; }
                return c;
            }
            """,
        )
        val ts2588 = d.filter { it.code == 2588 }
        assertTrue(
            ts2588.size == 1 && ts2588[0].line == 3,
            "exactly one TS2588 on the outer const reassignment (line 3); got: " +
                d.joinToString { "TS${it.code}@${it.line}:${it.character}" },
        )
    }
}
