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
 * Round 468 (M1.12): a NUMERIC key on a union member that is an INTERSECTION with
 * an ARRAY-LIKE constituent resolves through the number index —
 * `Array.isArray(diag) ? diag[0] : …` intersects each member with
 * `readonly unknown[]` and the named-property fold missed '0' (tsc utilities.ts
 * diagnosticToString on `DiagnosticMessage | DiagnosticAndArguments`).
 */
class NumericKeyOnArrayIntersectionTest {

    @Test
    fun `a numeric key resolves through an array-narrowed union member`() {
        diagnose(
            """
            interface Msg { code: number; text: string; }
            type MsgAndArgs = [message: Msg, ...args: (string | number)[]];
            declare function fmt(m: Msg): string;
            export function toStr(diag: Msg | MsgAndArgs): string {
                return Array.isArray(diag) ? fmt(diag[0]) : fmt(diag);
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a numeric key on a plain non-array interface still fires`() {
        diagnose(
            """
            interface Msg { code: number; text: string; }
            export function bad(diag: Msg): unknown {
                return diag[0];
            }
            """,
        ) should {
            have(any { it.code == 2339 || it.code == 7053 })
        }
    }
}
