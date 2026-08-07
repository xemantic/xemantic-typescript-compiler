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
 * INV.3(c)(iii) phase 3 (round 507b): getTypeOfIdentifier's merged-globals
 * fallback keys by the identifier node's owning file. A name with no per-file
 * meaning types as `any` instead of a foreign module file's leaked local
 * (real tsc sees TS2304 → any there). Unlike the round-442 by-NAME nulling
 * (measured dead-end — it broke import-driven typing), the visibility probe
 * keeps every imported/own/script/lib name resolving to the SAME merged
 * instance.
 */
class Inv3IdentifierTypingNodeKeyTest {

    @Test
    fun `an UNIMPORTED foreign const no longer types an identifier - the bogus TS2322 dies`() {
        diagnose(
            """
            // @filename: a.ts
            export const leakedNum = 42;

            // @filename: b.ts
            export function f(): void {
                const x: string = leakedNum;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an IMPORTED const keeps typing - the real TS2322 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export const num = 42;

            // @filename: b.ts
            import { num } from "./a";
            export function f(): void {
                const x: string = num;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a cross-file SCRIPT const keeps typing - TS2322 fires`() {
        diagnose(
            """
            // @filename: a.ts
            const scriptNum = 42;

            // @filename: b.ts
            const x: string = scriptNum;
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - import-driven initializer inference keeps typing - TS2322 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export const num = 42;

            // @filename: b.ts
            import { num } from "./a";
            export const y = num;
            const z: string = y;
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
