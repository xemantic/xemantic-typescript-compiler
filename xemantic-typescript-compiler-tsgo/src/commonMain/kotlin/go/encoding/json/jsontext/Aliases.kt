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
