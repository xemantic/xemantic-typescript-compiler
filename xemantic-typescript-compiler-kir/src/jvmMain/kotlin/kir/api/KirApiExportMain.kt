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

package com.xemantic.typescript.compiler.kir.api

import com.xemantic.typescript.compiler.kir.exportTypeScriptProjectApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Exports a TypeScript project's public API as a Kotlin metadata klib.
 *
 * ```
 * java -Xmx4g -cp <kir + core + deps> \
 *   com.xemantic.typescript.compiler.kir.api.KirApiExportMainKt \
 *   <project-dir> <entry-file> <out.klib> [package] [runtime.klib]
 * ```
 *
 * The same shape as `…kir.census.StructuralCensusMain`: a `main` run off the
 * module's classpath rather than a Gradle task or a shipped CLI entry point.
 * Deliberately so — the CLI is a GraalVM native image and this pipeline embeds
 * the Kotlin compiler, so putting it there would put kotlinc inside a binary
 * that must not carry it.
 *
 * The generated Kotlin source is written beside the klib as a `.kt` file, which
 * is the reviewable form of what the artifact contains.
 */
public fun main(args: Array<String>) {
    if (args.size < 3) {
        println(
            "usage: <project-dir> <entry-file> <out.klib> [package] [runtime.klib]\n" +
                "  entry-file   the module whose exports ARE the public API, e.g. index.ts\n" +
                "  package      the Kotlin package, default 'ts'\n" +
                "  runtime.klib where to write the runtime surface; without it an\n" +
                "               object type or an array is exported as `Any?`"
        )
        exitProcess(2)
    }
    val output = Path.of(args[2])
    val export = exportTypeScriptProjectApi(
        projectPath = args[0],
        entryFileName = args[1],
        outputKlib = output,
        packageName = args.getOrNull(3) ?: "ts",
        runtimeKlib = args.getOrNull(4)?.let { Path.of(it) },
    )
    println(export)
    if (export.source.isNotEmpty()) {
        val source = output.resolveSibling("${output.fileName}.kt")
        Files.writeString(source, export.source)
        println("  source: $source")
    }
    export.klib?.let { println("  klib: $it") }
    export.runtimeKlib?.let { println("  runtime: $it") }
    if (!export.successful) exitProcess(1)
}
