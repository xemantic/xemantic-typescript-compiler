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

// Parts of this file are translated from github.com/go-json-experiment/json (the `jsontext` and
// `internal/jsonwire` packages, the version tsgo v7.0.2 pins), Copyright The Go Authors, used
// under its BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext

// `jsontext`: JSON tokens, a VALIDATING streaming [Decoder] and an [Encoder], with go-json-
// experiment's v2 rules — RFC 8259 grammar, strict number syntax, no trailing commas, control
// characters rejected in strings, invalid UTF-8 and duplicate object names rejected unless
// [allowInvalidUTF8] / [allowDuplicateNames], `\u` surrogate pairs validated; strings are
// unquoted to UTF-8 byte strings (invalid bytes become U+FFFD when allowed). The encoder writes
// Go's compact form, or the multiline form under [withIndent] / [withIndentPrefix] (": " after a
// name, one member per line, `{}`/`[]` when empty). Go 1.27's `encoding/json/jsontext` is the
// same package (go-json-experiment aliases it), so the lowering maps both import paths here.
// APPROXIMATION (docs/goport-runtime.md § 10): error MESSAGES follow Go's wording but not its
// JSON-pointer context.

import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.Options
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goDecodeRune
import com.xemantic.typescript.tsgo.runtime.goPanic
import com.xemantic.typescript.tsgo.runtime.goStringToBytes

/** `jsontext.Kind`: the first byte of a token's JSON form (`'n' 'f' 't' '"' '0' '{' '}' '[' ']'`); 0 is invalid. */
@kotlin.jvm.JvmInline
value class Kind(val value: Int) {
    /** `k.String()`. */
    fun string(): String = when (value.toChar()) {
        'n' -> "null"
        'f' -> "false"
        't' -> "true"
        '"' -> "string"
        '0' -> "number"
        '{' -> "{"
        '}' -> "}"
        '[' -> "["
        ']' -> "]"
        else -> "<invalid jsontext.Kind: " + com.xemantic.typescript.tsgo.go.strconv.quote(value.toChar().toString()) + ">"
    }
}

/**
 * `jsontext.Token`: one JSON token as a VALUE — its [Kind], and for a string its (unquoted) value
 * or for a number its literal text and value. Go's `Token` is a struct (zero value = the invalid
 * token, kind 0); it is immutable here, so [goCopy] returns the token itself.
 */
class Token(private val k: Int = 0, private val str: String = "", private val num: Double = 0.0) {

    /** `t.Kind()`. */
    fun kind(): Kind = Kind(k)

    /** `t.String()`: the string value for a string token, else the token's JSON text. */
    fun string(): String = when (k.toChar()) {
        '"' -> str
        'n' -> "null"
        'f' -> "false"
        't' -> "true"
        '0' -> str
        '{', '}', '[', ']' -> k.toChar().toString()
        else -> "<invalid jsontext.Token>"
    }

    /** `t.Bool()`: panics unless a boolean token, as in Go. */
    fun bool(): Boolean = when (k.toChar()) {
        't' -> true
        'f' -> false
        else -> goPanic("invalid JSON token kind: " + kind().string())
    }

    /** `t.Float()` for a number token. */
    fun float(): Double {
        if (k.toChar() != '0') goPanic("invalid JSON token kind: " + kind().string())
        return num
    }

    /** `t.Int()`: a number token's value truncated toward zero (saturating), as Go. */
    fun int(): Long {
        if (k.toChar() != '0') goPanic("invalid JSON token kind: " + kind().string())
        str.toLongOrNull()?.let { return it }
        return com.xemantic.typescript.tsgo.runtime.goFloat64ToInt64(num)
    }

    fun goCopy(): Token = this

    override fun toString(): String = string()

    /** The token's JSON text (the encoder's form). */
    internal fun jsonText(allowInvalidUTF8: Boolean): Tuple2<String, GoError?> = when (k.toChar()) {
        '"' -> appendQuote(str, allowInvalidUTF8)
        '0' -> Tuple2(str, null)
        else -> Tuple2(string(), null)
    }
}

const val BeginObjectByte: Int = '{'.code

