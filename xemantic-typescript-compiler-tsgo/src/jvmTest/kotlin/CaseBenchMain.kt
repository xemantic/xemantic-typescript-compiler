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

import com.xemantic.typescript.tsgo.go.testing.T
import java.io.File
import kotlin.system.exitProcess

/**
 * (TSGO.2) warm throughput of the ported compiler over MANY SMALL programs (docs/goport-perf.md § 6):
 * every [stride]-th configuration of the diagnostics oracle's manifest, compiled check-only through the
 * ported compiler test harness exactly as `DiagParityTest` does (the bundled libs are parsed once and
 * shared, as tsgo's harness shares them). The complement of `CheckBenchMain`'s one large program.
 *
 * `CaseBenchMain <repoRoot> [stride=10] [warmup=3] [iters=5]`
 */
fun main(args: Array<String>) {
    val repo = File(args.getOrElse(0) { ".." }).absoluteFile.normalize()
    val stride = args.getOrNull(1)?.toInt() ?: 10
    val warmup = args.getOrNull(2)?.toInt() ?: 3
    val iters = args.getOrNull(3)?.toInt() ?: 5
    var failure: Throwable? = null
    val th = Thread(null, {
        try {
            CaseBench(repo, stride, warmup, iters).run()
        } catch (t: Throwable) {
            failure = t
        }
    }, "case-bench-deep-stack", 1L shl 30)
    th.start()
    th.join()
    failure?.let { throw it }
    exitProcess(0)
}

private class CaseBench(val repo: File, val stride: Int, val warmup: Int, val iters: Int) {

    class Case(val fileName: String, val content: String, val variation: String)

    fun run() {
        val manifest = File(repo, "build/goport/diag-oracle/manifest.json").readText()
        val entries = Regex(""""case":\s*"([^"]+)",\s*"variation":\s*"([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        val casesRoot = File(repo, "build/goport/diag-src")
        val cases = entries.filterIndexed { i, _ -> i % stride == 0 }.map { (case, variation) ->
            val f = File(casesRoot, case)
            Case(f.path, com.xemantic.typescript.tsgo.harness.decodeCaseText(String(f.readBytes(), Charsets.ISO_8859_1)), variation)
        }
        TsgoPort.init()
        com.xemantic.typescript.tsgo.harness.typeScriptSubmodule = File(repo, "build/goport/ts-submodule").path
        println("configurations=${cases.size} of ${entries.size} (stride $stride) warmup=$warmup iters=$iters java=${System.getProperty("java.version")}")
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val totals = ArrayList<Double>()
        val cpus = ArrayList<Double>()
        val allocs = ArrayList<Double>()
        var checksum = 0L
        for (i in 0 until warmup + iters) {
            val c0 = threads.currentThreadCpuTime
            val a0 = threads.currentThreadAllocatedBytes
            val t0 = System.nanoTime()
            var diags = 0L
            var failed = 0
            for (c in cases) {
                try {
                    val t = T("${c.fileName} ${c.variation}")
                    val named = com.xemantic.typescript.tsgo.harness.caseConfigurations(c.content, t)
                        .single { com.xemantic.typescript.tsgo.harness.variationName(it) == c.variation }
                    diags += com.xemantic.typescript.tsgo.harness.runConfiguration(c.fileName, c.content, named, t).check.diagnostics.len
                } catch (e: Exception) {
                    failed++
                } finally {
                    com.xemantic.typescript.tsgo.harness.purgeSourceFileCache()
                }
            }
            val ms = (System.nanoTime() - t0) / 1e6
            val cpu = (threads.currentThreadCpuTime - c0) / 1e6
            val alloc = (threads.currentThreadAllocatedBytes - a0) / 1e6
            checksum = checksum * 31 + diags
            println("iter ${i + 1} ${if (i < warmup) "warm" else "meas"} total_ms=%.1f cpu_ms=%.1f alloc_mb=%.0f diags=$diags failed=$failed".format(ms, cpu, alloc))
            if (i >= warmup) {
                totals += ms
                cpus += cpu
                allocs += alloc
            }
        }
        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
        println(
            "RESULT median_ms=%.1f min_ms=%.1f max_ms=%.1f cpu_ms=%.1f gc_ms=0 alloc_mb=%.0f checksum=$checksum"
                .format(median(totals), totals.min(), totals.max(), median(cpus), median(allocs)),
        )
    }
}
