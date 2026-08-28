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

package com.xemantic.typescript.compiler.kir.corpus

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.compileTypeScriptToJvm
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.runGeneratedProgram
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The acceptance corpus: each program is COMPILED TO BYTECODE, RUN in a fresh
 * JVM, and its stdout compared to the `.expected` file byte for byte.
 *
 * Nothing here inspects an IR tree, and that is the point — the distance
 * between "a well-typed IR tree" and "a class file that runs" is where every
 * mechanical constraint in `docs/kir-design.md` was found. A subprocess rather
 * than a class loader for the same reason: loading the classes into THIS JVM
 * would prove they verify, not that they are a program.
 *
 * One test function per program rather than one loop over all of them, so a
 * failure names the program that failed and the ordering — each first forces
 * exactly one new capability, see `resources/corpus/README.md` — stays legible.
 */
class CorpusTest {

    @Test
    fun `01 literals`() {
        assertCorpusProgram("01-literals")
    }

    @Test
    fun `02 locals`() {
        assertCorpusProgram("02-locals")
    }

    @Test
    fun `03 concat`() {
        assertCorpusProgram("03-concat")
    }

    @Test
    fun `04 functions`() {
        assertCorpusProgram("04-functions")
    }

    @Test
    fun `05 control flow`() {
        assertCorpusProgram("05-control-flow")
    }

    @Test
    fun `06 union narrowing`() {
        assertCorpusProgram("06-union-narrowing")
    }

    @Test
    fun `07 classes`() {
        assertCorpusProgram("07-classes")
    }

    @Test
    fun `08 optional union`() {
        assertCorpusProgram("08-optional-union")
    }

    @Test
    fun `09 arrays`() {
        assertCorpusProgram("09-arrays")
    }

    @Test
    fun `10 closures`() {
        assertCorpusProgram("10-closures")
    }

    @Test
    fun `11 objects`() {
        assertCorpusProgram("11-objects")
    }

    /**
     * A REAL LIBRARY: mitt 3.0.1, its published source unmodified, plus a
     * driver appended below a comment banner.
     *
     * Everything above the banner is what `npm i mitt` ships as `src/index.ts`
     * — generics with a `Record` constraint, `keyof` indexed access, an
     * interface with overloads, a `Map`, an object literal with methods that
     * close over the factory's parameter, an `as` cast, `!`, `>>>`, an optional
     * parameter, `Array.slice/map/push/splice/indexOf`. It compiles to bytecode
     * and its event emitter works.
     */
    @Test
    fun `12 mitt - a real published library`() {
        assertCorpusProgram("12-mitt")
    }

    /**
     * Compiles, runs and compares — failing with the whole story rather than
     * with a boolean.
     *
     * The compilation report is put into the failure message deliberately: a
     * refusal names the file, the position and the construct, and that IS the
     * diagnosis. An assertion that only said `false` would send the reader back
     * to a debugger for something the backend already knows.
     */
    @Test
    fun `13 operators`() {
        assertCorpusProgram("13-operators")
    }

    @Test
    fun `14 strings`() {
        assertCorpusProgram("14-strings")
    }

    @Test
    fun `15 control flow`() {
        assertCorpusProgram("15-control")
    }

    @Test
    fun `16 inheritance statics and accessors`() {
        assertCorpusProgram("16-inheritance")
    }

    @Test
    fun `17 rest parameters`() {
        assertCorpusProgram("17-rest-params")
    }

    @Test
    fun `18 var scoping`() {
        assertCorpusProgram("18-var-scoping")
    }

    @Test
    fun `19 nullish coalescing`() {
        assertCorpusProgram("19-nullish")
    }

    private fun assertCorpusProgram(name: String) {
        val source = resource("$name.ts")
        val expected = resource("$name.expected")
        val output = Files.createTempDirectory("xtsc-kir-corpus-$name")
        val compilation = compileTypeScriptToJvm(
            fileName = "$name.ts",
            source = source,
            outputDirectory = output,
        )
        if (!compilation.successful) fail("$name did not compile\n$compilation")
        val run = runGeneratedProgram(
            output,
            compilation.mainClass,
            GeneratedProgramClasspath.minimal()
        )
        if (run.exitCode != 0) fail("$name did not run\n$run")
        val matches = run.stdout == expected
        if (!matches) {
            fail("$name printed\n${render(run.stdout)}\nbut expected\n${render(expected)}")
        }
        assert(matches)
    }

    private fun render(text: String): String =
        text.lines().joinToString("\n") { "    |$it|" }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun resource(name: String): String {
        val url = javaClass.getResource("/corpus/$name")
            ?: error("no corpus resource '$name'")
        return url.openStream().use { it.readBytes().decodeToString() }
    }

}
