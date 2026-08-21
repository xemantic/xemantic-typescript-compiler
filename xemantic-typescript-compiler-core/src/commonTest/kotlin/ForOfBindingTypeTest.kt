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
 * (CHK.29) A `for…of` binding has its ELEMENT type, everywhere.
 *
 * It always had one inside `checkPropertyAccessInStatement`, which carries its
 * own B70.4 copy of the rule — and nowhere else, so the binding read `any` at
 * every other consumer and the misuses below were SILENT. tsgo 7.0.2 reports
 * each of them.
 *
 * The last two are the boundary: an iterable whose element type is only
 * reachable through `[Symbol.iterator]` keeps `any`, which is a false NEGATIVE
 * and deliberately not a guess — (CHK.23) is the question that answers it.
 */
class ForOfBindingTypeTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `an array element binding is the element type`() {
        compile(
            """
            // @strict: true
            const words: string[] = ["a"]
            for (const w of words) {
                const probe: number = w
                console.log(probe)
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `the element type is usable - the control`() {
        compile(
            """
            // @strict: true
            const words: string[] = ["a"]
            for (const w of words) {
                const probe: string = w
                console.log(probe)
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a string iterates as strings`() {
        compile(
            """
            // @strict: true
            for (const c of "abc") {
                const probe: number = c
                console.log(probe)
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `a tuple iterates as the union of its elements`() {
        compile(
            """
            // @strict: true
            const pair: [string, number] = ["a", 1]
            for (const item of pair) {
                const probe: boolean = item
                console.log(probe)
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `an unmodelled iterable keeps any rather than guessing`() {
        compile(
            """
            // @strict: true
            declare const numbers: Set<number>
            for (const n of numbers) {
                const probe: string = n
                console.log(probe)
            }
            """
        ) should {
            // A false NEGATIVE, recorded as such: reaching this element type
            // means resolving `[Symbol.iterator]`'s return, which is (CHK.23).
            have(none { it.code == 2322 })
        }
    }

}
