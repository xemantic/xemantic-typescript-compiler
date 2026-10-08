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

import com.xemantic.typescript.compiler.kir.compileTypeScriptProjectToJvm
import com.xemantic.typescript.compiler.kir.compileTypeScriptToJvm
import com.xemantic.typescript.compiler.kir.front.checkTypeScript
import com.xemantic.typescript.compiler.kir.front.checkTypeScriptProject
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * (TSGO.4-c) The two KIR front ends, side by side: `XTSC_KIR_ENGINE=core` runs
 * `-core`'s checker, unset runs the ported tsgo one, and everything after the
 * facts is the same lowering. Run it once per engine (the engine is read from
 * the environment, which one process cannot change) and diff the two output
 * trees: each program's generated classes as `javap -c -p` text, so a
 * LOWERING change shows up as a text diff whatever the program prints.
 *
 * `KirFrontEndCompareMain <out-dir> <resources-dir>` — `<resources-dir>` is
 * `src/jvmTest/resources`. Also prints the front end's own wall and the
 * process's peak heap per program, the receipts the `-core` sunset report
 * ((TSGO.4-d)) needs.
 */
fun main(args: Array<String>) {
    val out = File(args[0]).also { it.mkdirs() }
    val resources = File(args[1])
    val engine = System.getenv("XTSC_KIR_ENGINE") ?: "tsgo"
    val corpus = File(resources, "corpus").listFiles { f -> f.name.endsWith(".ts") }!!.sortedBy { it.name }
    val projects = File(resources, "projects").listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
    val report = StringBuilder()
    val warmups = System.getenv("KIR_COMPARE_WARMUP")?.toInt() ?: 1
    // Warm the JIT on the whole corpus first, so the per-program front-end
    // walls below are steady state, not the first compile of each engine.
    repeat(warmups) {
        corpus.forEach { checkTypeScript(it.name, it.readText()) }
    }
    var frontTotal = 0L
    for (file in corpus) {
        val start = System.nanoTime()
        checkTypeScript(file.name, file.readText())
        val front = (System.nanoTime() - start) / 1_000_000
        frontTotal += front
        val dir = Files.createTempDirectory("kir-compare")
        val compilation = compileTypeScriptToJvm(file.name, file.readText(), dir)
        report.append("${file.nameWithoutExtension} front=${front}ms compiled=${compilation.successful}\n")
        dump(dir, File(out, file.nameWithoutExtension + ".javap"), compilation.toString())
    }
    for (project in projects) {
        val copy = Files.createTempDirectory("kir-compare-project").toFile()
        project.copyRecursively(copy)
        val start = System.nanoTime()
        checkTypeScriptProject(copy.path)
        val front = (System.nanoTime() - start) / 1_000_000
        frontTotal += front
        val dir = Files.createTempDirectory("kir-compare")
        val compilation = compileTypeScriptProjectToJvm(copy.path, "main.ts", dir)
        report.append("${project.name} front=${front}ms compiled=${compilation.successful}\n")
        dump(dir, File(out, project.name + ".javap"), compilation.toString().replace(copy.path, "<project>"))
    }
    val peak = ManagementFactory.getMemoryPoolMXBeans()
        .filter { it.type == java.lang.management.MemoryType.HEAP }
        .sumOf { it.peakUsage.used } / (1024 * 1024)
    report.append("engine=$engine frontTotal=${frontTotal}ms peakHeap=${peak}MB\n")
    File(out, "report.txt").writeText(report.toString())
    print(report)
}

private fun dump(classes: Path, target: File, header: String) {
    val javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString()
    val files = classes.toFile().walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
        .sortedBy { it.path }.map { it.path }.toList()
    val text = StringBuilder(header).append('\n')
    if (files.isNotEmpty()) {
        val process = ProcessBuilder(listOf(javap, "-c", "-p") + files).redirectErrorStream(true).start()
        text.append(process.inputStream.readBytes().decodeToString().replace(classes.toString(), "<out>"))
        process.waitFor()
    }
    target.writeText(text.toString())
}
