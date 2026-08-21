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
 * One regular expression, used repeatedly and in every way at once.
 *
 * The pin for two cost changes that share a hazard: `JsRegExp` reuses a single
 * `Matcher` across `test` and `exec`, and every distinct `(source, flags)` pair
 * now compiles to a `Pattern` shared by every object built from it. Both are
 * invisible when a regular expression is used once, and both would fail the
 * same way — a second use reading state the first left behind.
 *
 * So the cases below use one expression MANY times, alternate the two methods
 * that share the matcher, and interleave them with `replace` and `split`, which
 * take their own matcher and keep it across iterations. A shared `Pattern` is
 * safe by construction (it holds no match state) and a shared `Matcher` is safe
 * only because nothing runs between starting a match and reading its groups;
 * that is exactly what these assert.
 */
class KirRegExpTest {

    private fun compileAndRun(source: String): String {
        val output = Files.createTempDirectory("xtsc-kir-regexp")
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
    fun `one expression answers independently on every input it is tested with`() {
        val stdout = compileAndRun(
            """
            const digits = /^[0-9]+${'$'}/
            const inputs: string[] = ['12', 'x', '345', '', '6']
            let out = ''
            for (const input of inputs) out += digits.test(input) ? 'y' : 'n'
            console.log(out)
            """.trimIndent()
        )
        assert(stdout == "ynyny\n")
    }

    @Test
    fun `test and exec alternate on one expression without disturbing each other`() {
        val stdout = compileAndRun(
            """
            const pair = /([a-z]+)=([0-9]+)/
            console.log(String(pair.test('a=1')))
            const first = pair.exec('bb=22')
            console.log(first ? first[1] + ':' + first[2] : 'none')
            console.log(String(pair.test('nope')))
            const second = pair.exec('ccc=333')
            console.log(second ? second[1] + ':' + second[2] : 'none')
            console.log(String(pair.test('d=4')))
            """.trimIndent()
        )
        assert(stdout == "true\nbb:22\nfalse\nccc:333\ntrue\n")
    }

    @Test
    fun `an expression built inside a function is a fresh object with the same meaning`() {
        // The shape the `Pattern` cache exists for: a literal in a function body
        // evaluates to a new object per call, and used to re-parse its source.
        val stdout = compileAndRun(
            """
            function strip(value: string): string {
              return value.replace(/_/g, '')
            }
            let out = ''
            for (const value of ['1_0', '2__0', 'no']) out += strip(value) + ' '
            console.log(out)
            """.trimIndent()
        )
        assert(stdout == "10 20 no \n")
    }

    @Test
    fun `replace and split keep their own matcher while test shares one`() {
        val stdout = compileAndRun(
            """
            const separator = /[,;]/g
            const word = /^[a-z]+${'$'}/
            const parts = 'a,b;c'.split(separator)
            let out = ''
            for (const part of parts) out += (word.test(part) ? part : '?')
            console.log(out)
            console.log('x1,y2'.replace(separator, '-'))
            console.log(String(word.test('zz')))
            """.trimIndent()
        )
        assert(stdout == "abc\nx1-y2\ntrue\n")
    }

    @Test
    fun `an unmatched optional group is undefined, and a repeat use says the same`() {
        val stdout = compileAndRun(
            """
            const optional = /(a)(b)?/
            const withBoth = optional.exec('ab')
            console.log(withBoth ? (withBoth[2] ? withBoth[2] : 'absent') : 'none')
            const withoutIt = optional.exec('ac')
            console.log(withoutIt ? (withoutIt[2] ? withoutIt[2] : 'absent') : 'none')
            const again = optional.exec('ab')
            console.log(again ? (again[2] ? again[2] : 'absent') : 'none')
            """.trimIndent()
        )
        assert(stdout == "b\nabsent\nb\n")
    }

}
