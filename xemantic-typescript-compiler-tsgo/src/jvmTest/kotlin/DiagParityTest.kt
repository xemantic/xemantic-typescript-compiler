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
import com.xemantic.typescript.tsgo.ast.equalDiagnosticsNoRelatedInfo
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.go.testing.T
import com.xemantic.typescript.tsgo.harness.HarnessRun
import com.xemantic.typescript.tsgo.runtime.GoString
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

/**
 * (TSGO.2) the diagnostics differential (docs/goport-diag-oracle.md § 4, the PREFERRED route): for every
 * configuration the oracle materialized, the PORTED compiler test harness (`com.xemantic.typescript.tsgo.harness`
 * over the generated `testrunner`/`harnessutil`) reads the RAW case (`typescript-repo/tests/cases/<case>`),
 * enumerates its configurations, prepares and compiles the named one check-only, and the result is written to
 * `build/goport/diag-kotlin/<case>/<variation>.jsonl` in the oracle's format. Before any diagnostic is
 * compared, the harness's derived state is cross-checked against the materialized `case.json` (current
 * directory, root files, every file's path/role/hash, symlinks, the final compiler options) — a cheap first
 * gate that the port prepared the SAME program the oracle compiled. Then the test grades every configuration
 * against `build/goport/diag-oracle` ([gate]) and FAILS unless all are equal. Opt-in: `TSGO_DIAG=1`
 * (`TSGO_DIAG_LIMIT=n`, `TSGO_DIAG_FILTER=<substring of the case path>`, `TSGO_DIAG_INJECT=<case>/<variation>`
 * to perturb one result and watch the gate go red).
 */
class DiagParityTest {

    private val root = File("..").absoluteFile.normalize()
    private val cases = File(root, "build/goport/diag-cases")
    private val casesRoot = File(root, "typescript-repo/tests/cases")
    private val out = File(root, "build/goport/diag-kotlin")

    // ---------------------------------------------------------------- one configuration, through the ported harness

    private fun runConfiguration(case: String, variation: String): List<String> {
        val filename = File(casesRoot, case).path
        val content = com.xemantic.typescript.tsgo.harness.decodeCaseText(String(File(filename).readBytes(), Charsets.ISO_8859_1))
        val t = T("$case $variation")
        val configs = com.xemantic.typescript.tsgo.harness.caseConfigurations(content, t)
        val found = configs.filter { com.xemantic.typescript.tsgo.harness.variationName(it) == variation }
        check(found.size == 1) { // (a configuration may be null: a case with no directives at all)
            "variation not found: $variation (the harness enumerates ${configs.map { com.xemantic.typescript.tsgo.harness.variationName(it) }})"
        }
        val named = found[0]
        val run = com.xemantic.typescript.tsgo.harness.runConfiguration(filename, content, named, t)
        try {
            crossCheck(File(cases, "$case/$variation/case.json"), content, run)
            val check = run.check
            return (0 until check.diagnostics.len).map { i ->
                val d = check.diagnostics[i]!!
                val phase = check.phase.lookup(d).let { (p, ok) -> if (ok) p else null }
                    ?: check.phase.keysSnapshot().firstOrNull { equalDiagnosticsNoRelatedInfo(it, d) }?.let { check.phase[it] }
                    ?: "unknown"
                line(d, phase)
            }
        } finally {
            com.xemantic.typescript.tsgo.harness.purgeSourceFileCache()
        }
    }

