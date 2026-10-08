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

// Parts of this file are translated from github.com/go-json-experiment/json (the version tsgo
// v7.0.2 pins), Copyright The Go Authors, used under its BSD-style licence: see LICENSE-GO.

package com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json

// go-json-experiment `json` (= Go 1.27's `encoding/json/v2`) WITHOUT reflection. Values are
// encoded and decoded by their RUNTIME representation (docs/goport-design.md § 3): `nil`,
// `Boolean`, `String` (byte string), the integer kinds, `Double`, `GoSlice`, `GoArray`, `GoMap`
// (string keys), `GoPtr`; a type with `MarshalJSONTo`/`MarshalJSON`/`MarshalText` or
// `UnmarshalJSONFrom`/`UnmarshalJSON` is dispatched to (the `encoding/json/v2` interfaces);
// and a STRUCT must expose its JSON members through [GoJsonStruct] (the reflection stand-in).
//
// Unmarshal into a pointer decides the Go type from the pointer's CURRENT value — the zero value
// a generated field or local starts with: `""` → string, `false` → bool, `0.0` → float64, an
// integer kind → that integer, a (nil) `GoMap` → `map[string]V` with V from its element kind, a
// (nil) `GoSlice` → `[]E` likewise, and `null` → `any` (objects → `map[string]any`, arrays →
// `[]any`, numbers → float64). v2 semantics that tsgo's package.json reading relies on are kept:
// every unmarshal error is FATAL (the first one is returned, earlier map entries stay), JSON `null`
// sets the zero value, a map is MERGED into, a slice is replaced, unknown struct members are
// skipped, member names match EXACTLY (case-sensitive), and duplicate names / invalid UTF-8 are
// rejected unless allowed. Approximations in docs/goport-runtime.md § 10.

import com.xemantic.typescript.tsgo.go.encoding.TextMarshaler
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Decoder
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Encoder
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Flags
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.newDecoderString
import com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.readAll
import com.xemantic.typescript.tsgo.go.strconv.formatFloat
import com.xemantic.typescript.tsgo.runtime.GoArray
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoPtr
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes

/** `json.Options`: an opaque option value (name, value). */
class Options(val name: String, val value: Any?)

/** `json.Deterministic(v)`. */
fun deterministic(v: Boolean): Options = Options("Deterministic", v)

/** `json.Marshaler`, `MarshalerTo`, `Unmarshaler`, `UnmarshalerFrom` (= `encoding/json/v2`'s). */
typealias Marshaler = com.xemantic.typescript.tsgo.go.encoding.json.v2.Marshaler
typealias MarshalerTo = com.xemantic.typescript.tsgo.go.encoding.json.v2.MarshalerTo
typealias Unmarshaler = com.xemantic.typescript.tsgo.go.encoding.json.v2.Unmarshaler
typealias UnmarshalerFrom = com.xemantic.typescript.tsgo.go.encoding.json.v2.UnmarshalerFrom

/** `json.Decoder`, `json.Encoder` (aliases of the `jsontext` ones, as in v2). */
typealias Decoder = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Decoder
typealias Encoder = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.Encoder

/**
 * NOT Go — the reflection stand-in for a STRUCT: its JSON members in Go's order (declaration
 * order, embedded structs' members flattened in place, `json:"-"` dropped), each with the name
 * after its `json:` tag and a pointer to the field. A generated struct that reaches `json` must
 * implement it (or its caller is overridden).
 */
interface GoJsonStruct {
    fun goJsonFields(): List<JsonField>
}

/** One [GoJsonStruct] member: `omitzero`/`omitempty` per its tag; [isZero] answers `omitzero`. */
class JsonField(
    val name: String,
    val ptr: GoPtr<Any?>,
    val omitEmpty: Boolean = false,
    val omitZero: Boolean = false,
    val isZero: ((Any?) -> Boolean)? = null,
)

/** `json.SemanticError` (message only; Go's JSON pointer and Go type are not tracked). */
class SemanticError(private val msg: String) : GoError {
    override fun error(): String = "json: $msg"
    override fun toString(): String = error()
}

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

// ---- marshal ----

