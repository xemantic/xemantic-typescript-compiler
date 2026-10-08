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
 * (TSGO.3-b) THE TYPE-ORACLE GATE (docs/goport-api.md § 4): every request the shipped tsgo binary answered
 * in `build/goport/api-oracle` (scripts/tsgo-api-oracle.py) is replayed against the PORTED API session
 * in process ([TsgoProject.request] → `Session.HandleRequest` → `XtscMarshal`), and the response JSON must
 * be the binary's.
 *
 * Handles (type, symbol and signature ids) are per-process counters, so they are compared as a
 * BIJECTION rather than as numbers: the first time a tsgo id meets a port id at the same place of two
 * responses they are bound, and every later occurrence of either must meet its partner. A request's
 * handle params are translated through the same maps, and the snapshot handle is the port's. Everything
 * else — names, flags, node handles, type strings, booleans, field order, omitted fields — is compared
 * exactly. An error response must be an error on both sides (messages compared with digits masked).
 *
 * Opt-in: `TSGO_API=1` (`TSGO_API_LIMIT=n` replays the first n projects, `TSGO_API_FILTER=s` those whose
 * name contains s). FAILS on any differing or crashing request. Positive control:
 * `TSGO_API_INJECT=<project>:<line>` corrupts that recorded response, which must read red.
 */
class ApiParityTest {

    private val root = File("..").absoluteFile.normalize()

