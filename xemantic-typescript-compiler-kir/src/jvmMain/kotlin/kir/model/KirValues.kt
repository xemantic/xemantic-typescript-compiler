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

package com.xemantic.typescript.compiler.kir.model

import com.xemantic.typescript.compiler.kir.ast.Node
import java.util.concurrent.atomic.AtomicInteger

// (TSGO.7-d) KIR'S OWN VALUE MODEL — the types, symbols and signatures the lowering reads.
//
// `TsgoTranslator` (front/TsgoFacts.kt) rebuilds tsgo's answers in these shapes, which are the shapes
// `-core`'s checker gave the lowering before (TSGO.4-c): flags by name, an instantiation as a
// [Type.Reference] over its target [Type.Interface], a tuple's element types, call signatures with
// their declarations. Only what the lowering reads is here — no checker state. Declarations are still
// `-core` syntax nodes until the lowering walks KIR's own tree ((TSGO.7-e)).

@JvmInline
public value class TypeFlags(public val value: Int) {
    public operator fun contains(flag: TypeFlags): Boolean = (value and flag.value) != 0
    public infix fun or(other: TypeFlags): TypeFlags = TypeFlags(value or other.value)
    public infix fun and(other: TypeFlags): TypeFlags = TypeFlags(value and other.value)
    public fun hasAny(flags: TypeFlags): Boolean = (value and flags.value) != 0
    public fun hasNone(flags: TypeFlags): Boolean = (value and flags.value) == 0

    public companion object {
        public val None: TypeFlags = TypeFlags(0)
        public val Any: TypeFlags = TypeFlags(1 shl 0)
        public val Unknown: TypeFlags = TypeFlags(1 shl 1)
        public val String: TypeFlags = TypeFlags(1 shl 2)
        public val Number: TypeFlags = TypeFlags(1 shl 3)
        public val Boolean: TypeFlags = TypeFlags(1 shl 4)
        public val Enum: TypeFlags = TypeFlags(1 shl 5)
        public val BigInt: TypeFlags = TypeFlags(1 shl 6)
        public val StringLiteral: TypeFlags = TypeFlags(1 shl 7)
        public val NumberLiteral: TypeFlags = TypeFlags(1 shl 8)
        public val BooleanLiteral: TypeFlags = TypeFlags(1 shl 9)
        public val EnumLiteral: TypeFlags = TypeFlags(1 shl 10)
        public val BigIntLiteral: TypeFlags = TypeFlags(1 shl 11)
        public val ESSymbol: TypeFlags = TypeFlags(1 shl 12)
        public val UniqueESSymbol: TypeFlags = TypeFlags(1 shl 13)
        public val Void: TypeFlags = TypeFlags(1 shl 14)
        public val Undefined: TypeFlags = TypeFlags(1 shl 15)
        public val Null: TypeFlags = TypeFlags(1 shl 16)
        public val Never: TypeFlags = TypeFlags(1 shl 17)
        public val TypeParameter: TypeFlags = TypeFlags(1 shl 18)
        public val Object: TypeFlags = TypeFlags(1 shl 19)
        public val Union: TypeFlags = TypeFlags(1 shl 20)
        public val Intersection: TypeFlags = TypeFlags(1 shl 21)
        public val Index: TypeFlags = TypeFlags(1 shl 22)
        public val IndexedAccess: TypeFlags = TypeFlags(1 shl 23)
        public val Conditional: TypeFlags = TypeFlags(1 shl 24)
        public val Substitution: TypeFlags = TypeFlags(1 shl 25)
        public val NonPrimitive: TypeFlags = TypeFlags(1 shl 26)
        public val TemplateLiteral: TypeFlags = TypeFlags(1 shl 27)
        public val StringMapping: TypeFlags = TypeFlags(1 shl 28)

        public val StringLike: TypeFlags = String or StringLiteral or TemplateLiteral or StringMapping
        public val NumberLike: TypeFlags = Number or NumberLiteral
        public val BigIntLike: TypeFlags = BigInt or BigIntLiteral
        public val BooleanLike: TypeFlags = Boolean or BooleanLiteral
        public val ESSymbolLike: TypeFlags = ESSymbol or UniqueESSymbol
        public val EnumLike: TypeFlags = Enum or EnumLiteral
        public val VoidLike: TypeFlags = Void or Undefined
    }
}

@JvmInline
public value class SymbolFlags(public val value: Int) {
    public operator fun contains(flag: SymbolFlags): Boolean = (value and flag.value) != 0
    public infix fun or(other: SymbolFlags): SymbolFlags = SymbolFlags(value or other.value)
    public infix fun and(other: SymbolFlags): SymbolFlags = SymbolFlags(value and other.value)
    public fun hasAny(flags: SymbolFlags): Boolean = (value and flags.value) != 0
    public fun hasNone(flags: SymbolFlags): Boolean = (value and flags.value) == 0

    public companion object {
        public val None: SymbolFlags = SymbolFlags(0)
        public val FunctionScopedVariable: SymbolFlags = SymbolFlags(1 shl 0)
        public val BlockScopedVariable: SymbolFlags = SymbolFlags(1 shl 1)
        public val Property: SymbolFlags = SymbolFlags(1 shl 2)
        public val EnumMember: SymbolFlags = SymbolFlags(1 shl 3)
        public val Function: SymbolFlags = SymbolFlags(1 shl 4)
        public val Class: SymbolFlags = SymbolFlags(1 shl 5)
        public val Interface: SymbolFlags = SymbolFlags(1 shl 6)
        public val ConstEnum: SymbolFlags = SymbolFlags(1 shl 7)
        public val RegularEnum: SymbolFlags = SymbolFlags(1 shl 8)
        public val ValueModule: SymbolFlags = SymbolFlags(1 shl 9)
        public val NamespaceModule: SymbolFlags = SymbolFlags(1 shl 10)
        public val TypeAlias: SymbolFlags = SymbolFlags(1 shl 11)
        public val Alias: SymbolFlags = SymbolFlags(1 shl 12)
        public val ExportValue: SymbolFlags = SymbolFlags(1 shl 13)
        public val Method: SymbolFlags = SymbolFlags(1 shl 14)
        public val GetAccessor: SymbolFlags = SymbolFlags(1 shl 15)
        public val SetAccessor: SymbolFlags = SymbolFlags(1 shl 16)
        public val TypeParameter: SymbolFlags = SymbolFlags(1 shl 17)

        public val Variable: SymbolFlags = FunctionScopedVariable or BlockScopedVariable
        public val Enum: SymbolFlags = RegularEnum or ConstEnum
        public val Module: SymbolFlags = ValueModule or NamespaceModule
    }
}

