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

    fun apply(
        name: String, arg: Type, overString: MutableMap<String, Type.StringMapping>, union: (List<Type>) -> Type,
    ): Type? = when {
        arg is Type.StringLiteral -> Type.StringLiteral(map(name, arg.value))
        // (P18.302) over `string` itself the mapping is a type of its own (tsgo's
        // `StringMappingType`), one instance per mapping and CHECKER ([overString] is the
        // checker's — a process-global instance would mint its id on whichever worker asks first).
        arg === stringType -> overString.getOrPut(name) { Type.StringMapping(name, stringType) }
        // Re-applying the same mapping is the identity (`Lowercase<Lowercase<string>>`).
        arg is Type.StringMapping && arg.mapping == name && (name == "Uppercase" || name == "Lowercase") -> arg
        arg !is Type.Union -> null
        else -> {
            val parts = arg.types.map { (it as? Type.StringLiteral)?.let { s -> Type.StringLiteral(map(name, s.value)) } ?: return null }
            union(parts)
        }
    }

    /**
     * (P18.302) The verdict of relating [source] to the string-mapping [target] (tsgo's
     * `isMemberOfStringMapping` for a `string` target), or null when the caller's ordinary path
     * decides (a union decomposes there; `any` / `never` / a type parameter / an imprecise
     * template keep the old answer). A plain `string` is refused only inside a conditional, as
     * a template target refuses it — elsewhere this checker's `string` is too often a widened
     * literal.
     */
    fun relate(source: Type, target: Type.StringMapping, inConditional: Boolean): Boolean? {
        if (target.target !== stringType) return null
        return when {
            source is Type.StringLiteral -> map(target.mapping, source.value) == source.value
            source is Type.StringMapping -> if (source.target === stringType) source.mapping == target.mapping else null
            source === stringType -> if (inConditional) false else null
            // tsgo maps the source and asks for identity: a precise template's placeholder is
            // rewritten (`${string}end` -> `${Lowercase<string>}end`), so it is never a member.
            source is Type.TemplateLiteral && source.precise && !source.generic -> if (inConditional) false else null
            else -> null
        }
    }

    private fun map(name: String, s: String): String = when (name) {
        "Uppercase" -> s.uppercase()
        "Lowercase" -> s.lowercase()
        "Capitalize" -> if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
        else -> if (s.isEmpty()) s else s.substring(0, 1).lowercase() + s.substring(1)
    }
}
