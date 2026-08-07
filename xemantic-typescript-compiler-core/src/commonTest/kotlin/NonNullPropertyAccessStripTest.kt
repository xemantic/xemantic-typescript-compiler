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
 * Round 479: a PROPERTY-ACCESS `.x!` operand strips nullish from an
 * all-concrete union like the round-456 bare-Identifier form —
 * `start: diagnostic.start!` vs `start: number` (fourslashImpl's
 * realizeDiagnostic). An operand carrying an un-inferred type param keeps
 * the deferred union behavior.
 */
class NonNullPropertyAccessStripTest {

    @Test
    fun `property-access non-null assertion strips undefined`() {
        diagnose(
            """
            interface Diag { start: number | undefined; length: number | undefined; }
            interface Realized { start: number; length: number; }
            export function realize(diagnostic: Diag): Realized {
                return {
                    start: diagnostic.start!,
                    length: diagnostic.length!,
                };
            }
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2739 })
        }
    }

    @Test
    fun `negative control - without the assertion the mismatch still fires`() {
        diagnose(
            """
            interface Diag { start: number | undefined; }
            interface Realized { start: number; }
            export function f(diagnostic: Diag): Realized {
                return { start: diagnostic.start };
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