private val nextTypeId = AtomicInteger(1)
private val nextSymbolId = AtomicInteger(1)

public sealed class Type {
    public abstract val flags: TypeFlags

    /** Program-wide identity (renderings are filed by it, `CheckedFacts.render`). */
    public val id: Int = nextTypeId.getAndIncrement()

    /** A primitive or keyword type: any, unknown, string, number, boolean, void, undefined, null, never, … */
    public class Intrinsic(override val flags: TypeFlags, public val intrinsicName: String) : Type() {
        override fun toString(): String = intrinsicName
    }

    public class StringLiteral(public val value: String) : Type() {
        override val flags: TypeFlags = TypeFlags.StringLiteral
        override fun toString(): String = "\"$value\""
    }

    public class NumberLiteral(public val value: Double) : Type() {
        override val flags: TypeFlags = TypeFlags.NumberLiteral
        override fun toString(): String =
            if (value == value.toLong().toDouble() && !value.isInfinite()) value.toLong().toString() else value.toString()
    }

    /** `100n`, its digits as text. */
    public class BigIntLiteral(public val value: String) : Type() {
        override val flags: TypeFlags = TypeFlags.BigIntLiteral
        override fun toString(): String = "${value}n"
    }

    /** An object type: anonymous, a class or interface ([Interface]), an instantiation ([Reference]). */
    public open class Object(override val flags: TypeFlags = TypeFlags.Object) : Type() {
        public var symbol: Symbol? = null
        public var callSignatures: List<Signature>? = null
        public var tupleElementTypes: List<Type>? = null
        override fun toString(): String = symbol?.name ?: "Object#$id"
    }

    /** A named interface or class type: the target of its instantiations ([Reference]). */
    public class Interface(flags: TypeFlags = TypeFlags.Object) : Object(flags)

    /** A generic instantiation: `Array<string>`, `Map<K, V>`. */
    public class Reference(public val target: Interface, public var resolvedTypeArguments: List<Type>? = null) :
        Object(TypeFlags.Object)

    public class Union(public val types: List<Type>) : Type() {
        override val flags: TypeFlags = TypeFlags.Union
        override fun toString(): String = types.joinToString(" | ")
    }

    public class Intersection(public val types: List<Type>) : Type() {
        override val flags: TypeFlags = TypeFlags.Intersection
        override fun toString(): String = types.joinToString(" & ")
    }

    public class TypeParam : Type() {
        override val flags: TypeFlags = TypeFlags.TypeParameter
        public var symbol: Symbol? = null
        override fun toString(): String = symbol?.name ?: "TypeParam#$id"
    }
}

public val anyType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Any, "any")
public val unknownType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Unknown, "unknown")
public val stringType: Type.Intrinsic = Type.Intrinsic(TypeFlags.String, "string")
public val numberType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Number, "number")
public val booleanType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Boolean, "boolean")
public val voidType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Void, "void")
public val undefinedType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Undefined, "undefined")
public val nullType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Null, "null")
public val neverType: Type.Intrinsic = Type.Intrinsic(TypeFlags.Never, "never")
public val bigintType: Type.Intrinsic = Type.Intrinsic(TypeFlags.BigInt, "bigint")
public val esSymbolType: Type.Intrinsic = Type.Intrinsic(TypeFlags.ESSymbol, "symbol")
public val nonPrimitiveType: Type.Intrinsic = Type.Intrinsic(TypeFlags.NonPrimitive, "object")
public val trueType: Type.Intrinsic = Type.Intrinsic(TypeFlags.BooleanLiteral, "true")
public val falseType: Type.Intrinsic = Type.Intrinsic(TypeFlags.BooleanLiteral, "false")

/** A call or construct signature. */
public class Signature(
    public val declaration: Node? = null,
    public val parameters: List<Symbol> = emptyList(),
    public var resolvedReturnType: Type? = null,
)

public typealias SymbolTable = MutableMap<String, Symbol>

/** A named entity: its flags, declarations and, for a container, its members and exports. */
public class Symbol(public var flags: SymbolFlags, public val name: String) {
    public val id: Int = nextSymbolId.getAndIncrement()
    public val declarations: MutableList<Node> = mutableListOf()
    public var valueDeclaration: Node? = null
    /** A namespace the program declares: its exports by name, aliases resolved (`TsgoTranslator`). */
    public var exports: SymbolTable? = null
    public var members: SymbolTable? = null
    override fun toString(): String = "Symbol($name, flags=${flags.value})"
}
