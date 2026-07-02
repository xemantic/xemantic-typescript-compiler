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

package com.xemantic.typescript.compiler

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient JSON reader for `tsconfig.json` / `package.json` from the wild. These are
 * JSONC: they routinely carry `//` and `/* */` comments and trailing commas, so the
 * comment/trailing-comma relaxations are required (and unknown keys are ignored — we
 * only read a handful of fields, and a `package.json`'s `exports` can be arbitrarily
 * shaped). Everything is read through kotlinx-serialization's battle-tested parser
 * rather than a hand-rolled one.
 *
 * Shared by [TsConfigLoader] (`decodeFromString<TsConfigFile>`, errors reported as
 * TS5014/TS5083/TS6053) and [ModuleResolver] (`parseToJsonElement` of `package.json`,
 * lenient — a broken dependency manifest must not abort resolution). Both catch
 * [kotlinx.serialization.SerializationException] at their call site. `internal`, not
 * public: it's a driver detail, kept off the published library's API surface.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val LENIENT_JSON: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
    allowComments = true
    allowTrailingComma = true
}

/** This element's content if it is a JSON string, else `null` (numbers/bools/null/objects/arrays → null). */
internal val JsonElement.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Member [key] of this element if it is an object, else `null`. */
internal fun JsonElement.member(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** This element as a list of strings: array → its string items; a bare string → singleton; else empty. */
internal val JsonElement.asStringList: List<String>
    get() = when (this) {
        is JsonArray -> mapNotNull { it.stringValue }
        else -> stringValue?.let { listOf(it) } ?: emptyList()
    }

/**
 * The strongly-typed surface of a `tsconfig.json` that maps cleanly to data: the file
 * lists. The polymorphic fields are kept as raw JSON: `extends` is `string | string[]`,
 * and `compilerOptions` is funneled through [applyDirective] (the same string-keyed
 * option pipeline the in-source `// @directive` tests use), so neither benefits from a
 * fixed schema. Unknown keys (`references`, `ts-node`, …) are ignored by [LENIENT_JSON].
 */
@Serializable
internal data class TsConfigFile(
    val extends: JsonElement? = null,
    val compilerOptions: JsonObject? = null,
    val include: List<String>? = null,
    val exclude: List<String>? = null,
    val files: List<String>? = null,
)
