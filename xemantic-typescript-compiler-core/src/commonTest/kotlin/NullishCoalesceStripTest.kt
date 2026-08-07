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
 * Round 440 (self-compile burn-down): the `??` operator result is `NonNullable<left> | right`
 * — combineBinaryTypes previously unioned the RAW left type with the right, so
 * `verbosityLevel ?? -1` (`verbosityLevel: number | undefined`) kept `number | -1 | undefined`
 * and FP'd against a non-undefined `number` target (tsc's checker.ts nodeBuilder
 * `maxExpansionDepth`). The left operand's null/undefined/void members are now stripped.
 */
class NullishCoalesceStripTest {

    @Test
    fun `nullish-coalesce strips undefined from the left operand`() {
        diagnose(
            """
            export function f(verbosityLevel: number | undefined): number {
                const x: number = verbosityLevel ?? -1;
                return x;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `nullish-coalesce strips null from the left operand`() {
        diagnose(
            """
            export function f(v: string | null): string {
                const x: string = v ?? "default";
                return x;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `nullish-coalesce with a pure-undefined left yields the right operand`() {
        diagnose(
            """
            export function f(): number {
                const u: undefined = undefined;
                const x: number = u ?? 5;
                return x;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `nullish-coalesce keeps a genuinely-mismatched right operand - negative control`() {
        // The right operand's type is NOT stripped — a `string` right vs a `number` target
        // still fires TS2322.
        diagnose(
            """
            export function f(v: number | undefined): number {
                const x: number = v ?? "oops";
                return x;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
