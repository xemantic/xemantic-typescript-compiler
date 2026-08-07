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

// NOT `com.xemantic.typescript.compiler` — this file declares a top-level
// `main(Array<String>)`, and so does `src/commonMain/kotlin/Main.kt`. On the JVM those
// are two distinct classes (`MainKt` / `BenchMainKt`) and coexist happily; Kotlin/Native
// mangles a top-level function to `kfun:<package>#main(kotlin.Array<kotlin.String>){}`
// with NO file component, so in one package they are ONE symbol — and the test binary
// links the main klib together with the test klib, which makes it a hard `ld.lld:
// duplicate symbol` at link time (round 775). Only the native TEST link can see this:
// the main executable link never pulls in commonTest. Keep any future commonTest
// entry point out of the compiler's own package.
package com.xemantic.typescript.compiler.bench

import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import kotlin.time.measureTimedValue

/**
 * Warm in-process whole-project benchmark entry point (NOT a test — carries no
 * `@Test` methods, so `jvmTest` runs are unaffected). Each iteration performs a
 * complete rebuild: tsconfig load, glob discovery, module resolution, parse,
 * bind and check.
 *
 * This runs **check-only**: `noEmit = true` reaches `ProjectCompiler`'s
 * `skipEmitOutputs`, so since round 738 nothing is transformed or emitted at all
 * (the earlier "emit-to-memory, only the disk writes are skipped" note here
 * predates that gate and was stale). The number is therefore directly comparable
 * to the arc's `--noEmit` figures — `ab-interleaved.sh` and `cost_gate.py` — and
 * NOT to the emit-mode CI ratio in `bench-history/`.
 *
 * **This is the only harness that measures a WARM compile.** `bench-compile-tsc.sh`
 * forks a fresh JVM per run, so its `--warmup N` warms the page cache and never
 * the JIT; every archived CI row is a cold single-shot.
 *
 * Usage:
 * ```
 * java -cp <test-classpath> com.xemantic.typescript.compiler.bench.BenchMainKt \
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
        // The probe's own falsification: an in-process rebuild shares whatever state
        // the pipeline does not reset (id counters, interning caches, the Vfs object),
        // so a WARM number is only a measurement while every iteration still answers
        // the SAME program. A drifting errors/files column means state is leaking and
        // the timings below it measure a different compile, not a faster one.
        println("""{"iter":$i,"ms":$ms,"files":${result.programFiles.size},"errors":${result.errorCount}}""")
    }

    val sorted = times.sorted()
    val median = sorted[sorted.size / 2]
    println(
        """{"summary":true,"project":"$project","files":$files,"errors":$errors,""" +
            """"warmup":$warmup,"iters":$iters,"medianMs":$median,"minMs":${sorted.first()},"maxMs":${sorted.last()}}"""
    )
}
