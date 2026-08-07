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
 * Round 468 (Blocker #3): a returned object literal whose target interface is
 * file-local and NOT itself conflated, but has a MEMBER whose declared type names
 * a CONFLATED interface this file also declares — tsc importTracker's
 * `ExportedSymbol { kind; symbol; exportInfo: ExportInfo }` where `ExportInfo` is
 * conflated with convertExport.ts's own. The NESTED object-literal value checks
 * against the file-local X; the other members must relate normally.
 */
class NestedConflatedMemberReturnTest {

    @Test
    fun `a nested object-literal member checks against the conflated file-local interface`() {
        diagnose(
            """
            // @filename: other.ts
            export interface ExportInfo { exportNode: string; wasDefault: boolean; }
            export const dummy = 1;
            // @filename: main.ts
            interface ExportInfo { exportingModule: string; exportKind: number; }
            interface ExportedSymbol { kind: number; exportInfo: ExportInfo; }
            export function getExport(m: string, k: number): ExportedSymbol | undefined {
                return { kind: k, exportInfo: { exportingModule: m, exportKind: k } };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a nested object literal missing the FILE-LOCAL member still fires`() {
        diagnose(
            """
            // @filename: other.ts
            export interface ExportInfo { exportNode: string; wasDefault: boolean; }
            export const dummy = 1;
            // @filename: main.ts
            interface ExportInfo { exportingModule: string; exportKind: number; }
            interface ExportedSymbol { kind: number; exportInfo: ExportInfo; }
            export function getExport(m: string, k: number): ExportedSymbol | undefined {
                return { kind: k, exportInfo: { exportingModule: m } };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `exportKind` is required by THIS file's own ExportInfo — genuinely missing.
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a non-relating sibling member still fires`() {
        diagnose(
            """
            // @filename: other.ts
            export interface ExportInfo { exportNode: string; wasDefault: boolean; }
            export const dummy = 1;
            // @filename: main.ts
            interface ExportInfo { exportingModule: string; exportKind: number; }
            interface ExportedSymbol { kind: number; exportInfo: ExportInfo; }
            export function getExport(m: string, k: number): ExportedSymbol | undefined {
                return { kind: "wrong", exportInfo: { exportingModule: m, exportKind: k } };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `kind: "wrong"` fails ExportedSymbol's own `kind: number` — must keep firing.
            have(any { it.code == 2322 })
        }
    }
}
