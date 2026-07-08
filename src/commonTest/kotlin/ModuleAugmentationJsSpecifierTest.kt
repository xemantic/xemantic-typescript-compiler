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
 * Round 443 (self-compile burn-down): a module augmentation whose target is a RELATIVE ESM `.js`
 * specifier (`declare module "../compiler/types.js"`) must resolve `.js` → the `.ts` sibling
 * (nodenext), so no TS2664 "Invalid module name in augmentation ... cannot be found." fires.
 * tsc's own services/types.ts augments `"../compiler/types.js"` 10 times; our TS2664 check went
 * through `resolveModuleSpecifierRelative`, which deliberately does NOT strip `.js` — now it uses
 * the `.js`-tolerant [resolveModuleSpecifierRelativeJsAware] (strictly FP-suppressing: it can only
 * make more specifiers resolve).
 */
class ModuleAugmentationJsSpecifierTest {

    private fun compile(source: String, primary: String = "services/types.ts") =
        TypeScriptCompiler().compile(source.trimIndent(), primary).diagnostics

    @Test
    fun `augmentation of a relative js specifier resolving to a ts sibling - no TS2664`() {
        compile(
            """
            // @strict: true
            // @module: nodenext
            // @moduleResolution: nodenext

            // @Filename: compiler/types.ts
            export interface Node { kind: number; }

            // @Filename: services/types.ts
            import { Node } from "../compiler/types.js";
            export const useNode = (n: Node): number => n.kind;
            declare module "../compiler/types.js" {
                export interface Node { getStart(): number; }
            }
            """
        ) should {
            have(none { it.code == 2664 })
        }
    }

    @Test
    fun `negative control - augmentation of a genuinely-missing relative js specifier still fires TS2664`() {
        compile(
            """
            // @strict: true
            // @module: nodenext
            // @moduleResolution: nodenext

            // @Filename: services/types.ts
            export const x = 1;
            declare module "../compiler/nonexistent.js" {
                export interface Node { getStart(): number; }
            }
            """
        ) should {
            have(any { it.code == 2664 })
        }
    }
}
