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
 * Round 468 (Blocker #3): the ARG variant of the conflated-interface rule — the
 * param annotation names a CONFLATED interface (declared in ≥2 module files; the
 * merged symbol carries every file's members) that the CALLING file merely
 * IMPORTS. The annotation really resolves to ONE declaring file's interface, so
 * an object-literal arg that EXACTLY satisfies some declaring file's version is
 * correct — tsc findAllReferences passing `{ exportingModuleSymbol, exportKind }`
 * to importTracker's `ExportInfo` param (conflated with convertExport.ts's).
 */
class ConflatedInterfaceArgTest {

    @Test
    fun `an object-literal arg exactly satisfying some declaring file's interface is accepted`() {
        diagnose(
            """
            // @filename: a.ts
            export interface ExportInfo { x: string; }
            export function useInfo(info: ExportInfo): void {}
            // @filename: b.ts
            export interface ExportInfo { y: number; }
            export const dummy = 1;
            // @filename: main.ts
            import { ExportInfo, useInfo } from "./a";
            export function run(s: string): void {
                useInfo({ x: s });
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an object-literal arg satisfying NO declaring file still fires`() {
        diagnose(
            """
            // @filename: a.ts
            export interface ExportInfo { x: string; }
            export function useInfo(info: ExportInfo): void {}
            // @filename: b.ts
            export interface ExportInfo { y: number; }
            export const dummy = 1;
            // @filename: main.ts
            import { ExportInfo, useInfo } from "./a";
            export function run(s: string): void {
                useInfo({});
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `{}` misses `x` in a.ts's version AND `y` in b.ts's — genuinely wrong.
            have(any { it.code == 2345 })
        }
    }
}
