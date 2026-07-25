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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 453: an un-annotated parameter (no type annotation, non-function default
 * or no default) is implicitly `any` and must SHADOW a same-named outer binding —
 * most importantly a top-level module-level var that leaked into `globals`.
 * tsc's own program.ts trips this: `const indent = "    "` (module const) shadowed
 * by `flattenDiagnosticMessageText(diag, newLine, indent = 0)`'s param, where the
 * recursive arg `indent` resolved to the leaked const's `string` → FP TS2345.
 *
 * `populateParameterLocalTypes` registered annotated / function-default params but
 * left an un-annotated non-function-default param unregistered, so it fell through
 * to the leaked binding. Now such a param name is added to the shadow set AND (when
 * a same-named entry is inherited) overrides currentLocalTypes with anyType. The
 * fix is un-annotated-only — an ANNOTATED shadowing param is still type-checked.
 */
class UnannotatedParamShadowsModuleVarTest {

    @Test
    fun `defaulted un-annotated param shadows same-named module const - no TS2345`() {
        diagnose(
            """
            const indent = "    ";
            export function flatten(diag: string, newLine: string, indent = 0): string {
                if (indent) { return newLine; }
                return flatten(diag, newLine, indent);
            }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a shadowing defaulted param passed to another function does not leak the const type - no TS2345`() {
        diagnose(
            """
            const size = "big";
            export function inner(n: number): number { return n; }
            export function outer(size = 3): number {
                return inner(size);
            }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an ANNOTATED shadowing param is still type-checked - TS2322`() {
        diagnose(
            """
            const indent = "    ";
            export function f(indent: string): number { return indent; }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