/** `jsontext.BeginObject`, `EndObject`, `BeginArray`, `EndArray`, `Null`, `True`, `False` (vars). */
val beginObject: Token = Token('{'.code)
val endObject: Token = Token('}'.code)
val beginArray: Token = Token('['.code)
val endArray: Token = Token(']'.code)
val `null`: Token = Token('n'.code)
val `true`: Token = Token('t'.code)
val `false`: Token = Token('f'.code)

/** `jsontext.Bool(b)`. */
fun bool(b: Boolean): Token = if (b) `true` else `false`

/** `jsontext.String(s)`. */
fun string(s: String): Token = Token('"'.code, s)

/**
 * `jsontext.Float(f)`: a number token whose text is the JSON (ES6) form of [f]. Go turns a NaN or
 * infinity into a STRING token (`"NaN"`, `"Infinity"`, `"-Infinity"`); so does this.
 */
fun float(f: Double): Token = when {
    f.isNaN() -> string("NaN")
    f == Double.POSITIVE_INFINITY -> string("Infinity")
    f == Double.NEGATIVE_INFINITY -> string("-Infinity")
    else -> Token('0'.code, com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.formatJsonFloat(f), f)
}

/** `jsontext.Int(n)`. */
fun int(n: Long): Token = Token('0'.code, n.toString(), n.toDouble())

/** `jsontext.Uint(n)`. */
fun uint(n: ULong): Token = Token('0'.code, n.toString(), n.toDouble())

/** `jsontext.Value` (raw JSON bytes). */
typealias Value = GoSlice<Int>

/** `jsontext.SyntacticError`. */
class SyntacticError(val byteOffset: Long, private val msg: String) : GoError {
    override fun error(): String = "jsontext: $msg after offset $byteOffset"
    override fun toString(): String = error()
}

/** `io.ErrUnexpectedEOF` as the decoder reports it. */
val errUnexpectedEOF: GoError = com.xemantic.typescript.tsgo.runtime.GoPlainError("unexpected EOF")

/** `jsontext.ErrDuplicateName`, `ErrNonStringName` (vars). */
val errDuplicateName: GoError = com.xemantic.typescript.tsgo.runtime.GoPlainError("duplicate object member name")
val errNonStringName: GoError = com.xemantic.typescript.tsgo.runtime.GoPlainError("object member name must be a string")

/** The option flags the coders read, folded from an option list (later options win). */
internal class Flags(opts: Array<out Options>) {
    var allowInvalidUTF8 = false
    var allowDuplicateNames = false
    var indent: String? = null
    var indentPrefix: String? = null
    var deterministic = false

    init {
        for (o in opts) add(o)
    }

    fun add(o: Options) {
        when (o.name) {
            "AllowInvalidUTF8" -> allowInvalidUTF8 = o.value as Boolean
            "AllowDuplicateNames" -> allowDuplicateNames = o.value as Boolean
            "WithIndent" -> indent = o.value as String
            "WithIndentPrefix" -> indentPrefix = o.value as String
            "Deterministic" -> deterministic = o.value as Boolean
        }
    }

    val multiline: Boolean get() = indent != null || indentPrefix != null
}

// ---- jsonwire: strings ----

private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun appendRune(sb: StringBuilder, r: Int) {
    for (c in com.xemantic.typescript.tsgo.runtime.goRuneToString(r)) sb.append(c)
}

/** jsonwire.AppendQuote with the default flags (no HTML/JS escaping): (quoted, ErrInvalidUTF8?). */
internal fun appendQuote(s: String, allowInvalidUTF8: Boolean): Tuple2<String, GoError?> {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    var invalid = false
    var n = 0
    while (n < s.length) {
        val c = s[n].code
        if (c < 0x80) {
            n++
            when {
                c == '"'.code || c == '\\'.code -> sb.append('\\').append(c.toChar())
                c == 0x08 -> sb.append("\\b")
                c == 0x0C -> sb.append("\\f")
                c == '\n'.code -> sb.append("\\n")
                c == '\r'.code -> sb.append("\\r")
                c == '\t'.code -> sb.append("\\t")
                c < 0x20 -> {
                    sb.append("\\u00")
                    sb.append("0123456789abcdef"[c shr 4]).append("0123456789abcdef"[c and 0xF])
                }
                else -> sb.append(c.toChar())
            }
        } else {
            val p = goDecodeRune(s, n)
            val r = p.toInt()
            val w = (p ushr 32).toInt()
            if (r == 0xFFFD && w == 1) {
                invalid = true
                sb.append("ï¿½")
            } else {
                sb.append(s, n, n + w)
            }
            n += w
        }
    }
    sb.append('"')
    return Tuple2(sb.toString(), if (invalid && !allowInvalidUTF8) errInvalidUTF8 else null)
}

