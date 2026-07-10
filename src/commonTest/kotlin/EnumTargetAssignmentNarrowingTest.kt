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
 * Round 468: the assignment-RHS flow-narrowing gate (round 410/438/456) extends to
 * an ENUM target — a Type.Object whose symbol carries the Enum flag. tsc
 * importFixes.ts writeFixes: `let quotePreference: QuotePreference; if (… &&
 * oldFileQuotePreference !== undefined) { quotePreference = oldFileQuotePreference; }`
 * — the RHS's declared `QuotePreference | undefined` is guard-narrowed. Same
 * monotone relation-passes rule.
 */
class EnumTargetAssignmentNarrowingTest {

    @Test
    fun `a guard-narrowed enum-or-undefined RHS assigns to the enum target`() {
        diagnose(
            """
            enum QuotePreference { Single, Double }
            export function run(old: QuotePreference | undefined, n: number): QuotePreference {
                let pref: QuotePreference;
                if (n === 0 && old !== undefined) {
                    pref = old;
                } else {
                    pref = QuotePreference.Double;
                }
                return pref;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an un-narrowed enum-or-undefined RHS still fires`() {
        diagnose(
            """
            enum QuotePreference { Single, Double }
            export function run(old: QuotePreference | undefined): QuotePreference {
                let pref: QuotePreference;
                pref = old;
                return pref;
            }
            """,
        ) should {
            // No guard — `QuotePreference | undefined` genuinely fails.
            have(any { it.code == 2322 })
        }
    }
}
