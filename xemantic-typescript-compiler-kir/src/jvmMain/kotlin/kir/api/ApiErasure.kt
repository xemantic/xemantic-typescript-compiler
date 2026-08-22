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

package com.xemantic.typescript.compiler.kir.api

import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeFlags

/**
 * TypeScript [Type] → [KotlinType], for a DECLARATION surface.
 *
 * The sibling of `ErasedTypes`, which maps the same TypeScript types to Kotlin
 * IR for the JVM backend. Two mappers rather than one, and the reason is worth
 * being explicit about because CLAUDE.md's standing warning is that a second
 * copy of a rule diverges by widening:
 *
 *  - the CODOMAINS differ. `ErasedTypes` answers with an `IrType`, which needs
 *    a live `IrBuiltIns` and a `SymbolTable` from a running kotlinc frontend;
 *    metadata is produced without either, and its answer is a NAME.
 *  - the FAILURE MODES differ, and this is the load-bearing half. Inside a
 *    function body a type this backend cannot map means an operation it cannot
 *    lower, so `ErasedTypes` answers null and the lowering refuses. In a
 *    SIGNATURE there is no operation — the position merely carries a value —
 *    so an unmappable type erases to `Any?`, which is what TypeScript's own
 *    erasure does to it and what the JVM sees anyway.
 *
 * The one place that difference could bite is a position where the two must
 * AGREE — the JVM artifact's signatures against this metadata's — and that
 * agreement is the next slice's obligation, recorded in
 * `docs/kir-kotlin-metadata.md` §6. Where both map a type today they answer the
 * same shape, which `ApiErasureAgreementTest` pins.
 */
