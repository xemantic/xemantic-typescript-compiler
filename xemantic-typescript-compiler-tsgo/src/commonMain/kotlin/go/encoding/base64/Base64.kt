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

// Parts of this file are translated from the Go standard library (go1.27.1), Copyright The Go
// Authors, used under Go's BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.go.encoding.base64

// Go's `encoding/base64` (`sourcemap` encodes inline source maps and decodes `data:` URLs):
// encode and decode are exact, including the decoder's error OFFSETS (`CorruptInputError`), its
// skipping of '\r'/'\n', and the streaming encoder's output.

import com.xemantic.typescript.tsgo.go.io.WriteCloser
import com.xemantic.typescript.tsgo.go.io.Writer
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes

/** `base64.StdPadding`, `base64.NoPadding`. */
const val StdPadding: Int = '='.code
const val NoPadding: Int = -1

/** `base64.CorruptInputError` (an `int64` offset). */
class CorruptInputError(val value: Long) : GoError {
    override fun error(): String = "illegal base64 data at input byte $value"
    override fun toString(): String = error()
    override fun equals(other: Any?): Boolean = other is CorruptInputError && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/** `base64.Encoding`: an alphabet, a padding character (or [NoPadding]) and strictness. */
class Encoding internal constructor(private val alphabet: String, private val padChar: Int, private val strict: Boolean) {

    private val decodeMap = IntArray(256) { 0xFF }.also { m ->
        for (i in alphabet.indices) m[alphabet[i].code] = i
    }

    /** `enc.WithPadding(padding)`. */
    fun withPadding(padding: Int): Encoding = Encoding(alphabet, padding, strict)

    /** `enc.Strict()`. */
    fun strict(): Encoding = Encoding(alphabet, padChar, true)

    /** `enc.EncodedLen(n)`. */
    fun encodedLen(n: Int): Int = if (padChar == NoPadding) n / 3 * 4 + (n % 3 * 8 + 5) / 6 else (n + 2) / 3 * 4

    /** `enc.DecodedLen(n)`. */
    fun decodedLen(n: Int): Int = if (padChar == NoPadding) n / 4 * 3 + n % 4 * 6 / 8 else n / 4 * 3

    /** `enc.EncodeToString(src)`. */
    fun encodeToString(src: GoSlice<Int>): String = encodeString(goBytesToString(src))

    /** Encodes a byte string. */
    internal fun encodeString(s: String): String {
        val sb = StringBuilder(encodedLen(s.length))
        var si = 0
        val n = s.length / 3 * 3
        while (si < n) {
            val v = (s[si].code shl 16) or (s[si + 1].code shl 8) or s[si + 2].code
            sb.append(alphabet[v shr 18 and 0x3F]).append(alphabet[v shr 12 and 0x3F])
                .append(alphabet[v shr 6 and 0x3F]).append(alphabet[v and 0x3F])
            si += 3
        }
        val remain = s.length - si
        if (remain == 0) return sb.toString()
        var v = s[si].code shl 16
        if (remain == 2) v = v or (s[si + 1].code shl 8)
        sb.append(alphabet[v shr 18 and 0x3F]).append(alphabet[v shr 12 and 0x3F])
        if (remain == 2) {
            sb.append(alphabet[v shr 6 and 0x3F])
            if (padChar != NoPadding) sb.append(padChar.toChar())
        } else if (padChar != NoPadding) {
            sb.append(padChar.toChar()).append(padChar.toChar())
        }
        return sb.toString()
    }

    /** `enc.Encode(dst, src)`. */
    fun encode(dst: GoSlice<Int>, src: GoSlice<Int>) {
        val e = encodeString(goBytesToString(src))
        for (i in e.indices) dst[i] = e[i].code
    }

    /** `enc.AppendEncode(dst, src)`. */
    fun appendEncode(dst: GoSlice<Int>, src: GoSlice<Int>): GoSlice<Int> =
        com.xemantic.typescript.tsgo.runtime.goAppendString(dst, encodeString(goBytesToString(src)))

    /** `enc.DecodeString(s)` → (bytes, err): the bytes decoded before an error are returned with it. */
    fun decodeString(s: String): Tuple2<GoSlice<Int>, GoError?> {
        val out = StringBuilder(decodedLen(s.length))
        val err = decode(s, out)
        return Tuple2(goStringToBytes(out.toString()), err)
    }

    /** `enc.Decode(dst, src)` → (n, err). */
    fun decode(dst: GoSlice<Int>, src: GoSlice<Int>): Tuple2<Int, GoError?> {
        val out = StringBuilder()
        val err = decode(goBytesToString(src), out)
        for (i in out.indices) dst[i] = out[i].code
        return Tuple2(out.length, err)
    }

