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
 * Round 470: a flow-narrowing-VERIFIED return source is a PRECISE verdict — the
 * engine block early-returns instead of falling to the string fallback, which
 * re-checks the un-narrowed varTypes entry (`unknown` vs `string | undefined`)
 * and FP'd. The tsc shape is stringCompletions.ts
 * getPatternFromFirstMatchingCondition: `if (typeof target === "string") return
 * target;` with `target: unknown`. The narrowedDeclaredTypes membership is the
 * gate — a blanket engine-confirmed early return stays forbidden (the round-436c
 * gotcha).
 */
class UnknownTypeofNarrowedReturnTest {

    @Test
    fun `an unknown param narrowed by typeof relates on return`() {
        diagnose(
            """
            function f(target: unknown): string | undefined {
                if (typeof target === "string") {
                    return target;
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed unknown return still fails`() {
        diagnose(
            """
            function bad(target: unknown): string | undefined {
                return target;
            }
            """
        ) should {
            have(any { it.code == 2322 && "unknown" in it.message })
        }
    }

    @Test
    fun `negative control - a wrong-primitive narrowing still fails with the precise type`() {
        diagnose(
            """
            function bad2(target: unknown): string | undefined {
                if (typeof target === "number") {
                    return target;
                }
                return undefined;
            }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message })
        }
    }
}