/** `json.Marshal(in, opts...)` → (bytes, err): Go's compact (or, with an indent option, multiline) text. */
fun marshal(input: Any?, vararg opts: Options): Tuple2<GoSlice<Int>, GoError?> {
    val enc = Encoder(null, Flags(opts), true)
    val err = marshalValue(enc, input)
    if (err != null && input is Double) return Tuple2(GoElem.BYTE.nilSlice, err)
    return Tuple2(goStringToBytes(enc.buf.toString()), err)
}

/** `json.MarshalWrite(out, in, opts...)`. */
fun marshalWrite(out: com.xemantic.typescript.tsgo.go.io.Writer?, input: Any?, vararg opts: Options): GoError? {
    val enc = Encoder(out, Flags(opts), true)
    return marshalValue(enc, input)
}

/** `json.MarshalEncode(enc, in, opts...)`: options other than the encoder's own are ignored. */
fun marshalEncode(out: Encoder?, input: Any?, vararg opts: Options): GoError? {
    val enc = out ?: throw NullPointerException("json: nil *jsontext.Encoder")
    for (o in opts) if (o.name == "Deterministic") enc.flags.add(o)
    return marshalValue(enc, input)
}

private fun goTypeName(v: Any?): String = when (v) {
    is Boolean -> "bool"
    is String -> "string"
    is Int -> "int"
    is Long -> "int64"
    is Double -> "float64"
    is Float -> "float32"
    is UInt -> "uint32"
    is ULong -> "uint64"
    is GoSlice<*> -> "slice"
    is GoMap<*, *> -> "map"
    null -> "nil"
    else -> v::class.simpleName ?: "value"
}

internal fun marshalValue(enc: Encoder, v: Any?): GoError? {
    when (v) {
        null -> return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.`null`)
        is MarshalerTo -> {
            val (d0, l0) = enc.depthLength()
            val e = v.marshalJSONTo(enc)
            if (e != null) return e
            val (d1, l1) = enc.depthLength()
            if (d0 != d1 || l0 + 1 != l1) return SemanticError("must write exactly one JSON value")
            return null
        }
        is Marshaler -> {
            val (b, e) = v.marshalJSON()
            if (e != null) return e
            return enc.writeValue(b)
        }
        is TextMarshaler -> {
            val (b, e) = v.marshalText()
            if (e != null) return e
            return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.string(goBytesToString(b)))
        }
        is GoJsonStruct -> {
            enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.beginObject)?.let { return it }
            for (f in v.goJsonFields()) {
                val fv = f.ptr.value
                if (f.omitZero && (f.isZero?.invoke(fv) ?: isZeroValue(fv))) continue
                if (f.omitEmpty) {
                    val probe = Encoder(null, Flags(emptyArray()), true)
                    if (marshalValue(probe, fv) == null && probe.buf.toString() in EMPTY_JSON) continue
                }
                enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.string(f.name))?.let { return it }
                marshalValue(enc, fv)?.let { return it }
            }
            return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.endObject)
        }
        // A named basic type (a generated value class, `incremental.BuildInfoFileId`) with no marshaler
        // method marshals as its underlying kind, as Go's arshaler for that reflect.Kind does.
        is com.xemantic.typescript.tsgo.runtime.GoBasicValue -> return marshalValue(enc, v.goRaw)
        is Boolean -> return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.bool(v))
        is String -> return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.string(v))
        is Int -> return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.int(v.toLong()))
        is Long -> return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.int(v))
        is UInt -> return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.uint(v.toULong()))
        is ULong -> return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.uint(v))
        is Double -> {
            if (v.isNaN() || v.isInfinite()) {
                return SemanticError("cannot marshal from Go float64: unsupported value: " + formatFloat(v, 'g'.code, -1, 64))
            }
            return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.float(v))
        }
        is GoSlice<*> -> {
            enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.beginArray)?.let { return it }
            for (i in 0 until v.len) marshalValue(enc, v[i])?.let { return it }
            return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.endArray)
        }
        is GoArray<*> -> return marshalValue(enc, v.slice())
        is GoMap<*, *> -> {
            enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.beginObject)?.let { return it }
            val entries = ArrayList<Pair<String, Any?>>()
            var keyErr: GoError? = null
            @Suppress("UNCHECKED_CAST")
            (v as GoMap<Any?, Any?>).range { k, value ->
                val name = when (k) {
                    is String -> k
                    is Int, is Long, is UInt, is ULong -> k.toString()
                    is TextMarshaler -> {
                        val (b, e) = k.marshalText()
                        if (e != null) keyErr = e
                        goBytesToString(b)
                    }
                    is com.xemantic.typescript.tsgo.runtime.GoBasicValue -> when (val raw = k.goRaw) {
                        is String -> raw
                        is Int, is Long, is UInt, is ULong -> raw.toString()
                        else -> {
                            keyErr = SemanticError("cannot marshal map key of Go type " + goTypeName(k))
                            ""
                        }
                    }
                    else -> {
                        keyErr = SemanticError("cannot marshal map key of Go type " + goTypeName(k))
                        ""
                    }
                }
                entries += Pair(name, value)
                true
            }
            keyErr?.let { return it }
            entries.sortWith { a, b -> a.first.compareTo(b.first) }
            for ((name, value) in entries) {
                enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.string(name))?.let { return it }
                marshalValue(enc, value)?.let { return it }
            }
            return enc.writeToken(com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.endObject)
        }
        is GoPtr<*> -> return marshalValue(enc, v.value)
        else -> return SemanticError("cannot marshal from Go " + goTypeName(v))
    }
}

