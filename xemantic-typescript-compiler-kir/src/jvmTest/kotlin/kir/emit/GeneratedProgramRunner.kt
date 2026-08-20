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

package com.xemantic.typescript.compiler.kir.emit

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What a generated program did when it was run. */
internal class ProgramRun(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {

    override fun toString(): String =
        "exit code $exitCode\n--- stdout ---\n$stdout--- stderr ---\n$stderr"

}

/**
 * Runs a generated program in a fresh JVM.
 *
 * A subprocess rather than a class loader on purpose: loading the generated
 * classes into THIS JVM would prove they verify, but not that they are a
 * program — and the emitter's whole claim is that it produces something
 * `java -cp … app.MainKt` can execute.
 */
internal fun runGeneratedProgram(
    outputDirectory: Path,
    mainClass: String,
    classpath: List<Path> = GeneratedProgramClasspath.minimal()
): ProgramRun {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val entries = (listOf(outputDirectory) + classpath).joinToString(File.pathSeparator)
    val process = ProcessBuilder(java, "-cp", entries, mainClass)
        .redirectErrorStream(false)
        .start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    check(process.waitFor(2, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        "the generated program did not terminate"
    }
    return ProgramRun(process.exitValue(), stdout, stderr)
}
