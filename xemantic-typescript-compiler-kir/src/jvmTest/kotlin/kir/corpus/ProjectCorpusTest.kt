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
import com.xemantic.typescript.compiler.kir.compileTypeScriptProjectToJvm
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.runGeneratedProgram
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * The MULTI-FILE corpus: a project directory, compiled as one program and run.
 *
 * Separate from `CorpusTest` because the unit is different — a `tsconfig.json`
 * and a module graph rather than one file — and because what it proves is
 * different: that an `import` costs the backend nothing. The checker turns each
 * imported name into the declaration it names, and the declare pass records an
 * IR symbol per declaration for the whole program, so a call across a module
 * boundary is an ordinary direct call and the import statement is erased.
 *
 * The project is copied out of the test resources into a temporary directory,
 * because the compiler crawls a real filesystem and a resource may live in a
 * jar.
 */
class ProjectCorpusTest {

    /**
     * mitt 3.0.1 as a real MODULE: the library in one file, a consumer that
     * imports it in another, and no concatenation anywhere.
     */
    @Test
    fun `mitt imported as a module`() {
        assertProject(
            name = "mitt-consumer",
            files = listOf("tsconfig.json", "src/mitt.ts", "src/main.ts"),
            entry = "main.ts",
        )
    }

    /**
     * MODULE STATE: three files whose bodies RUN, in dependency order.
     *
     * `config.ts` computes a constant by calling a function declared BELOW it,
     * `report.ts` builds one from `config`'s, and `main.ts` reads both and
     * mutates a `let` across the module boundary. Every one of those is a fact
     * about WHEN a module body runs, which is the whole of what this pins.
     */
    @Test
    fun `module state initializes in dependency order`() {
        assertProject(
            name = "module-state",
            files = listOf("tsconfig.json", "src/config.ts", "src/report.ts", "src/main.ts"),
            entry = "main.ts",
        )
    }

    /**
     * A REAL PARSER: `smol-toml`'s own source, unmodified, compiled and RUN.
     *
     * 1,082 lines across seven files — a class extending `Date`, another
     * extending `Error`, a `const enum`, regular expressions, destructuring
     * with defaults, optional chaining, `bigint` literals, `void 0` — parsing a
     * TOML document and printing it as JSON. Provenance and licence are in the
     * project's own README; the only files that are ours are the driver and an
     * ambient shim for the `Temporal` types the library mentions.
     *
     * The checker reports ZERO errors on it, which tsgo 7.0.2 also does.
     */
    @Test
    fun `smol-toml - a real parser, compiled and run`() {
        assertProject(
            name = "toml",
            files = listOf(
                "tsconfig.json",
                "src/util.ts",
                "src/error.ts",
                "src/date.ts",
                "src/primitive.ts",
                "src/struct.ts",
                "src/extract.ts",
                "src/parse.ts",
                "src/temporal-shim.d.ts",
                "src/main.ts",
            ),
            entry = "main.ts",
        )
    }

    private fun assertProject(name: String, files: List<String>, entry: String) {
        val project = Files.createTempDirectory("xtsc-kir-project-$name")
        files.forEach { relative ->
            val target = project.resolve(relative)
            target.parent.createDirectories()
            target.writeText(resource("$name/$relative"))
        }
        val expected = resource("$name/expected.txt")
        val output = Files.createTempDirectory("xtsc-kir-project-out-$name")
        val compilation = compileTypeScriptProjectToJvm(
            projectPath = project.toString(),
            entryFileName = entry,
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
        if (!matches) fail("$name printed\n${run.stdout}\nbut expected\n$expected")
        assert(matches)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun resource(name: String): String {
        val url = javaClass.getResource("/projects/$name") ?: error("no resource '$name'")
        return url.openStream().use { it.readBytes().decodeToString() }
    }

}
