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

/** `jsontext.Kind`. */
@kotlin.jvm.JvmInline
value class Kind(val value: Int)

/** `jsontext.Token` (stub). */
class Token {
    fun kind(): Kind = TODO("shim: jsontext.Token.Kind")
}

const val BeginObjectByte: Int = '{'.code

/** `jsontext.BeginObject` and friends (tokens; stubs). */
val beginObject: Token get() = TODO("shim: jsontext.BeginObject")
val endObject: Token get() = TODO("shim: jsontext.EndObject")
val beginArray: Token get() = TODO("shim: jsontext.BeginArray")
val endArray: Token get() = TODO("shim: jsontext.EndArray")
val `null`: Token get() = TODO("shim: jsontext.Null")

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
