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
 * Round 467 (Blocker #3): the round-445 note's prescribed `||`-nested extension —
 * `return noSymbolError(name) || { exportNode, exportName, … }` against
 * `ExportInfo | RefactorErrorInfo | undefined` where `interface ExportInfo` is
 * CONFLATED (declared in ≥2 module files, merged members polluting the union
 * member). The RIGHT object literal checks against the conflated FILE-LOCAL
 * interface; the LEFT operand's non-falsy type must relate on its own.
 * Multi-file test (the conflation needs two module files).
 */
class OrNestedConflatedObjLitReturnTest {

    @Test
    fun `an or-nested object literal checks against the conflated file-local interface`() {
        diagnose(
            """
            // @filename: other.ts
            export interface ExportInfo { exportNode: string; exportKind: number; }
            export const dummy = 1;
            // @filename: main.ts
            interface RefactorErrorInfo { error: string; }
            interface ExportInfo { exportNode: string; wasDefault: boolean; }
            declare function noSymbolError(id: string): RefactorErrorInfo | undefined;
            export function getInfo(name: string): ExportInfo | RefactorErrorInfo | undefined {
                return noSymbolError(name) || { exportNode: name, wasDefault: true };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an object literal missing the FILE-LOCAL required member still fires`() {
        diagnose(
            """
            // @filename: other.ts
            export interface ExportInfo { exportNode: string; exportKind: number; }
            export const dummy = 1;
            // @filename: main.ts
            interface RefactorErrorInfo { error: string; }
            interface ExportInfo { exportNode: string; wasDefault: boolean; }
            declare function noSymbolError(id: string): RefactorErrorInfo | undefined;
            export function getInfo(name: string): ExportInfo | RefactorErrorInfo | undefined {
                return noSymbolError(name) || { exportNode: name };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `wasDefault` is required by THIS file's own ExportInfo — genuinely missing.
            have(any { it.code == 2322 })
        }
    }
}
