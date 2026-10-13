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

    private fun project(vararg files: Pair<String, String>): File = projectIn(Files.createTempDirectory("tsgo-cli").toFile(), *files)

    private fun projectIn(dir: File, vararg files: Pair<String, String>): File {
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
    fun `--build builds the project, reports its error and writes its buildinfo`() {
        // (TSGO.5-b) tsgo 7.0.2 prints exactly this, exits 2 and writes these two files for the same project
        val dir = project("tsconfig.json" to """{ "compilerOptions": { "strict": true, "outDir": "out" } }""", "a.ts" to "const n: number = 'x';\n")
        val (exit, out) = tsc(dir, "--build")
        assert(exit == 2)
        assert(out == "a.ts(1,7): error TS2322: Type 'string' is not assignable to type 'number'.\n")
        val written = File(dir, "out").list()!!.sorted()
        assert(written == listOf("a.js", "tsconfig.tsbuildinfo"))
    }

    /**
     * Runs `tsc [args]` in [dir] on a thread, lets [steps] change files (its argument waits until the n-th
     * "Watching for file changes" and then for quiet), cancels the context and returns the output, times masked.
     */
    private fun watch(dir: File, args: List<String>, steps: ((Int) -> Unit) -> Unit): String {
        val out = StringBuffer()
        val (ctx, cancel) = com.xemantic.typescript.tsgo.go.context.withCancel(com.xemantic.typescript.tsgo.go.context.background())
        var exit = -1
        val run = Thread { exit = TsgoCli.run(args, dir.absolutePath, { out.append(it.toString(Charsets.UTF_8)) }, context = ctx!!) }
        run.start()
        fun cycles() = Regex("Watching for file changes").findAll(out).count()
        steps { n ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (cycles() < n && System.nanoTime() < deadline) Thread.sleep(20)
            // quiet for a while: no further cycle follows the change
            Thread.sleep(700)
        }
        cancel()
        run.join(TimeUnit.SECONDS.toMillis(30))
        val alive = run.isAlive
        assert(!alive && exit == 0)
        return out.toString().replace(Regex("\\d\\d:\\d\\d:\\d\\d [AP]M"), "HH:MM:SS XM")
    }

    @Test
    fun `--build --watch rebuilds the project on each change and keeps its outputs and buildinfo`() {
        // (TSGO.5-c) tsgo 7.0.2 prints exactly this (times masked) for the same steps and leaves these files.
        val dir = projectIn(
            File(Files.createTempDirectory("tsgo-cli").toFile(), "work/app"),
            "tsconfig.json" to """{ "compilerOptions": { "strict": true, "rootDir": "src", "outDir": "out" }, "include": ["src"] }""",
            "src/a.ts" to "export const a: number = 1;\n",
        )
        val masked = watch(dir, listOf("-b", "-w")) { awaitCycles ->
            awaitCycles(1)
            File(dir, "src/a.ts").writeText("export const a: number = \"x\";\n")
            awaitCycles(2)
            File(dir, "src/b.ts").writeText("export const b: string = \"y\";\n")
            awaitCycles(3)
        }
        val cls = "\u001b[2J\u001b[3J\u001b[H"
        assert(
            masked == "${cls}HH:MM:SS XM - Starting compilation in watch mode...\n\n" +
                "HH:MM:SS XM - Found 0 errors. Watching for file changes.\n\n" +
                "${cls}HH:MM:SS XM - File change detected. Starting incremental compilation...\n\n" +
                "src/a.ts(1,14): error TS2322: Type 'string' is not assignable to type 'number'.\n" +
                "HH:MM:SS XM - Found 1 error. Watching for file changes.\n\n" +
                "${cls}HH:MM:SS XM - File change detected. Starting incremental compilation...\n\n" +
                "src/a.ts(1,14): error TS2322: Type 'string' is not assignable to type 'number'.\n" +
                "HH:MM:SS XM - Found 1 error. Watching for file changes.\n\n"
        )
        val files = dir.walk().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
        assert(files == listOf("out/a.js", "out/b.js", "src/a.ts", "src/b.ts", "tsconfig.json", "tsconfig.tsbuildinfo"))
    }

    @Test
    fun `--watch rebuilds on each relevant change and ends when its context is cancelled`() {
        // (TSGO.5-c) tsgo 7.0.2 prints exactly this (times masked) for the same steps; `notes.txt` (outside the
        // include) is an event the watcher finds irrelevant: no cycle. Two levels down: tsgo watches no directory
        // within two components of the root (`/tmp/x` reads like `/home/user`, watchmanager.CanWatchDirectory).
        val dir = projectIn(
            File(Files.createTempDirectory("tsgo-cli").toFile(), "work/app"),
            "tsconfig.json" to """{ "compilerOptions": { "strict": true, "noEmit": true }, "include": ["src"] }""",
            "src/a.ts" to "export const a: number = 1;\n",
        )
        val masked = watch(dir, listOf("--watch")) { awaitCycles ->
            awaitCycles(1)
            File(dir, "src/a.ts").writeText("export const a: number = \"x\";\n")
            awaitCycles(2)
            File(dir, "notes.txt").writeText("note\n")
            Thread.sleep(700)
            File(dir, "src/b.ts").writeText("export const b: string = 1;\n")
            awaitCycles(3)
            File(dir, "src/b.ts").delete()
            awaitCycles(4)
        }
        val cls = "\u001b[2J\u001b[3J\u001b[H"
        assert(
            masked == "${cls}HH:MM:SS XM - Starting compilation in watch mode...\n\n" +
                "HH:MM:SS XM - Found 0 errors. Watching for file changes.\n\n" +
                "${cls}HH:MM:SS XM - File change detected. Starting incremental compilation...\n\n" +
                "src/a.ts(1,14): error TS2322: Type 'string' is not assignable to type 'number'.\n" +
                "HH:MM:SS XM - Found 1 error. Watching for file changes.\n\n" +
                "${cls}HH:MM:SS XM - File change detected. Starting incremental compilation...\n\n" +
                "src/a.ts(1,14): error TS2322: Type 'string' is not assignable to type 'number'.\n" +
                "src/b.ts(1,14): error TS2322: Type 'number' is not assignable to type 'string'.\n" +
                "HH:MM:SS XM - Found 2 errors. Watching for file changes.\n\n" +
                "${cls}HH:MM:SS XM - File change detected. Starting incremental compilation...\n\n" +
                "src/a.ts(1,14): error TS2322: Type 'string' is not assignable to type 'number'.\n" +
                "HH:MM:SS XM - Found 1 error. Watching for file changes.\n\n"
        )
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

    @Test
    fun `the shipped main in watch mode prints each cycle as it happens and exits 0 on SIGTERM`() {
        // tsgo 7.0.2: its status lines reach a pipe at once (Go's stdout is unbuffered) and SIGTERM cancels the
        // watch (signal.NotifyContext), exit status 0.
        val dir = projectIn(
            File(Files.createTempDirectory("tsgo-cli").toFile(), "work/app"),
            "tsconfig.json" to """{ "compilerOptions": { "strict": true, "noEmit": true }, "include": ["src"] }""",
            "src/a.ts" to "export const a: number = 1;\n",
        )
        val java = File(System.getProperty("java.home"), "bin/java").path
        val outFile = File.createTempFile("tsgo-cli", ".out")
        val p = ProcessBuilder(java, "-Xss64m", "-cp", System.getProperty("java.class.path"), "com.xemantic.typescript.tsgo.cli.TsgoMainKt", "-w")
            .directory(dir).redirectOutput(outFile).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        fun awaitText(text: String): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (System.nanoTime() < deadline && p.isAlive) {
                if (text in outFile.readText()) return true
                Thread.sleep(50)
            }
            return false
        }
        val first = awaitText("Found 0 errors. Watching for file changes.")
        File(dir, "src/a.ts").writeText("let s: string = 1;\n")
        val second = awaitText("Found 1 error. Watching for file changes.")
        p.destroy()
        val finished = p.waitFor(30, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        assert(first && second && finished)
        val exit = p.exitValue()
        assert(exit == 0)
        val lines = outFile.readText().lines().filter { "error TS" in it }
        assert(lines == listOf("src/a.ts(1,5): error TS2322: Type 'number' is not assignable to type 'string'."))
    }
}
