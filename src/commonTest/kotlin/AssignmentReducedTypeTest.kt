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
 * Round 477 (tsc getAssignmentReducedType — the harness fourslash/evaluator
 * reassignment idioms): an assignment OVERWRITES the reference with the RHS's
 * type reduced against the declared union, so
 *
 * - `x = typeof x === "string" ? { name: x } : x;` drops the `string` member
 *   (the replacement arm is an object literal, the pass-through arm keeps only
 *   the surviving members) — [narrowByAssignmentRhs]'s ternary arm;
 * - `if (typeof x === "string") x = { … };` — the plain objlit-RHS arm drops
 *   the declared union's primitive/nullish members, so the post-if JOIN is the
 *   object member only.
 */
class AssignmentReducedTypeTest {

    private val prelude = """
        interface EntryObject {
            name: string;
            insertText?: string;
            kind?: string;
        }
        type Entry = string | EntryObject;
    """.trimIndent()

    @Test
    fun `ternary typeof reassignment drops the string member`() {
        diagnose(
            prelude + """

            function verify(expected: Entry): void {
                expected = typeof expected === "string" ? { name: expected } : expected;
                if (expected.insertText !== undefined) {
                    expected.kind;
                }
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `reversed ternary condition drops the string member too`() {
        diagnose(
            prelude + """

            function verify(expected: Entry): void {
                expected = typeof expected !== "string" ? expected : { name: expected };
                expected.insertText;
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `guarded plain objlit reassignment drops the string member after the join`() {
        diagnose(
            prelude + """

            function evaluate(source: string | { files: string[]; main: string; }): void {
                if (typeof source === "string") source = { files: [source], main: source };
                source.files;
                source.main;
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `guarded array-literal reassignment keeps only array-like members`() {
        diagnose(
            """
            interface Entry { name: string; }
            declare function isArr(x: unknown): x is readonly unknown[];
            function verify(expected: Entry | readonly Entry[]): void {
                if (!isArr(expected)) {
                    expected = [expected];
                }
                expected.map(e => e.name);
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - without the reassignment the string member still fails`() {
        diagnose(
            prelude + """

            function verify(expected: Entry): void {
                expected.insertText;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a non-objlit replacement arm keeps the union`() {
        diagnose(
            prelude + """

            declare function pick(): Entry;
            function verify(expected: Entry): void {
                expected = typeof expected === "string" ? pick() : expected;
                expected.insertText;
            }
            """.trimIndent(),
        ) should {
            // pick() can return a string again — the reads must keep failing.
            have(any { it.code == 2339 })
        }
    }
}
