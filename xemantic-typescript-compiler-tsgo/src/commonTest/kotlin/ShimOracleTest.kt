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

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.go.encoding.base64.rawStdEncoding
import com.xemantic.typescript.tsgo.go.encoding.base64.stdEncoding
import com.xemantic.typescript.tsgo.go.encoding.base64.urlEncoding
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.Options
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.deterministic
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.allowDuplicateNames
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.allowInvalidUTF8
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.newDecoder
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.withIndent
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.withIndentPrefix
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.marshal
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.unmarshal
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.Hasher
import com.xemantic.typescript.tsgo.go.io.EOF
import com.xemantic.typescript.tsgo.go.io.fs.FileMode
import com.xemantic.typescript.tsgo.go.path.filepath.base
import com.xemantic.typescript.tsgo.go.path.filepath.clean
import com.xemantic.typescript.tsgo.go.path.filepath.dir
import com.xemantic.typescript.tsgo.go.path.filepath.ext
import com.xemantic.typescript.tsgo.go.path.filepath.join
import com.xemantic.typescript.tsgo.go.regexp.mustCompile
import com.xemantic.typescript.tsgo.go.slices.compact
import com.xemantic.typescript.tsgo.go.slices.repeat
import com.xemantic.typescript.tsgo.go.strings.newReader
import com.xemantic.typescript.tsgo.go.strings.newReplacer
import com.xemantic.typescript.tsgo.runtime.GoBox
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import kotlin.test.Test

/**
 * The (TSGO.2) shims against real Go (go1.27.1; go-json-experiment and zeebo/xxh3 at tsgo's pins):
 * the streaming xxh3 `Hasher` over every chunking across the buffer/block edges, `json.Unmarshal`
 * into `any` and typed targets over valid and malformed inputs (values AND whether Go errs),
 * `json.Marshal` layouts and string escaping, the `jsontext` token stream with `PeekKind`, base64,
 * `strings.Replacer`, `regexp` Split/FindAllString, `filepath`, `fs.FileMode` and `slices`.
 * Every expected value is in `ShimOracleVectors.kt`, printed by a throwaway Go program.
 */
class ShimOracleTest {

    private fun f(row: String): List<String> = row.split('\u0001')