private val EMPTY_JSON = setOf("null", "\"\"", "{}", "[]")

private fun isZeroValue(v: Any?): Boolean = when (v) {
    null -> true
    is Boolean -> !v
    is String -> v.isEmpty()
    is Int -> v == 0
    is Long -> v == 0L
    is UInt -> v == 0u
    is ULong -> v == 0uL
    is Double -> v == 0.0 && 1.0 / v > 0
    is GoSlice<*> -> v.isNil
    is GoMap<*, *> -> v.isNil
    is com.xemantic.typescript.tsgo.runtime.GoBasicValue -> isZeroValue(v.goRaw)
    else -> false
}

// ---- unmarshal ----

/** `json.Unmarshal(in, out, opts...)`: exactly one JSON value (whitespace around it allowed). */
fun unmarshal(input: GoSlice<Int>, out: Any?, vararg opts: Options): GoError? = unmarshalFull(goBytesToString(input), out, opts)

/** `json.UnmarshalRead(in, out, opts...)`: reads [input] to EOF, then as [unmarshal]. */
fun unmarshalRead(input: com.xemantic.typescript.tsgo.go.io.Reader?, out: Any?, vararg opts: Options): GoError? {
    val (text, e) = readAll(input)
    if (e != null) return e
    return unmarshalFull(text, out, opts)
}

private fun unmarshalFull(text: String, out: Any?, opts: Array<out Options>): GoError? {
    val dec = newDecoderString(text, *opts)
    var err = unmarshalInto(dec, out)
    if (err === com.xemantic.typescript.tsgo.go.io.EOF) err = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.errUnexpectedEOF
    if (err != null) return err
    return dec.checkEOF()
}

/** `json.UnmarshalDecode(dec, out, opts...)`: the next value of [input]; decoder options apply. */
fun unmarshalDecode(input: Decoder?, out: Any?, vararg opts: Options): GoError? {
    val dec = input ?: throw NullPointerException("json: nil *jsontext.Decoder")
    @Suppress("UNUSED_VARIABLE") val ignored = opts
    return unmarshalInto(dec, out)
}

/** Decodes the next value into [out] (a pointer, an unmarshaler, or a [GoJsonStruct]). */
internal fun unmarshalInto(dec: Decoder, out: Any?): GoError? {
    return when (out) {
        null -> SemanticError("cannot unmarshal into Go value of type <nil>")
        is UnmarshalerFrom -> {
            val (d0, l0) = dec.depthLength()
            val e = out.unmarshalJSONFrom(dec)
            if (e != null) return e
            val (d1, l1) = dec.depthLength()
            if (d0 != d1 || l0 + 1 != l1) SemanticError("must read exactly one JSON value") else null
        }
        is Unmarshaler -> {
            val (raw, e) = dec.readValue()
            if (e != null) return e
            out.unmarshalJSON(raw)
        }
        is GoJsonStruct -> unmarshalStruct(dec, out)
        is GoPtr<*> -> {
            val cur = out.value
            if (cur is UnmarshalerFrom || cur is Unmarshaler || cur is GoJsonStruct) return unmarshalInto(dec, cur)
            val (v, e) = decodeShaped(dec, cur)
            @Suppress("UNCHECKED_CAST")
            (out as GoPtr<Any?>).value = v
            e
        }
        else -> SemanticError("cannot unmarshal into non-pointer Go value of type " + goTypeName(out))
    }
}

