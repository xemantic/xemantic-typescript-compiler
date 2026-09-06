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

import kotlin.jvm.JvmInline

// ---------------------------------------------------------------------------
// TypeFlags — bit field classifying what kind of type a Type represents
// Values match TypeScript/tsgo exactly for compatibility.
// ---------------------------------------------------------------------------

@JvmInline
value class TypeFlags(val value: Int) {
    operator fun contains(flag: TypeFlags): Boolean = (value and flag.value) != 0
    infix fun or(other: TypeFlags): TypeFlags = TypeFlags(value or other.value)
    infix fun and(other: TypeFlags): TypeFlags = TypeFlags(value and other.value)
    fun hasAny(flags: TypeFlags): Boolean = (value and flags.value) != 0
    fun hasNone(flags: TypeFlags): Boolean = (value and flags.value) == 0

    companion object {
        val None = TypeFlags(0)
        val Any = TypeFlags(1 shl 0)
        val Unknown = TypeFlags(1 shl 1)
        val String = TypeFlags(1 shl 2)
        val Number = TypeFlags(1 shl 3)
        val Boolean = TypeFlags(1 shl 4)
        val Enum = TypeFlags(1 shl 5)
        val BigInt = TypeFlags(1 shl 6)
        val StringLiteral = TypeFlags(1 shl 7)
        val NumberLiteral = TypeFlags(1 shl 8)
        val BooleanLiteral = TypeFlags(1 shl 9)
        val EnumLiteral = TypeFlags(1 shl 10)
        val BigIntLiteral = TypeFlags(1 shl 11)
        val ESSymbol = TypeFlags(1 shl 12)
        val UniqueESSymbol = TypeFlags(1 shl 13)
        val Void = TypeFlags(1 shl 14)
        val Undefined = TypeFlags(1 shl 15)
        val Null = TypeFlags(1 shl 16)
        val Never = TypeFlags(1 shl 17)
        val TypeParameter = TypeFlags(1 shl 18)
        val Object = TypeFlags(1 shl 19)
        val Union = TypeFlags(1 shl 20)
        val Intersection = TypeFlags(1 shl 21)
        val Index = TypeFlags(1 shl 22)
        val IndexedAccess = TypeFlags(1 shl 23)
        val Conditional = TypeFlags(1 shl 24)
        val Substitution = TypeFlags(1 shl 25)
        val NonPrimitive = TypeFlags(1 shl 26)
        val TemplateLiteral = TypeFlags(1 shl 27)
        val StringMapping = TypeFlags(1 shl 28)

        // Composite flags
        val StringLike = String or StringLiteral or TemplateLiteral or StringMapping
        val NumberLike = Number or NumberLiteral
        val BigIntLike = BigInt or BigIntLiteral
        val BooleanLike = Boolean or BooleanLiteral
        val ESSymbolLike = ESSymbol or UniqueESSymbol
        val EnumLike = Enum or EnumLiteral
        val VoidLike = Void or Undefined
        val Literal = TypeFlags(
            StringLiteral.value or NumberLiteral.value or BigIntLiteral.value or BooleanLiteral.value
        )
        val Unit = TypeFlags(
            Literal.value or UniqueESSymbol.value or Undefined.value or Null.value
        )
        val Freshable = TypeFlags(Literal.value or Enum.value)
        val Primitive = TypeFlags(
            String.value or Number.value or BigInt.value or Boolean.value or
            Void.value or Undefined.value or Null.value or ESSymbol.value or Enum.value
        )
        val UnionOrIntersection = Union or Intersection
        val StructuredType = TypeFlags(Object.value or Union.value or Intersection.value)
        val StructuredOrInstantiable = TypeFlags(
            StructuredType.value or TypeParameter.value or Index.value or
            IndexedAccess.value or Conditional.value or Substitution.value
        )
        val Instantiable = TypeFlags(
            TypeParameter.value or Index.value or IndexedAccess.value or
            Conditional.value or Substitution.value
        )
        val Narrowable = TypeFlags(
            Any.value or Unknown.value or StructuredOrInstantiable.value or
            StringLike.value or NumberLike.value or BigIntLike.value or
            BooleanLike.value or ESSymbol.value or UniqueESSymbol.value or NonPrimitive.value
        )
        val DefinitelyFalsy = TypeFlags(
            StringLiteral.value or NumberLiteral.value or BigIntLiteral.value or
            BooleanLiteral.value or Void.value or Undefined.value or Null.value
        )
        val PossiblyFalsy = TypeFlags(
            DefinitelyFalsy.value or String.value or Number.value or BigInt.value or Boolean.value
        )
    }
}