    /** The harness's derived state against the oracle's `case.json` (docs/goport-diag-oracle.md § 2). */
    @Suppress("UNCHECKED_CAST")
    private fun crossCheck(caseJson: File, content: String, run: HarnessRun) {
        val cj = Json(caseJson.readText()).value() as Map<String, Any?>
        val prep = run.prepared
        val check = run.check
        val problems = ArrayList<String>()
        fun expect(what: String, want: Any?, got: Any?) {
            if (want != got) problems += "$what: case.json=$want harness=$got"
        }
        expect("caseSha256", cj["caseSha256"], sha(content))
        expect("currentDirectory", cj["currentDirectory"], u(prep.currentDirectory))
        expect("rootFiles", cj["rootFiles"], (0 until check.programFileNames.len).map { u(check.programFileNames[it]) })
        // files: last content wins per path, in first-write order (the oracle's writeProject)
        val files = LinkedHashMap<String, Pair<String, String>>()
        fun norm(name: String) = u(com.xemantic.typescript.tsgo.tspath.getNormalizedAbsolutePath(name, prep.currentDirectory))
        for (i in 0 until prep.tsConfigFiles.len) prep.tsConfigFiles[i]!!.let { files[norm(it.unitName)] = "tsconfig" to sha(it.content) }
        for (i in 0 until prep.toBeCompiled.len) prep.toBeCompiled[i]!!.let { files[norm(it.unitName)] = "root" to sha(it.content) }
        for (i in 0 until prep.otherFiles.len) prep.otherFiles[i]!!.let { files[norm(it.unitName)] = "other" to sha(it.content) }
        for (p in check.libDirFiles.keysSnapshot().sorted()) files[u(p)] = "testlib" to sha(check.libDirFiles[p])
        expect("files", (cj["files"] as List<Map<String, Any?>>).map { listOf(it["path"], it["role"], it["sha256"]) },
            files.map { (p, rs) -> listOf(p, rs.first, rs.second) })
        val symlinks = prep.symlinks.keysSnapshot().associate { norm(it) to norm(prep.symlinks[it]) }
        expect("symlinks", cj["symlinks"], symlinks)
        // The final options: case.json holds them in tsgo's own JSON form. Decoded into the ported struct and
        // re-encoded by the ported marshaller, they must read exactly as the harness's options do through the
        // same marshaller (the oracle's own check is a reflect.DeepEqual round trip, diags.go writeProject; the
        // reflect shim cannot see into a non-reflect struct such as `collections.OrderedMap`, json can).
        val want = com.xemantic.typescript.tsgo.core.CompilerOptions()
        val wantJson = caseJson.readText().let { t -> // the raw "compilerOptions" object, as written
            val i = t.indexOf("\"compilerOptions\"")
            val o = t.indexOf('{', i)
            var depth = 0
            var j = o
            var inString = false
            while (true) {
                val c = t[j]
                when {
                    inString -> if (c == '\\') j++ else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '{' -> depth++
                    c == '}' -> { depth--; if (depth == 0) break }
                }
                j++
            }
            t.substring(o, j + 1)
        }
        val err = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.unmarshal(
            com.xemantic.typescript.tsgo.runtime.goStringToBytes(GoString.fromUtf16(wantJson)), want,
        )
        check(err == null) { "case.json compilerOptions: ${err!!.error()}" }
        fun encode(o: Any?): String {
            val (b, e) = com.xemantic.typescript.tsgo.json.marshal(o, com.xemantic.typescript.tsgo.runtime.GoElem.ref<com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.Options?>().nilSlice)
            check(e == null) { "marshal compilerOptions: ${e!!.error()}" }
            return u(com.xemantic.typescript.tsgo.runtime.goBytesToString(b))
        }
        // compared as JSON VALUES: case.json's object members are key-sorted (the materializer re-serializes it),
        // so a map option's (`paths`) member order is not part of what it records.
        expect("compilerOptions", Json(encode(want)).value(), Json(encode(check.options)).value())
        check(problems.isEmpty()) { "harness state differs from case.json:\n  " + problems.joinToString("\n  ") }
    }

    /** A Go byte string as Kotlin text. */
    private fun u(s: String): String = GoString.toUtf16(s)

