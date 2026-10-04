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

/**
 * (P18.293) the four string-mapping intrinsics (`Uppercase` / `Lowercase` / `Capitalize` /
 * `Uncapitalize`, declared `= intrinsic` in the lib) applied to a string LITERAL argument, or a
 * union of them — tsgo's `getStringMappingType` / `applyStringMapping`. Anything else (`string`,
 * a type parameter, a template literal) answers null and keeps the previous unevaluated answer,
 * so this only ever replaces an `any` with the exact literal tsgo computes.
 *
 * It became load-bearing with [AliasDefaultTypeArgs]: type-fest's `CamelCase<'FooBar'>` now
 * instantiates (its `Options` is defaulted), and an unevaluated `Uncapitalize<…>` span made the
 * whole template literal imprecise — a false TS2345 on every `expectType<'fooBar'>(…)`.
 */
internal object StringMappingTypes {
    val NAMES: Set<String> = setOf("Uppercase", "Lowercase", "Capitalize", "Uncapitalize")

    fun apply(name: String, arg: Type, union: (List<Type>) -> Type): Type? = when (arg) {
        is Type.StringLiteral -> Type.StringLiteral(map(name, arg.value))
        is Type.Union -> {
            val parts = arg.types.map { (it as? Type.StringLiteral)?.let { s -> Type.StringLiteral(map(name, s.value)) } ?: return null }
            union(parts)
        }
        else -> null
    }

    private fun map(name: String, s: String): String = when (name) {
        "Uppercase" -> s.uppercase()
        "Lowercase" -> s.lowercase()
        "Capitalize" -> if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
        else -> if (s.isEmpty()) s else s.substring(0, 1).lowercase() + s.substring(1)
    }
}