// ---------------------------------------------------------------------------
// Type — semantic type resolved from AST TypeNodes by the checker.
//
// Subclasses are nested inside Type to avoid name conflicts with TypeNode
// subclasses in Ast.kt (UnionType, IntersectionType, TypeReference, etc.).
// Access as: Type.Intrinsic, Type.Union, Type.Object, Type.Interface, etc.
// ---------------------------------------------------------------------------

sealed class Type {
    abstract val flags: TypeFlags
    val id: Int = allocTypeId()

    /** Intrinsic (primitive) types: any, unknown, string, number, boolean, void, undefined, null, never, etc. */
    class Intrinsic(
        override val flags: TypeFlags,
        val intrinsicName: kotlin.String,
    ) : Type() {
        override fun toString(): kotlin.String = intrinsicName
    }

    /** String literal type: "hello" */
    class StringLiteral(
        val value: kotlin.String,
        var freshType: StringLiteral? = null,
        var regularType: StringLiteral? = null,
    ) : Type() {
        override val flags: TypeFlags = TypeFlags.StringLiteral
        override fun toString(): kotlin.String = "\"$value\""
    }

    /** Number literal type: 42 */
    class NumberLiteral(
        val value: Double,
        var freshType: NumberLiteral? = null,
        var regularType: NumberLiteral? = null,
    ) : Type() {
        override val flags: TypeFlags = TypeFlags.NumberLiteral
        override fun toString(): kotlin.String {
            return if (value == value.toLong().toDouble() && !value.isInfinite()) {
                value.toLong().toString()
            } else value.toString()
        }
    }

    /** BigInt literal type: 100n */
    class BigIntLiteral(
        val value: kotlin.String, // stored as string for multiplatform
        var freshType: BigIntLiteral? = null,
        var regularType: BigIntLiteral? = null,
    ) : Type() {
        override val flags: TypeFlags = TypeFlags.BigIntLiteral
        override fun toString(): kotlin.String = "${value}n"
    }

    /** Object type with lazily resolved members, properties, and signatures. */
    open class Object(override val flags: TypeFlags = TypeFlags.Object) : Type() {
        var symbol: Symbol? = null
        var members: SymbolTable? = null
        var properties: List<Symbol>? = null
        var callSignatures: List<Signature>? = null
        var constructSignatures: List<Signature>? = null
        var stringIndexInfo: IndexInfo? = null
        var numberIndexInfo: IndexInfo? = null
        /** Non-null for tuple types — stores element types for display and length checking. */
        var tupleElementTypes: List<Type>? = null
        /**
         * (CHK.93) stage 2: a READONLY tuple — `readonly [T, U]`, or an array literal in a
         * const context with no mutable array-like contextual type (tsc's
         * `createTupleType(..., readonly)`). Meaningful only while [tupleElementTypes] is
         * non-null: its members fall to `ReadonlyArray`, it displays `readonly [...]`, and
         * it never relates to a MUTABLE array or tuple (TS4104).
         */
        var readonlyTuple: Boolean = false
        /**
         * (CHK.94): the index of a tuple's REST element (`[number, ...string[]]` → 1;
         * tsc's `hasRestElement`), or -1 for none. The rest slot is stored in
         * [tupleElementTypes] as the rest's ARRAY type (`getTupleType` collapses it — the
         * B526 gotcha), so its `length` is `number` and its `Array<union>` base indexes
         * that slot's element (`Checker.tupleArrayBase`). A tuple has at most ONE rest
         * element (TS1265). Meaningful only while [tupleElementTypes] is non-null.
         */
        var tupleRestIndex: Int = -1
        val tupleHasRest: Boolean get() = tupleRestIndex >= 0
        override fun toString(): kotlin.String = symbol?.name ?: "Object#$id"
    }

    /** Named interface or class type, with type parameters and base types. */
    class Interface(
        flags: TypeFlags = TypeFlags.Object,
    ) : Object(flags) {
        var typeParameters: List<TypeParam>? = null
        var baseTypes: List<Type>? = null
        var declaredCallSignatures: List<Signature>? = null
        var declaredConstructSignatures: List<Signature>? = null
        var declaredStringIndexInfo: IndexInfo? = null
        var declaredNumberIndexInfo: IndexInfo? = null
        // Static-side members for class declarations. Populated alongside the
        // instance-side `members` map by [resolveInterfaceMembers] when iterating
        // ClassDeclaration members carrying ModifierFlag.Static. Consumers of the
        // class's static shape (`typeof Class` / `Class.staticMethod` access /
        // class-side inheritance) read this map; instance-side comparisons (e.g.
        // `class C implements A` checking that C satisfies A's instance shape)
        // read [members] only. See "static-member bifurcation" notes.
        var staticMembers: SymbolTable? = null
    }

