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
 * A condition and a string conversion mean the same thing whether or not the
 * lowering could name the operand's type.
 *
 * The other half of the pin for the primitive-operand specializations — the
 * equality half is `KirEqualitySemanticsTest`. Every case here is one arm of
 * `jsTruthy` or of `jsToString` reached WITHOUT the box, and the arms that are
 * easy to get wrong are the ones JavaScript decides against intuition: `-0` and
 * `NaN` are falsy while the STRING `'0'` is truthy, and a whole number prints
 * without the `.0` that `Double.toString` would give it.
 *
 * The dynamic cases are here too, and they are not decoration: they are the
 * control that says the specialization changed which entry point is called and
 * not what the program means.
 */
class KirPrimitiveOperandTest {

    private fun compileAndRun(source: String): String {
        val output = Files.createTempDirectory("xtsc-kir-operand")
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
    fun `a numeric condition is false for zero, negative zero and NaN alone`() {
        val stdout = compileAndRun(
            """
            function truthy(value: number): string { return value ? 'y' : 'n' }
            console.log(truthy(0))
            console.log(truthy(-0))
            console.log(truthy(0 / 0))
            console.log(truthy(1))
            console.log(truthy(-1))
            console.log(truthy(0.5))
            """.trimIndent()
        )
        assert(stdout == "n\nn\nn\ny\ny\ny\n")
    }

    @Test
    fun `a string condition is false only for the empty string`() {
        val stdout = compileAndRun(
            """
            function truthy(value: string): string { return value ? 'y' : 'n' }
            console.log(truthy(''))
            console.log(truthy('0'))
            console.log(truthy('false'))
            console.log(truthy(' '))
            """.trimIndent()
        )
        assert(stdout == "n\ny\ny\ny\n")
    }

    @Test
    fun `a dynamic condition still answers through the runtime`() {
        val stdout = compileAndRun(
            """
            function truthy(value: any): string { return value ? 'y' : 'n' }
            console.log(truthy(null))
            console.log(truthy(undefined))
            console.log(truthy(0))
            console.log(truthy(''))
            console.log(truthy(false))
            console.log(truthy(true))
            console.log(truthy({}))
            console.log(truthy([]))
            """.trimIndent()
        )
        assert(stdout == "n\nn\nn\nn\nn\ny\ny\ny\n")
    }

    @Test
    fun `negating a typed condition is the same test`() {
        val stdout = compileAndRun(
            """
            const zero: number = 0
            const empty: string = ''
            console.log(!zero ? 'y' : 'n')
            console.log(!empty ? 'y' : 'n')
            console.log(!1 ? 'y' : 'n')
            """.trimIndent()
        )
        assert(stdout == "y\ny\nn\n")
    }

    @Test
    fun `a number in a concatenation prints as JavaScript prints it`() {
        val stdout = compileAndRun(
            """
            function show(value: number): string { return '[' + value + ']' }
            console.log(show(1))
            console.log(show(-0))
            console.log(show(1.5))
            console.log(show(0 / 0))
            console.log(show(1 / 0))
            console.log(show(-1 / 0))
            console.log(show(1000000))
            """.trimIndent()
        )
        assert(stdout == "[1]\n[0]\n[1.5]\n[NaN]\n[Infinity]\n[-Infinity]\n[1000000]\n")
    }

}
