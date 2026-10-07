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

package com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json

import com.xemantic.typescript.tsgo.go.strconv.formatFloat
import com.xemantic.typescript.tsgo.go.strconv.formatInt
import com.xemantic.typescript.tsgo.go.strconv.formatUint
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goStringToBytes

// The go-json-experiment `json` subset the spike closure reaches. `Marshal` of a float64 is the
// one LOAD-BEARING use: the scanner normalizes every numeric literal's text through
// jsnum.Number.String(), which formats non-safe-integers with json.Marshal(float64) — so
// [marshal] of Double is the exact port of jsonwire.AppendFloat (ES6 Number::toString, except
// that -0 prints as "-0"). Other Go values marshal for the simple kinds below; structs, maps,
// slices and the streaming API are stubs.

/** `json.Options`: an opaque option value (name, value). */
class Options(val name: String, val value: Any?)

/** `json.Deterministic(v)`. */
fun deterministic(v: Boolean): Options = Options("Deterministic", v)

/** `json.MarshalerTo` (stub interface). */
interface MarshalerTo

/** `json.UnmarshalerFrom` (stub interface). */
interface UnmarshalerFrom

/** jsonwire.AppendFloat for bits = 64: ES6 number formatting, `e` exponent without a leading zero. */
internal fun formatJsonFloat(src: Double): String {
    val abs = kotlin.math.abs(src)
    var fmt = 'f'
    if (abs != 0.0 && (abs < 1e-6 || abs >= 1e21)) fmt = 'e'
    var s = formatFloat(src, fmt.code, -1, 64)
    if (fmt == 'e') {
        val n = s.length
        if (n >= 4 && s[n - 4] == 'e' && s[n - 3] == '-' && s[n - 2] == '0') {
            s = s.substring(0, n - 2) + s[n - 1]
        }
    }
    return s
}

/**
 * `json.Marshal(in, opts...)` → (bytes, err). Exact for `float64` (incl. the NaN/Inf error),
 * integers, booleans, nil and ASCII-only strings without characters needing escapes; TODO for
 * everything else.
 */
fun marshal(input: Any?, vararg opts: Options): Tuple2<GoSlice<Int>, GoError?> {
    val text = when (input) {
        null -> "null"
        is Boolean -> if (input) "true" else "false"
        is Double -> {
            if (input.isNaN() || input.isInfinite()) {
                return Tuple2(com.xemantic.typescript.tsgo.runtime.GoElem.INT.nilSlice,
                    GoPlainError("json: cannot marshal from Go float64: unsupported value: ${formatFloat(input, 'g'.code, -1, 64)}"))
            }
            formatJsonFloat(input)
        }
        is Int -> formatInt(input.toLong(), 10)
        is Long -> formatInt(input, 10)
        is UInt -> formatUint(input.toULong(), 10)
        is ULong -> formatUint(input, 10)
        is String -> {
            if (input.any { it.code < 0x20 || it.code >= 0x7F || it == '"' || it == '\\' || it == '<' || it == '>' || it == '&' }) {
                TODO("shim: json.Marshal of a string needing escapes (opts: ${opts.size})")
            }
            "\"" + input + "\""
        }
        else -> TODO("shim: json.Marshal of ${input::class.simpleName}")
    }
    return Tuple2(goStringToBytes(text), null)
}

/** `json.Unmarshal` (stub). */
@Suppress("UNUSED_PARAMETER")
fun unmarshal(input: GoSlice<Int>, out: Any?, vararg opts: Options): GoError? = TODO("shim: json.Unmarshal")

/** `json.MarshalWrite` (stub). */
@Suppress("UNUSED_PARAMETER")
fun marshalWrite(out: com.xemantic.typescript.tsgo.go.io.Writer?, input: Any?, vararg opts: Options): GoError? = TODO("shim: json.MarshalWrite")

/** `json.MarshalEncode` (stub). */
@Suppress("UNUSED_PARAMETER")
fun marshalEncode(out: com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Encoder?, input: Any?, vararg opts: Options): GoError? =
    TODO("shim: json.MarshalEncode")

/** `json.UnmarshalRead` (stub). */
@Suppress("UNUSED_PARAMETER")
fun unmarshalRead(input: com.xemantic.typescript.tsgo.go.io.Reader?, out: Any?, vararg opts: Options): GoError? = TODO("shim: json.UnmarshalRead")

/** `json.UnmarshalDecode` (stub). */
@Suppress("UNUSED_PARAMETER")
fun unmarshalDecode(input: com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Decoder?, out: Any?, vararg opts: Options): GoError? =
    TODO("shim: json.UnmarshalDecode")
