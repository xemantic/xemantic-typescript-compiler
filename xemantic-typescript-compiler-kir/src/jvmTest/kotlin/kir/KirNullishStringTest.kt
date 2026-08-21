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
 * `undefined` and `null` render as THEMSELVES in a string position.
 *
 * The backend collapses both onto the JVM's `null` in every erased slot -
 * `string | undefined` and `string | null` are both `String?` - so the runtime
 * alone cannot tell them apart and answered `"null"` for both. JavaScript
 * prints `undefined` for the first and `null` for the second, and a template or
 * a `+` is exactly where a program's output is MADE of that conversion.
 *
 * The decision therefore belongs to the LOWERING, which still has the
 * TypeScript type: it picks the rendering from the operand's own nullish
 * member. A type admitting BOTH, or `any`, keeps `"null"` - there is nothing
 * left to decide it by.
 */
class KirNullishStringTest {

    private fun compileAndRun(source: String): String {
        val output = Files.createTempDirectory("xtsc-kir-nullish")
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
    fun `an undefined operand of a concatenation renders as undefined`() {
        val stdout = compileAndRun(
            """
            function join(a: string, b?: string): string {
              return a + '|' + b
            }
            console.log(join('x'))
            console.log(join('x', 'y'))
            """.trimIndent()
        )
        assert(stdout == "x|undefined\nx|y\n")
    }

    @Test
    fun `an undefined substitution of a template renders as undefined`() {
        val stdout = compileAndRun(
            """
            function join(a: string, b?: string): string {
              return `${'$'}{a}|${'$'}{b}`
            }
            console.log(join('x'))
            """.trimIndent()
        )
        assert(stdout == "x|undefined\n")
    }

    @Test
    fun `a null operand still renders as null`() {
        val stdout = compileAndRun(
            """
            function join(a: string, b: string | null): string {
              return a + '|' + b
            }
            console.log(join('x', null))
            """.trimIndent()
        )
        assert(stdout == "x|null\n")
    }

    @Test
    fun `a type admitting both nullish members keeps the null rendering`() {
        // Nothing is left to decide it by, so this pins the fallback rather
        // than a JavaScript answer - the program below prints `x|null` here
        // and `x|undefined` on Node.
        val stdout = compileAndRun(
            """
            function join(a: string, b: string | null | undefined): string {
              return a + '|' + b
            }
            console.log(join('x', undefined))
            """.trimIndent()
        )
        assert(stdout == "x|null\n")
    }

    @Test
    fun `an explicitly undefined local renders as undefined`() {
        val stdout = compileAndRun(
            """
            function show(): string {
              const empty: string | undefined = undefined
              return 'v=' + empty
            }
            console.log(show())
            """.trimIndent()
        )
        assert(stdout == "v=undefined\n")
    }

}