/** `jsonwire.ErrInvalidUTF8`. */
val errInvalidUTF8: GoError = com.xemantic.typescript.tsgo.runtime.GoPlainError("invalid UTF-8")

/**
 * Consumes the JSON string starting at [start] (a `"`), validating it; returns the index after
 * the closing quote, or `-(errorIndex + 1)` with [err] set.
 */
private class StringScan(val end: Int, val err: String?, val errAt: Int, val eof: Boolean)

private fun consumeString(src: String, start: Int, validateUTF8: Boolean): StringScan {
    var n = start + 1
    val len = src.length
    while (n < len) {
        val c = src[n].code
        if (c < 0x80 && c >= 0x20 && c != '\\'.code && c != '"'.code) {
            n++
            continue
        }
        if (c == '"'.code) return StringScan(n + 1, null, 0, false)
        if (c == '\\'.code) {
            if (n + 2 > len) return StringScan(n, null, n, true)
            when (src[n + 1]) {
                '/', '"', '\\', 'b', 'f', 'n', 'r', 't' -> n += 2
                'u' -> {
                    if (n + 6 > len) return StringScan(n, null, n, true)
                    if (!(2 until 6).all { isHex(src[n + it]) }) return StringScan(n, "invalid escape sequence " + quoteRaw(src, n, n + 6) + " in string", n, false)
                    val v1 = src.substring(n + 2, n + 6).toInt(16)
                    n += 6
                    if (validateUTF8 && v1 in 0xD800..0xDFFF) {
                        if (n + 6 > len) return StringScan(n - 6, if (n >= len) null else "invalid escape sequence " + quoteRaw(src, n - 6, len) + " in string", n - 6, n >= len)
                        val ok = src[n] == '\\' && src[n + 1] == 'u' && (2 until 6).all { isHex(src[n + it]) }
                        val v2 = if (ok) src.substring(n + 2, n + 6).toInt(16) else 0
                        if (!ok || v1 >= 0xDC00 || v2 !in 0xDC00..0xDFFF) {
                            return StringScan(n - 6, "invalid escape sequence " + quoteRaw(src, n - 6, n + 6) + " in string", n - 6, false)
                        }
                        n += 6
                    }
                }
                else -> return StringScan(n, "invalid escape sequence " + quoteRaw(src, n, n + 2) + " in string", n, false)
            }
            continue
        }
        if (c >= 0x80) {
            val p = goDecodeRune(src, n)
            val r = p.toInt()
            val w = (p ushr 32).toInt()
            if (r == 0xFFFD && w == 1) {
                if (!fullRune(src, n)) return StringScan(n, null, n, true)
                if (validateUTF8) return StringScan(n, "invalid UTF-8 within string", n, false)
                n++
            } else {
                n += w
            }
            continue
        }
        // a control character
        return StringScan(n, "invalid character " + quoteRune(src, n) + " in string (expecting non-control character)", n, false)
    }
    return StringScan(n, null, n, true)
}

/** utf8.FullRuneInString(s[i:]). */
private fun fullRune(s: String, i: Int): Boolean {
    val n = s.length - i
    if (n <= 0) return false
    val c = s[i].code
    val need = when {
        c < 0x80 -> 1
        c < 0xC2 -> 1
        c < 0xE0 -> 2
        c < 0xF0 -> 3
        c < 0xF5 -> 4
        else -> 1
    }
    if (n >= need) return true
    // an invalid continuation byte before the end makes it "full" (decodes to RuneError width 1)
    for (k in 1 until n) {
        val b = s[i + k].code
        if (b < 0x80 || b > 0xBF) return true
    }
    return false
}

