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

/**
 * A Go pointer to a NON-struct location (`*int`, `*[]T`, `&local`, `&s.field`, `&s[i]`).
 * A pointer to a struct is the struct reference itself (docs/goport-design.md § 3).
 *
 * Equality is Go pointer equality: two pointers to the same location are equal.
 */
interface GoPtr<T> {
    /** `*p` (read) and `*p = v` (write). */
    var value: T
}

/**
 * A boxed variable: the storage of a local (or `new(T)`) whose address is taken. The box IS the
 * location, so equality is identity.
 */
class GoBox<T>(override var value: T) : GoPtr<T> {
    override fun toString(): String = "&$value"
}

/**
 * A pointer to a field of a struct: equal to another pointer to the same field ([fieldId]) of the
 * same object ([owner], by identity).
 */
class GoFieldPtr<T>(
    private val owner: Any,
    private val fieldId: Int,
    private val getter: () -> T,
    private val setter: (T) -> Unit,
) : GoPtr<T> {

    override var value: T
        get() = getter()
        set(v) = setter(v)

    override fun equals(other: Any?): Boolean =
        other is GoFieldPtr<*> && other.owner === owner && other.fieldId == fieldId

    override fun hashCode(): Int = owner.hashCode() * 31 + fieldId
}

/** `new(T)` for a non-struct `T`. */
fun <T> goNew(elem: GoElem<T>): GoPtr<T> = GoBox(elem.zeroValue())

/**
 * `&x` of an opaque type parameter T flowing into an interface (the lowering's real pointer [p]): when T is
 * instantiated with a struct or an array ([elem] copies values), Go's `*T` is the reference itself
 * (docs/goport-design.md § 3), so a `.(*S)` assertion on the interface sees the struct; for any other T
 * it is the pointer. `api.unmarshallerFor[P]`'s `return &v` is the case ((TSGO.3-b)).
 */
fun <T> goOpaqueAddr(elem: GoElem<T>, p: GoPtr<T>): Any? = if (elem.copy != null) p.value else p
