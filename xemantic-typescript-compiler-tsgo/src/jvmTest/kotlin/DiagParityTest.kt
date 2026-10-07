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
import com.xemantic.typescript.tsgo.ast.SourceFile
import com.xemantic.typescript.tsgo.ast.SourceFileParseOptions
import com.xemantic.typescript.tsgo.ast.equalDiagnosticsNoRelatedInfo
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.compiler.CompilerHost
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.core.CompilerOptions
import com.xemantic.typescript.tsgo.core.ParsedOptions
import com.xemantic.typescript.tsgo.core.TSFalse
import com.xemantic.typescript.tsgo.core.TSTrue
import com.xemantic.typescript.tsgo.core.clone
import com.xemantic.typescript.tsgo.core.getEmitDeclarations
import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.io.fs.WalkDirFunc
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoBox
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import com.xemantic.typescript.tsgo.tsoptions.ParsedCommandLine
import com.xemantic.typescript.tsgo.vfs.Entries
import com.xemantic.typescript.tsgo.vfs.FS
import java.io.File
import java.util.IdentityHashMap
import kotlin.test.Test

/**
 * (TSGO.2) the diagnostics differential (docs/goport-diag-oracle.md § 4, fallback route): drives the
 * PORTED compiler over every configuration the oracle materialized (`build/goport/diag-cases`), writes
 * `build/goport/diag-kotlin/<case>/<variation>.jsonl` in the oracle's format; then
 * `scripts/tsgo-diag-compare.py build/goport/diag-kotlin` grades it. Opt-in: `TSGO_DIAG=1`
 * (`TSGO_DIAG_LIMIT=n`, `TSGO_DIAG_FILTER=<substring of the case path>`).
 */
class DiagParityTest {

    private val root = File("..").absoluteFile.normalize()
    private val cases = File(root, "build/goport/diag-cases")
    private val out = File(root, "build/goport/diag-kotlin")

    // ---------------------------------------------------------------- json via the ported shim

