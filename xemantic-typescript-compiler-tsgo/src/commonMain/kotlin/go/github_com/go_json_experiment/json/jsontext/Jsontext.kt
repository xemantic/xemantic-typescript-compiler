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

package com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext

import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.Options

// `jsontext` (STUBS except the options): reached from `collections` (ordered-map JSON) and
// `internal/json`, neither of which the parser/encoder path uses. Go 1.27's `encoding/json/jsontext`
// is the same package (go-json-experiment aliases it), so the lowering maps both import paths here.

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
 * `jsontext.Token`: one JSON token as a VALUE — its [Kind] and, for a string or number, its value.
 * Go's `Token` is a struct (zero value = the invalid token, kind 0); it is immutable here, so
 * [goCopy] returns the token itself. Only the token VALUES are implemented: the streaming
 * `Encoder`/`Decoder` below are still stubs.
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
        else -> com.xemantic.typescript.tsgo.runtime.goPanic("invalid JSON token kind: " + kind().string())
    }

    /** `t.Float()` for a number token. */
    fun float(): Double {
        if (k.toChar() != '0') com.xemantic.typescript.tsgo.runtime.goPanic("invalid JSON token kind: " + kind().string())
        return num
    }

    fun goCopy(): Token = this

    override fun toString(): String = string()
}

const val BeginObjectByte: Int = '{'.code

/** `jsontext.BeginObject`, `EndObject`, `BeginArray`, `EndArray`, `Null`, `True`, `False`. */
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
typealias Value = com.xemantic.typescript.tsgo.runtime.GoSlice<Int>

/** `jsontext.Encoder` (stub). */
class Encoder {
    fun writeToken(t: Token): com.xemantic.typescript.tsgo.runtime.GoError? = TODO("shim: jsontext.Encoder.WriteToken $t")
}

/** `jsontext.Decoder` (stub). */
class Decoder {
    fun peekKind(): Kind = TODO("shim: jsontext.Decoder.PeekKind")
    fun readToken(): com.xemantic.typescript.tsgo.runtime.Tuple2<Token, com.xemantic.typescript.tsgo.runtime.GoError?> =
        TODO("shim: jsontext.Decoder.ReadToken")
}

/** `jsontext.NewDecoder(r, opts...)` (stub). */
@Suppress("UNUSED_PARAMETER")
fun newDecoder(r: com.xemantic.typescript.tsgo.go.io.Reader?, vararg opts: Options): Decoder = TODO("shim: jsontext.NewDecoder")

/** `jsontext.AllowInvalidUTF8(v)`. */
fun allowInvalidUTF8(v: Boolean): Options = Options("AllowInvalidUTF8", v)

/** `jsontext.AllowDuplicateNames(v)`. */
fun allowDuplicateNames(v: Boolean): Options = Options("AllowDuplicateNames", v)

/** `jsontext.WithIndent(indent)`. */
fun withIndent(indent: String): Options = Options("WithIndent", indent)

/** `jsontext.WithIndentPrefix(prefix)`. */
fun withIndentPrefix(prefix: String): Options = Options("WithIndentPrefix", prefix)
