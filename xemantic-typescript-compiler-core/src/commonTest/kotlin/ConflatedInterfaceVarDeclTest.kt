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
 * Round 468 (Blocker #3): the VAR-DECL variant of the round-445 conflated
 * file-local interface rule — `const identifiers: Identifiers = { original, additional }`
 * where two module files each declare their own `interface Identifiers` (tsc's
 * convertToEsModule.ts vs addMissingAwait.ts). The merged global symbol carries the
 * union of both files' members, so the annotation resolved through it FP'd
 * "missing identifiers, isCompleteFix". tsc checks against the FILE-LOCAL interface.
 */
class ConflatedInterfaceVarDeclTest {

    @Test
    fun `a var-decl object literal checks against the conflated file-local interface`() {
        diagnose(
            """
            // @filename: other.ts
            export interface Identifiers { identifiers: string[]; isCompleteFix: boolean; }
            export const dummy = 1;
            // @filename: main.ts
            interface Identifiers { original: string; additional: number; }
            export function convert(name: string): number {
                const identifiers: Identifiers = { original: name, additional: 0 };
                return identifiers.additional;
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2322 || it.code == 2739 || it.code == 2740 || it.code == 2741 })
        }
    }

    @Test
    fun `negative control - a var-decl object literal missing the FILE-LOCAL member still fires`() {
        diagnose(
            """
            // @filename: other.ts
            export interface Identifiers { identifiers: string[]; isCompleteFix: boolean; }
            export const dummy = 1;
            // @filename: main.ts
            interface Identifiers { original: string; additional: number; }
            export function convert(name: string): number {
                const identifiers: Identifiers = { original: name };
                return identifiers.additional;
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `additional` is required by THIS file's own Identifiers — genuinely missing.
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 })
        }
    }
}
