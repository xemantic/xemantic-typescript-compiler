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
 * (TSGO.7-e) A template literal's escapes are COOKED, as JavaScript cooks them. The lowering reads a template's
 * text as the runtime string; `-core`'s parser handed a template's middle, tail and substitution-free text over
 * RAW (and a head in some forms), so `` `a\nb` `` compiled to a backslash and an `n`. The syntax is tsgo's tree
 * now, whose template text is the cooked string (`TsgoSyntaxParityTest` names the difference).
 */
class KirTemplateEscapeTest {

    private fun compileAndRun(source: String): String {
        val output = Files.createTempDirectory("xtsc-kir-template")
        val compilation = compileTypeScriptToJvm("t.ts", source, output)
        assert(compilation.successful)
        val run = runGeneratedProgram(output, compilation.mainClass, GeneratedProgramClasspath.minimal())
        assert(run.exitCode == 0)
        return run.stdout
    }

    @Test
    fun `escapes in a template's head, middle and tail are cooked`() {
        val stdout = compileAndRun(
            """
            const who: string = 'x'
            console.log(`a\tb${'$'}{who}c\nd${'$'}{who}e\\f`)
            """.trimIndent()
        )
        assert(stdout == "a\tbxc\ndxe\\f\n")
    }

    @Test
    fun `escapes in a template without substitutions are cooked`() {
        val stdout = compileAndRun(
            """
            console.log(`no\nsub \${'$'}{literal} \u0041`)
            """.trimIndent()
        )
        assert(stdout == "no\nsub \${literal} A\n")
    }
}