    private fun gen(n: Int): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(((i * 31 + (i shr 8) * 7 + 0x5a) and 0xFF).toChar())
        return sb.toString()
    }

    @Test
    fun `xxh3 Hasher matches Go for every chunking`() {
        val bad = ArrayList<String>()
        for (row in XXH3_STREAM) {
            val r = f(row)
            val n = r[0].toInt()
            val chunk = r[1].toInt()
            val data = gen(n)
            val h = Hasher()
            var mid = 0uL
            val c = if (chunk == 0) n + 1 else chunk
            var i = 0
            while (i < n) {
                val j = minOf(i + c, n)
                if (i % 2 == 0) h.write(goStringToBytes(data.substring(i, j))) else h.writeString(data.substring(i, j))
                if (i <= n / 2 && j > n / 2) mid = h.sum64()
                i += c
            }
            val s = h.sum128()
            if (h.sum64().toString(16) != r[2] || s.hi.toString(16) != r[3] || s.lo.toString(16) != r[4] || mid.toString(16) != r[5]) bad += "$n/$chunk"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `a Hasher copy continues independently`() {
        val h = Hasher()
        h.writeString(gen(1500))
        val c = h.goCopy()
        h.writeString("x")
        c.writeString("x")
        assert(h.sum64() == c.sum64())
    }

    private fun opts(code: String): Array<Options> {
        val o = ArrayList<Options>()
        if ('d' in code) o += allowDuplicateNames(true)
        if ('u' in code) o += allowInvalidUTF8(true)
        return o.toTypedArray()
    }

    private fun dump(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> "b:$v"
        is Double -> "n:" + doubleBits(v)
        is String -> "s:$v"
        is GoMap<*, *> -> {
            if (v.isNil) {
                "nil{}"
            } else {
                val entries = ArrayList<Pair<String, Any?>>()
                @Suppress("UNCHECKED_CAST")
                (v as GoMap<String, Any?>).range { k, x -> entries += Pair(k, x); true }
                entries.sortBy { it.first }
                "{" + entries.joinToString("") { it.first + "=" + dump(it.second) + ";" } + "}"
            }
        }
        is GoSlice<*> -> if (v.isNil) "nil[]" else "[" + (0 until v.len).joinToString(",") { dump(v[it]) } + "]"
        else -> "?" + v
    }

    @Test
    fun `json Unmarshal matches Go in value and in whether it errs`() {
        val bad = ArrayList<String>()
        for (row in JSON_UNMARSHAL) {
            val r = f(row)
            val input = goStringToBytes(r[0])
            val o = opts(r[1])
            val target: GoBox<*> = when (r[2]) {
                "any" -> GoBox<Any?>(null)
                "string" -> GoBox("init")
                "float64" -> GoBox(7.0)
                "bool" -> GoBox(true)
                "mapss" -> GoBox(GoMap.nil<String, String>(GoElem.STRING))
                "slicestr" -> GoBox(GoElem.STRING.nilSlice)
                "mapss-prefilled" -> GoBox(GoMap.make<String, String>(GoElem.STRING).also { it["pre"] = "p" })
                "slicestr-prefilled" -> GoBox(GoSlice.of(GoElem.STRING, "old1", "old2"))
                else -> error(r[2])
            }
            val err = unmarshal(input, target, *o)
            val got = dump(target.value)
            if (got != r[3] || (err != null) != (r[4] == "E")) bad += "${r[0]} [${r[1]}] ${r[2]}: $got ${err?.error()}"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `json Marshal layouts match Go`() {
        val bad = ArrayList<String>()
        for (row in JSON_MARSHAL) {
            val r = f(row)
            val a = GoBox<Any?>(null)
            assert(unmarshal(goStringToBytes(r[0]), a, allowInvalidUTF8(true), allowDuplicateNames(true)) == null)
            val o = when (r[1]) {
                "i" -> arrayOf(deterministic(true), withIndent("  "))
                "p" -> arrayOf(deterministic(true), withIndentPrefix("\t"), withIndent(" "))
                else -> arrayOf(deterministic(true))
            }
            val (b, err) = marshal(a.value, *o)
            if (goBytesToString(b) != r[2] || (err != null) != (r[3] == "E")) bad += "${r[0]} [${r[1]}]: ${goBytesToString(b)}"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `json Marshal of a string escapes as Go`() {
        val bad = ArrayList<String>()
        for (row in JSON_MARSHAL_STRING) {
            val r = f(row)
            val (b, err) = marshal(r[0], *opts(r[1]))
            if (goBytesToString(b) != r[2] || (err != null) != (r[3] == "E")) bad += "${r[0]} [${r[1]}]: ${goBytesToString(b)}"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `jsontext token stream and PeekKind match Go`() {
        val bad = ArrayList<String>()
        for (row in JSONTEXT_TOKENS) {
            val r = f(row)
            val dec = newDecoder(newReader(r[0]))
            val steps = ArrayList<String>()
            for (k in 0 until 40) {
                val pk = dec.peekKind().value
                val (t, err) = dec.readToken()
                if (err != null) {
                    steps += if (err === EOF) "$pk/EOF" else "$pk/E"
                    break
                }
                val kind = t.kind().value
                val txt = if (kind == '"'.code || kind == '0'.code) t.string() else ""
                steps += "$pk/${kind.toChar()}:$txt"
            }
            val got = steps.joinToString("\u0002")
            if (got != r[1]) bad += "${r[0]}: $got"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `base64 encodes and decodes as Go, error offsets included`() {
        val bad = ArrayList<String>()
        for (row in BASE64_ENCODE) {
            val r = f(row)
            val b = goStringToBytes(r[0])
            if (stdEncoding.encodeToString(b) != r[1] || rawStdEncoding.encodeToString(b) != r[2] || urlEncoding.encodeToString(b) != r[3]) bad += r[0]
        }
        for (row in BASE64_DECODE) {
            val r = f(row)
            val (b, err) = stdEncoding.decodeString(r[0])
            if (goBytesToString(b) != r[1] || (err?.error() ?: "-") != r[2]) bad += "${r[0]}: ${goBytesToString(b)} ${err?.error()}"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `strings Replacer matches Go priority and empty-match rules`() {
        val bad = ArrayList<String>()
        for (row in REPLACER) {
            val r = f(row)
            val args = r[0].split('\u0002').toTypedArray()
            val got = newReplacer(*args).replace(r[1])
            if (got != r[2]) bad += "${r[0]} / ${r[1]}: $got"
        }
        assert(bad.isEmpty())
    }

    private fun enc(l: GoSlice<String>): String = if (l.isNil) "\u0003" else l.toList().joinToString("\u0002")

    @Test
    fun `regexp Split and FindAllString match Go`() {
        val bad = ArrayList<String>()
        for (row in REGEXP_SPLIT) {
            val r = f(row)
            val re = mustCompile(r[0])
            val n = r[2].toInt()
            val split = enc(re.split(r[1], n))
            val all = enc(re.findAllString(r[1], n))
            if (split != r[3] || all != r[4]) bad += "${r[0]} / ${r[1]} / $n: $split | $all"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `filepath is Go's lexical Unix path handling`() {
        val bad = ArrayList<String>()
        for (row in FILEPATH) {
            val r = f(row)
            val got = listOf(clean(r[0]), dir(r[0]), base(r[0]), ext(r[0]), join(r[0], "x/../y"))
            if (got != r.subList(1, 6)) bad += "${r[0]}: $got"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `fs FileMode String and Type match Go`() {
        val bad = ArrayList<String>()
        for (row in FILEMODE) {
            val r = f(row)
            val m = FileMode(r[0].toUInt(16))
            val got = listOf(m.string(), m.type().value.toString(16), m.isDir().toString(), m.isRegular().toString())
            if (got != r.subList(1, 5)) bad += "${r[0]}: $got"
        }
        assert(bad.isEmpty())
    }

    @Test
    fun `slices Compact and Repeat match Go`() {
        val bad = ArrayList<String>()
        for (row in SLICES_COMPACT) {
            val r = f(row)
            val xs = if (r[0].isEmpty()) emptyList() else r[0].split(',').map { it.toInt() }
            val c = compact(GoSlice.of(GoElem.INT, *xs.toTypedArray()))
            val rep = repeat(GoSlice.of(GoElem.INT, *xs.toTypedArray()), 3)
            if (c.toList().joinToString(",") != r[1] || rep.toList().joinToString(",") != r[2]) bad += r[0]
        }
        assert(bad.isEmpty())
    }
}
