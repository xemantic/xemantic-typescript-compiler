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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test

/**
 * (TSGO.3) the EMIT differential (docs/goport-emit-oracle.md), the emit twin of [DiagParityTest]: for every
 * configuration the oracle covered (`build/goport/emit-oracle/manifest.json` — the four compiler-runner suites,
 * read RAW from `build/goport/diag-src`), the PORTED runner compiles the case exactly as tsgo's
 * `newCompilerTest` does (`harnessutil.CompileFiles`: pre-emit program, emitting program, the TS-1 count check)
 * and renders the three emit baselines `runSingleConfigTest` writes — `output` (the `.js` baseline, with its
 * `.d.ts` section, its `DtsFileErrors` re-compile and its noCheck comparison), `sourcemap` (`.js.map`) and
 * `sourcemap record` (`.sourcemap.txt`) — into the oracle's frame. The result goes to
 * `build/goport/emit-kotlin/<case>/<variation>.emit`, and the test FAILS unless every configuration's frame is
 * byte-identical to tsgo's (`build/goport/emit-oracle`, itself checked byte for byte against tsgo's committed
 * baselines). Opt-in: `TSGO_EMIT=1` (`TSGO_EMIT_LIMIT=n`, `TSGO_EMIT_FILTER=<substring of the case path>`,
 * `TSGO_EMIT_THREADS=n` (default 4), `TSGO_EMIT_INJECT=<case>/<variation>` to perturb one result and watch the
 * gate go red).
 */
class EmitParityTest {

    private val root = File("..").absoluteFile.normalize()
    private val casesRoot = File(root, "build/goport/diag-src") // scripts/tsgo-diag-cases.py makes it
    private val oracle = File(root, "build/goport/emit-oracle") // scripts/tsgo-emit-oracle.py makes it
    private val out = File(root, "build/goport/emit-kotlin")

    /** One configuration through the ported runner: its frame (a Go byte string). */
    private fun runConfiguration(case: String, variation: String): String {
        val filename = File(casesRoot, case).path
        val content = com.xemantic.typescript.tsgo.harness.decodeCaseText(String(File(filename).readBytes(), Charsets.ISO_8859_1))
        val t = T("$case $variation")
        val configs = com.xemantic.typescript.tsgo.harness.caseConfigurations(content, t)
        val found = configs.filter { com.xemantic.typescript.tsgo.harness.variationName(it) == variation }
        check(found.size == 1) {
            "variation not found: $variation (the harness enumerates ${configs.map { com.xemantic.typescript.tsgo.harness.variationName(it) }})"
        }
        val header = "tests/cases/" + case.removePrefix("local/")
        val arts = com.xemantic.typescript.tsgo.harness.emitBaselines(filename, content, found[0], header, t)
        return com.xemantic.typescript.tsgo.harness.emitFrame(arts)
    }