internal class ApiErasure(
    /**
     * The exported Kotlin name of a TypeScript `class` declaration, if this
     * export declares one — otherwise the class is not on the surface and its
     * type erases like any other object.
     */
    private val classNameOf: (Node) -> String?,
    /** What an `enum` declaration's members' VALUES are typed as. */
    private val enumValueTypeOf: (Node) -> KotlinType?,
    /**
     * Whether a declaration is a structural shape THIS program wrote.
     *
     * `ErasedTypes`' gate of the same name, and load-bearing for the same
     * reason: an interface the program declares is a property bag at run time,
     * and a LIBRARY type — `Map`, `Date`, `RegExp` — is NOT, so letting a
     * library type fall through to the bag would type a consumer's calls
     * against members the value does not have. Silent, and shaped exactly like
     * a working API.
     */
    private val isOwnStructuralDeclaration: (Node) -> Boolean = { false },
    /**
     * Whether the runtime's own metadata klib is on the export's classpath.
     *
     * False — the default — is the self-contained artifact: an object or an
     * array has no common type to name, so it is `Any?`. True names
     * `JsObject`/`JsArray`, and the consumer then needs both klibs, which is
     * what any library dependency is.
     */
    private val runtimeTypes: Boolean = false,
) {

    /**
     * The erasure of [type] on an API surface. Never null: see the class KDoc.
     */
    fun map(type: Type): KotlinType {
        val flags = type.flags
        return when {
            flags.hasAny(TypeFlags.NumberLike) -> KotlinType.DOUBLE
            flags.hasAny(TypeFlags.StringLike) -> KotlinType.STRING
            flags.hasAny(TypeFlags.BooleanLike) -> KotlinType.BOOLEAN
            // `bigint` is `java.math.BigInteger` at run time on the JVM, and
            // COMMON Kotlin cannot name that class. Erasing it here rather than
            // naming a JVM type keeps the artifact loadable by every platform.
            flags.hasAny(TypeFlags.BigIntLike) -> KotlinType.ANY
            flags.hasAny(TypeFlags.Void) -> KotlinType.UNIT
            flags.hasAny(TypeFlags.Never) -> KotlinType.NOTHING
            flags.hasAny(TypeFlags.Undefined or TypeFlags.Null) -> KotlinType.NULL
            flags.hasAny(TypeFlags.Any or TypeFlags.Unknown) -> KotlinType.ANY
            // A bare type parameter erases to `Any?`. TypeScript erases generics
            // and so does the JVM, so this loses nothing that survives to run
            // time — where `ErasedTypes` refuses one, because an OPERATION on a
            // value of unknown type is a lowering it cannot write.
            type is Type.TypeParam -> KotlinType.ANY
            type is Type.Union -> mapUnion(type)
            type is Type.Intersection -> mapIntersection(type)
            type is Type.Object -> mapObject(type)
            else -> KotlinType.ANY
        }
    }

    /**
     * §3.2's rule, unchanged: all non-nullish members erasing to the same type
     * gives that type, anything else gives `Any`, nullable iff a nullish member
     * was present.
     */
    private fun mapUnion(type: Type.Union): KotlinType {
        val nullish = { member: Type ->
            member.flags.hasAny(TypeFlags.Undefined or TypeFlags.Null or TypeFlags.Void)
        }
        val nullable = type.types.any(nullish)
        val present = type.types.filterNot(nullish)
        if (present.isEmpty()) return KotlinType.NULL
        val erasures = present.map { map(it) }
        val first = erasures.first().asNonNullable()
        val uniform = erasures.all { it.asNonNullable() == first }
        val erased = if (uniform) first else KotlinType.ANY_NON_NULL
        return if (nullable) erased.asNullable() else erased
    }

    /**
     * An INTERSECTION of object shapes is a property bag, like each of them.
     *
     * `ParseOptions & { integersAsBigInt: … }` — the branded-options shape every
     * library writes — has no runtime witness of its own: what flows through it
     * is one object carrying the union of the members' properties, which is what
     * a bag is.
     *
     * EVERY member must be POSITIVELY a bag, which is stricter than
     * `ErasedTypes.mapIntersection`, and the difference is forced. There, a
     * member it cannot map is read as a pure type-level constraint, because a
     * NOMINAL member would have mapped to a runtime class and been refused.
     * Here there is no library-type table, so `Date` maps to `Any?` — the same
     * answer an unmappable constraint gives — and treating that as a constraint
     * would type `Date & Tag` as a bag whose members the value does not have.
     * A pin holds both directions.
     */
    private fun mapIntersection(type: Type.Intersection): KotlinType {
        if (!runtimeTypes) return KotlinType.ANY
        val mapped = type.types.map { map(it) }
        return if (mapped.all { it == KirRuntimeApi.jsObject }) {
            KirRuntimeApi.jsObject
        } else {
            KotlinType.ANY
        }
    }

    private fun mapObject(type: Type.Object): KotlinType {
        // An array or a tuple is a `JsArray` at run time — nameable only when
        // the runtime's own metadata is on the classpath (§3.1).
        if (isArrayLike(type)) return if (runtimeTypes) KirRuntimeApi.jsArray else KotlinType.ANY
        val symbol = type.symbol
        val declaration = symbol?.valueDeclaration ?: symbol?.declarations?.firstOrNull()
        declaration?.let { enumValueTypeOf(it) }?.let { return it }
        declaration?.let { classNameOf(it) }?.let { return KotlinType.Named(it) }
        // A callable — a function type, a method reference, a handler.
        type.callSignatures?.firstOrNull()?.let {
            return KotlinType.Function(it.parameters.size)
        }
        if (!runtimeTypes) return KotlinType.ANY
        // A bag, but only where the shape is one this program wrote: an
        // anonymous object type has no declaration at all and is one by
        // construction, and a declared one has to pass the gate — a `Date` is a
        // `JsDate` at run time and calling a bag's members on it would fail
        // inside the runtime.
        if (declaration == null) return KirRuntimeApi.jsObject
        return if (isOwnStructuralDeclaration(declaration)) {
            KirRuntimeApi.jsObject
        } else {
            KotlinType.ANY
        }
    }

    private fun isArrayLike(type: Type.Object): Boolean =
        type.tupleElementTypes != null ||
            (type as? Type.Reference)?.target?.symbol?.name in ARRAY_TARGETS

    private companion object {
        val ARRAY_TARGETS = setOf("Array", "ReadonlyArray")
    }

}
