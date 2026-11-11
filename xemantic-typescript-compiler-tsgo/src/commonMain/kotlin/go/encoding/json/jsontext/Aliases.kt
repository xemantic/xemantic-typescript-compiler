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

package com.xemantic.typescript.tsgo.go.encoding.json.jsontext

// Go 1.27's standard `encoding/json/jsontext` is what go-json-experiment's `jsontext` aliases,
// so `go/types` reports METHOD selections on these types under this import path. Same shims.

typealias Kind = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Kind
typealias Token = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Token
typealias Encoder = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Encoder
typealias Decoder = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Decoder
typealias Value = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Value

/** `v.Kind()` (an extension: a method of the typealias). */
fun Value.kind(): Kind = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.valueKind(this)

// ---- TypeScript 7.1: `internal/json` calls the standard package directly. Delegates to the go-json-experiment shim.

private typealias Options = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.Options

/** `jsontext.AllowInvalidUTF8(v)`. */
fun allowInvalidUTF8(v: Boolean): Options = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.allowInvalidUTF8(v)

/** `jsontext.AllowDuplicateNames(v)`. */
fun allowDuplicateNames(v: Boolean): Options = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.allowDuplicateNames(v)

/** `jsontext.WithIndent(indent)`. */
fun withIndent(indent: String): Options = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.withIndent(indent)

/** `jsontext.WithIndentPrefix(prefix)`. */
fun withIndentPrefix(prefix: String): Options = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.withIndentPrefix(prefix)

/** `jsontext.NewDecoder(r, opts...)`. */
fun newDecoder(r: com.xemantic.typescript.tsgo.go.io.Reader?, vararg opts: Options): Decoder = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.newDecoder(r, *opts)

/** `jsontext.NewEncoder(w, opts...)`. */
fun newEncoder(w: com.xemantic.typescript.tsgo.go.io.Writer?, vararg opts: Options): Encoder = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.newEncoder(w, *opts)

/** `jsontext.BeginObject`, `EndObject`, `BeginArray`, `EndArray`, `Null`, `True`, `False` (vars). */
val beginObject: Token get() = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.beginObject
val endObject: Token get() = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.endObject
val beginArray: Token get() = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.beginArray
val endArray: Token get() = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.endArray
val `null`: Token get() = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.`null`
val `true`: Token get() = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.`true`
val `false`: Token get() = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.`false`

/** `v.Clone()`: a copy of the raw value's bytes. */
fun Value.clone(): Value = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.valueClone(this)