    /** Generic type instantiation: Array<string>, Map<K, V>, etc. */
    class Reference(
        val target: Interface,
        var resolvedTypeArguments: List<Type>? = null,
    ) : Object(TypeFlags.Object)

    /** Union type: A | B */
    class Union(
        val types: List<Type>,
    ) : Type() {
        override val flags: TypeFlags = TypeFlags.Union
        override fun toString(): kotlin.String = types.joinToString(" | ")
    }

    /** Intersection type: A & B */
    class Intersection(
        val types: List<Type>,
    ) : Type() {
        override val flags: TypeFlags = TypeFlags.Intersection
        override fun toString(): kotlin.String = types.joinToString(" & ")
    }

    /** Type parameter: T in <T extends Constraint = Default> */
    class TypeParam(
        var constraint: Type? = null,
        var default: Type? = null,
    ) : Type() {
        override val flags: TypeFlags = TypeFlags.TypeParameter
        var symbol: Symbol? = null
        override fun toString(): kotlin.String = symbol?.name ?: "TypeParam#$id"
    }

    companion object {
        /** INV.6(6c0): per-thread sequence — see [IntThreadLocal] for the rationale. */
        private val nextTypeId = IntThreadLocal(1)

        internal fun allocTypeId(): Int = nextTypeId.get().also { nextTypeId.set(it + 1) }

        /** INV.6(6c): re-base THIS thread's type-id sequence (parallel-worker startup). */
        fun rebaseThreadIds(base: Int) {
            nextTypeId.set(base)
        }

        /** INV.6(6c0): snapshot/restore THIS thread's sequence (deep-stack handoff). */
        fun captureThreadId(): Int = nextTypeId.get()
        fun restoreThreadId(value: Int) { nextTypeId.set(value) }

        fun resetIdCounter() {
            nextTypeId.set(1)
        }
    }
}

// ---------------------------------------------------------------------------
// Pre-allocated singleton intrinsic types
// ---------------------------------------------------------------------------

val anyType = Type.Intrinsic(TypeFlags.Any, "any")
val unknownType = Type.Intrinsic(TypeFlags.Unknown, "unknown")
/**
 * Distinguished empty-object sentinel produced ONLY when a bare `unknown` is
 * narrowed truthy under the checker's scoped `narrowUnknownToEmptyObject` pass
 * (see `inKeywordAndUnknown`). tsc narrows `unknown` truthy to `{}` ("may
 * represent a primitive"); identity-distinct from any user `{}` so the `in`-RHS
 * check can emit TS2638 for it while declared `{}` / instanceof-narrowed
 * operands do not. Never escapes the scoped pass.
 */
val truthyUnknownType = Type.Object()
val stringType = Type.Intrinsic(TypeFlags.String, "string")
val numberType = Type.Intrinsic(TypeFlags.Number, "number")
val booleanType = Type.Intrinsic(TypeFlags.Boolean, "boolean")
val voidType = Type.Intrinsic(TypeFlags.Void, "void")
val undefinedType = Type.Intrinsic(TypeFlags.Undefined, "undefined")
val nullType = Type.Intrinsic(TypeFlags.Null, "null")
val neverType = Type.Intrinsic(TypeFlags.Never, "never")
val bigintType = Type.Intrinsic(TypeFlags.BigInt, "bigint")
val esSymbolType = Type.Intrinsic(TypeFlags.ESSymbol, "symbol")
val nonPrimitiveType = Type.Intrinsic(TypeFlags.NonPrimitive, "object")

// Boolean literal singletons (true and false are intrinsic with BooleanLiteral flag)
val trueType = Type.Intrinsic(TypeFlags.BooleanLiteral, "true")
val falseType = Type.Intrinsic(TypeFlags.BooleanLiteral, "false")
val regularTrueType = Type.Intrinsic(TypeFlags.BooleanLiteral, "true")
val regularFalseType = Type.Intrinsic(TypeFlags.BooleanLiteral, "false")

// Error/sentinel types used during type resolution
val errorType = Type.Intrinsic(TypeFlags.Any, "error")
val unresolvedType = Type.Intrinsic(TypeFlags.Any, "unresolved")

// ---------------------------------------------------------------------------
// Signature — call or construct signature for functions and methods
// ---------------------------------------------------------------------------

class Signature(
    val declaration: Node? = null,
    val typeParameters: List<Type.TypeParam>? = null,
    val parameters: List<Symbol> = emptyList(),
    var resolvedReturnType: Type? = null,
    val minArgumentCount: Int = 0,
    val isAbstract: Boolean = false,
)

// ---------------------------------------------------------------------------
// IndexInfo — index signature (e.g., [key: string]: number)
// ---------------------------------------------------------------------------

class IndexInfo(
    val keyType: Type,
    val type: Type,
    val isReadonly: Boolean = false,
    val declaration: Node? = null,
)