    @Test
    fun `the ported compiler's emit baselines per compiler-runner configuration`() {
        if (System.getenv("TSGO_EMIT").isNullOrEmpty()) {
            println("EmitParityTest: skipped (set TSGO_EMIT=1; needs build/goport/diag-cases and emit-oracle from scripts/tsgo-diag-cases.py / tsgo-emit-oracle.py)")
            return
        }
        val manifest = File(oracle, "manifest.json").readText()
        check(Regex(""""complete":\s*true""").containsMatchIn(manifest)) { "the emit oracle manifest is incomplete" }
        val limit = System.getenv("TSGO_EMIT_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val filter = System.getenv("TSGO_EMIT_FILTER")
        val entries = Regex(""""case":\s*"([^"]+)",\s*"variation":\s*"([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }.filter { filter == null || it.first.contains(filter) }.take(limit).toList()
        TsgoPort.init()
        com.xemantic.typescript.tsgo.harness.typeScriptSubmodule = File(root, "build/goport/ts-submodule").also {
            check(File(it, "tests/lib").isDirectory) { "no /.lib test libraries at $it/tests/lib (scripts/tsgo-diag-cases.py extracts them)" }
        }.path
        val threads = (System.getenv("TSGO_EMIT_THREADS") ?: "4").toInt()
        val timeoutMs = (System.getenv("TSGO_EMIT_TIMEOUT") ?: "120").toLong() * 1000
        File(out, "crashes.txt").delete()
        out.mkdirs()
        // Deep-stack daemon workers: a ported loop that does not terminate (a porting bug) costs that
        // configuration, not the run — its worker is abandoned (a JVM thread cannot be stopped) and replaced.
        val factory = ThreadFactory { r -> Thread(null, r, "tsgo-emit", 1L shl 30).also { it.isDaemon = true } }
        var pool = Executors.newFixedThreadPool(threads, factory)
        var ok = 0
        var crashed = 0
        val crashes = LinkedHashMap<String, Int>()
        val t0 = System.nanoTime()
        val pending = ArrayDeque<Pair<Pair<String, String>, Future<String>>>()
        fun settle(key: Pair<String, String>, f: Future<String>) {
            val (case, variation) = key
            val dst = File(out, "$case/$variation.emit")
            dst.parentFile.mkdirs()
            val t: Throwable? = try {
                val frame = f.get(timeoutMs, TimeUnit.MILLISECONDS)
                dst.writeBytes(frame.toByteArray(Charsets.ISO_8859_1))
                ok++
                null
            } catch (e: TimeoutException) {
                f.cancel(true)
                pool.shutdown()
                pool = Executors.newFixedThreadPool(threads, factory)
                IllegalStateException("timeout after ${timeoutMs / 1000} s")
            } catch (e: java.util.concurrent.ExecutionException) {
                e.cause ?: e
            }
            if (t != null) {
                dst.delete()
                crashed++
                val where = t.stackTrace.firstOrNull { it.className.startsWith("com.xemantic.typescript.tsgo") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
                val k = "${t::class.simpleName}: ${t.message?.take(140)} @ $where"
                crashes[k] = (crashes[k] ?: 0) + 1
                synchronized(this) {
                    File(out, "crashes.txt").appendText("== $case/$variation: $k\n" + (if (crashes[k] == 1) t.stackTraceToString().lines().take(30).joinToString("\n") + "\n" else ""))
                }
            }
        }
        for (key in entries) {
            val (case, variation) = key
            pending.addLast(key to pool.submit<String> { runConfiguration(case, variation) })
            while (pending.size >= threads * 4) pending.removeFirst().let { settle(it.first, it.second) }
        }
        while (pending.isNotEmpty()) pending.removeFirst().let { settle(it.first, it.second) }
        pool.shutdown()
        println("EmitParityTest: ${entries.size} configurations, $ok written, $crashed crashed (${(System.nanoTime() - t0) / 1_000_000} ms, $threads threads)")
        crashes.entries.sortedByDescending { it.value }.take(25).forEach { (k, v) -> println("  $v × $k") }
        gate(entries)
    }

    /**
     * Grades every configuration against `build/goport/emit-oracle`: EQUAL when the two frames are the same
     * BYTES; a missing actual file (a crashed configuration) is `missing`. Fails unless every configuration is
     * equal, printing the first differences (the artifact and the first differing line).
     * `TSGO_EMIT_INJECT=<case>/<variation>` appends one byte to that actual frame first — the positive control.
     */
    private fun gate(entries: List<Pair<String, String>>) {
        val inject = System.getenv("TSGO_EMIT_INJECT")
        var equal = 0
        var missing = 0
        val differ = ArrayList<String>()
        val byArtifact = LinkedHashMap<String, Int>()
        for ((case, variation) in entries) {
            val key = "$case/$variation"
            val wantFile = File(oracle, "$key.emit")
            check(wantFile.isFile) { "oracle file missing: $wantFile" }
            val gotFile = File(out, "$key.emit")
            if (!gotFile.isFile) { missing++; if (differ.size < 10) differ += "MISSING (crashed) $key"; continue }
            val want = String(wantFile.readBytes(), Charsets.ISO_8859_1)
            var got = String(gotFile.readBytes(), Charsets.ISO_8859_1)
            if (key == inject) got += "!"
            if (got == want) { equal++; continue }
            val w = sections(want)
            val g = sections(got)
            val kinds = (w.keys + g.keys).filter { w[it] != g[it] }
            for (k in kinds) byArtifact[k] = (byArtifact[k] ?: 0) + 1
            if (differ.size < 10) {
                val k = kinds.firstOrNull() ?: "<frame>"
                val wl = (w[k] ?: want).split('\n')
                val gl = (g[k] ?: got).split('\n')
                val i = (0 until minOf(wl.size, gl.size)).firstOrNull { wl[it] != gl[it] } ?: minOf(wl.size, gl.size)
                differ += "DIFFER $key [$k] first difference at line ${i + 1}\n" +
                    "   oracle: ${u(wl.getOrNull(i))?.take(300) ?: "<end>"}\n   actual: ${u(gl.getOrNull(i))?.take(300) ?: "<end>"}"
            }
        }
        val unequal = entries.size - equal
        println("EmitParityTest gate: ${entries.size} configurations: equal $equal, differ ${unequal - missing}, missing $missing" +
            if (byArtifact.isEmpty()) "" else " (differing artifacts: $byArtifact)")
        if (unequal != 0) {
            kotlin.test.fail("emit differential: $unequal of ${entries.size} configurations not equal to tsgo\n" + differ.joinToString("\n"))
        }
    }

    /** A frame's artifacts by kind: `<kind>` -> its header line's status/baseline and its content. */
    private fun sections(frame: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        var i = 0
        while (i < frame.length) {
            val nl = frame.indexOf('\n', i)
            if (!frame.startsWith("== ", i) || nl < 0) { m["<malformed>"] = frame.substring(i); break }
            val head = frame.substring(i + 3, nl).split('\t')
            val len = head.getOrNull(3)?.toIntOrNull() ?: run { m["<malformed>"] = frame.substring(i); return m }
            val end = minOf(frame.length, nl + 1 + len)
            m[head[0]] = head.drop(1).joinToString("\t") + "\n" + frame.substring(nl + 1, end)
            i = end + 1
        }
        return m
    }

    /** A Go byte string as Kotlin text (for the report). */
    private fun u(s: String?): String? = s?.let { com.xemantic.typescript.tsgo.runtime.GoString.toUtf16(it) }
}
