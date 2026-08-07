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
 * M1.12 (self-compile burn-down): indexing a NUMERIC ENUM object (`NumericEnum[key]`) is a valid
 * reverse mapping (number → member name), so tsc emits no TS7053. A numeric enum in value
 * position resolves to an empty `Type.Object` in our checker, which matched the empty-object
 * TS7053 branch (`any`/`string` key on a no-members/no-index object). tsc's own
 * `moduleNameResolver.ts` does `ModuleResolutionKind[moduleResolution]` (twice) and FP'd.
 *
 * Fix: exclude an enum-object receiver from the empty-object TS7053 branch (mirrors the enum
 * exclusion the sibling `tryEmitNoImplicitAnyIndexAccess` already has). Negative control: an
 * actually-empty `{}` object indexed by an `any` key must still fire TS7053.
 */
class EnumReverseMappingIndexTest {

    @Test
    fun `numeric enum reverse mapping with any key - no TS7053`() {
        diagnose(
            """
            enum Kind { A, B, C }
            export function nameOf(k: any): string {
                return Kind[k];
            }
            """,
        ) should {
            have(none { it.code == 7053 })
        }
    }

    @Test
    fun `const enum reverse mapping with any key - no TS7053`() {
        diagnose(
            """
            enum ModuleResolutionKind { Classic = 1, NodeJs = 2 }
            export function trace(k: any): string {
                return ModuleResolutionKind[k];
            }
            """,
        ) should {
            have(none { it.code == 7053 })
        }
    }

    @Test
    fun `empty object indexed by any key STILL fires TS7053 - negative control`() {
        diagnose(
            """
            export function bad(obj: {}, k: any): any {
                return obj[k];
            }
            """,
        ) should {
            have(any { it.code == 7053 })
        }
    }
}
