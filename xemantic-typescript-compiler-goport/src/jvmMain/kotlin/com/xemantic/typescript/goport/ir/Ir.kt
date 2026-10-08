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

package com.xemantic.typescript.goport.ir

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import kotlin.io.encoding.Base64

/**
 * The goport IR (docs/goport-ir.md) is read as a raw JSON tree: every node is a [JsonObject]
 * and the lowering reads it through these accessors. A typed model of ~60 node shapes would be
 * a second copy of the schema to keep in step with the extractor; the accessors fail loudly
 * ([IrException]) on a missing required field instead.
 */
typealias Node = JsonObject

class IrException(message: String) : RuntimeException(message)

/** The node kind (`k`). */
val Node.k: String get() = str("k") ?: throw IrException("node without k: ${toString().take(200)}")

fun Node.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun Node.req(key: String): String = str(key) ?: throw IrException("missing '$key' in ${k0()}")

fun Node.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun Node.reqInt(key: String): Int = int(key) ?: throw IrException("missing int '$key' in ${k0()}")

fun Node.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

fun Node.obj(key: String): Node? = this[key] as? JsonObject

fun Node.reqObj(key: String): Node = obj(key) ?: throw IrException("missing node '$key' in ${k0()}")

fun Node.list(key: String): List<Node> = (this[key] as? JsonArray)?.map { it as JsonObject } ?: emptyList()

fun Node.ints(key: String): List<Int> = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).intOrNull!! } ?: emptyList()

fun Node.strs(key: String): List<String> = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()

/** A list that may hold JSON nulls (e.g. `implTuple`). */
fun Node.nullableList(key: String): List<Node?> = (this[key] as? JsonArray)?.map { it as? JsonObject } ?: emptyList()

private fun Node.k0(): String = str("k") ?: "?"

/** The type id (`t`) of an expression node. */
val Node.t: Int? get() = int("t")

/** The mode (`m`) of an expression node. */
val Node.mode: String? get() = str("m")

fun decodeB64(s: String): ByteArray = Base64.decode(s)

/** One Go package of the IR. */
class IrPackage(
    val path: String,
    val name: String,
    val files: List<Node>,
    val types: List<Node>,
    val objects: List<Node>,
    val scopes: List<Node>,
    val initOrder: List<Node>,
) {
    /** Output directory under `gen/` (and the report's name): `ast`, `testutil/tsbaseline`, or `thirdparty/<module path>`. */
    val shortPath: String get() = if (isTsgo) path.removePrefix(MODULE + "/internal/") else "thirdparty/$path"

    /** A package of typescript-go itself (`internal/…`); otherwise one of its third-party dependencies ([ThirdParty]). */
    val isTsgo: Boolean get() = path.startsWith("$MODULE/internal/")

    /** The Go source directory, as a generated file's header names it. */
    val sourceDir: String get() = if (isTsgo) "internal/$shortPath" else path

    fun obj(id: Int): Node = objects[id]

    companion object {
        const val MODULE = "github.com/microsoft/typescript-go"
        const val SCHEMA = 2

        private val json = Json { ignoreUnknownKeys = true }

        fun load(file: File): IrPackage {
            val root = json.parseToJsonElement(file.readText()) as JsonObject
            val schema = root.int("schema")
            if (schema != SCHEMA) throw IrException("${file.name}: unknown IR schema $schema (porter speaks $SCHEMA)")
            return IrPackage(
                path = root.req("path"),
                name = root.req("name"),
                files = root.list("files"),
                types = root.list("types"),
                objects = root.list("objects"),
                scopes = root.list("scopes"),
                initOrder = root.list("initOrder"),
            )
        }
    }
}

