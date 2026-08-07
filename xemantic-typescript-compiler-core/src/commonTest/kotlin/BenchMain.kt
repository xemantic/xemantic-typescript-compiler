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

import com.xemantic.typescript.compiler.PassTiming
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
 * ## The WARM per-pass table (opt-in 4th argument)
 *
 * Every `--passTiming` attribution table in `docs/perf` was produced by a COLD
 * one-shot `MainKt` JVM, so every per-pass row on record prices interpreted or
 * half-compiled code. Passing `passTiming` as the 4th argument makes this
 * harness run ONE ADDITIONAL instrumented rebuild **after** the measured loop
 * and dump [PassTiming]'s table from it — i.e. attribution over a fully
 * JIT-compiled compiler, which no other harness can produce.
 *
 * Two properties make that number trustworthy, and a change here must keep
 * both:
 *
 *  1. **The probe never runs inside a measured iteration.** The instrumentation
 *     is not free (round 733 measured the probe alone moving `checkSpine` by
 *     +29 ms, and the section probes cost far more), so the reported `medianMs`
 *     must stay probe-free. The instrumented rebuild prints its OWN wall ms on
 *     a separate `instrumented` line, which is what prices the probe against
 *     the uninstrumented median rather than hiding inside it.
 *  2. **It self-falsifies like every other iteration.** An instrumented rebuild
 *     that answers a different program is not a measurement of this one, so its
 *     `files`/`errors` are compared against the measured iterations and a
 *     disagreement aborts with a marked line and a non-zero exit.
 *
 * Usage:
 * ```
 * java -cp <test-classpath> com.xemantic.typescript.compiler.bench.BenchMainKt \
 *     <projectDir> <warmupIters> <measuredIters> [passTiming]
 * ```
 * Prints one JSON object per measured iteration, then a `summary` line — and,
 * with the 4th argument present, an `instrumented` line followed by the INV.0
 * pass-timing table. The 4th argument is OFF unless it is `passTiming` / `true`
 * / `1`; omitting it leaves this harness behaving exactly as it did before the
 * argument existed (this is what `scripts/ab-warm.sh` invokes, and its parser
 * reads only the `iter` lines).
 */
fun main(args: Array<String>) {
    val project = args.getOrNull(0)
        ?: error("usage: BenchMainKt <projectDir> <warmup> <iters> [passTiming]")
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 3
    val iters = args.getOrNull(2)?.toIntOrNull() ?: 10
    // Opt-in, and deliberately NOT a "any 4th argument means yes" test: a typo
    // would then silently buy a probe-contaminated extra rebuild.
    val instrumented = when (val flag = args.getOrNull(3)?.lowercase()) {
        null, "", "false", "0", "off" -> false
        "passtiming", "true", "1", "on" -> true
        else -> error("usage: 4th argument must be `passTiming` or omitted, not '$flag'")
    }

    repeat(warmup) {
        ProjectCompiler(SystemVfs).build(project, noEmit = true)
    }

    val times = mutableListOf<Double>()
    var files = 0
    var errors = 0
    // The measured loop's own answer, for the instrumented rebuild to be held
    // against. Recorded from the FIRST iteration; `measuredDrift` remembers
    // whether the loop was even self-consistent, since an instrumented rebuild
    // cannot be validated against a reference that already moved.
    var refFiles = -1
    var refErrors = -1
    var measuredDrift = false
    repeat(iters) { i ->
        val (result, duration) = measureTimedValue {
            ProjectCompiler(SystemVfs).build(project, noEmit = true)
        }
        val ms = duration.inWholeMicroseconds / 1000.0
        times.add(ms)
        files = result.programFiles.size
        errors = result.errorCount
        if (refFiles < 0) {
            refFiles = files; refErrors = errors
        } else if (files != refFiles || errors != refErrors) {
            measuredDrift = true
        }
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

    if (!instrumented) return

    // --- the WARM per-pass table --------------------------------------------
    // AFTER the summary, so nothing above this line has paid for a probe. The
    // enabled=false/reset()/enabled=true sequence is deliberate: `reset()` does
    // NOT clear `enabled` (nor `censusMode`/`disabledPasses`), so clearing it
    // first is what guarantees the counters start from zero even if some
    // earlier code in this process had the instrumentation on.
    PassTiming.enabled = false
    PassTiming.reset()
    PassTiming.enabled = true
    val (probeResult, probeDuration) = measureTimedValue {
        ProjectCompiler(SystemVfs).build(project, noEmit = true)
    }
    PassTiming.enabled = false
    val probeMs = probeDuration.inWholeMicroseconds / 1000.0
    val probeFiles = probeResult.programFiles.size
    val probeErrors = probeResult.errorCount
    // `overheadMs` is the whole point of printing this separately: it is the
    // price of the instrumentation on an otherwise identical warm rebuild, so a
    // reader can say how much of any per-pass row is the probe.
    println(
        """{"instrumented":true,"ms":$probeMs,"files":$probeFiles,"errors":$probeErrors,""" +
            """"medianMs":$median,"overheadMs":${probeMs - median}}"""
    )
    if (measuredDrift || probeFiles != refFiles || probeErrors != refErrors) {
        println(
            """{"instrumentedFalsified":true,"expectedFiles":$refFiles,""" +
                """"expectedErrors":$refErrors,"gotFiles":$probeFiles,""" +
                """"gotErrors":$probeErrors,"measuredDrift":$measuredDrift}"""
        )
        PassTiming.reset()
        error(
            "!! ABORT — the instrumented rebuild answered a DIFFERENT program than the " +
                "measured iterations (expected $refFiles files / $refErrors errors, got " +
                "$probeFiles / $probeErrors; measured iterations themselves drifted: " +
                "$measuredDrift). A per-pass table taken from a different compile " +
                "attributes nothing about the one that was timed — the instrumentation " +
                "is either changing behaviour, or in-process state is leaking across " +
                "rebuilds. No table is printed."
        )
    }
    PassTiming.dump(::println)
    // Leave the process's instrumentation exactly as this harness found it: the
    // table is dumped, so the counters (and the multi-million-entry distinct-node
    // set behind them) have no further reader.
    PassTiming.reset()
}
