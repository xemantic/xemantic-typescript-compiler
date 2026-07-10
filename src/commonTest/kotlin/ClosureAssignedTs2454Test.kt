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
 * Round 469: a variable assigned inside a NESTED function-like can be assigned at
 * any time relative to an outer read — tsc's definite-assignment analysis never
 * fires TS2454 for it (tsc formatting.ts formatSpanWorker's `previousRange`,
 * assigned only inside nested helpers and read in the trailing-edit block).
 */
class ClosureAssignedTs2454Test {

    @Test
    fun `a variable assigned only inside a nested function does not fire TS2454`() {
        diagnose(
            """
            export function outer(): number {
                let count: number;
                function bump() { count = 1; }
                bump();
                return count;
            }
            """,
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a straight-line use-before-assign still fires`() {
        diagnose(
            """
            export function outer(): number {
                let count: number;
                const c = count;
                count = 1;
                return c;
            }
            """,
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a closure assigning a DIFFERENT variable does not suppress`() {
        diagnose(
            """
            export function outer(): number {
                let count: number;
                let other: number;
                function bump() { other = 1; }
                bump();
                return count;
            }
            """,
        ) should {
            have(any { it.code == 2454 })
        }
    }
}