/** Decodes into a fresh or existing element value; a struct/unmarshaler is filled IN PLACE and returned. */
private fun decodeElement(dec: Decoder, cur: Any?): Tuple2<Any?, GoError?> {
    if (cur is UnmarshalerFrom || cur is Unmarshaler || cur is GoJsonStruct) return Tuple2(cur, unmarshalInto(dec, cur))
    return decodeShaped(dec, cur)
}

private fun mismatch(dec: Decoder, kind: Int, goType: String): GoError =
    SemanticError("cannot unmarshal JSON " + jsonKindName(kind) + " into Go " + goType + " within value ending at offset " + dec.inputOffset())

private fun jsonKindName(k: Int): String = when (k.toChar()) {
    'n' -> "null"
    't', 'f' -> "boolean"
    '"' -> "string"
    '0' -> "number"
    '{' -> "object"
    '[' -> "array"
    else -> "value"
}

/**
 * Whether a named NUMERIC type decodes as its underlying number: the LSP protocol's ((TSGO.4-a),
 * `lsproto.CompletionItemKind`, `DiagnosticSeverity`, … in requests and capabilities) do; any other stays
 * refused (docs/goport-runtime.md § 10 #14: `incremental`'s buildinfo reader would get past one gap into two more).
 */
private fun decodesNamedNumber(v: Any): Boolean =
    v::class.qualifiedName?.startsWith("com.xemantic.typescript.tsgo.lsp.lsproto.") == true

