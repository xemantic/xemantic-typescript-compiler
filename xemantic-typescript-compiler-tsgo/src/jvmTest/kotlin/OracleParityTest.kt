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
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.Uint128
import com.xemantic.typescript.tsgo.tspath.Path
import java.io.File
import kotlin.test.Test

/**
 * THE (TSGO.1) GATE MEASUREMENT: the ported parser + encoder against tsgo's own bytes
 * (docs/goport-oracle.md). Opt-in, because it needs the gitignored oracle under
 * `build/goport/` — run with `TSGO_ORACLE=parse-only` (bytes of tsgo's parser+encoder without
 * the binder, `build/goport/oracle-parse-only/`) and optionally `TSGO_ORACLE_LIMIT=n`,
 * `TSGO_ORACLE_SOURCE=tsc`. Writes the per-file verdicts to `build/goport/port/oracle-parity.tsv`.
 *
 * The content hash (header bytes 4-19, xxh3-128) is copied from the oracle: the xxh3 shim has no
 * hash function yet, and copying it isolates what this measures — the AST.
 */
class OracleParityTest {

    private val repo = File("..").absoluteFile.normalize()

    class Entry(val fileName: String, val output: String, val source: String, val jsx: Boolean, val force: Boolean)

    private fun manifest(): List<Entry> {
        val text = File(repo, "build/goport/oracle/manifest.json").readText()
        val rx = Regex(""""source":\s*"([^"]*)",.*?"fileName":\s*"([^"]*)",.*?"output":\s*"([^"]*)",.*?"jsx":\s*(true|false),\s*"force":\s*(true|false)""", RegexOption.DOT_MATCHES_ALL)
        return rx.findAll(text).map { Entry(it.groupValues[2], it.groupValues[3], it.groupValues[1], it.groupValues[4] == "true", it.groupValues[5] == "true") }.toList()
    }

    /** tsgo's `vfs` decoding: UTF-8 BOM stripped, UTF-16 (BOM) decoded to UTF-8, otherwise raw bytes — as a byte string. */
    private fun decode(b: ByteArray): String {
        val bytes = when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> b.copyOfRange(3, b.size)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE).toByteArray(Charsets.UTF_8)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE).toByteArray(Charsets.UTF_8)
            else -> b
        }
        return String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
    }

    private fun le64(b: ByteArray, at: Int): ULong {
        var v = 0uL
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toULong() and 0xFFuL)
        return v
    }

    /** One file through the port: parse → encode. */
    fun encode(e: Entry, oracle: ByteArray): ByteArray {
        val text = decode(File(e.fileName).readBytes())
        val opts = SourceFileParseOptions(
            fileName = e.fileName,
            path = Path(e.fileName),
            externalModuleIndicatorOptions = ExternalModuleIndicatorOptions(jsx = e.jsx, force = e.force),
        )
        val kind = com.xemantic.typescript.tsgo.core.getScriptKindFromFileName(e.fileName)
        val sf = com.xemantic.typescript.tsgo.parser.parseSourceFile(opts, text, kind)!!
        sf.hash = Uint128(hi = le64(oracle, 12), lo = le64(oracle, 4))
        if (System.getenv("TSGO_ORACLE_DIAGS") != null) {
            for (d in sf.diagnostics.toList()) println("  diag ${e.output}: ${d!!.loc.pos}..${d.loc.end} TS${d.code} ${d.message?.text}")
        }
        val r = com.xemantic.typescript.tsgo.api.encoder.encodeSourceFile(sf)
        check(r.third == null) { "encode error: ${r.third!!.error()}" }
        val out = r.first
        return ByteArray(out.len) { out[it].toByte() }
    }

    @Test
    fun `ported parser and encoder against tsgo's bytes`() {
        val mode = System.getenv("TSGO_ORACLE") ?: run {
            println("OracleParityTest: skipped (set TSGO_ORACLE=parse-only to measure)")
            return
        }
        // Go's goroutine stacks grow; the JVM's do not. tsgo recurses per nesting level
        // (binderBinaryExpressionStress), so the port runs on a deep stack, as -core does.
        var failure: Throwable? = null
        val th = Thread(null, { try { measure(mode) } catch (t: Throwable) { failure = t } }, "tsgo-deep-stack", 1L shl 30)
        th.start()
        th.join()
        failure?.let { throw it }
    }

    private fun measure(mode: String) {
        val dir = File(repo, if (mode == "parse-only") "build/goport/oracle-parse-only" else "build/goport/oracle")
        com.xemantic.typescript.tsgo.parser.goInitPackage()
        val limit = System.getenv("TSGO_ORACLE_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val only = System.getenv("TSGO_ORACLE_SOURCE")?.takeIf { it.isNotBlank() }
        val entries = manifest().filter { only == null || it.source == only }.take(limit)
        var same = 0
        var differ = 0
        var crashed = 0
        val lines = ArrayList<String>()
        val crashes = HashMap<String, Int>()
        val traces = ArrayList<String>()
        val t0 = System.nanoTime()
        for (e in entries) {
            val oracle = File(dir, e.output).readBytes()
            val verdict = try {
                val mine = encode(e, oracle)
                if (mine.contentEquals(oracle)) {
                    same++
                    "same"
                } else {
                    differ++
                    File(repo, "build/goport/port/out/${e.output}").also { it.parentFile.mkdirs() }.writeBytes(mine)
                    "differ\t${mine.size}\t${oracle.size}"
                }
            } catch (t: Throwable) {
                crashed++
                val where = t.stackTrace.firstOrNull { it.className.startsWith("com.xemantic.typescript.tsgo") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "?"
                val key = "${t::class.simpleName}: ${t.message?.take(120)} @ $where"
                if (crashes.merge(key, 1, Int::plus) == 1) traces += "== ${e.output}: $key\n" + generateSequence(t) { it.cause }.joinToString("\ncaused by ") { c ->
                    "$c\n" + c.stackTrace.take(25).joinToString("\n") { "    at $it" }
                }
                "crash\t$key"
            }
            lines += "${e.output}\t$verdict"
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        File(repo, "build/goport/port").mkdirs()
        File(repo, "build/goport/port/oracle-parity.tsv").writeText(lines.joinToString("\n") + "\n")
        File(repo, "build/goport/port/oracle-crashes.txt").writeText(traces.joinToString("\n\n"))
        println("OracleParityTest [$mode]: ${entries.size} files, $same byte-identical, $differ differ, $crashed crashed ($ms ms)")
        crashes.entries.sortedByDescending { it.value }.take(25).forEach { (k, v) -> println("  $v x $k") }
    }
}
