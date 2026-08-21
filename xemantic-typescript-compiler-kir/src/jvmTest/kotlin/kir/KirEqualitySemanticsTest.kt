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

package com.xemantic.typescript.compiler.kir

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.runGeneratedProgram
import java.nio.file.Files
import kotlin.test.Test

/**
 * `===`, `!==`, `==` and a `switch` mean the same thing whichever entry point
 * the lowering picked.
 *
 * The pin for the equality specializations, which exist for cost — the general
 * `jsStrictEquals` takes `Any?`, so `s.charCodeAt(p) === 0x20` boxed both
 * operands and then rediscovered their types — and must therefore be
 * indistinguishable from it in behaviour. Three things make that non-obvious,
 * and each has a case below.
 *
 * **`NaN`.** JavaScript's `===` is IEEE-754, so `NaN !== NaN` and `0 === -0`.
 * A specialization that compared boxed `Double`s with `equals` would invert
 * BOTH, silently and only for programs that produce a `NaN` — which a parser
 * that reads numbers out of text certainly does.
 *
 * **Evaluation order.** Reaching one half-specialized entry point by swapping
 * the operands would reorder two expressions that may both have effects, so
 * both directions exist and both are pinned.
 *
 * **`==` is not `===`.** Abstract equality coincides with strict equality only
 * when both operands are the same primitive; `1 == true` and `null == undefined`
 * are true, and those cases must stay on the runtime.
 */
class KirEqualitySemanticsTest {

    private fun compileAndRun(source: String): String {
        val output = Files.createTempDirectory("xtsc-kir-equality")
        val compilation = compileTypeScriptToJvm("t.ts", source, output)
        assert(compilation.successful)
        val run = runGeneratedProgram(
            output,
            compilation.mainClass,
            GeneratedProgramClasspath.minimal()
        )
        assert(run.exitCode == 0)
        return run.stdout
    }

    @Test
    fun `two statically numeric operands compare by IEEE-754, so NaN is unequal to itself`() {
        val stdout = compileAndRun(
            """
            const nan: number = 0 / 0
            const zero: number = 0
            const negativeZero: number = -0
            console.log(String(nan === nan))
            console.log(String(nan !== nan))
            console.log(String(zero === negativeZero))
            console.log(String(zero === 1))
            """.trimIndent()
        )
        assert(stdout == "false\ntrue\ntrue\nfalse\n")
    }

    @Test
    fun `a NaN reached through 'any' compares the same way as a statically numeric one`() {
        val stdout = compileAndRun(
            """
            const nan: any = 0 / 0
            const one: number = 1
            console.log(String(nan === 0 / 0))
            console.log(String(0 / 0 === nan))
            console.log(String(nan === one))
            console.log(String(one === nan))
            """.trimIndent()
        )
        assert(stdout == "false\nfalse\nfalse\nfalse\n")
    }

    @Test
    fun `strings compare by VALUE, including one the program built at run time`() {
        val stdout = compileAndRun(
            """
            const built: string = 'a' + 'b'
            const literal: string = 'ab'
            const dynamic: any = 'ab'
            console.log(String(built === literal))
            console.log(String(built === 'ba'))
            console.log(String(dynamic === literal))
            console.log(String(literal === dynamic))
            console.log(String(dynamic === 'other'))
            """.trimIndent()
        )
        assert(stdout == "true\nfalse\ntrue\ntrue\nfalse\n")
    }

    @Test
    fun `a number and a string are never strictly equal, whichever side is dynamic`() {
        val stdout = compileAndRun(
            """
            const text: any = '1'
            const number: any = 1
            console.log(String(text === 1))
            console.log(String(1 === text))
            console.log(String(number === '1'))
            console.log(String('1' === number))
            """.trimIndent()
        )
        assert(stdout == "false\nfalse\nfalse\nfalse\n")
    }

    @Test
    fun `booleans compare as themselves and never as numbers under strict equality`() {
        val stdout = compileAndRun(
            """
            const yes: boolean = true
            const no: boolean = false
            const dynamic: any = true
            console.log(String(yes === yes))
            console.log(String(yes === no))
            console.log(String(dynamic === 1))
            """.trimIndent()
        )
        assert(stdout == "true\nfalse\nfalse\n")
    }

    @Test
    fun `the left operand is evaluated first in BOTH half-specialized directions`() {
        val stdout = compileAndRun(
            """
            let log: string = ''
            function typed(): number { log += 'typed'; return 1 }
            function dynamic(): any { log += 'dynamic'; return 1 }
            const numberFirst: boolean = typed() === dynamic()
            console.log(log + ' ' + String(numberFirst))
            log = ''
            const dynamicFirst: boolean = dynamic() === typed()
            console.log(log + ' ' + String(dynamicFirst))
            """.trimIndent()
        )
        assert(stdout == "typeddynamic true\ndynamictyped true\n")
    }

    @Test
    fun `abstract equality keeps its own rules for mixed operands`() {
        val stdout = compileAndRun(
            """
            const text: any = '1'
            const nothing: any = null
            const missing: any = undefined
            const truth: any = true
            console.log(String(text == 1))
            console.log(String(nothing == missing))
            console.log(String(0 == truth))
            console.log(String(1 == truth))
            """.trimIndent()
        )
        assert(stdout == "true\ntrue\nfalse\ntrue\n")
    }

    @Test
    fun `abstract equality between two operands of one primitive is strict equality`() {
        val stdout = compileAndRun(
            """
            const nan: number = 0 / 0
            const a: string = 'a' + ''
            console.log(String(nan == nan))
            console.log(String(nan != nan))
            console.log(String(a == 'a'))
            console.log(String(a != 'a'))
            """.trimIndent()
        )
        assert(stdout == "false\ntrue\ntrue\nfalse\n")
    }

    @Test
    fun `a switch matches its clauses by strict equality, numbers and strings alike`() {
        val stdout = compileAndRun(
            """
            function pick(value: number): string {
              switch (value) {
                case 1: return 'one'
                case 2: return 'two'
                default: return 'other'
              }
            }
            function name(value: string): string {
              switch (value) {
                case 'a': return 'A'
                default: return 'other'
              }
            }
            console.log(pick(1))
            console.log(pick(2))
            console.log(pick(0 / 0))
            console.log(name('a'))
            console.log(name('b'))
            """.trimIndent()
        )
        assert(stdout == "one\ntwo\nother\nA\nother\n")
    }

}
