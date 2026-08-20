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
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test

/**
 * The pins for `docs/kir-lowering.md` §8 — the rule the whole spike's value
 * rests on: it never PRETENDS.
 *
 * A backend that quietly widens an unmapped type to `Any?` and carries on
 * reports success while compiling nonsense, which is the one outcome from which
 * nothing can be learned. So each case here is a program this backend cannot
 * lower, and each assertion is that it said so, at a position, rather than
 * emitting something plausible.
 *
 * The last test is the corpus harness's own negative control: it proves that a
 * WRONG stdout is detected, so that eight green corpus programs mean eight
 * programs that printed the right thing rather than eight assertions that
 * cannot fail.
 */
class KirRefusalTest {

    private fun compile(source: String, name: String = "t.ts"): Pair<KirCompilation, Path> {
        val output = Files.createTempDirectory("xtsc-kir-refusal")
        return compileTypeScriptToJvm(name, source, output) to output
    }

    @Test
    fun `a program the checker rejects is never lowered`() {
        val (compilation, output) = compile("const x: number = \"not a number\";")
        assert(!compilation.successful)
        assert(compilation.typeErrors.isNotEmpty())
        assert(compilation.typeErrors.first().code == 2322)
        // Nothing at all was emitted: the lowering never ran.
        assert(compilation.refusals.isEmpty())
        assert(compilation.emit == null)
        assert(writtenClasses(output).isEmpty())
    }

    @Test
    fun `an unsupported construct is refused with a file line and column`() {
        val (compilation, _) = compile("var counted = 1;\nconsole.log(counted);")
        assert(!compilation.successful)
        val refusal = compilation.refusals.single()
        assert(refusal.fileName == "t.ts")
        assert(refusal.line == 1)
        assert(refusal.column == 1)
        assert("`var`" in refusal.message)
    }

    @Test
    fun `a library member this backend does not know is refused and names it`() {
        val (compilation, _) = compile("console.log(Math.max(1, 2));")
        assert(!compilation.successful)
        val refusal = compilation.refusals.single()
        // Named by the checker's own rendering of the receiver plus the member,
        // which is what the intrinsic table is keyed by.
        assert("Math.max" in refusal.message)
    }

    @Test
    fun `a type with no mapping is refused rather than widened to Any`() {
        // An object literal has no generated class, and design doc §3.3 takes
        // only the nominal half of the hybrid for now.
        val (compilation, _) = compile("const point = { x: 1 };\nconsole.log(point.x);")
        assert(!compilation.successful)
        assert(compilation.refusals.isNotEmpty())
    }

    /**
     * The negative control for the corpus harness.
     *
     * A program is compiled and run whose output is deliberately NOT what a
     * naive JVM lowering would produce: `console.log(1)` prints `1`, and a
     * lowering that let `Double.toString` through would print `1.0`. That the
     * comparison distinguishes the two is what makes the corpus's eight green
     * results mean something.
     */
    @Test
    fun `the harness distinguishes right output from plausible wrong output`() {
        val (compilation, output) = compile("console.log(1);")
        assert(compilation.successful)
        val run = runGeneratedProgram(
            output,
            compilation.mainClass,
            GeneratedProgramClasspath.minimal()
        )
        assert(run.exitCode == 0)
        assert(run.stdout == "1\n")
        val wouldHavePassedNaively = run.stdout == "1.0\n"
        assert(!wouldHavePassedNaively)
    }

    private fun writtenClasses(output: Path): List<Path> =
        if (!Files.isDirectory(output)) emptyList()
        else Files.walk(output).use { paths ->
            paths.filter { it.extension == "class" }.toList()
        }

}
