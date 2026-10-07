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

import com.xemantic.typescript.tsgo.ast.ExternalModuleIndicatorOptions
import com.xemantic.typescript.tsgo.ast.SourceFileParseOptions
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.tspath.Path
import java.io.File
import kotlin.system.exitProcess

/**
 * (TSGO.1) gate: "parse throughput, JVM warm, tsc's 78 sources: within 1.5x of `-core`'s
 * `Parser`" (docs/tsgo-port-plan.md § 4.1). Results: docs/goport-perf.md; driver:
 * scripts/tsgo-parse-bench.sh (one arm per JVM, ABBA across processes).
 *
 * `ParseBenchMain <tsgo|core> <repoRoot> [warmup=8] [iters=12]`
 *
 * Both arms start from the same Kotlin UTF-16 texts (read and decoded once, outside the timed
 * loop — a host holds its sources as Kotlin strings). The `tsgo` arm pays the conversion to
 * Go's byte-string representation ([GoString.fromUtf16]) inside the timed iteration and reports
 * it as its own column. Every iteration parses all 78 files and folds the parsers' own node
 * counts into a checksum that is printed, so no parse is dead code. Runs on a 1 GB-stack thread
 * (both arms), as the port and `-core` both do in production.
 */
fun main(args: Array<String>) {
    val arm = args.getOrNull(0) ?: run {
        System.err.println("usage: ParseBenchMain <tsgo|core> <repoRoot> [warmup] [iters]")
        exitProcess(2)
    }
    val repo = File(args.getOrElse(1) { ".." }).absoluteFile.normalize()
    val warmup = args.getOrNull(2)?.toInt() ?: 8
    val iters = args.getOrNull(3)?.toInt() ?: 12
    var failure: Throwable? = null
    val th = Thread(null, {
        try {
            ParseBench(arm, repo, warmup, iters).run()
        } catch (t: Throwable) {
            failure = t
        }
    }, "parse-bench-deep-stack", 1L shl 30)
    th.start()
    th.join()
    failure?.let { throw it }
}

private class ParseBench(val arm: String, val repo: File, val warmup: Int, val iters: Int) {

    class Input(val fileName: String, val text: String)

    private fun tscSources(): List<Input> {
        val text = File(repo, "build/goport/oracle/manifest.json").readText()
        val rx = Regex(""""source":\s*"tsc",.*?"fileName":\s*"([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
        val names = rx.findAll(text).map { it.groupValues[1] }.toList()
        check(names.size == 78) { "expected tsc's 78 sources in the manifest, found ${names.size}" }
        return names.map { name ->
            var s = File(name).readText(Charsets.UTF_8)
            if (s.startsWith('﻿')) s = s.substring(1)
            Input(name, s)
        }
    }

    fun run() {
        val inputs = tscSources()
        val chars = inputs.sumOf { it.text.length.toLong() }
        if (arm == "tsgo") com.xemantic.typescript.tsgo.parser.goInitPackage()
        val opts = inputs.map {
            SourceFileParseOptions(
                fileName = it.fileName,
                path = Path(it.fileName),
                externalModuleIndicatorOptions = ExternalModuleIndicatorOptions(jsx = false, force = false),
            )
        }
        val kinds = inputs.map { com.xemantic.typescript.tsgo.core.getScriptKindFromFileName(it.fileName) }
        println("arm=$arm files=${inputs.size} chars=$chars warmup=$warmup iters=$iters java=${System.getProperty("java.version")}")
        val totals = ArrayList<Double>()
        val convs = ArrayList<Double>()
        var checksum = 0L
        for (i in 0 until warmup + iters) {
            var convNs = 0L
            var nodes = 0L
            val t0 = System.nanoTime()
            when (arm) {
                "tsgo" -> for (k in inputs.indices) {
                    val c0 = System.nanoTime()
                    val bytes = GoString.fromUtf16(inputs[k].text)
                    convNs += System.nanoTime() - c0
                    val sf = com.xemantic.typescript.tsgo.parser.parseSourceFile(opts[k], bytes, kinds[k])!!
                    nodes += sf.nodeCount + sf.diagnostics.len
                }
                "core" -> for (input in inputs) {
                    val p = com.xemantic.typescript.compiler.Parser(input.text, input.fileName)
                    val sf = p.parse()
                    nodes += sf.nodeCount + p.getDiagnostics().size
                }
                else -> error("unknown arm $arm")
            }
            val ms = (System.nanoTime() - t0) / 1e6
            checksum = checksum * 31 + nodes
            val tag = if (i < warmup) "warm" else "meas"
            println("iter ${i + 1} $tag total_ms=%.1f conv_ms=%.2f nodes=$nodes".format(ms, convNs / 1e6))
            if (i >= warmup) {
                totals += ms
                convs += convNs / 1e6
            }
        }
        println("RESULT arm=$arm median_ms=%.1f min_ms=%.1f max_ms=%.1f conv_median_ms=%.2f checksum=$checksum"
            .format(median(totals), totals.min(), totals.max(), median(convs)))
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