    /** sha256 of a Go byte string (its bytes, as the oracle hashes them). */
    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }

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
            println("DiagParityTest: skipped (set TSGO_DIAG=1; needs build/goport/diag-cases and diag-oracle from scripts/tsgo-diag-cases.py / tsgo-diag-oracle.py)")
            return
        }
        // The manifest's entries in order (a regex over the 4.8 MB file: the case JSON is what the shim decodes).
        val manifest = File(root, "build/goport/diag-oracle/manifest.json").readText()
        val limit = System.getenv("TSGO_DIAG_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val filter = System.getenv("TSGO_DIAG_FILTER")
        val entries = Regex(""""case":\s*"([^"]+)",\s*"variation":\s*"([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }.filter { filter == null || it.first.contains(filter) }.take(limit).toList()
        TsgoPort.init()
        com.xemantic.typescript.tsgo.harness.typeScriptSubmodule = File(root, "build/goport/ts-submodule").also {
            check(File(it, "tests/lib").isDirectory) { "no /.lib test libraries at $it/tests/lib (scripts/tsgo-diag-cases.py extracts them)" }
        }.path
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
                try { result = runConfiguration(case, variation) } catch (t: Throwable) { error = t }
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
        gate(entries)
    }

    // ---------------------------------------------------------------- the gate (scripts/tsgo-diag-compare.py's rule)

    /**
     * Grades every configuration this run covered against `build/goport/diag-oracle`, with the rule of
     * `scripts/tsgo-diag-compare.py` (docs/goport-diag-oracle.md § 3): EQUAL when both files hold the same
     * SEQUENCE of parsed JSON values (every key, `related` and `phase` included); a missing actual file (a
     * crashed configuration) is `missing`, never "no diagnostics". Fails the test unless every configuration
     * is equal, printing the first differences. `TSGO_DIAG_INJECT=<case>/<variation>` perturbs one actual
     * result before grading — the positive control that this gate can go red.
     */
    private fun gate(entries: List<Pair<String, String>>) {
        val oracle = File(root, "build/goport/diag-oracle")
        check(Regex(""""complete":\s*true""").containsMatchIn(File(oracle, "manifest.json").readText())) { "the oracle manifest is incomplete" }
        val inject = System.getenv("TSGO_DIAG_INJECT")
        var equal = 0
        var missing = 0
        val differ = ArrayList<String>()
        for ((case, variation) in entries) {
            val key = "$case/$variation"
            val wantFile = File(oracle, "$key.jsonl")
            check(wantFile.isFile) { "oracle file missing: $wantFile" }
            val gotFile = File(out, "$key.jsonl")
            if (!gotFile.isFile) { missing++; if (differ.size < 10) differ += "MISSING (crashed) $key"; continue }
            val want = jsonLines(wantFile.readText())
            var got = jsonLines(gotFile.readText())
            if (key == inject) got = got + mapOf("injected" to true)
            if (got == want) { equal++; continue }
            if (differ.size < 10) {
                val i = (0 until minOf(got.size, want.size)).firstOrNull { got[it] != want[it] } ?: minOf(got.size, want.size)
                differ += "DIFFER $key oracle=${want.size} actual=${got.size} first difference at #$i\n" +
                    "   oracle: ${want.getOrNull(i)?.toString()?.take(300) ?: "<end>"}\n   actual: ${got.getOrNull(i)?.toString()?.take(300) ?: "<end>"}"
            }
        }
        val unequal = entries.size - equal
        println("DiagParityTest gate: ${entries.size} configurations: equal $equal, differ ${unequal - missing}, missing $missing")
        if (unequal != 0) {
            kotlin.test.fail("diagnostics differential: $unequal of ${entries.size} configurations not equal to tsgo\n" + differ.joinToString("\n"))
        }
    }

    /** One parsed JSON value per non-blank line. */
    private fun jsonLines(text: String): List<Any?> = text.lineSequence().filter { it.isNotBlank() }.map { Json(it).value() }.toList()

    /** A minimal JSON reader: objects to maps, arrays to lists, numbers to Double — value equality as Python's `==`. */
    private class Json(private val s: String) {
        private var i = 0
        fun value(): Any? {
            val v = read()
            ws()
            check(i == s.length) { "trailing JSON at $i" }
            return v
        }
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun read(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; check(i > st) { "bad JSON char '$c' at $st" }; s.substring(st, i).toDouble() }
            }
        }
        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            do {
                ws()
                val k = str()
                ws()
                check(s[i++] == ':')
                m[k] = read()
                ws()
            } while (s[i++] == ',')
            return m
        }
        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            do {
                l += read()
                ws()
            } while (s[i++] == ',')
            return l
        }
        private fun str(): String {
            check(s[i++] == '"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