/** strconv.QuoteRune for the decoder's messages (ASCII escapes; other runes verbatim). */
private fun goQuoteRune(r: Int): String = when (r) {
    '\''.code -> "'\\''"
    '\\'.code -> "'\\\\'"
    '\n'.code -> "'\\n'"
    '\r'.code -> "'\\r'"
    '\t'.code -> "'\\t'"
    0x07 -> "'\\a'"
    0x08 -> "'\\b'"
    0x0C -> "'\\f'"
    0x0B -> "'\\v'"
    else -> when {
        r < 0x20 || r == 0x7F -> "'\\x" + "0123456789abcdef"[r shr 4] + "0123456789abcdef"[r and 0xF] + "'"
        else -> "'" + com.xemantic.typescript.tsgo.runtime.goRuneToString(r) + "'"
    }
}

private fun quoteRaw(s: String, from: Int, to: Int): String =
    com.xemantic.typescript.tsgo.go.strconv.quote(s.substring(from, minOf(to, s.length)))

private fun quoteRune(s: String, i: Int): String {
    val c = s[i].code
    return when {
        c == '\''.code -> "'\\''"
        c == '"'.code -> "'\"'"
        c < 0x80 -> goQuoteRune(c)
        else -> {
            val p = goDecodeRune(s, i)
            if (p.toInt() == 0xFFFD && (p ushr 32).toInt() == 1) com.xemantic.typescript.tsgo.go.strconv.quote(s.substring(i, i + 1))
            else goQuoteRune(p.toInt())
        }
    }
}

/** jsonwire.AppendUnquote of a VALIDATED string `src[start, end)` (quotes included): its UTF-8 value. */
internal fun unquote(src: String, start: Int, end: Int): String {
    val sb = StringBuilder(end - start)
    var n = start + 1
    val last = end - 1
    while (n < last) {
        val c = src[n]
        if (c == '\\') {
            when (val e = src[n + 1]) {
                '"', '\\', '/' -> { sb.append(e); n += 2 }
                'b' -> { sb.append('\b'); n += 2 }
                'f' -> { sb.append('\u000C'); n += 2 }
                'n' -> { sb.append('\n'); n += 2 }
                'r' -> { sb.append('\r'); n += 2 }
                't' -> { sb.append('\t'); n += 2 }
                else -> { // 'u'
                    var r = src.substring(n + 2, n + 6).toInt(16)
                    n += 6
                    if (r in 0xD800..0xDFFF) {
                        val ok = n + 6 <= last && src[n] == '\\' && src[n + 1] == 'u' && (2 until 6).all { isHex(src[n + it]) }
                        val v2 = if (ok) src.substring(n + 2, n + 6).toInt(16) else 0
                        if (ok && r < 0xDC00 && v2 in 0xDC00..0xDFFF) {
                            r = 0x10000 + ((r - 0xD800) shl 10) + (v2 - 0xDC00)
                            n += 6
                        } else {
                            r = 0xFFFD
                        }
                    }
                    appendRune(sb, r)
                }
            }
        } else if (c.code >= 0x80) {
            val p = goDecodeRune(src, n)
            val w = (p ushr 32).toInt()
            if (p.toInt() == 0xFFFD && w == 1) sb.append("ï¿½") else sb.append(src, n, n + w)
            n += w
        } else {
            sb.append(c)
            n++
        }
    }
    return sb.toString()
}

/** The strict JSON number grammar from [start]; returns the end index, or -1 if invalid, -2 on EOF mid-number. */
private fun consumeNumber(s: String, start: Int): Int {
    var n = start
    val len = s.length
    if (n < len && s[n] == '-') n++
    if (n >= len) return -2
    when {
        s[n] == '0' -> n++
        s[n] in '1'..'9' -> {
            n++
            while (n < len && s[n] in '0'..'9') n++
        }
        else -> return -1
    }
    if (n < len && s[n] == '.') {
        n++
        if (n >= len) return -2
        if (s[n] !in '0'..'9') return -1
        while (n < len && s[n] in '0'..'9') n++
    }
    if (n < len && (s[n] == 'e' || s[n] == 'E')) {
        n++
        if (n < len && (s[n] == '+' || s[n] == '-')) n++
        if (n >= len) return -2
        if (s[n] !in '0'..'9') return -1
        while (n < len && s[n] in '0'..'9') n++
    }
    return n
}

