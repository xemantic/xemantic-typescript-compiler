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
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.core.CompilerOptions
import com.xemantic.typescript.tsgo.core.ParsedOptions
import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.io.fs.WalkDirFunc
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.tsoptions.ParsedCommandLine
import com.xemantic.typescript.tsgo.vfs.Entries
import com.xemantic.typescript.tsgo.vfs.FS
import kotlin.test.Test

/**
 * (TSGO.2) milestone: the PORTED checker reports its first semantic diagnostic. Builds a Program over
 * an in-memory project the way tsgo's own `compiler` tests do (`vfstest.FromMap` + `bundled.WrapFS` +
 * `NewCompilerHost` + `NewProgram`), then asks it for the semantic diagnostics of `const x: number = "s"`.
 */
class CheckerSmokeTest {

    /** A minimal read-only `vfs.FS` over a map (tsgo's `vfstest.FromMap`, case-sensitive). */
    class MapFS(files: Map<String, String>) : FS {
        private val files = files.mapValues { GoString.fromUtf16(it.value) }
        private val dirs = files.keys.flatMap { f -> f.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.map { it.ifEmpty { "/" } } }.toSet() + "/"

        override fun useCaseSensitiveFileNames(): Boolean = true
        override fun fileExists(p0: String): Boolean = p0 in files
        override fun readFile(p0: String): Tuple2<String, Boolean> = files[p0]?.let { Tuple2(it, true) } ?: Tuple2("", false)
        override fun directoryExists(p0: String): Boolean = p0 in dirs
        override fun getAccessibleEntries(p0: String): Entries {
            val prefix = if (p0.endsWith("/")) p0 else "$p0/"
            val children = (files.keys + dirs).filter { it.startsWith(prefix) && it.length > prefix.length && '/' !in it.substring(prefix.length) }
            return Entries(
                files = GoSlice.of(GoElem.STRING, *children.filter { it in files }.map { it.substring(prefix.length) }.sorted().toTypedArray()),
                directories = GoSlice.of(GoElem.STRING, *children.filter { it in dirs }.map { it.substring(prefix.length) }.sorted().toTypedArray()),
                symlinks = GoMap.make(com.xemantic.typescript.tsgo.runtime.goUnitElem),
            )
        }
        override fun realpath(p0: String): String = p0
        override fun stat(p0: String): FileInfo? = null
        override fun walkDir(p0: String, p1: WalkDirFunc?): GoError? = error("walkDir not supported")
        override fun writeFile(p0: String, p1: String): GoError? = error("read-only")
        override fun appendFile(p0: String, p1: String): GoError? = error("read-only")
        override fun remove(p0: String): GoError? = error("read-only")
        override fun chtimes(p0: String, p1: Time, p2: Time): GoError? = error("read-only")
    }

    private fun onDeepStack(body: () -> Unit) {
        var failure: Throwable? = null
        val th = Thread(null, { try { body() } catch (t: Throwable) { failure = t } }, "tsgo-deep-stack", 1L shl 30)
        th.start()
        th.join()
        failure?.let { throw it }
    }

    /** The ported pipeline's semantic diagnostics of `/src/a.ts` holding [source], as `TSnnnn: message`. */
    private fun check(source: String): List<String> {
        TsgoPort.init()
        val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(MapFS(mapOf("/src/a.ts" to source)))
        val host = com.xemantic.typescript.tsgo.compiler.newCompilerHost("/src", fs, com.xemantic.typescript.tsgo.bundled.libPath(), null, null)
        val config = ParsedCommandLine(parsedConfig = ParsedOptions(compilerOptions = CompilerOptions(), fileNames = GoSlice.of(GoElem.STRING, "/src/a.ts")))
        val program = com.xemantic.typescript.tsgo.compiler.newProgram(ProgramOptions(host = host, config = config))!!
        val file = program.getSourceFile("/src/a.ts")
        val diags = program.getSemanticDiagnostics(com.xemantic.typescript.tsgo.go.context.background(), file)
        return (0 until diags.len).map { i ->
            val d = diags[i]!!
            "TS${d.code}: ${GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale()))}"
        }
    }

    @Test
    fun `the ported checker reports TS2322 for a string assigned to a number`() = onDeepStack {
        val diags = check("const x: number = \"s\";\n")
        println("ported checker diagnostics for /src/a.ts: $diags")
        // tsgo 7.0.2 prints exactly this for the same file.
        assert(diags == listOf("TS2322: Type 'string' is not assignable to type 'number'."))
    }

    /** Debugging aid: `TSGO_SNIPPET=<file.ts>` prints the ported checker's diagnostics for that file. */
    @Test
    fun `snippet`() = onDeepStack {
        val f = System.getenv("TSGO_SNIPPET")?.takeIf { it.isNotEmpty() } ?: return@onDeepStack
        check(java.io.File(f).readText()).forEach { println("SNIPPET $it") }
    }

    @Test
    fun `negative control - a well-typed file has no semantic diagnostics`() = onDeepStack {
        assert(check("const x: number = 1;\nexport const y: string[] = [\"a\"].map(s => s + x);\n").isEmpty())
    }
}
