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

import kotlin.test.Test
import kotlin.test.assertTrue

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

    private fun ts2345s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2345 }

    private val prelude = """
        interface Version { major: number }
        declare function createComparator(op: "<" | "<=" | ">" | ">=" | "=", operand: Version): number;
    """.trimIndent()

    /** The semver.ts shape: fall-through case pair narrows to a 2-literal union. */
    @Test fun caseNarrowedBareStringArgIsLegal() {
        val d = ts2345s(
            prelude + "\n" +
                """
                function parseComparator(operator: string, v: Version) {
                    switch (operator) {
                        case "<":
                        case ">=":
                            createComparator(operator, v);
                            break;
                    }
                }
                """.trimIndent()
        )
        assertTrue(d.isEmpty(), "expected no TS2345, got: $d")
    }

    /** NEGATIVE control: a case literal OUTSIDE the param union still fires. */
    @Test fun caseNarrowedToNonMemberLiteralStillFires() {
        val d = ts2345s(
            prelude + "\n" +
                """
                function parseComparator(operator: string, v: Version) {
                    switch (operator) {
                        case "~":
                            createComparator(operator, v);
                            break;
                    }
                }
                """.trimIndent()
        )
        assertTrue(d.isNotEmpty(), "expected TS2345 for '\"~\"' vs the comparator union")
    }

    /** NEGATIVE control: an UN-narrowed bare string still fires (no switch). */
    @Test fun unNarrowedBareStringStillFires() {
        val d = ts2345s(
            prelude + "\n" +
                """
                function f(operator: string, v: Version) {
                    createComparator(operator, v);
                }
                """.trimIndent()
        )
        assertTrue(d.isNotEmpty(), "expected TS2345 for 'string' vs the comparator union")
    }
}
