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
import com.xemantic.typescript.tsgo.core.TSTrue
import com.xemantic.typescript.tsgo.core.Tristate
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.tsoptions.ParsedCommandLine
import kotlin.test.Test

/**
 * tsgo's DEFAULT program (no `SingleThreaded`): files parsed and bound by goroutines, four checkers
 * checking in parallel (`WaitGroup.Go` → `goSpawn`, docs/goport-runtime.md § 9a). It used to deadlock
 * (the files parser queues children under a mutex they take) while every test passed `SingleThreaded`.
 * The parallel program must report exactly what the single-threaded one does.
 */
class ParallelProgramTest {

    private fun onDeepStack(body: () -> Unit) {
        var failure: Throwable? = null
        val th = Thread(null, { try { body() } catch (t: Throwable) { failure = t } }, "tsgo-deep-stack", 1L shl 30)
        th.start()
        th.join()
        failure?.let { throw it }
    }

    private val files: Map<String, String> = (0 until 12).associate { i ->
        "/src/f$i.ts" to """
            import { shared } from "./shared";
            export function f$i(x: number): string { return x + shared; }
            const bad$i: number = "s$i";
            export const use$i = f$i(${if (i % 3 == 0) "\"no\"" else "$i"});
        """.trimIndent()
    } + ("/src/shared.ts" to "export const shared: string = \"!\";\n")

    private fun diagnostics(singleThreaded: Tristate): List<String> {
        TsgoPort.init()
        val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(CheckerSmokeTest.MapFS(files))
        val host = com.xemantic.typescript.tsgo.compiler.newCompilerHost("/src", fs, com.xemantic.typescript.tsgo.bundled.libPath(), null, null)
        val roots = GoSlice.of(GoElem.STRING, *files.keys.sorted().toTypedArray())
        val config = ParsedCommandLine(parsedConfig = ParsedOptions(compilerOptions = CompilerOptions(), fileNames = roots))
        val program = com.xemantic.typescript.tsgo.compiler.newProgram(ProgramOptions(host = host, config = config, singleThreaded = singleThreaded))!!
        val diags = program.getSemanticDiagnostics(com.xemantic.typescript.tsgo.go.context.background(), null)
        return (0 until diags.len).map { i ->
            val d = diags[i]!!
            "${d.file?.fileName()}:${d.loc.pos.value}: TS${d.code}: ${GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale()))}"
        }.sorted()
    }

    @Test
    fun `a parallel program reports what the single-threaded one does`() = onDeepStack {
        val single = diagnostics(TSTrue)
        // 12 files: `const bad = "s"` (TS2322) each, and every third one calls f with a string (TS2345).
        assert(single.count { "TS2322" in it } == 12 && single.count { "TS2345" in it } == 4)
        repeat(3) {
            val parallel = diagnostics(Tristate(0))
            assert(parallel == single)
        }
    }
}
