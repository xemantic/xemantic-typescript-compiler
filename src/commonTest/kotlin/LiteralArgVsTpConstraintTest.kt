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
 * Round 435d: a bare TYPE-PARAMETER param whose CONSTRAINT contains literal
 * types is a literal-preserving inference position (tsc keeps literal
 * candidates when inferring to a TP whose constraint includes literals) —
 * `propTypeContainsLiteral` gained a TypeParam-constraint arm, so the 17.67
 * arg-typing rule keeps a literal arg's literal type and the 16.4i
 * constraint check passes/fails on the LITERAL, matching tsc.
 *
 * tsc's own shapes: moduleNameResolver.ts `readPackageJsonPathField<K extends
 * "typings" | "types" | "main" | "tsconfig">(json, fieldName: K)` called with
 * `"typings"`; utilities.ts/checker.ts `pragmas.get("jsx")` against
 * `TKey extends keyof PragmaPseudoMap`.
 */
class LiteralArgVsTpConstraintTest {

    private fun ts2345s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2345 }

    /** A literal arg satisfying the TP's literal-union constraint is legal. */
    @Test fun literalArgInConstraintUnionIsLegal() {
        val diags = ts2345s(
            """
            function readField<K extends "typings" | "types" | "main" | "tsconfig">(
                json: object, fieldName: K): string | undefined {
                return undefined;
            }
            export function readTypes(json: object) {
                return readField(json, "typings") || readField(json, "types");
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2345, got: $diags")
    }

    /** NEGATIVE control: a literal OUTSIDE the constraint union still fails —
     *  and displays with its LITERAL type (tsc's display for this shape). */
    @Test fun literalArgOutsideConstraintUnionStillFires() {
        val diags = ts2345s(
            """
            function readField<K extends "typings" | "types" | "main" | "tsconfig">(
                json: object, fieldName: K): string | undefined {
                return undefined;
            }
            export function bad(json: object) {
                return readField(json, "nope");
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2345 for a literal outside the constraint")
    }

    /** A widened (non-literal) string arg still fails a literal-union constraint. */
    @Test fun widenedStringArgStillFires() {
        val diags = ts2345s(
            """
            function readField<K extends "typings" | "types" | "main" | "tsconfig">(
                json: object, fieldName: K): string | undefined {
                return undefined;
            }
            export function bad(json: object, s: string) {
                return readField(json, s);
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2345 for a plain string arg")
    }
}
