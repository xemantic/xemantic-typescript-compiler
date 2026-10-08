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

import com.xemantic.typescript.tsgo.ast.Diagnostic
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.compiler.EmitOptions
import com.xemantic.typescript.tsgo.compiler.Program
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.core.CompilerOptions
import com.xemantic.typescript.tsgo.core.TSFalse
import com.xemantic.typescript.tsgo.core.TSTrue
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.vfs.FS
import java.io.File
import kotlin.system.exitProcess

/**
 * (TSGO.4-d) the `-core` sunset report's probe (docs/core-sunset.md).
 *
 * `SunsetProbeMain rows <tsconfig.json>` — every diagnostic tsgo's `tsc --noEmit -p` would report,
 * one per line in tsc's `file(line,col): error TSnnnn: message` shape (absolute file name, the HEAD
 * message only), so the row set can be diffed against the tsgo binary's and `-core`'s.
 *
 * `SunsetProbeMain bench <tsconfig.json> <warmup> <iters> <check|emit> <single|parallel>` — the warm
 * whole-program loop: a fresh host + `Program` per iteration (file contents read once, as
 * `CheckBenchMain`), every diagnostic phase, and in `emit` mode `Program.emit` into a sink that only
 * counts the bytes (no disk writes, as `-core`'s `BenchMain ... emit`, which emits to memory).
 */
fun main(args: Array<String>) {
    if (args.size < 2 || args[0] !in setOf("rows", "bench")) {
        System.err.println("usage: SunsetProbeMain rows <tsconfig.json> | bench <tsconfig.json> <warmup> <iters> <check|emit> <single|parallel>")
        exitProcess(2)
    }
    var failure: Throwable? = null
    val th = Thread(null, {
        try {
            val config = File(args[1]).absoluteFile.normalize()
            if (args[0] == "rows") sunsetRows(config)
            else sunsetBench(config, args[2].toInt(), args[3].toInt(), args[4] == "emit", args[5] == "parallel")
        } catch (t: Throwable) {
            failure = t
        }
    }, "sunset-probe-deep-stack", 1L shl 30)
    th.start()
    th.join()
    failure?.let { throw it }
}

/** [DiskFS] whose writes are counted and dropped. */
private class SinkFS(private val disk: DiskFS) : FS by disk {
    var bytes = 0L
    var files = 0
    override fun writeFile(p0: String, p1: String): GoError? {
        bytes += p1.length
        files++
        return null
    }
    override fun chtimes(p0: String, p1: Time, p2: Time): GoError? = null
}

private fun openProgram(config: File, fs: FS, parallel: Boolean, noEmit: Boolean = false): Pair<Program, GoSlice<Diagnostic?>> {
    TsgoPort.init()
    val wrapped = com.xemantic.typescript.tsgo.bundled.wrapFS(fs)
    val cwd = GoString.fromUtf16(config.parentFile.path)
    val host = com.xemantic.typescript.tsgo.compiler.newCompilerHost(cwd, wrapped, com.xemantic.typescript.tsgo.bundled.libPath(), null, null)!!
    val (parsed, configDiags) = com.xemantic.typescript.tsgo.tsoptions.getParsedCommandLineOfConfigFile(
        GoString.fromUtf16(config.path), CompilerOptions().also { if (noEmit) it.noEmit = TSTrue }, null, host, null,
    )
    val program = com.xemantic.typescript.tsgo.compiler.newProgram(
        ProgramOptions(host = host, config = parsed, singleThreaded = if (parallel) TSFalse else TSTrue),
    )!!
    return program to configDiags
}

private fun allDiagnostics(program: Program, configDiags: GoSlice<Diagnostic?>): List<Diagnostic> {
    val ctx = com.xemantic.typescript.tsgo.go.context.background()
    val all = ArrayList<Diagnostic>()
    fun add(ds: GoSlice<Diagnostic?>) { for (k in 0 until ds.len) all += ds[k]!! }
    add(configDiags)
    add(program.getConfigFileParsingDiagnostics())
    add(program.getProgramDiagnostics())
    add(program.getSyntacticDiagnostics(ctx, null))
    add(program.getGlobalDiagnostics(ctx))
    add(program.getSemanticDiagnostics(ctx, null))
    return all
}

private fun sunsetRows(config: File) {
    // What `tsc --noEmit -p` reports: the CLI's `--noEmit`, then `compiler.GetDiagnosticsOfAnyProgram` (which
    // stops after config / syntactic / global errors exactly as the CLI does), sorted and de-duplicated.
    val (program, configDiags) = openProgram(config, DiskFS(), parallel = true, noEmit = true)
    val locale = com.xemantic.typescript.tsgo.locale.Locale()
    val ctx = com.xemantic.typescript.tsgo.go.context.background()
    val reported = configDiags.appendSlice(
        com.xemantic.typescript.tsgo.compiler.getDiagnosticsOfAnyProgram(
            ctx, program, null, false,
            { c, f -> program.getBindDiagnostics(c, f) },
            { c, f -> program.getSemanticDiagnostics(c, f) },
        ),
    )
    val sorted = com.xemantic.typescript.tsgo.compiler.sortAndDeduplicateDiagnostics(reported)
    val rows = (0 until sorted.len).map { sorted[it]!! }
    for (d in rows) {
        val message = GoString.toUtf16(d.localize(locale)).lineSequence().first()
        val file = d.file
        if (file == null) {
            println("error TS${d.code}: $message")
        } else {
            val (line, character) = com.xemantic.typescript.tsgo.scanner.getECMALineAndUTF16CharacterOfPosition(file, d.loc.pos.value)
            println("${GoString.toUtf16(file.fileName())}(${line + 1},${character.value + 1}): error TS${d.code}: $message")
        }
    }
}

private fun sunsetBench(config: File, warmup: Int, iters: Int, emit: Boolean, parallel: Boolean) {
    val disk = DiskFS()
    println("project=$config warmup=$warmup iters=$iters mode=${if (emit) "emit" else "check"} parallel=$parallel")
    val times = ArrayList<Double>()
    val gcBeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
    for (i in 0 until warmup + iters) {
        val g0 = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
        val t0 = System.nanoTime()
        val sink = SinkFS(disk)
        val (program, configDiags) = openProgram(config, sink, parallel)
        val n = allDiagnostics(program, configDiags).size
        if (emit) program.emit(com.xemantic.typescript.tsgo.go.context.background(), EmitOptions())
        val ms = (System.nanoTime() - t0) / 1e6
        val gc = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) } - g0
        println("iter ${i + 1} ${if (i < warmup) "warm" else "meas"} ms=%.1f gc_ms=%d diags=%d emitted_files=%d emitted_chars=%d".format(ms, gc, n, sink.files, sink.bytes))
        if (i >= warmup) times += ms
    }
    val s = times.sorted()
    val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    println("RESULT median_ms=%.1f min_ms=%.1f max_ms=%.1f peak_heap_mb=%d".format(
        median, s.first(), s.last(),
        java.lang.management.ManagementFactory.getMemoryPoolMXBeans().sumOf { it.peakUsage.used } / (1 shl 20),
    ))
}