/** Decodes the next value as the Go type the CURRENT value [cur] stands for (see the file comment). */
private fun decodeShaped(dec: Decoder, cur: Any?): Tuple2<Any?, GoError?> {
    when (cur) {
        null -> return decodeAny(dec)
        is GoMap<*, *> -> return decodeMap(dec, cur)
        is GoSlice<*> -> return decodeSlice(dec, cur)
        // A named STRING type (a value class: an element of `[]api.NodeHandle`) decodes as its underlying
        // value, rewrapped ((TSGO.3-b)). A named NUMERIC type still refuses (docs/goport-runtime.md § 10 #14):
        // decoding it lets `incremental`'s buildinfo reader get past `[][]BuildInfoFileId` into two more
        // gaps (`[]*BuildInfoFileInfo` and `*[2]BuildInfoFileId` decode as `any`), so it stays failing
        // where it failed before — the program is rebuilt, and the diagnostics agree either way.
        is com.xemantic.typescript.tsgo.runtime.GoBasicValue -> if (cur.goRaw is String || decodesNamedNumber(cur)) {
            val (v, e) = decodeShaped(dec, cur.goRaw)
            return Tuple2(if (e == null && v != null) cur.goWithRaw(v) else cur, e)
        }
        else -> {}
    }
    val (raw, e) = dec.readValueText()
    if (e != null) return Tuple2(cur, e)
    val k = raw[0].code
    val kind = if (k == '-'.code || k in '0'.code..'9'.code) '0'.code else k
    if (kind == 'n'.code) {
        return Tuple2(
            when (cur) {
                is String -> ""
                is Boolean -> false
                is Int -> 0
                is Long -> 0L
                is UInt -> 0u
                is ULong -> 0uL
                is Double -> 0.0
                is Float -> 0.0f
                else -> cur
            },
            null,
        )
    }
    return when (cur) {
        is String -> if (kind == '"'.code) Tuple2(stringValue(raw), null) else Tuple2(cur, mismatch(dec, kind, "string"))
        is Boolean -> when (kind) {
            't'.code -> Tuple2(true, null)
            'f'.code -> Tuple2(false, null)
            else -> Tuple2(cur, mismatch(dec, kind, "bool"))
        }
        is Double, is Float -> {
            if (kind != '0'.code) return Tuple2(cur, mismatch(dec, kind, if (cur is Double) "float64" else "float32"))
            val (f, perr) = com.xemantic.typescript.tsgo.go.strconv.parseFloat(raw, 64)
            val v: Any = if (cur is Double) f else f.toFloat()
            Tuple2(v, if (perr != null) SemanticError("cannot unmarshal JSON number $raw into Go ${if (cur is Double) "float64" else "float32"}: value out of range") else null)
        }
        is Int, is Long -> {
            if (kind != '0'.code) return Tuple2(cur, mismatch(dec, kind, if (cur is Int) "int" else "int64"))
            val n = raw.toLongOrNull()
            val bits = if (cur is Int) 32 else 64
            if (n == null || (bits == 32 && (n < Int.MIN_VALUE || n > Int.MAX_VALUE))) {
                Tuple2(cur, SemanticError("cannot unmarshal JSON number $raw into Go ${if (cur is Int) "int" else "int64"}: " + if (raw.any { it == '.' || it == 'e' || it == 'E' }) "invalid syntax" else "value out of range"))
            } else {
                Tuple2(if (cur is Int) n.toInt() else n, null)
            }
        }
        is UInt, is ULong -> {
            if (kind != '0'.code) return Tuple2(cur, mismatch(dec, kind, if (cur is UInt) "uint32" else "uint64"))
            val n = raw.toULongOrNull()
            if (n == null || (cur is UInt && n > UInt.MAX_VALUE.toULong())) {
                Tuple2(cur, SemanticError("cannot unmarshal JSON number $raw into Go ${if (cur is UInt) "uint32" else "uint64"}: " + if (raw.startsWith("-") || raw.any { it == '.' || it == 'e' || it == 'E' }) "invalid syntax" else "value out of range"))
            } else {
                Tuple2(if (cur is UInt) n.toUInt() else n, null)
            }
        }
        else -> Tuple2(cur, SemanticError("cannot unmarshal into Go " + goTypeName(cur)))
    }
}

/** The (validated) JSON string text [raw] unquoted. */
private fun stringValue(raw: String): String =
    com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.jsontext.unquote(raw, 0, raw.length)

/** `any`: objects → `map[string]any`, arrays → `[]any`, numbers → float64. */
private fun decodeAny(dec: Decoder): Tuple2<Any?, GoError?> {
    // Go takes its reflective path for `any` when AllowDuplicateNames is set: it first makes the
    // zero value of the PEEKED kind, which a failed scalar read then leaves behind (oracle-tested).
    val peek = if (dec.flags.allowDuplicateNames) dec.peekKind().value else 0
    val (t, e) = dec.readToken()
    if (e != null) {
        val zero: Any? = when (peek.toChar()) {
            '0' -> 0.0
            '"' -> ""
            't', 'f' -> false
            else -> null
        }
        return Tuple2(zero, e)
    }
    return when (t.kind().value.toChar()) {
        'n' -> Tuple2(null, null)
        't' -> Tuple2(true, null)
        'f' -> Tuple2(false, null)
        '"' -> Tuple2(t.string(), null)
        '0' -> {
            val (f, perr) = com.xemantic.typescript.tsgo.go.strconv.parseFloat(t.string(), 64)
            Tuple2(f, if (perr != null) SemanticError("cannot unmarshal JSON number ${t.string()} into Go float64: value out of range") else null)
        }
        '{' -> {
            val m = GoMap.make<String, Any?>(GoElem.ref())
            while (dec.peekKind().value != '}'.code) {
                val (nt, ne) = dec.readToken()
                if (ne != null) return Tuple2(m, ne)
                val (v, ve) = decodeAny(dec)
                m[nt.string()] = v
                if (ve != null) return Tuple2(m, ve)
            }
            val (_, ce) = dec.readToken()
            Tuple2(m, ce)
        }
        '[' -> {
            var s: GoSlice<Any?> = GoElem.ref<Any?>().nilSlice
            while (dec.peekKind().value != ']'.code) {
                val (v, ve) = decodeAny(dec)
                if (ve != null) return Tuple2(s.append1(v), ve)
                s = s.append1(v)
            }
            if (s.isNil) s = GoSlice.make(GoElem.ref(), 0)
            val (_, ce) = dec.readToken()
            Tuple2(s, ce)
        }
        else -> Tuple2(null, SemanticError("unexpected token"))
    }
}

