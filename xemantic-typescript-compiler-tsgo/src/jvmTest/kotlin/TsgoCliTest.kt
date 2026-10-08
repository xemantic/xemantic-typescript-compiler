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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * (TSGO.5) the command line's own pins (docs/goport-cli.md); the differential against the tsgo binary is
 * [CliParityTest]. Texts here were read from `tools/tsgo-7.0.2/lib/tsc` over the same inputs.
 */
class TsgoCliTest {

    private fun tsc(dir: File, vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val exit = TsgoCli.run(args.toList(), dir.absolutePath, { buf.write(it) })
        return exit to buf.toString(Charsets.UTF_8)
    }

    private fun project(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("tsgo-cli").toFile()
        for ((name, text) in files) File(dir, name).also { it.parentFile.mkdirs() }.writeText(text)
        return dir
    }

    @Test
    fun `--version prints tsgo's version and exits 0`() {
        val (exit, out) = tsc(project(), "--version")
        assert(exit == 0)
        assert(out == "Version 7.0.2\n")
    }

    @Test
    fun `a type error is reported relative to the working directory and exits 2 with outputs generated`() {
        val dir = project("tsconfig.json" to """{ "compilerOptions": { "strict": true, "outDir": "out" } }""", "a.ts" to "const n: number = 'x';\n")
        val (exit, out) = tsc(dir, "-p", ".")
        assert(exit == 2)
        assert(out == "a.ts(1,7): error TS2322: Type 'string' is not assignable to type 'number'.\n")
        assert(File(dir, "out/a.js").readText() == "\"use strict\";\nconst n = 'x';\n")
    }

    @Test
    fun `--noEmit writes nothing and exits 1 on errors`() {
        val dir = project("tsconfig.json" to """{ "compilerOptions": { "strict": true, "outDir": "out" } }""", "a.ts" to "let s: string = 1;\n")
        val (exit, out) = tsc(dir, "--noEmit", "-p", ".")
        assert(exit == 1)
        assert(out == "a.ts(1,5): error TS2322: Type 'number' is not assignable to type 'string'.\n")
        assert(!File(dir, "out").exists())
    }

    @Test
    fun `a missing project is TS5058 with outputs skipped`() {
        val (exit, out) = tsc(project(), "-p", "/nonexistent/project")
        assert(exit == 1)
        assert(out == "error TS5058: The specified path does not exist: '/nonexistent/project'.\n")
    }

    @Test
    fun `--build is not ported yet and says so with the not-implemented status`() {
        val (exit, out) = tsc(project(), "--build")
        assert(exit == TsgoCli.EXIT_NOT_IMPLEMENTED)
        assert(out.startsWith("error: this tsc mode is not available in the port yet (execute.tscBuildCompilation)"))
    }

    @Test
    fun `the shipped main runs in its own process - stdout and exit status as tsc`() {
        val dir = project("tsconfig.json" to """{ "compilerOptions": { "strict": true, "noEmit": true } }""", "a.ts" to "const b: boolean = 0;\n")
        val java = File(System.getProperty("java.home"), "bin/java").path
        // Output to FILES and the deadline around waitFor (CLAUDE.md: a blocking pipe read above a deadline hangs).
        val outFile = File.createTempFile("tsgo-cli", ".out")
        val errFile = File.createTempFile("tsgo-cli", ".err")
        val p = ProcessBuilder(java, "-Xss64m", "-cp", System.getProperty("java.class.path"), "com.xemantic.typescript.tsgo.cli.TsgoMainKt", "-p", ".")
            .directory(dir).redirectOutput(outFile).redirectError(errFile).start()
        val finished = p.waitFor(5, TimeUnit.MINUTES)
        if (!finished) p.destroyForcibly()
        assert(finished)
        val stdout = outFile.readText()
        val stderr = errFile.readText()
        val exit = p.exitValue()
        assert(exit == 1)
        assert(stdout == "a.ts(1,7): error TS2322: Type 'number' is not assignable to type 'boolean'.\n")
        assert(stderr == "")
    }
}