    @Test
    fun `the ported API session answers as tsgo's`() {
        if (System.getenv("TSGO_API") == null) {
            println("ApiParityTest: skipped (set TSGO_API=1; record with scripts/tsgo-api-oracle.py)")
            return
        }
        val dir = File(root, "build/goport/api-oracle")
        val manifest = File(dir, "manifest.tsv").readLines()
        check(manifest.none { it.startsWith("#incomplete") }) { "the API oracle manifest is incomplete" }
        val filter = System.getenv("TSGO_API_FILTER")?.takeIf { it.isNotBlank() }
        val limit = System.getenv("TSGO_API_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val inject = System.getenv("TSGO_API_INJECT")?.takeIf { it.isNotBlank() }?.split(':')?.let { it[0] to it[1].toInt() }
        val projects = manifest.filter { it.isNotBlank() }.map { it.split('\t') }.filter { filter == null || filter in it[0] }.take(limit)
        val out = File(root, "build/goport/api-kotlin").apply { mkdirs() }
        // The recorded answers name the shipped binary's own lib files (it reads them next to its executable).
        val libDir = File(root, "tools/tsgo-7.0.2/lib").canonicalPath
        val byMethod = java.util.TreeMap<String, IntArray>() // equal, differ, crash
        val report = StringBuilder()
        var equal = 0
        var differ = 0
        var crash = 0
        var openFailed = 0
        val t0 = System.nanoTime()
        for ((name, config) in projects.map { it[0] to it[1] }) {
            val project = try {
                TsgoProject.open(config, libDirectory = libDir)
            } catch (t: Throwable) {
                openFailed++
                report.append("$name: OPEN FAILED ${t.stackTraceToString().lines().take(8).joinToString("\n  ")}\n")
                continue
            }
            val ids = IdMaps()
            var lineNo = 0
            GZIPInputStream(File(dir, "$name.jsonl.gz").inputStream()).bufferedReader().useLines { lines ->
                for (line in lines) {
                    lineNo++
                    val rec = Json.parse(line) as Map<*, *>
                    val method = (rec["m"] as Json.Str).value
                    val counts = byMethod.getOrPut(method) { IntArray(3) }
                    // A handle param whose tsgo id never met a port id: an earlier response already differed.
                    val params = try {
                        ids.translateParams(rec["p"], project.snapshotId)
                    } catch (e: Unmapped) {
                        counts[1]++
                        differ++
                        if (differ <= 200) report.append("$name:$lineNo $method unmapped ${e.message} (an earlier response differed)\n")
                        continue
                    }
                    // "r" is the response payload as JSON text (a json.RawMessage would be inlined; the
                    // recorder stores it inline, so re-serialize the parsed value with its raw tokens).
                    val expectedRaw = if ("r" in rec) Json.write(rec["r"]) else null
                    val expectedErr = (rec["e"] as Json.Str?)?.value
                    val wantText = if (inject != null && inject.first == name && inject.second == lineNo) "\"injected\"" else expectedRaw
                    val verdict: String? = try {
                        val got = project.request(method, Json.write(params))
                        when {
                            expectedErr != null -> "tsgo errs ($expectedErr), the port answers $got"
                            else -> ids.compare(Json.parse(wantText!!), Json.parse(got), "$")
                        }
                    } catch (e: TsgoApiException) {
                        // A panic is recovered into `panic: <value>` + a goroutine dump by tsgo's connection
                        // (conn_sync.go) and by the facade: both panicking is agreement (the Go and Kotlin
                        // runtime-error texts and stacks differ by construction).
                        if (expectedErr != null && expectedErr.startsWith("panic: ") && e.panicked) null
                        else if (expectedErr != null && mask(expectedErr) == mask(e.message!!)) null
                        else "port errs (${e.message}), tsgo ${expectedErr?.let { "errs ($it)" } ?: "answers ${wantText?.take(300)}"}"
                    } catch (t: Throwable) {
                        counts[2]++
                        crash++
                        if (crash <= 30) report.append("$name:$lineNo $method CRASH ${t.stackTraceToString().lines().take(12).joinToString("\n  ")}\n")
                        continue
                    }
                    if (verdict == null) {
                        counts[0]++
                        equal++
                    } else {
                        counts[1]++
                        differ++
                        if (differ <= 200) report.append("$name:$lineNo $method ${Json.write(params).take(300)}\n  $verdict\n")
                    }
                }
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val summary = buildString {
            appendLine("ApiParityTest: ${projects.size} projects, ${equal + differ + crash} requests in ${"%.1f".format(secs)} s: equal $equal, differ $differ, crash $crash, open failed $openFailed")
            for ((m, c) in byMethod) appendLine("  %-36s equal %7d  differ %6d  crash %5d".format(m, c[0], c[1], c[2]))
        }
        File(out, "report.txt").writeText(summary + "\n" + report)
        println(summary)
        if (report.isNotEmpty()) println(report.take(20000))
        check(differ == 0 && crash == 0 && openFailed == 0 && equal > 0) { "API parity: $differ differ, $crash crash, $openFailed open failed (build/goport/api-kotlin/report.txt)" }
    }

    private class Unmapped(m: String) : RuntimeException(m)

    private fun mask(s: String) = s.replace(Regex("[0-9]+"), "#")

    /** The tsgo↔port handle bijections, per handle kind. */
    private class IdMaps {
        private val fwd = HashMap<String, MutableMap<String, String>>()
        private val rev = HashMap<String, MutableMap<String, String>>()

        fun bind(kind: String, want: String, got: String): Boolean {
            val f = fwd.getOrPut(kind) { HashMap() }
            val r = rev.getOrPut(kind) { HashMap() }
            val a = f[want]
            val b = r[got]
            if (a == null && b == null) {
                f[want] = got
                r[got] = want
                return true
            }
            return a == got && b == want
        }

        fun port(kind: String, want: Any?): Any? = when (want) {
            is Json.Num -> Json.Num(fwd[kind]?.get(want.text) ?: throw Unmapped("tsgo $kind ${want.text}"))
            is List<*> -> want.map { port(kind, it) }
            else -> want
        }

        /** The recorded params with tsgo's handles replaced by the port's. */
        fun translateParams(p: Any?, snapshot: ULong): Any? {
            val m = (p as Map<*, *>).toMutableMap()
            for ((k, v) in m.entries.toList()) {
                m[k] = when (k) {
                    "snapshot" -> Json.Num(snapshot.toString())
                    "type", "source", "target", "types" -> port("T", v)
                    "symbol", "symbols" -> port("S", v)
                    "signature" -> port("G", v)
                    else -> v
                }
            }
            return m
        }

        private val typeKeys = setOf("id", "target", "objectType", "indexType", "checkType", "extendsType", "baseType", "substConstraint", "freshType", "regularType")
        private val typeListKeys = setOf("typeParameters", "outerTypeParameters", "localTypeParameters", "aliasTypeArguments")

        /** What kind of handle [key] of an object of [shape] holds: "T", "S", "G", "[T]", "[S]", or null. */
        private fun handleKind(shape: Char, key: String): String? = when (shape) {
            'S' -> if (key == "id" || key == "parent" || key == "exportSymbol") "S" else null
            'T' -> when (key) {
                in typeKeys -> "T"
                in typeListKeys -> "[T]"
                "aliasSymbol", "symbol" -> "S"
                else -> null
            }
            'G' -> when (key) {
                "id", "target" -> "G"
                "typeParameters" -> "[T]"
                "parameters" -> "[S]"
                "thisParameter" -> "S"
                else -> null
            }
            else -> null
        }

        /** A response object's shape: a SymbolResponse names itself, a TypeResponse always carries `value`. */
        private fun shapeOf(m: Map<*, *>): Char = when {
            "name" in m && "checkFlags" in m -> 'S'
            "value" in m -> 'T'
            "id" in m && "flags" in m -> 'G'
            else -> '?'
        }

        /** null when [want] (tsgo) and [got] (port) agree, else where and how they differ. */
        fun compare(want: Any?, got: Any?, at: String): String? {
            if (want is Map<*, *> && got is Map<*, *>) {
                if (want.keys.toList() != got.keys.toList()) return "$at: keys ${want.keys} vs ${got.keys}"
                // The whole response is walked (binding every handle pair it can) and the FIRST difference reported.
                val shape = shapeOf(want)
                var first: String? = null
                for ((k, wv) in want) {
                    val gv = got[k]
                    val kind = handleKind(shape, k as String)
                    val r = if (kind != null) handles(kind, wv, gv, "$at.$k") else compare(wv, gv, "$at.$k")
                    if (first == null) first = r
                }
                return first
            }
            if (want is List<*> && got is List<*>) {
                if (want.size != got.size) return "$at: ${want.size} elements vs ${got.size}"
                var first: String? = null
                for (i in want.indices) compare(want[i], got[i], "$at[$i]")?.let { if (first == null) first = it }
                return first
            }
            if (want is Json.Str && got is Json.Str && want != got) embeddedIds(want.raw, got.raw)?.let { return if (it) null else "$at: embedded id: tsgo ${want.raw} vs port ${got.raw}" }
            return if (want == got) null else "$at: tsgo ${Json.write(want).take(200)} vs port ${Json.write(got).take(200)}"
        }

        /**
         * A symbol name with a SYMBOL ID inside (checker.go: a unique symbol's property `__@<name>@<id>`, a
         * private name's `__#<id>@<name>`): the ids are bound as handles. null when [want] has no such id.
         */
        fun embeddedIds(want: String, got: String): Boolean? {
            val unique = Regex("^\"__@(.*)@([0-9]+)\"$")
            val private = Regex("^\"__#([0-9]+)@(.*)\"$")
            unique.matchEntire(want)?.let { w ->
                val g = unique.matchEntire(got) ?: return false
                return w.groupValues[1] == g.groupValues[1] && bind("S", w.groupValues[2], g.groupValues[2])
            }
            private.matchEntire(want)?.let { w ->
                val g = private.matchEntire(got) ?: return false
                return w.groupValues[2] == g.groupValues[2] && bind("S", w.groupValues[1], g.groupValues[1])
            }
            return null
        }

        private fun handles(kind: String, want: Any?, got: Any?, at: String): String? {
            if (kind.startsWith("[")) {
                if (want !is List<*> || got !is List<*> || want.size != got.size) return "$at: ${Json.write(want)} vs ${Json.write(got)}"
                for (i in want.indices) handles(kind.substring(1, 2), want[i], got[i], "$at[$i]")?.let { return it }
                return null
            }
            if (want is Json.Num && got is Json.Num) {
                if (want.text == "0" || got.text == "0") return if (want.text == got.text) null else "$at: handle ${want.text} vs ${got.text}"
                return if (bind(kind, want.text, got.text)) null else "$at: $kind handle tsgo ${want.text} -> port ${fwd[kind]?.get(want.text)}, got ${got.text}"
            }
            return if (want == got) null else "$at: ${Json.write(want)} vs ${Json.write(got)}"
        }
    }

    /** A minimal JSON reader/writer: objects keep member order, numbers keep their text. */
    private object Json {
        data class Num(val text: String)

        /** A string VALUE, compared by its raw token (escapes included: the port must write tsgo's bytes). */
        data class Str(val raw: String) {
            val value: String get() = Reader(raw).str()
        }

        fun parse(s: String): Any? = Reader(s).run { val v = value(); ws(); check(i == s.length) { "trailing JSON at $i" }; v }

        fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

        private fun write(v: Any?, b: StringBuilder) {
            when (v) {
                null -> b.append("null")
                is Boolean -> b.append(v)
                is Num -> b.append(v.text)
                is Str -> b.append(v.raw)
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
                else -> error("not JSON: $v")
            }
        }

        private class Reader(val s: String) {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun value(): Any? {
                ws()
                return when (s[i]) {
                    '{' -> {
                        i++
                        val m = LinkedHashMap<String, Any?>()
                        ws()
                        if (s[i] == '}') { i++; return m }
                        while (true) {
                            ws()
                            val k = str()
                            ws(); check(s[i++] == ':')
                            m[k] = value()
                            ws()
                            if (s[i++] == '}') return m
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
                            if (s[i++] == ']') return l
                        }
                        @Suppress("UNREACHABLE_CODE") l
                    }
                    '"' -> { val st = i; str(); Str(s.substring(st, i)) }
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> {
                        val st = i
                        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                        Num(s.substring(st, i))
                    }
                }
            }
            fun str(): String {
                check(s[i++] == '"')
                val b = StringBuilder()
                while (true) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return b.toString()
                        '\\' -> when (val e = s[i++]) {
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            'n' -> b.append('\n')
                            't' -> b.append('\t')
                            'r' -> b.append('\r')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            else -> b.append(e)
                        }
                        else -> b.append(c)
                    }
                }
            }
        }
    }
}