/** `map[string]V`: null → nil; an object is merged into [cur] (allocated if nil); a value starts from the existing entry. */
private fun decodeMap(dec: Decoder, cur: GoMap<*, *>): Tuple2<Any?, GoError?> {
    @Suppress("UNCHECKED_CAST")
    val m0 = cur as GoMap<Any?, Any?>
    val (t, e) = dec.readToken()
    if (e != null) return Tuple2(cur, e)
    val k = t.kind().value
    if (k == 'n'.code) return Tuple2(GoMap.nil<Any?, Any?>(m0.elem), null)
    if (k != '{'.code) {
        if (k == '['.code) dec.skipRestOfContainer()
        return Tuple2(cur, mismatch(dec, k, "map"))
    }
    val m = if (m0.isNil) GoMap.make<Any?, Any?>(m0.elem) else m0
    while (dec.peekKind().value != '}'.code) {
        val (nt, ne) = dec.readToken()
        if (ne != null) return Tuple2(m, ne)
        val key = nt.string()
        val (existing, present) = m.lookup(key)
        val start = if (present) existing else m.elem.zeroValue()
        val (v, ve) = decodeElement(dec, start)
        m[key] = v
        if (ve != null) return Tuple2(m, ve)
    }
    val (_, ce) = dec.readToken()
    return Tuple2(m, ce)
}

/** `[]E`: null → nil; an array → a new slice of fresh zero elements each decoded; `[]` → empty non-nil. */
private fun decodeSlice(dec: Decoder, cur: GoSlice<*>): Tuple2<Any?, GoError?> {
    @Suppress("UNCHECKED_CAST")
    val s0 = cur as GoSlice<Any?>
    val (t, e) = dec.readToken()
    if (e != null) return Tuple2(cur, e)
    val k = t.kind().value
    if (k == 'n'.code) return Tuple2(s0.elem.nilSlice, null)
    if (k != '['.code) {
        if (k == '{'.code) dec.skipRestOfContainer()
        return Tuple2(cur, mismatch(dec, k, "slice"))
    }
    var s = s0.elem.nilSlice
    while (dec.peekKind().value != ']'.code) {
        val (v, ve) = decodeElement(dec, s0.elem.zeroValue())
        s = s.append1(v)
        if (ve != null) return Tuple2(s, ve)
    }
    if (s.isNil) s = GoSlice.make(s0.elem, 0)
    val (_, ce) = dec.readToken()
    return Tuple2(s, ce)
}

/** A [GoJsonStruct]: null leaves it unchanged (Go zeroes it); unknown members are skipped. */
private fun unmarshalStruct(dec: Decoder, out: GoJsonStruct): GoError? {
    val (t, e) = dec.readToken()
    if (e != null) return e
    val k = t.kind().value
    if (k == 'n'.code) return null
    if (k != '{'.code) {
        if (k == '['.code) dec.skipRestOfContainer()
        return mismatch(dec, k, "struct")
    }
    val fields = out.goJsonFields()
    val byName = HashMap<String, JsonField>(fields.size * 2)
    for (f in fields) if (f.name !in byName) byName[f.name] = f
    while (dec.peekKind().value != '}'.code) {
        val (nt, ne) = dec.readToken()
        if (ne != null) return ne
        val f = byName[nt.string()]
        if (f == null) {
            dec.skipValue()?.let { return it }
            continue
        }
        unmarshalInto(dec, f.ptr)?.let { return it }
    }
    return dec.readToken().second
}

/** Skips to the end of the container whose opening token was just read. */
private fun Decoder.skipRestOfContainer() {
    val depth = stackDepth() - 1
    while (stackDepth() > depth) {
        if (readToken().second != null) return
    }
}

