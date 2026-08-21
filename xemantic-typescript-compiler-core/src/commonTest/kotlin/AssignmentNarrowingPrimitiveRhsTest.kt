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
 * (CHK.33) Assigning a computed PRIMITIVE keeps the narrow it satisfies.
 *
 * tsc's `getAssignmentReducedType`: after `x = <expr>` the declared union is
 * reduced to the members the assigned type relates to. This checker had that
 * rule for a `new`, an object literal, an array literal and a CALL right-hand
 * side — but not for a computed primitive, so `x = 'a' + x` and `` x = `t${x}` ``
 * dropped a narrow that `x = x.trim()` kept, and the two spellings of one idiom
 * disagreed.
 *
 * Measured on the `smol-toml` parser, whose `TomlDate` constructor rebuilds its
 * narrowed `string | Date` parameter from itself twice in one block — three
 * false TS2339 in a 1,082-line library that tsgo 7.0.2 checks clean.
 */
class AssignmentNarrowingPrimitiveRhsTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `a concatenation keeps the string narrow`() {
        compile(
            """
            // @strict: true
            declare const d: Date
            function f(x: string | Date): string {
                if (typeof x === 'string') {
                    x = 'a' + x
                    return x.toUpperCase()
                }
                return 'other'
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a template literal keeps the string narrow`() {
        compile(
            """
            // @strict: true
            function f(x: string | number): string {
                if (typeof x === 'string') {
                    x = `t${'$'}{x}`
                    return x.toUpperCase()
                }
                return 'other'
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a call right-hand side still keeps it - the control`() {
        compile(
            """
            // @strict: true
            function f(x: string | number): string {
                if (typeof x === 'string') {
                    x = x.trim()
                    return x.toUpperCase()
                }
                return 'other'
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    /**
     * The KNOWN FALSE NEGATIVE this rule leaves, recorded rather than asserted
     * away.
     *
     * Assigning a value of the OTHER member (`x = 1 + 1` inside a `typeof x ===
     * 'string'` branch) should make `x` a `number`, and tsgo 7.0.2 reports
     * `Property 'toUpperCase' does not exist on type 'number'`. We report
     * nothing, because the member-access check reads `currentLocalTypes` — which
     * the `if` narrowed and the assignment does not update — rather than the
     * flow type this rule computes. Before this rule the same program errored
     * for the WRONG reason (the narrow was simply lost, so the read was against
     * `string | number`), so the change trades an accidental true positive for a
     * silence, and closes a false-positive family in exchange.
     *
     * Closing it means making an assignment update `currentLocalTypes` too,
     * which is a second type source and a separate piece of work.
     */
    @Test
    fun `assigning the other member is a known false negative`() {
        compile(
            """
            // @strict: true
            function f(x: string | number): string {
                if (typeof x === 'string') {
                    x = 1 + 1
                    return x.toUpperCase()
                }
                return 'other'
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

}
