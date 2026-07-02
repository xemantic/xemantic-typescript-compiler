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

package com.xemantic.typescript.compiler

import kotlin.time.measureTimedValue

/**
 * Warm in-process whole-project benchmark entry point (NOT a test — carries no
 * `@Test` methods, so `jvmTest` runs are unaffected). Each iteration performs a
 * complete rebuild: tsconfig load, glob discovery, module resolution, parse,
 * bind, check, transform, and emit-to-memory (`noEmit = true` skips only the
 * final disk writes; the JS text is still produced by the shared pipeline).
 *
 * Usage:
 * ```
 * java -cp <test-classpath> com.xemantic.typescript.compiler.BenchMainKt \
 *     <projectDir> <warmupIters> <measuredIters>
 * ```
 * Prints one JSON object per measured iteration, then a `summary` line.
 */
fun main(args: Array<String>) {
    val project = args.getOrNull(0) ?: error("usage: BenchMainKt <projectDir> <warmup> <iters>")
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 3
    val iters = args.getOrNull(2)?.toIntOrNull() ?: 10

    repeat(warmup) {
        ProjectCompiler(SystemVfs).build(project, noEmit = true)
    }

    val times = mutableListOf<Double>()
    var files = 0
    var errors = 0
    repeat(iters) { i ->
        val (result, duration) = measureTimedValue {
            ProjectCompiler(SystemVfs).build(project, noEmit = true)
        }
        val ms = duration.inWholeMicroseconds / 1000.0
        times.add(ms)
        files = result.programFiles.size
        errors = result.errorCount
        println("""{"iter":$i,"ms":$ms}""")
    }

    val sorted = times.sorted()
    val median = sorted[sorted.size / 2]
    println(
        """{"summary":true,"project":"$project","files":$files,"errors":$errors,""" +
            """"warmup":$warmup,"iters":$iters,"medianMs":$median,"minMs":${sorted.first()},"maxMs":${sorted.last()}}"""
    )
}
