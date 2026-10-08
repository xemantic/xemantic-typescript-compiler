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
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.core.TSTrue
import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.io.fs.WalkDirFunc
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.vfs.Entries
import com.xemantic.typescript.tsgo.vfs.FS
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * (TSGO.2) warm CHECK throughput of the ported compiler (docs/goport-perf.md § 6).
 *
 * `CheckBenchMain <tsconfig.json> [warmup=4] [iters=8] [libcache]`
 *
 * Every iteration does what `tsc --noEmit -p <project> --singleThreaded` does after reading the
 * files: parse the tsconfig, build a `compiler.Program` (parse + bind every root, the libs, the
 * resolution walk), and ask it for config, program, syntactic, semantic and global diagnostics.
 * File CONTENTS are read from disk once and served from memory (a warm host), so the figure is the
 * compiler's work. `libcache` additionally shares parsed bundled-lib `SourceFile`s across iterations
 * (the harness's cachedCompilerHost; tsgo's language server does the same). The diagnostic count is
 * printed per iteration and must equal tsgo's (65 on tsc's own sources).
 */
fun main(args: Array<String>) {
    val config = args.getOrNull(0) ?: run {
        System.err.println("usage: CheckBenchMain <tsconfig.json> [warmup] [iters] [libcache]")
        exitProcess(2)
    }
    val warmup = args.getOrNull(1)?.toInt() ?: 4
    val iters = args.getOrNull(2)?.toInt() ?: 8
    val libCache = args.getOrNull(3) == "libcache"
    var failure: Throwable? = null
    val th = Thread(null, {
        try {
            CheckBench(File(config).absoluteFile.normalize(), warmup, iters, libCache).run()
        } catch (t: Throwable) {
            failure = t
        }
    }, "check-bench-deep-stack", 1L shl 30)
    th.start()
    th.join()
    failure?.let { throw it }
}

/** A read-only `vfs.FS` over the real disk, file contents cached as Go byte strings after the first read. */
class DiskFS : FS {
    private val contents = ConcurrentHashMap<String, String>()
    private val missing = Any()
    private fun file(p: String) = File(GoString.toUtf16(p))

    override fun useCaseSensitiveFileNames(): Boolean = true
    override fun fileExists(p0: String): Boolean = file(p0).isFile
    override fun readFile(p0: String): Tuple2<String, Boolean> {
        contents[p0]?.let { return Tuple2(it, true) }
        val f = file(p0)
        if (!f.isFile) return Tuple2("", false)
        var s = String(f.readBytes(), Charsets.ISO_8859_1)
        if (s.length >= 3 && s[0].code == 0xEF && s[1].code == 0xBB && s[2].code == 0xBF) s = s.substring(3)
        contents[p0] = s
        return Tuple2(s, true)
    }
    override fun directoryExists(p0: String): Boolean = file(p0).isDirectory
    override fun getAccessibleEntries(p0: String): Entries {
        val list = file(p0).listFiles()?.sortedBy { it.name } ?: return Entries()
        return Entries(
            files = GoSlice.of(GoElem.STRING, *list.filter { it.isFile }.map { GoString.fromUtf16(it.name) }.toTypedArray()),
            directories = GoSlice.of(GoElem.STRING, *list.filter { it.isDirectory }.map { GoString.fromUtf16(it.name) }.toTypedArray()),
            symlinks = GoMap.make(com.xemantic.typescript.tsgo.runtime.goUnitElem),
        )
    }
    override fun realpath(p0: String): String = p0
    override fun stat(p0: String): FileInfo? = null
    override fun walkDir(p0: String, p1: WalkDirFunc?): GoError? = error("walkDir not supported")
    override fun writeFile(p0: String, p1: String): GoError? = error("read-only")
    override fun appendFile(p0: String, p1: String): GoError? = error("read-only")
    override fun remove(p0: String): GoError? = error("read-only")
    override fun chtimes(p0: String, p1: Time, p2: Time): GoError? = error("read-only")
}

private class CheckBench(val config: File, val warmup: Int, val iters: Int, val libCache: Boolean) {

    fun run() {
        TsgoPort.init()
        val disk = DiskFS()
        val cwd = GoString.fromUtf16(config.parentFile.path)
        val configName = GoString.fromUtf16(config.path)
        println("project=$config warmup=$warmup iters=$iters libcache=$libCache java=${System.getProperty("java.version")}")
        val totals = ArrayList<Double>()
        var checksum = 0L
        for (i in 0 until warmup + iters) {
            val t0 = System.nanoTime()
            val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(disk)
            val inner = com.xemantic.typescript.tsgo.compiler.newCompilerHost(cwd, fs, com.xemantic.typescript.tsgo.bundled.libPath(), null, null)!!
            val host = if (libCache) DiagParityTest.CachedHost(inner) else inner
            val (parsed, configDiags) = com.xemantic.typescript.tsgo.tsoptions.getParsedCommandLineOfConfigFile(configName, null, null, host, null)
            val t1 = System.nanoTime()
            val program = com.xemantic.typescript.tsgo.compiler.newProgram(ProgramOptions(host = host, config = parsed, singleThreaded = TSTrue))!!
            val t2 = System.nanoTime()
            val ctx = com.xemantic.typescript.tsgo.go.context.background()
            var n = configDiags.len
            fun add(ds: GoSlice<Diagnostic?>) { n += ds.len }
            add(program.getConfigFileParsingDiagnostics())
            add(program.getProgramDiagnostics())
            add(program.getSyntacticDiagnostics(ctx, null))
            add(program.getGlobalDiagnostics(ctx))
            val t3 = System.nanoTime()
            add(program.getSemanticDiagnostics(ctx, null))
            val t4 = System.nanoTime()
            val ms = (t4 - t0) / 1e6
            checksum = checksum * 31 + n
            val tag = if (i < warmup) "warm" else "meas"
            println(
                "iter ${i + 1} $tag total_ms=%.1f config_ms=%.1f program_ms=%.1f syn_ms=%.1f check_ms=%.1f files=%d diags=%d"
                    .format(ms, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, (t4 - t3) / 1e6, program.getSourceFiles().len, n),
            )
            if (i >= warmup) totals += ms
        }
        val s = totals.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        println("RESULT median_ms=%.1f min_ms=%.1f max_ms=%.1f checksum=$checksum".format(median, s.min(), s.max()))
    }
}
