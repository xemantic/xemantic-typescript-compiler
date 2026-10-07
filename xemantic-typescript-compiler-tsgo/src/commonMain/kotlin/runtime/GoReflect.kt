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

package com.xemantic.typescript.tsgo.runtime

import kotlin.reflect.KClass

// Reflection by codegen (docs/goport-runtime.md § 11, docs/goport-lowering.md § 3): Go's `reflect`
// reads a value's dynamic type; Kotlin's erased generics and value classes cannot, so the PORTER
// emits the static type information `reflect` and `json` need, and these declarations carry it.

/**
 * A static Go type as `reflect` sees it. [kind] is Go's `reflect.Kind` number; [name] the Go type's
 * qualified name ("" for an unnamed type); [cls] the Kotlin class of a named struct or value class
 * (`reflect.TypeOf(x) == T` compares it); [zero] answers the Go zero value of the type.
 */
class GoTypeInfo(
    val kind: Int,
    val name: String = "",
    val cls: KClass<*>? = null,
    val elem: GoTypeInfo? = null,
    val key: GoTypeInfo? = null,
    private val structInfo: (() -> GoStructInfo)? = null,
    val zero: () -> Any? = { null },
) {
    /** The fields of a struct type (null: not generated — the struct never reaches `reflect`). */
    val struct: GoStructInfo? by lazy { structInfo?.invoke() }

    override fun equals(other: Any?): Boolean = other is GoTypeInfo && kind == other.kind && name == other.name &&
        cls == other.cls && elem == other.elem && key == other.key

    override fun hashCode(): Int = (kind * 31 + name.hashCode()) * 31 + (elem?.hashCode() ?: 0)

    override fun toString(): String = when (kind) {
        KIND_POINTER -> "*$elem"
        KIND_SLICE -> "[]$elem"
        KIND_MAP -> "map[$key]$elem"
        else -> name.ifEmpty { KIND_NAMES.getOrElse(kind) { "kind$kind" } }
    }

    companion object {
        const val KIND_BOOL = 1
        const val KIND_INT = 2
        const val KIND_INT64 = 6
        const val KIND_UINT32 = 10
        const val KIND_UINT64 = 11
        const val KIND_FLOAT64 = 14
        const val KIND_ARRAY = 17
        const val KIND_FUNC = 19
        const val KIND_INTERFACE = 20
        const val KIND_MAP = 21
        const val KIND_POINTER = 22
        const val KIND_SLICE = 23
        const val KIND_STRING = 24
        const val KIND_STRUCT = 25

        val KIND_NAMES = listOf(
            "invalid", "bool", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
            "uintptr", "float32", "float64", "complex64", "complex128", "array", "chan", "func", "interface", "map", "ptr",
            "slice", "string", "struct", "unsafe.Pointer",
        )

        fun ptr(elem: GoTypeInfo): GoTypeInfo = GoTypeInfo(KIND_POINTER, elem = elem)
        fun slice(elem: GoTypeInfo): GoTypeInfo = GoTypeInfo(KIND_SLICE, elem = elem, zero = { GoSlice.nil(GoElem.REF) })
        fun map(key: GoTypeInfo, elem: GoTypeInfo): GoTypeInfo = GoTypeInfo(KIND_MAP, key = key, elem = elem, zero = { GoMap.nil<Any?, Any?>(GoElem.REF) })
    }
}

/** The fields of a generated struct, in declaration order. */
class GoStructInfo(val name: String, val fields: List<GoFieldInfo>)

/** One struct field: Go name, raw tag text (`json:"name,omitempty"`), and its type. */
class GoFieldInfo(val name: String, val tag: String, val exported: Boolean, val embedded: Boolean, val type: GoTypeInfo)

/**
 * A generated struct that reaches `reflect` or `json`: its static description and a live pointer to
 * each field (field [i] of [goStructInfo]).
 */
interface GoReflectStruct {
    fun goStructInfo(): GoStructInfo
    fun goFieldPtr(i: Int): GoPtr<Any?>
}

/** A generated value class (a named basic type): its underlying value, for `reflect.Value.Int()` and friends. */
interface GoBasicValue {
    val goRaw: Any

    /** The same named type holding [raw] (of the underlying Kotlin type): `reflect`'s `Set`, json decoding. */
    fun goWithRaw(raw: Any): GoBasicValue
}