private fun isWS(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

/**
 * `jsontext.Decoder`: a validating reader over the whole input (a `Reader` is read to EOF on
 * construction). Tracks the container stack so separators and closing brackets are checked and
 * [stackDepth]/[depthLength] answer like Go's `Tokens.DepthLength()`.
 */
class Decoder internal constructor(private val src: String, internal val flags: Flags) {

    private var pos = 0
    private val kinds = ArrayList<Boolean>() // true = object
    private val lengths = ArrayList<Int>()
    private val names = ArrayList<HashSet<String>?>()
    private var topLength = 0
    private var prepared = false
    private var sticky: GoError? = null

    private fun err(at: Int, msg: String): GoError = SyntacticError(at.toLong(), msg)

    /** At the top level the input may simply end (`io.EOF`); inside a value it may not. */
    private fun eofErr(): GoError = if (kinds.isEmpty()) com.xemantic.typescript.tsgo.go.io.EOF else errUnexpectedEOF

    private fun skipWS() {
        while (pos < src.length && isWS(src[pos])) pos++
    }

    /** Positions [pos] at the next token, consuming a pending ',' or ':'; idempotent until a token is consumed. */
    private fun prepare(): GoError? {
        if (prepared) return null
        skipWS()
        if (kinds.isNotEmpty()) {
            val obj = kinds.last()
            val len = lengths.last()
            val closing = if (obj) '}' else ']'
            if (pos >= src.length) return errUnexpectedEOF
            val c = src[pos]
            if (obj && len % 2 == 1) {
                if (c != ':') return err(pos, "invalid character " + quoteRune(src, pos) + " after object name (expecting ':')")
                pos++
                skipWS()
            } else if (len > 0) {
                if (c == closing) {
                    prepared = true
                    return null
                }
                if (c != ',') {
                    return err(pos, "invalid character " + quoteRune(src, pos) +
                        (if (obj) " after object value (expecting ',' or '}')" else " after array element (expecting ',' or ']')"))
                }
                pos++
                skipWS()
                if (pos < src.length && src[pos] == closing) {
                    return err(pos, "invalid character " + quoteRune(src, pos) + if (obj) " at start of string (expecting '\"')" else " at start of value")
                }
            }
        }
        prepared = true
        return null
    }

    /** `dec.PeekKind()`: the next token's kind, or 0 (the error is reported by the next read). */
    fun peekKind(): Kind {
        if (sticky != null) return Kind(0)
        val e = prepare()
        if (e != null) {
            sticky = e
            return Kind(0)
        }
        if (pos >= src.length) return Kind(0)
        return Kind(kindOf(src[pos]))
    }

    private fun kindOf(c: Char): Int = when (c) {
        'n', 't', 'f', '"', '{', '}', '[', ']' -> c.code
        '-', in '0'..'9' -> '0'.code
        else -> 0
    }

    private fun countValue() {
        if (kinds.isEmpty()) topLength++ else lengths[lengths.size - 1] = lengths.last() + 1
    }

    /** `dec.ReadToken()`. */
    fun readToken(): Tuple2<Token, GoError?> {
        sticky?.let { return Tuple2(Token(), it) }
        prepare()?.let { sticky = it; return Tuple2(Token(), it) }
        if (pos >= src.length) {
            val e = eofErr()
            return Tuple2(Token(), e)
        }
        val start = pos
        val c = src[pos]
        // an object member name must be a string (PeekKind reports the raw kind; ReadToken refuses)
        if (kinds.isNotEmpty() && kinds.last() && lengths.last() % 2 == 0 && c != '"' && c != '}') {
            return fail(err(pos, "invalid character " + quoteRune(src, pos) + " at start of string (expecting '\"')"))
        }
        val tok: Token
        when (c) {
            '{', '[' -> {
                countValue()
                kinds += c == '{'
                lengths += 0
                names += if (c == '{' && !flags.allowDuplicateNames) HashSet() else null
                pos++
                tok = if (c == '{') beginObject else beginArray
            }
            '}', ']' -> {
                val obj = c == '}'
                if (kinds.isEmpty() || kinds.last() != obj || (obj && lengths.last() % 2 == 1)) {
                    return fail(err(pos, "invalid character " + quoteRune(src, pos) + " at start of value"))
                }
                kinds.removeAt(kinds.size - 1)
                lengths.removeAt(lengths.size - 1)
                names.removeAt(names.size - 1)
                pos++
                tok = if (obj) endObject else endArray
            }
            '"' -> {
                val scan = consumeString(src, pos, !flags.allowInvalidUTF8)
                if (scan.eof) return fail(errUnexpectedEOF)
                if (scan.err != null) return fail(err(scan.errAt, scan.err))
                val value = unquote(src, pos, scan.end)
                if (kinds.isNotEmpty() && kinds.last() && lengths.last() % 2 == 0) {
                    val set = names.last()
                    if (set != null && !set.add(value)) {
                        return fail(SyntacticError(start.toLong(), "duplicate object member name " + appendQuote(value, true).first))
                    }
                }
                pos = scan.end
                countValue()
                tok = string(value)
            }
            'n', 't', 'f' -> {
                val lit = when (c) { 'n' -> "null"; 't' -> "true"; else -> "false" }
                if (!src.startsWith(lit, pos)) {
                    var k = 0
                    while (pos + k < src.length && k < lit.length && src[pos + k] == lit[k]) k++
                    if (pos + k >= src.length) return fail(errUnexpectedEOF)
                    return fail(err(pos + k, "invalid character " + quoteRune(src, pos + k) + " within literal $lit (expecting " + goQuoteRune(lit[k].code) + ")"))
                }
                pos += lit.length
                countValue()
                tok = when (c) { 'n' -> `null`; 't' -> `true`; else -> `false` }
            }
            else -> {
                if (c != '-' && c !in '0'..'9') return fail(err(pos, "invalid character " + quoteRune(src, pos) + " at start of value"))
                val end = consumeNumber(src, pos)
                if (end == -2) return fail(errUnexpectedEOF)
                if (end == -1) {
                    var k = pos + 1
                    while (k < src.length && (src[k] in '0'..'9' || src[k] == '.' || src[k] == '-')) k++
                    return fail(err(k.coerceAtMost(src.length - 1), "invalid character " + quoteRune(src, k.coerceAtMost(src.length - 1)) + " in number"))
                }
                val text = src.substring(pos, end)
                pos = end
                countValue()
                tok = Token('0'.code, text, text.toDouble())
            }
        }
        prepared = false
        return Tuple2(tok, null)
    }

    private fun fail(e: GoError): Tuple2<Token, GoError?> {
        sticky = e
        return Tuple2(Token(), e)
    }

    /** `dec.ReadValue()`: the next complete value's raw bytes (validated). */
    fun readValue(): Tuple2<Value, GoError?> {
        val (s, e) = readValueText()
        return Tuple2(goStringToBytes(s), e)
    }

    /** [readValue] as a byte string. */
    internal fun readValueText(): Tuple2<String, GoError?> {
        sticky?.let { return Tuple2("", it) }
        prepare()?.let { sticky = it; return Tuple2("", it) }
        val start = pos
        val depth = kinds.size
        val (t0, e0) = readToken()
        if (e0 != null) return Tuple2("", e0)
        val k = t0.kind().value
        if (k == '}'.code || k == ']'.code) {
            return Tuple2("", fail(err(start, "invalid character " + quoteRune(src, start) + " at start of value")).second)
        }
        if (k == '{'.code || k == '['.code) {
            while (kinds.size > depth) {
                val (_, e) = readToken()
                if (e != null) return Tuple2("", e)
            }
        }
        return Tuple2(src.substring(start, pos), null)
    }

    /** `dec.SkipValue()`. */
    fun skipValue(): GoError? = readValueText().second

    /** `dec.InputOffset()`. */
    fun inputOffset(): Long = pos.toLong()

    /** `dec.StackDepth()`. */
    fun stackDepth(): Int = kinds.size

    /** Go's `Tokens.DepthLength()`: (depth with the top level as 1, values read at this depth). */
    internal fun depthLength(): Pair<Int, Int> = Pair(kinds.size + 1, if (kinds.isEmpty()) topLength else lengths.last())

    /** Go's `CheckEOF`: only whitespace may follow (after a complete top-level value). */
    internal fun checkEOF(): GoError? {
        sticky?.let { return it }
        skipWS()
        if (pos < src.length) return err(pos, "invalid character " + quoteRune(src, pos) + " after top-level value")
        return null
    }

    internal fun setSticky(e: GoError) {
        sticky = e
    }

    /** `dec.Reset(r, opts...)` is not provided; a [Decoder] is single-use. */
    fun goCopy(): Decoder = this
}

/** Reads [r] to EOF as a byte string. */
internal fun readAll(r: com.xemantic.typescript.tsgo.go.io.Reader?): Tuple2<String, GoError?> {
    if (r == null) throw NullPointerException("jsontext: nil io.Reader")
    val sb = StringBuilder()
    val buf = GoSlice.make(com.xemantic.typescript.tsgo.runtime.GoElem.BYTE, 4096)
    while (true) {
        val (n, e) = r.read(buf)
        for (i in 0 until n) sb.append(buf[i].toChar())
        if (e === com.xemantic.typescript.tsgo.go.io.EOF) return Tuple2(sb.toString(), null)
        if (e != null) return Tuple2(sb.toString(), e)
    }
}

/** `jsontext.NewDecoder(r, opts...)`: reads [r] to its end up front (a read error surfaces on the first read). */
fun newDecoder(r: com.xemantic.typescript.tsgo.go.io.Reader?, vararg opts: Options): Decoder {
    val (text, e) = readAll(r)
    val d = Decoder(text, Flags(opts))
    if (e != null) d.setSticky(e)
    return d
}

/** A decoder over a byte string (NOT Go: the shim's own entry point for `json.Unmarshal`). */
fun newDecoderString(s: String, vararg opts: Options): Decoder = Decoder(s, Flags(opts))


/**
 * `jsontext.Encoder`: writes tokens and raw values with Go's separators and (multiline) layout;
 * a stream encoder ([newEncoder]) writes to its `io.Writer` after every top-level value, followed
 * by a newline (Go's default), and `json.Marshal`/`MarshalWrite` omit that newline.
 */
class Encoder internal constructor(
    private val out: com.xemantic.typescript.tsgo.go.io.Writer?,
    internal val flags: Flags,
    internal val omitTopLevelNewline: Boolean,
) {
    internal val buf = StringBuilder()
    private val kinds = ArrayList<Boolean>()
    private val lengths = ArrayList<Int>()
    private val names = ArrayList<HashSet<String>?>()
    private var topLength = 0
    private var sticky: GoError? = null

    private fun depth(): Int = kinds.size + 1

    private fun lastLength(): Int = if (kinds.isEmpty()) topLength else lengths.last()

    private fun needImplicitColon(): Boolean = kinds.isNotEmpty() && kinds.last() && lengths.last() % 2 == 1

    private fun needImplicitComma(next: Int): Boolean =
        kinds.isNotEmpty() && lastLength() > 0 && next != '}'.code && next != ']'.code && !needImplicitColon()

    private fun needIndent(next: Int): Int {
        val willEnd = next == '}'.code || next == ']'.code
        return when {
            depth() == 1 -> 0
            lastLength() == 0 && willEnd -> 0
            lastLength() == 0 || needImplicitComma(next) -> depth()
            willEnd -> depth() - 1
            else -> 0
        }
    }

    private fun appendDelimAndWhitespace(next: Int) {
        if (needImplicitColon()) {
            buf.append(':')
            if (flags.multiline) buf.append(' ')
            return
        }
        if (needImplicitComma(next)) buf.append(',')
        if (flags.multiline) {
            val n = needIndent(next)
            if (n > 0) {
                buf.append('\n')
                buf.append(flags.indentPrefix ?: "")
                val ind = flags.indent ?: "\t"
                for (k in 1 until n) buf.append(ind)
            }
        }
    }

    private fun countValue() {
        if (kinds.isEmpty()) topLength++ else lengths[lengths.size - 1] = lengths.last() + 1
    }

    /** `enc.WriteToken(t)`. */
    fun writeToken(t: Token): GoError? {
        sticky?.let { return it }
        val k = t.kind().value
        if (k == 0) return fail("invalid jsontext.Token")
        if (kinds.isNotEmpty() && kinds.last() && lengths.last() % 2 == 0 && k != '"'.code && k != '}'.code) {
            return failErr(errNonStringName)
        }
        when (k) {
            '}'.code, ']'.code -> {
                val obj = k == '}'.code
                if (kinds.isEmpty() || kinds.last() != obj || (obj && lengths.last() % 2 == 1)) {
                    return fail("invalid " + t.kind().string() + " token")
                }
                appendDelimAndWhitespace(k)
                buf.append(k.toChar())
                kinds.removeAt(kinds.size - 1)
                lengths.removeAt(lengths.size - 1)
                names.removeAt(names.size - 1)
            }
            '{'.code, '['.code -> {
                appendDelimAndWhitespace(k)
                buf.append(k.toChar())
                countValue()
                kinds += k == '{'.code
                lengths += 0
                names += if (k == '{'.code && !flags.allowDuplicateNames) HashSet() else null
            }
            else -> {
                val (text, e) = t.jsonText(flags.allowInvalidUTF8)
                if (e != null) return failErr(e)
                if (k == '"'.code && kinds.isNotEmpty() && kinds.last() && lengths.last() % 2 == 0) {
                    val set = names.last()
                    if (set != null && !set.add(t.string())) return failErr(errDuplicateName)
                }
                appendDelimAndWhitespace(k)
                buf.append(text)
                countValue()
            }
        }
        return afterValue()
    }

    /** `enc.WriteValue(v)`: the raw value is re-tokenized (validated) and re-emitted in this encoder's layout. */
    fun writeValue(v: Value): GoError? = writeValueText(goBytesToString(v))

    internal fun writeValueText(text: String): GoError? {
        sticky?.let { return it }
        val d = Decoder(text, Flags(arrayOf(Options("AllowInvalidUTF8", flags.allowInvalidUTF8), Options("AllowDuplicateNames", true))))
        val depth0 = kinds.size
        do {
            val (t, e) = d.readToken()
            if (e != null) return failErr(e)
            writeToken(t)?.let { return it }
        } while (kinds.size > depth0)
        return d.checkEOF()?.let { failErr(it) }
    }

    private fun afterValue(): GoError? {
        if (kinds.isEmpty()) {
            if (!omitTopLevelNewline) buf.append('\n')
            if (out != null) {
                val e = out.write(goStringToBytes(buf.toString())).second
                buf.setLength(0)
                if (e != null) return failErr(e)
            }
        }
        return null
    }

    private fun fail(msg: String): GoError = failErr(SyntacticError(buf.length.toLong(), msg))

    private fun failErr(e: GoError): GoError {
        sticky = e
        return e
    }

    /** `enc.OutputOffset()`. */
    fun outputOffset(): Long = buf.length.toLong()

    /** `enc.StackDepth()`. */
    fun stackDepth(): Int = kinds.size

    internal fun depthLength(): Pair<Int, Int> = Pair(depth(), lastLength())

    fun goCopy(): Encoder = this
}

/** `jsontext.NewEncoder(w, opts...)`. */
fun newEncoder(w: com.xemantic.typescript.tsgo.go.io.Writer?, vararg opts: Options): Encoder = Encoder(w, Flags(opts), false)

/** `jsontext.AllowInvalidUTF8(v)`. */
fun allowInvalidUTF8(v: Boolean): Options = Options("AllowInvalidUTF8", v)

/** `jsontext.AllowDuplicateNames(v)`. */
fun allowDuplicateNames(v: Boolean): Options = Options("AllowDuplicateNames", v)

/** `jsontext.WithIndent(indent)`: panics on a non-space/tab indent, as Go. */
fun withIndent(indent: String): Options {
    if (indent.any { it != ' ' && it != '\t' }) goPanic("json: invalid character " + com.xemantic.typescript.tsgo.go.strconv.quote(indent.trim(' ', '\t').take(1)) + " in indent")
    return Options("WithIndent", indent)
}

/** `jsontext.WithIndentPrefix(prefix)`: panics on a non-space/tab prefix, as Go. */
fun withIndentPrefix(prefix: String): Options {
    if (prefix.any { it != ' ' && it != '\t' }) goPanic("json: invalid character " + com.xemantic.typescript.tsgo.go.strconv.quote(prefix.trim(' ', '\t').take(1)) + " in indent prefix")
    return Options("WithIndentPrefix", prefix)
}
