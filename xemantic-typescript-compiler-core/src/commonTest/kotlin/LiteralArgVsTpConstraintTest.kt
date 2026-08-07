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

    @Test
    fun `a literal arg satisfying the TP literal-union constraint is legal`() {
        diagnose(
            """
            function readField<K extends "typings" | "types" | "main" | "tsconfig">(
                json: object, fieldName: K): string | undefined {
                return undefined;
            }
            export function readTypes(json: object) {
                return readField(json, "typings") || readField(json, "types");
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a literal outside the constraint union still fires`() {
        // The failure displays with its LITERAL type (tsc's display for this shape).
        diagnose(
            """
            function readField<K extends "typings" | "types" | "main" | "tsconfig">(
                json: object, fieldName: K): string | undefined {
                return undefined;
            }
            export function bad(json: object) {
                return readField(json, "nope");
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a widened string arg still fails a literal-union constraint`() {
        diagnose(
            """
            function readField<K extends "typings" | "types" | "main" | "tsconfig">(
                json: object, fieldName: K): string | undefined {
                return undefined;
            }
            export function bad(json: object, s: string) {
                return readField(json, s);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
