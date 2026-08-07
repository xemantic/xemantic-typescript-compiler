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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 436f (M3.4): a switch over a BARE `string`/`number` reference narrows
 * the subject to the matched clause range's literal(s) inside each case body
 * (tsc getAssignmentReducedType) — tsc semver.ts's
 * `switch (operator) { case "<": case ">=": createComparator(operator, v) }`
 * where createComparator's param is the literal union ×3. The call-arg path
 * accepts the narrowing for bare-string/number identifier args, relation-gated
 * (suppression-only).
 */
class SwitchCaseBareStringNarrowingTest {

    private val prelude = """
        interface Version { major: number }
        declare function createComparator(op: "<" | "<=" | ">" | ">=" | "=", operand: Version): number;
    """.trimIndent()

    @Test
    fun `case-narrowed bare string arg is legal`() {
        // The semver.ts shape: fall-through case pair narrows to a 2-literal union.
        diagnose(
            prelude + """

            function parseComparator(operator: string, v: Version) {
                switch (operator) {
                    case "<":
                    case ">=":
                        createComparator(operator, v);
                        break;
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a case literal outside the param union still fires`() {
        diagnose(
            prelude + """

            function parseComparator(operator: string, v: Version) {
                switch (operator) {
                    case "~":
                        createComparator(operator, v);
                        break;
                }
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an un-narrowed bare string still fires`() {
        // No switch at all: 'string' vs the comparator union must report.
        diagnose(
            prelude + """

            function f(operator: string, v: Version) {
                createComparator(operator, v);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
