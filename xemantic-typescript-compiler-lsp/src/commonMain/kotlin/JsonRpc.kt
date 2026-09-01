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

package com.xemantic.typescript.compiler.lsp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 vocabulary for the LSP server: the error codes it answers with
 * and the two response shapes it writes.
 *
 * Messages are handled as a [JsonElement] TREE — parsed with
 * `Json.parseToJsonElement`, fields read by name — rather than through
 * `@Serializable` DTO mirrors of the protocol. That is a deliberate choice,
 * not a shortcut: the LSP surface is large, versioned and mostly optional
 * fields, so a DTO layer would either mirror far more of it than (LSP.1)
 * implements or silently drop what it did not model; the tree reads exactly
 * the fields each handler needs and is transparent to everything else.
 */

/** JSON-RPC 2.0: invalid JSON was received. */
internal const val PARSE_ERROR: Int = -32700

/** JSON-RPC 2.0: the JSON sent is not a valid request object. */
internal const val INVALID_REQUEST: Int = -32600

/** JSON-RPC 2.0: the method does not exist. */
internal const val METHOD_NOT_FOUND: Int = -32601

/** JSON-RPC 2.0: internal JSON-RPC error — a handler threw. */
internal const val INTERNAL_ERROR: Int = -32603

/**
 * A success response: `jsonrpc`, the request's own [id] echoed verbatim —
 * number and string ids alike, which is why it is carried as the parsed
 * [JsonElement] and never re-encoded through a Kotlin type — and [result],
 * which is ALWAYS present (`"result": null` is a real answer in LSP: a hover
 * with nothing to say).
 */
internal fun successResponse(id: JsonElement, result: JsonElement): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

/**
 * An error response with [code] and [message]. [id] is the request's id, or
 * `JsonNull` where no id could be read — the `-32700` case, where the spec
 * says `"id": null`.
 */
internal fun errorResponse(id: JsonElement, code: Int, message: String): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }.toString()

/** The `params` of this request as an object, or null when absent or not one. */
internal fun JsonObject.paramsObject(): JsonObject? = this["params"] as? JsonObject

/** The member [name] as a JSON string, or null when absent or not a string. */
internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The member [name] as a JSON number read as [Int], or null otherwise. */
internal fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

/** The member [name] as a nested object, or null when absent or not one. */
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

/**
 * The local filesystem path of a `file://` [uri], percent-decoded, or null for
 * any other scheme — an `untitled:` buffer, a `jar:` archive — which this
 * server does not model and must not mistake for a path.
 *
 * The authority (the part between `file://` and the next `/`, usually empty,
 * sometimes `localhost`) is dropped; everything after it is taken as the path —
 * LSP document URIs carry no query and no fragment.
 */
internal fun uriToPath(uri: String): String? {
    if (!uri.startsWith("file://")) return null
    val rest = uri.removePrefix("file://")
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    return percentDecode(rest.substring(slash))
}

/**
 * [s] with every `%XX` escape decoded, contiguous escape runs decoded as ONE
 * UTF-8 byte sequence — `%F0%9D%95%8F` is a single astral-plane character, not
 * four replacement chars — and everything else, malformed escapes included,
 * passed through literally.
 */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    val bytes = ArrayList<Byte>(8)
    fun flush() {
        if (bytes.isNotEmpty()) {
            out.append(bytes.toByteArray().decodeToString())
            bytes.clear()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hi = if (c == '%' && i + 2 < s.length) hexDigit(s[i + 1]) else -1
        val lo = if (hi >= 0) hexDigit(s[i + 2]) else -1
        if (hi >= 0 && lo >= 0) {
            bytes.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            flush()
            out.append(c)
            i += 1
        }
    }
    flush()
    return out.toString()
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
