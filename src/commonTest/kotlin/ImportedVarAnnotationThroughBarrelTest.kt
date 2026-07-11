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
 * Round 473 (Blocker #3, the tsc server-profile `emptyArray` conflation):
 * when ≥2 module files declare the SAME top-level var name with different
 * annotations (compiler/core.ts `emptyArray: never[]` vs server/
 * utilitiesPublic.ts `emptyArray: SortedReadonlyArray<never>`), an importing
 * file must resolve ITS OWN import's target through the barrel it names
 * ([importedTopLevelVarAnnotationType]) — the merged-globals fallback picks a
 * file-order-dependent winner. Pins both directions: a barrel exporting the
 * `never[]` decl gives the importing file `never[]` (assignable everywhere),
 * and a barrel exporting the branded decl gives THAT type (the same
 * assignment then genuinely errors).
 */
class ImportedVarAnnotationThroughBarrelTest {

    private val decls = """
        // @module: nodenext
        // @filename: core.ts
        export const emptyList: never[] = [] as never[];
        // @filename: sorted.ts
        export interface SortedList<T> {
            readonly brand: T[];
        }
        export const emptyList: SortedList<never> = { brand: [] };
    """.trimIndent()

    @Test
    fun `an import through a barrel resolves the barrel's own declaration`() {
        diagnose(
            decls + """

            // @filename: ns.ts
            export * from "./core.js";
            // @filename: use.ts
            import { emptyList } from "./ns.js";
            interface Sym { name: string; }
            export function f(ok: boolean): Sym[] {
                if (!ok) return emptyList;
                return [];
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 || it.code == 2740 || it.code == 2739 })
        }
    }

    @Test
    fun `negative control - a barrel exporting the other declaration keeps erroring`() {
        diagnose(
            decls + """

            // @filename: ns.ts
            export * from "./sorted.js";
            // @filename: use.ts
            import { emptyList } from "./ns.js";
            interface Sym { name: string; }
            export function f(ok: boolean): Sym[] {
                if (!ok) return emptyList;
                return [];
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 || it.code == 2740 || it.code == 2739 })
        }
    }
}
