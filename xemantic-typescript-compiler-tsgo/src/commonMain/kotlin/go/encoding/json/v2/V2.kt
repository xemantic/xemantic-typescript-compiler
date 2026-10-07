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

package com.xemantic.typescript.tsgo.go.encoding.json.v2

// Go 1.27's `encoding/json/v2` — the package go-json-experiment's `json` ALIASES, so `go/types`
// names these interfaces under this path (the extractor's "closure types satisfying EXTERNAL
// interfaces" table: `core.Tristate`, `packagejson.JSONValue`, `packagejson.ExportsOrImports`).
// The marshal/unmarshal machinery lives in the go-json-experiment shim, whose `json` package
// typealiases these. A generated type that has the Go method must declare the interface
// (docs/goport-runtime.md § 8), or the shim takes the default path.

import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Decoder
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Encoder
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2

/** `json.Marshaler`: `MarshalJSON() ([]byte, error)`. */
interface Marshaler {
    fun marshalJSON(): Tuple2<GoSlice<Int>, GoError?>
}

/** `json.MarshalerTo`: `MarshalJSONTo(*jsontext.Encoder) error`. */
interface MarshalerTo {
    fun marshalJSONTo(enc: Encoder?): GoError?
}

/** `json.Unmarshaler`: `UnmarshalJSON([]byte) error`. */
interface Unmarshaler {
    fun unmarshalJSON(data: GoSlice<Int>): GoError?
}

/** `json.UnmarshalerFrom`: `UnmarshalJSONFrom(*jsontext.Decoder) error`. */
interface UnmarshalerFrom {
    fun unmarshalJSONFrom(dec: Decoder?): GoError?
}
