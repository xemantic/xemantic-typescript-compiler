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

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * (TSGO.4-a) THE LANGUAGE-SERVICE GATE (docs/goport-ls.md § 4): every request the shipped tsgo LANGUAGE
 * SERVER (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`) answered in `build/goport/ls-oracle`
 * (scripts/tsgo-ls-oracle.py: hover, definition, references, completion, pull diagnostics over tsc's 78
 * sources and 200 conformance projects) is replayed against the PORTED language service in process
 * ([TsgoLanguageService.request], the server's handler for the method over `ls.LanguageService`), with
 * the client the recording described (its `initialize` params and `workspace/configuration` answer).
 * The result JSON must equal the server's: objects compared as maps (field order free), a completion list's
 * items as a multiset (tsgo's own order is a Go map's, see [itemsAsSet]), everything else exactly. An error
 * must be an error on both sides.
 *
 * Opt-in: `TSGO_LS=1` (`TSGO_LS_LIMIT=n` replays the first n projects, `TSGO_LS_FILTER=s` those whose name
 * contains s). FAILS on any differing or crashing request. Positive control: `TSGO_LS_INJECT=<project>:<line>`
 * corrupts that recorded response, which must read red.
 */
class LsParityTest {

    private val root = File("..").absoluteFile.normalize()

    @Test
    fun `the ported language service answers as tsgo's language server`() {
        if (System.getenv("TSGO_LS") == null) {
            println("LsParityTest: skipped (set TSGO_LS=1; record with scripts/tsgo-ls-oracle.py)")
            return
        }
        val dir = File(root, "build/goport/ls-oracle")
        val manifest = File(dir, "manifest.tsv").readLines().filter { it.isNotBlank() }
        val filter = System.getenv("TSGO_LS_FILTER")?.takeIf { it.isNotBlank() }
        val limit = System.getenv("TSGO_LS_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val inject = System.getenv("TSGO_LS_INJECT")?.takeIf { it.isNotBlank() }?.split(':')?.let { it[0] to it[1].toInt() }
        val projects = manifest.map { it.split('\t')[0] }.filter { filter == null || filter in it }.take(limit)
        val out = File(root, "build/goport/ls-kotlin").apply { mkdirs() }
        val byMethod = java.util.TreeMap<String, IntArray>() // equal, differ, crash
        val report = StringBuilder()
        var equal = 0
        var differ = 0
        var crash = 0
        var openFailed = 0
        val t0 = System.nanoTime()
        for (name in projects) {
            GZIPInputStream(File(dir, "$name.jsonl.gz").inputStream()).bufferedReader().useLines { seq ->
                val lines = seq.iterator()
                val header = J.parse(lines.next()) as Map<*, *>
                // The server's project for a file is the configured project of the NEAREST tsconfig.json above
                // it (tsc's sources hold a nested `src/compiler/tsconfig.json`, whose `extends` is missing).
                val services = HashMap<String, TsgoLanguageService?>()
                fun serviceFor(params: Any?): TsgoLanguageService? {
                    val uri = ((params as Map<*, *>)["textDocument"] as Map<*, *>)["uri"] as String
                    val config = nearestConfig(java.net.URI(uri).path) ?: header["config"] as String
                    return services.getOrPut(config) {
                        try {
                            TsgoLanguageService.open(
                                config,
                                libDirectory = header["libDir"] as String,
                                initializeParams = J.write(header["initialize"]),
                                configuration = J.write(header["configuration"]),
                            )
                        } catch (t: Throwable) {
                            openFailed++
                            report.append("$name: OPEN FAILED $config ${t.stackTraceToString().lines().take(8).joinToString("\n  ")}\n")
                            null
                        }
                    }
                }
                var lineNo = 1
                for (line in lines) {
                    lineNo++
                    val rec = J.parse(line) as Map<*, *>
                    val method = rec["m"] as String
                    val counts = byMethod.getOrPut(method) { IntArray(3) }
                    val params = J.write(rec["p"])
                    val want: Any? = if (inject != null && inject.first == name && inject.second == lineNo) "injected" else rec["r"]
                    val service = serviceFor(rec["p"]) ?: continue
                    val verdict: String? = try {
                        val got = J.parse(service.request(method, params))
                        when {
                            "e" in rec -> "tsgo errs (${J.write(rec["e"])}), the port answers ${J.write(got).take(300)}"
                            else -> compare(if (method == COMPLETION) itemsAsSet(want) else want, if (method == COMPLETION) itemsAsSet(got) else got, "$")
                        }
                    } catch (e: TsgoApiException) {
                        if ("e" in rec) null else "port errs (${e.message}), tsgo answers ${J.write(want).take(300)}"
                    } catch (t: Throwable) {
                        counts[2]++
                        crash++
                        if (crash <= 30) report.append("$name:$lineNo $method $params CRASH ${t.stackTraceToString().lines().take(14).joinToString("\n  ")}\n")
                        continue
                    }
                    if (verdict == null) {
                        counts[0]++
                        equal++
                    } else {
                        counts[1]++
                        differ++
                        if (differ <= 300) report.append("$name:$lineNo $method $params\n  $verdict\n")
                    }
                }
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val summary = buildString {
            appendLine("LsParityTest: ${projects.size} projects, ${equal + differ + crash} requests in ${"%.1f".format(secs)} s: equal $equal, differ $differ, crash $crash, open failed $openFailed")
            for ((m, c) in byMethod) appendLine("  %-28s equal %7d  differ %6d  crash %5d".format(m, c[0], c[1], c[2]))
        }
        File(out, "report.txt").writeText(summary + "\n" + report)
        println(summary)
        if (report.isNotEmpty()) println(report.take(20000))
        check(differ == 0 && crash == 0 && openFailed == 0 && equal > 0) { "LS parity: $differ differ, $crash crash, $openFailed open failed (build/goport/ls-kotlin/report.txt)" }
    }

    /**
     * A completion list's items in a canonical order: tsgo collects them from symbol tables it ITERATES (Go maps
     * are randomly ordered), so two runs of the binary itself answer the same items in different orders
     * (measured: conf-0005 recorded twice). The client orders them by `sortText`; the gate compares the multiset.
     */
    private fun itemsAsSet(v: Any?): Any? {
        val m = v as? Map<*, *> ?: return v
        val items = m["items"] as? List<*> ?: return v
        return LinkedHashMap(m).also { it["items"] = items.sortedBy { item -> J.write(item) } }
    }

    /** The nearest `tsconfig.json` (or `jsconfig.json`) in [path]'s directory or above, as the project system looks it up. */
    private fun nearestConfig(path: String): String? {
        var dir = File(path).parentFile
        while (dir != null) {
            for (n in listOf("tsconfig.json", "jsconfig.json")) File(dir, n).takeIf { it.isFile }?.let { return it.path }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        const val COMPLETION = "textDocument/completion"
    }

    /** The first difference between [want] and [got] (maps compared by key set, then per key), or null. */
    private fun compare(want: Any?, got: Any?, at: String): String? {
        if (want is Map<*, *> && got is Map<*, *>) {
            if (want.keys != got.keys) return "$at: keys ${want.keys} vs ${got.keys}"
            for ((k, wv) in want) compare(wv, got[k], "$at.$k")?.let { return it }
            return null
        }
        if (want is List<*> && got is List<*>) {
            if (want.size != got.size) return "$at: ${want.size} elements vs ${got.size}: tsgo ${J.write(want).take(400)} vs port ${J.write(got).take(400)}"
            for (i in want.indices) compare(want[i], got[i], "$at[$i]")?.let { return it }
            return null
        }
        return if (want == got) null else "$at: tsgo ${J.write(want).take(300)} vs port ${J.write(got).take(300)}"
    }

    /** A small JSON reader/writer: objects as LinkedHashMap, arrays as List, numbers as Double, strings decoded. */
    private object J {
        fun parse(s: String): Any? = R(s).run { val v = value(); ws(); check(i == s.length) { "trailing JSON at $i" }; v }

        fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

        private fun write(v: Any?, b: StringBuilder) {
            when (v) {
                null -> b.append("null")
                is Boolean -> b.append(v)
                is Double -> if (v == Math.rint(v) && Math.abs(v) < 1e15) b.append(v.toLong()) else b.append(v)
                is String -> {
                    b.append('"')
                    for (c in v) when {
                        c == '"' -> b.append("\\\"")
                        c == '\\' -> b.append("\\\\")
                        c < ' ' -> b.append("\\u%04x".format(c.code))
                        else -> b.append(c)
                    }
                    b.append('"')
                }
                is Map<*, *> -> {
                    b.append('{')
                    var first = true
                    for ((k, x) in v) {
                        if (!first) b.append(',')
                        first = false
                        write(k, b)
                        b.append(':')
                        write(x, b)
                    }
                    b.append('}')
                }
                is List<*> -> {
                    b.append('[')
                    v.forEachIndexed { i, x -> if (i > 0) b.append(','); write(x, b) }
                    b.append(']')
                }
                else -> error("unexpected $v")
            }
        }

        private class R(val s: String) {
            var i = 0
            fun ws() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun value(): Any? {
                ws()
                return when (val c = s[i]) {
                    '{' -> {
                        i++
                        val m = LinkedHashMap<String, Any?>()
                        ws()
                        if (s[i] == '}') { i++; return m }
                        while (true) {
                            ws()
                            val k = str()
                            ws(); check(s[i] == ':'); i++
                            m[k] = value()
                            ws()
                            if (s[i] == ',') { i++; continue }
                            check(s[i] == '}'); i++
                            return m
                        }
                        @Suppress("UNREACHABLE_CODE") m
                    }
                    '[' -> {
                        i++
                        val l = ArrayList<Any?>()
                        ws()
                        if (s[i] == ']') { i++; return l }
                        while (true) {
                            l += value()
                            ws()
                            if (s[i] == ',') { i++; continue }
                            check(s[i] == ']'); i++
                            return l
                        }
                        @Suppress("UNREACHABLE_CODE") l
                    }
                    '"' -> str()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> {
                        val st = i
                        if (c == '-') i++
                        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                        s.substring(st, i).toDouble()
                    }
                }
            }

            fun str(): String {
                check(s[i] == '"'); i++
                val b = StringBuilder()
                while (s[i] != '"') {
                    val c = s[i++]
                    if (c != '\\') { b.append(c); continue }
                    when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b')
                        'f' -> b.append('\u000c'); 'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                }
                i++
                return b.toString()
            }
        }
    }
}
