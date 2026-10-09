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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class)

package com.xemantic.typescript.tsgo

import com.xemantic.typescript.tsgo.ast.Diagnostic
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.core.TSFalse
import com.xemantic.typescript.tsgo.core.TSTrue
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import kotlinx.cinterop.toKString
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * (TSGO.6) The end-to-end native probe: `tsgo-check <abs tsconfig.json> [single|parallel] [iters=1]`.
 *
 * Does what `tsc --noEmit -p <project>` does (with `--singleThreaded` for `single`): parse the tsconfig,
 * build a `compiler.Program` over the host disk with the bundled libs, collect config, program,
 * syntactic, global and semantic diagnostics, and print them through tsgo's own non-pretty writer
 * (`diagnosticwriter.WriteFormatDiagnostics`, paths relative to the project directory, so the output
 * diffs against `tsc --noEmit -p` run there), with a `time:` line per iteration on stderr
 * (`TSGO_GOROUTINE_STATS=1`: also the goroutine pool's spawn and thread counts).
 */
fun nativeCheckMain(args: Array<String>) {
    val config = args.getOrNull(0) ?: run {
        println("usage: tsgo-check <absolute tsconfig.json> [single|parallel] [iters]")
        exitProcess(2)
    }
    val parallel = args.getOrNull(1) == "parallel"
    val iters = args.getOrNull(2)?.toInt() ?: 1
    configureGc()
    val count = onGoStack {
        TsgoProject.init()
        var last = 0
        repeat(iters) { i ->
            val t0 = TimeSource.Monotonic.markNow()
            val rows = check(config, parallel)
            val ms = t0.elapsedNow().inWholeMilliseconds
            if (i == iters - 1) rows.forEach(::println)
            val n = rows.count { !it.startsWith(" ") }
            platform.posix.fprintf(platform.posix.stderr, "time: iter=${i + 1} ms=$ms diags=$n\n")
            last = n
        }
        last
    }
    if (platform.posix.getenv("TSGO_GOROUTINE_STATS") != null) {
        platform.posix.fprintf(platform.posix.stderr, "goroutines: ${com.xemantic.typescript.tsgo.go.sync.goroutinePoolStats()}\n")
    }
    exitProcess(if (count == 0) 0 else 1)
}

/**
 * Kotlin/Native's GC tuning, from the environment while it is being measured: `TSGO_GC_TARGET_MB`
 * (the initial target heap), `TSGO_GC_MIN_MB` / `TSGO_GC_MAX_MB` (the bounds autotune keeps the target
 * in), `TSGO_GC_UTILIZATION` (the live share of the target heap autotune aims at, 0..1),
 * `TSGO_GC_TRIGGER` (the share of the target heap at which a collection starts), `TSGO_GC_INTERVAL_MS`
 * (the timer collection), `TSGO_GC_PAUSE=0` (do not stall allocating threads when the target heap
 * overflows), `TSGO_GC_AUTOTUNE=0`.
 */
private fun configureGc() {
    val gc = kotlin.native.runtime.GC
    fun env(name: String) = platform.posix.getenv(name)?.toKString()
    env("TSGO_GC_MIN_MB")?.toLongOrNull()?.let { gc.minHeapBytes = it shl 20 }
    env("TSGO_GC_MAX_MB")?.toLongOrNull()?.let { gc.maxHeapBytes = it shl 20 }
    env("TSGO_GC_TARGET_MB")?.toLongOrNull()?.let { gc.targetHeapBytes = it shl 20 }
    env("TSGO_GC_UTILIZATION")?.toDoubleOrNull()?.let { gc.targetHeapUtilization = it }
    env("TSGO_GC_TRIGGER")?.toDoubleOrNull()?.let { gc.heapTriggerCoefficient = it }
    env("TSGO_GC_INTERVAL_MS")?.toLongOrNull()?.let { gc.regularGCInterval = kotlin.time.Duration.parse("${it}ms") }
    if (env("TSGO_GC_PAUSE") == "0") gc.pauseOnTargetHeapOverflow = false
    if (env("TSGO_GC_AUTOTUNE") == "0") gc.autotune = false
    platform.posix.fprintf(
        platform.posix.stderr,
        "gc: targetHeapBytes=${gc.targetHeapBytes} min=${gc.minHeapBytes} max=${gc.maxHeapBytes} " +
            "utilization=${gc.targetHeapUtilization} trigger=${gc.heapTriggerCoefficient} " +
            "interval=${gc.regularGCInterval} pauseOnTargetHeapOverflow=${gc.pauseOnTargetHeapOverflow} autotune=${gc.autotune}\n",
    )
}

private fun phase(name: String, mark: TimeSource.Monotonic.ValueTimeMark) {
    platform.posix.fprintf(platform.posix.stderr, "phase: $name ms=${mark.elapsedNow().inWholeMilliseconds}\n")
}

private fun check(config: String, parallel: Boolean): List<String> {
    var mark = TimeSource.Monotonic.markNow()
    val configName = com.xemantic.typescript.tsgo.tspath.normalizePath(GoString.fromUtf16(config))
    val cwd = com.xemantic.typescript.tsgo.tspath.getDirectoryPath(configName)
    val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(TsgoProject.diskFS())
    val host = com.xemantic.typescript.tsgo.compiler.newCompilerHost(cwd, fs, com.xemantic.typescript.tsgo.bundled.libPath(), null, null)!!
    val (parsed, configDiags) = com.xemantic.typescript.tsgo.tsoptions.getParsedCommandLineOfConfigFile(configName, null, null, host, null)
    val all = ArrayList<Diagnostic>()
    fun add(ds: GoSlice<Diagnostic?>) {
        for (k in 0 until ds.len) all += ds[k]!!
    }
    add(configDiags)
    phase("config", mark)
    if (parsed != null) {
        mark = TimeSource.Monotonic.markNow()
        val program = com.xemantic.typescript.tsgo.compiler.newProgram(
            ProgramOptions(host = host, config = parsed, singleThreaded = if (parallel) TSFalse else TSTrue),
        )!!
        phase("program (parse+resolve, ${program.getSourceFiles().len} files)", mark)
        mark = TimeSource.Monotonic.markNow()
        val ctx = com.xemantic.typescript.tsgo.go.context.background()
        add(program.getConfigFileParsingDiagnostics())
        add(program.getProgramDiagnostics())
        add(program.getSyntacticDiagnostics(ctx, null))
        add(program.getGlobalDiagnostics(ctx))
        phase("syntactic+global (bind)", mark)
        mark = TimeSource.Monotonic.markNow()
        add(program.getSemanticDiagnostics(ctx, null))
        phase("semantic (check)", mark)
    }
    // tsgo's own non-pretty writer (`tsc --noEmit -p` without a TTY), paths relative to the project.
    val out = com.xemantic.typescript.tsgo.go.strings.Builder()
    val sorted = com.xemantic.typescript.tsgo.compiler.sortAndDeduplicateDiagnostics(GoSlice.of(com.xemantic.typescript.tsgo.runtime.GoElem.ref(), *all.toTypedArray()))
    com.xemantic.typescript.tsgo.diagnosticwriter.writeFormatDiagnostics(
        out,
        com.xemantic.typescript.tsgo.diagnosticwriter.fromASTDiagnostics(sorted),
        com.xemantic.typescript.tsgo.diagnosticwriter.FormattingOptions(
            comparePathsOptions = com.xemantic.typescript.tsgo.tspath.ComparePathsOptions(true, cwd),
            newLine = "\n",
        ),
    )
    return GoString.toUtf16(out.string()).split('\n').filter { it.isNotEmpty() }
}
