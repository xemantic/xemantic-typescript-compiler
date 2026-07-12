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
 * Round 479: an `import * as X from "…"` alias IS a namespace in type positions
 * (`X.Type`) even though the binder gives it only SymbolFlags.Alias, so
 * [checkTypeNameResolved] must never route it into the TS2833/TS2702 branch —
 * a case-differing sibling namespace otherwise manufactures a bogus
 * "Did you mean 'Compiler'?" (harnessIO.ts's `compiler.CompilationResult` vs
 * the local `Compiler` namespace).
 */
class NamespaceImportQualifiedTypeTest {

    @Test
    fun `namespace-import qualifier with case-differing sibling namespace draws no TS2833`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: comp.ts
            export interface CompilationResult {
                errors: string[];
            }
            // @filename: main.ts
            import * as compiler from "./comp.js";
            export namespace Compiler {
                export const marker = 1;
            }
            export type Result = compiler.CompilationResult & { repeat(): Result; };
            export function f(r: compiler.CompilationResult): void {
                void r;
            }
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2833 || it.code == 2503 || it.code == 2702 })
        }
    }

    @Test
    fun `negative control - a value qualifier still errors as a namespace`() {
        diagnose(
            """
            export namespace Compiler {
                export interface Result { x: number; }
            }
            const compiler = 1;
            export type T = compiler.Result;
            void compiler;
            """,
        ) should {
            have(any { it.code == 2833 || it.code == 2702 || it.code == 2503 })
        }
    }
}
