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
 * Round 468: the per-property arg-check leaf widens an OPTIONAL target member to
 * `T | undefined` for a nullish-containing source (round 351's
 * widenOptionalTargetPropType at its SIXTH site) — `f({ suffix: cond ? ";" :
 * undefined })` vs `{ suffix?: string }` is legal without
 * exactOptionalPropertyTypes (tsc returnValueCorrect's textChanges options).
 */
class ArgPerPropOptionalTargetWidenTest {

    @Test
    fun `a possibly-undefined value for an OPTIONAL member is accepted`() {
        diagnose(
            """
            declare function f(opts: { name: string; suffix?: string }): void;
            export function run(cond: boolean): void {
                f({ name: "x", suffix: cond ? ";" : undefined });
            }
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a possibly-undefined value for a REQUIRED member still fires`() {
        diagnose(
            """
            declare function f(opts: { name: string; suffix: string }): void;
            export function run(cond: boolean): void {
                f({ name: "x", suffix: cond ? ";" : undefined });
            }
            """,
        ) should {
            // `suffix` is required — `string | undefined` genuinely fails.
            have(any { it.code == 2322 || it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a wrong-typed value for an OPTIONAL member still fires`() {
        diagnose(
            """
            declare function f(opts: { name: string; suffix?: string }): void;
            export function run(cond: boolean): void {
                f({ name: "x", suffix: cond ? 1 : undefined });
            }
            """,
        ) should {
            // `number | undefined` fails even the widened `string | undefined`.
            have(any { it.code == 2322 || it.code == 2345 })
        }
    }
}
