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

package com.xemantic.typescript.compiler.kir.probe

import com.xemantic.typescript.compiler.kir.compileTypeScriptToJvm
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.runGeneratedProgram
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Points the backend at a TypeScript file OUTSIDE the corpus and reports.
 *
 * The instrument the library work is driven by: pointing the compiler at
 * unfamiliar code and reading the first refusal is what turned "extend the
 * lowering" into an ordered list — every capability in corpus 09-12 was added
 * because this named it, at a line and a column.
 *
 * Gated on `-Dkir.probe.file=<path>` and a no-op without it: it is a tool, not
 * a pin, and a test that reads a path outside the repository would otherwise be
 * a suite failure on any machine where that path does not exist.
 *
 * ```
 * ./gradlew :xemantic-typescript-compiler-kir:jvmTest --tests '*LibraryProbe*' \
 *   -Dkir.probe.file=/tmp/kir-libs/yaml/src/parse/cst.ts -i
 * ```
 */
class LibraryProbe {

    @Test
    fun probe() {
        val file = System.getProperty("kir.probe.file") ?: return
        val source = Files.readString(Path.of(file))
        val output = Files.createTempDirectory("kir-probe")
        val compilation = compileTypeScriptToJvm(file.substringAfterLast('/'), source, output)
        println("=== PROBE $file ===")
        println("successful=${compilation.successful}, typeErrors=${compilation.typeErrors.size}")
        compilation.typeErrors.take(40).forEach {
            println("TS${it.code} ${it.fileName}:${it.line}:${it.character} ${it.message}")
        }
        compilation.refusals.forEach { println("refused: $it") }
        compilation.emit?.takeIf { !it.successful }?.messages?.forEach { println("emit: $it") }
        if (!compilation.successful) return
        val run = runGeneratedProgram(
            output,
            compilation.mainClass,
            GeneratedProgramClasspath.minimal()
        )
        println("exit=${run.exitCode}")
        println("--- stdout ---")
        print(run.stdout)
        println("--- stderr ---")
        print(run.stderr)
    }

}