    private fun parseJson(text: ByteArray): Any? {
        val box = GoBox<Any?>(null)
        val err = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.unmarshal(goStringToBytes(String(text, Charsets.ISO_8859_1)), box)
        check(err == null) { "json: ${err!!.error()}" }
        return box.value
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(): GoMap<String, Any?> = this as GoMap<String, Any?>
    private fun Any?.str(): String = GoString.toUtf16(this as String)
    private fun GoMap<String, Any?>.list(k: String): List<Any?> = (this[k] as? GoSlice<*>)?.let { s -> (0 until s.len).map { s[it] } } ?: emptyList()

    // ---------------------------------------------------------------- the harness FS (vfstest.FromMap + vfs/internal decoding)

    class CaseFS(private val files: Map<String, String>, private val symlinks: Map<String, String>, private val caseSensitive: Boolean) : FS {
        private val dirs: Set<String> = (files.keys + symlinks.keys).flatMap { f ->
            // Every ancestor directory; a Windows-style root (`A:`) is spelled `A:/` as tspath does.
            f.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.map { if (it.length == 2 && it[1] == ':') "$it/" else it.ifEmpty { "/" } }
        }.toSet() + "/"

        private fun canon(p: String) = if (caseSensitive) p else p.lowercase()
        private val byCanon = files.keys.associateBy { canon(it) }
        private val dirsByCanon = dirs.associateBy { canon(it) }

        /** Follows symlinks (whole-path prefixes), as the harness's MapFS does. */
        private fun resolve(p: String): String {
            var cur = p
            repeat(40) {
                val hit = symlinks.entries.firstOrNull { (src, _) -> cur == src || cur.startsWith("$src/") } ?: return cur
                cur = hit.value + cur.substring(hit.key.length)
            }
            return cur
        }

        override fun useCaseSensitiveFileNames(): Boolean = caseSensitive
        override fun fileExists(p0: String): Boolean = canon(resolve(p0)) in byCanon
        override fun readFile(p0: String): Tuple2<String, Boolean> {
            val k = byCanon[canon(resolve(p0))] ?: return Tuple2("", false)
            return Tuple2(decode(files.getValue(k)), true)
        }
        override fun directoryExists(p0: String): Boolean = canon(resolve(p0)) in dirsByCanon || p0 in symlinks && canon(resolve(p0)) in dirsByCanon
        override fun getAccessibleEntries(p0: String): Entries {
            val dir = dirsByCanon[canon(resolve(p0))] ?: return Entries()
            val prefix = if (dir.endsWith("/")) dir else "$dir/"
            val names = (files.keys + dirs + symlinks.keys).filter { it.startsWith(prefix) && it.length > prefix.length && '/' !in it.substring(prefix.length) }.toSortedSet()
            val fs = names.filter { fileExists(it) }.map { it.substring(prefix.length) }
            val ds = names.filter { !fileExists(it) && directoryExists(it) }.map { it.substring(prefix.length) }
            return Entries(
                files = GoSlice.of(GoElem.STRING, *fs.toTypedArray()),
                directories = GoSlice.of(GoElem.STRING, *ds.toTypedArray()),
                symlinks = GoMap.make<String, Unit>(com.xemantic.typescript.tsgo.runtime.goUnitElem).also { m ->
                    names.filter { it in symlinks }.forEach { m[it.substring(prefix.length)] = Unit }
                },
            )
        }
        override fun realpath(p0: String): String {
            val r = resolve(p0)
            return byCanon[canon(r)] ?: dirsByCanon[canon(r)] ?: r
        }
        override fun stat(p0: String): FileInfo? = null
        override fun walkDir(p0: String, p1: WalkDirFunc?): GoError? = error("walkDir not supported")
        override fun writeFile(p0: String, p1: String): GoError? = null
        override fun appendFile(p0: String, p1: String): GoError? = null
        override fun remove(p0: String): GoError? = null
        override fun chtimes(p0: String, p1: Time, p2: Time): GoError? = null

        /** vfs/internal.decodeBytes: a UTF-16 BOM decodes; a UTF-8 BOM is stripped. */
        private fun decode(s: String): String {
            if (s.length >= 2 && s[0].code == 0xFF && s[1].code == 0xFE) return GoString.fromUtf16(utf16(s.substring(2), little = true))
            if (s.length >= 2 && s[0].code == 0xFE && s[1].code == 0xFF) return GoString.fromUtf16(utf16(s.substring(2), little = false))
            if (s.length >= 3 && s[0].code == 0xEF && s[1].code == 0xBB && s[2].code == 0xBF) return s.substring(3)
            return s
        }

        private fun utf16(s: String, little: Boolean): String = buildString {
            var i = 0
            while (i + 1 < s.length) {
                val a = s[i].code
                val b = s[i + 1].code
                append((if (little) (b shl 8) or a else (a shl 8) or b).toChar())
                i += 2
            }
        }
    }

    /** The harness's cachedCompilerHost: parsed source files shared across programs by (options, text, kind). */
    class CachedHost(private val inner: CompilerHost) : CompilerHost by inner {
        override fun getSourceFile(p0: SourceFileParseOptions): SourceFile? {
            val read = inner.fs()!!.readFile(p0.fileName)
            if (!read.second) return null
            val kind = com.xemantic.typescript.tsgo.core.getScriptKindFromFileName(p0.fileName)
            // Only the bundled libs are shared across configurations (the harness caches every file; the
            // test JVM's heap does not hold 6,318 cases' trees).
            if (!p0.fileName.startsWith("bundled:")) return com.xemantic.typescript.tsgo.parser.parseSourceFile(p0, read.first, kind)
            return cache.getOrPut(Key(p0.goCopy(), read.first, kind.value)) {
                com.xemantic.typescript.tsgo.parser.parseSourceFile(p0, read.first, kind)!!
            }
        }

        /** The harness's SourceFileCacheKey: Go compares the options struct by value. */
        class Key(val opts: SourceFileParseOptions, val text: String, val kind: Int) {
            override fun equals(other: Any?): Boolean = other is Key && kind == other.kind && text == other.text && opts.goEquals(other.opts)
            override fun hashCode(): Int = opts.goHash() * 31 + text.hashCode() * 7 + kind
        }

        companion object {
            val cache = java.util.concurrent.ConcurrentHashMap<Key, SourceFile>()
        }
    }

    // ---------------------------------------------------------------- one configuration

    private fun runConfiguration(dir: File): List<String> {
        val case = parseJson(File(dir, "case.json").readBytes()).obj()
        val harness = case["harnessOptions"].obj()
        val caseSensitive = harness["UseCaseSensitiveFileNames"] as Boolean
        val files = LinkedHashMap<String, String>()
        for (f in case.list("files")) {
            val fo = f.obj()
            if (fo["role"].str() == "tsconfig") continue
            val path = fo["path"].str()
            files[path] = String(File(dir, "vfs/" + path.removePrefix("/")).readBytes(), Charsets.ISO_8859_1)
        }
        val symlinks = (case["symlinks"] as? GoMap<*, *>)?.let { m -> m.keysSnapshot().associate { k -> (k as String).let(GoString::toUtf16) to (m.obj()[k]).str() } } ?: emptyMap()
        val currentDirectory = case["currentDirectory"].str()
        // compilerOptions: tsgo's own json form, decoded by the ported json shim into the ported struct.
        val options = CompilerOptions()
        val (bytes, merr) = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.marshal(case["compilerOptions"])
        check(merr == null)
        val uerr = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.unmarshal(bytes, options)
        check(uerr == null) { "compilerOptions: ${uerr!!.error()}" }
        val pre = options.clone()!!
        pre.traceResolution = TSFalse
        val roots = case.list("rootFiles").map { it.str() }
        val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(CaseFS(files.mapKeys { GoString.fromUtf16(it.key) }, symlinks, caseSensitive))
        val host = CachedHost(com.xemantic.typescript.tsgo.compiler.newCompilerHost(GoString.fromUtf16(currentDirectory), fs, com.xemantic.typescript.tsgo.bundled.libPath(), null, null)!!)
        val config = ParsedCommandLine(parsedConfig = ParsedOptions(compilerOptions = pre, fileNames = GoSlice.of(GoElem.STRING, *roots.map { GoString.fromUtf16(it) }.toTypedArray())))
        val program = com.xemantic.typescript.tsgo.compiler.newProgram(ProgramOptions(host = host, config = config, singleThreaded = TSTrue))!!
        val ctx = com.xemantic.typescript.tsgo.go.context.background()
        val phases = ArrayList<Pair<String, GoSlice<Diagnostic?>>>()
        phases += "config" to program.getConfigFileParsingDiagnostics()
        phases += "program" to program.getProgramDiagnostics()
        phases += "syntactic" to program.getSyntacticDiagnostics(ctx, null)
        phases += "semantic" to program.getSemanticDiagnostics(ctx, null)
        phases += "global" to program.getGlobalDiagnostics(ctx)
        if (program.options().getEmitDeclarations()) phases += "declaration" to program.getDeclarationDiagnostics(ctx, null)
        if (harness["CaptureSuggestions"] == true) phases += "suggestion" to program.getSuggestionDiagnostics(ctx, null)
        val phaseOf = IdentityHashMap<Diagnostic, String>()
        val all = GoSlice.make(GoElem.ref<Diagnostic?>(), 0)
        var acc = all
        for ((name, ds) in phases) {
            for (i in 0 until ds.len) ds[i]?.let { phaseOf.putIfAbsent(it, name) }
            acc = acc.appendSlice(ds)
        }
        val sorted = com.xemantic.typescript.tsgo.compiler.sortAndDeduplicateDiagnostics(acc)
        return (0 until sorted.len).map { i ->
            val d = sorted[i]!!
            val phase = phaseOf[d] ?: phaseOf.entries.firstOrNull { equalDiagnosticsNoRelatedInfo(it.key, d) }?.value ?: "?"
            line(d, phase)
        }
    }

    // ---------------------------------------------------------------- the oracle's line format (§ 3)

    private fun q(s: String?): String = if (s == null) "null" else buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private fun flatten(d: Diagnostic): String = buildString {
        append(GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale())))
        fun chain(c: Diagnostic, level: Int) {
            append("\n").append("  ".repeat(level)).append(GoString.toUtf16(c.localize(com.xemantic.typescript.tsgo.locale.Locale())))
            for (j in 0 until c.messageChain.len) chain(c.messageChain[j]!!, level + 1)
        }
        for (j in 0 until d.messageChain.len) chain(d.messageChain[j]!!, 1)
    }

    private fun fields(d: Diagnostic): String {
        val file = d.file?.let { GoString.toUtf16(it.fileName()) }
        return "\"file\":${q(file)},\"start\":${d.loc.pos.value},\"length\":${d.loc.end.value - d.loc.pos.value},\"code\":${d.code}," +
            "\"category\":${q(GoString.toUtf16(d.category.name()))},\"text\":${q(flatten(d))}"
    }

    private fun line(d: Diagnostic, phase: String): String {
        val rel = (0 until d.relatedInformation.len).joinToString(",") { "{" + fields(d.relatedInformation[it]!!) + "}" }
        return "{" + fields(d) + ",\"related\":[$rel],\"phase\":${q(phase)},\"skippedOnNoEmit\":${d.skippedOnNoEmit}}"
    }

    // ---------------------------------------------------------------- the run

    @Test
    fun `the ported compiler's diagnostics per conformance configuration`() {
        if (System.getenv("TSGO_DIAG").isNullOrEmpty()) {
            println("DiagParityTest: skipped (set TSGO_DIAG=1; needs build/goport/diag-cases from scripts/tsgo-diag-cases.py)")
            return
        }
        // The manifest's entries in order (a regex over the 4.8 MB file: the case JSON is what the shim decodes).
        val manifest = File(root, "build/goport/diag-oracle/manifest.json").readText()
        val limit = System.getenv("TSGO_DIAG_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val filter = System.getenv("TSGO_DIAG_FILTER")
        val entries = Regex(""""case":\s*"([^"]+)",\s*"variation":\s*"([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }.filter { filter == null || it.first.contains(filter) }.take(limit).toList()
        TsgoPort.init()
        var ok = 0
        var crashed = 0
        val crashes = LinkedHashMap<String, Int>()
        val t0 = System.nanoTime()
        File(out, "crashes.txt").delete()
        val timeoutMs = (System.getenv("TSGO_DIAG_TIMEOUT") ?: "60").toLong() * 1000
        for ((case, variation) in entries) {
            val dst = File(out, "$case/$variation.jsonl")
            dst.parentFile.mkdirs()
            // One deep-stack thread per configuration, with a deadline: a ported loop that does not
            // terminate (a porting bug) costs that configuration, not the run. Such a thread cannot be
            // stopped on the JVM; it is a daemon and keeps its core until the run ends.
            var result: List<String>? = null
            var error: Throwable? = null
            val th = Thread(null, {
                try { result = runConfiguration(File(cases, "$case/$variation")) } catch (t: Throwable) { error = t }
            }, "tsgo-diag", 1L shl 30)
            th.isDaemon = true
            th.start()
            th.join(timeoutMs)
            val t = if (th.isAlive) IllegalStateException("timeout after ${timeoutMs / 1000} s at ${th.stackTrace.take(4).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }}") else error
            if (t == null) {
                dst.writeText(result!!.joinToString("") { "$it\n" })
                ok++
            } else {
                dst.delete()
                crashed++
                val where = t.stackTrace.firstOrNull { it.className.startsWith("com.xemantic.typescript.tsgo") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
                val key = "${t::class.simpleName}: ${t.message?.take(140)} @ $where"
                crashes[key] = (crashes[key] ?: 0) + 1
                File(out, "crashes.txt").appendText("== $case/$variation: $key\n" + (if (crashes[key] == 1) t.stackTraceToString().lines().take(30).joinToString("\n") + "\n" else ""))
            }
        }
        println("DiagParityTest: ${entries.size} configurations, $ok written, $crashed crashed (${(System.nanoTime() - t0) / 1_000_000} ms)")
        crashes.entries.sortedByDescending { it.value }.take(25).forEach { (k, v) -> println("  $v × $k") }
    }
}