    private fun decode(src: String, dst: StringBuilder): GoError? {
        var si = 0
        while (si < src.length) {
            val r = decodeQuantum(dst, src, si)
            si = r.first
            if (r.second != null) return r.second
        }
        return null
    }

    /** Go's `decodeQuantum`: one 4-character group (newlines skipped) → (next index, error). */
    private fun decodeQuantum(dst: StringBuilder, src: String, si0: Int): Pair<Int, GoError?> {
        var si = si0
        val dbuf = IntArray(4)
        var dlen = 4
        var err: GoError? = null
        var j = 0
        while (j < 4) {
            if (src.length == si) {
                if (j == 0) return Pair(si, null)
                if (j == 1 || padChar != NoPadding) return Pair(si, CorruptInputError((si - j).toLong()))
                dlen = j
                break
            }
            val ch = src[si].code
            si++
            val out = decodeMap[ch]
            if (out != 0xFF) {
                dbuf[j] = out
                j++
                continue
            }
            if (ch == '\n'.code || ch == '\r'.code) continue
            if (ch != padChar) return Pair(si, CorruptInputError((si - 1).toLong()))
            when (j) {
                0, 1 -> return Pair(si, CorruptInputError((si - 1).toLong()))
                2 -> {
                    while (si < src.length && (src[si] == '\n' || src[si] == '\r')) si++
                    if (si == src.length) return Pair(si, CorruptInputError(src.length.toLong()))
                    if (src[si].code != padChar) return Pair(si, CorruptInputError((si - 1).toLong()))
                    si++
                }
            }
            while (si < src.length && (src[si] == '\n' || src[si] == '\r')) si++
            if (si < src.length) err = CorruptInputError(si.toLong())
            dlen = j
            break
        }
        val v = (dbuf[0] shl 18) or (dbuf[1] shl 12) or (dbuf[2] shl 6) or dbuf[3]
        val b0 = v shr 16 and 0xFF
        val b1 = v shr 8 and 0xFF
        val b2 = v and 0xFF
        when (dlen) {
            4 -> {
                dst.append(b0.toChar()).append(b1.toChar()).append(b2.toChar())
            }
            3 -> {
                if (strict && b2 != 0) return Pair(si, CorruptInputError((si - 1).toLong()))
                dst.append(b0.toChar()).append(b1.toChar())
            }
            2 -> {
                if (strict && (b1 != 0 || b2 != 0)) return Pair(si, CorruptInputError((si - 2).toLong()))
                dst.append(b0.toChar())
            }
        }
        return Pair(si, err)
    }

    fun goCopy(): Encoding = this
}

private const val ENCODE_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val ENCODE_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** `base64.NewEncoding(encoder)`. */
fun newEncoding(encoder: String): Encoding = Encoding(encoder, StdPadding, false)

/** `base64.StdEncoding`, `URLEncoding`, `RawStdEncoding`, `RawURLEncoding` (vars). */
val stdEncoding: Encoding = Encoding(ENCODE_STD, StdPadding, false)
val urlEncoding: Encoding = Encoding(ENCODE_URL, StdPadding, false)
val rawStdEncoding: Encoding = Encoding(ENCODE_STD, NoPadding, false)
val rawURLEncoding: Encoding = Encoding(ENCODE_URL, NoPadding, false)

/**
 * `base64.NewEncoder(enc, w)`: complete 3-byte groups are encoded and written as they arrive; [close]
 * flushes the partial group (with padding). The bytes written equal `enc.EncodeToString` of
 * everything written, as in Go.
 */
fun newEncoder(enc: Encoding, w: Writer?): WriteCloser = StreamEncoder(enc, w!!)

private class StreamEncoder(private val enc: Encoding, private val w: Writer) : WriteCloser {
    private val pending = StringBuilder()
    private var err: GoError? = null

    override fun write(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        if (err != null) return Tuple2(0, err)
        pending.append(goBytesToString(p))
        val full = pending.length / 3 * 3
        if (full > 0) {
            val chunk = enc.encodeString(pending.substring(0, full))
            pending.deleteRange(0, full)
            err = w.write(goStringToBytes(chunk)).second
            if (err != null) return Tuple2(0, err)
        }
        return Tuple2(p.len, null)
    }

    override fun close(): GoError? {
        if (err == null && pending.isNotEmpty()) {
            err = w.write(goStringToBytes(enc.encodeString(pending.toString()))).second
            pending.setLength(0)
        }
        return err
    }
}

