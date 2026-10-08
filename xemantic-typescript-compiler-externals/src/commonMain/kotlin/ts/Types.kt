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

package com.xemantic.typescript.compiler.externals.ts

import com.xemantic.typescript.tsgo.ast.Symbol as RawSymbol
import com.xemantic.typescript.tsgo.checker.Type as RawType

/*
 * (TSGO.4-b) THE RESOLVED-TYPE VIEW: the shapes of tsgo's `checker.Type` the
 * type mapper distinguishes, one wrapper per tsgo type ([Tree.type]), so
 * identity is tsgo's. A type's constituents are wrapped on first ask — tsgo's
 * type graph is cyclic (`type Json = string | Json[]`). The classification is
 * what a Kotlin external can spell, decided once here:
 *
 *  - [Intrinsic] — `string`, `number`, `any` (and the `error` intrinsic, which
 *    carries the `any` flag under its own name), `void`, `null`, … and the
 *    `true`/`false` literals; tsgo's `boolean` is the union `false | true`
 *    carrying the `Boolean` flag, and is the [Intrinsic] `boolean` here;
 *  - the literal types — an ENUM member's literal is [Other], never a plain
 *    literal, or `p: Kind.A` would widen to `Double` silently; a template
 *    literal type and a string mapping are a [StringLiteral] (they widen to
 *    `String`, as a string literal does);
 *  - [Object] — an anonymous or mapped object type and, by its symbol, an
 *    ENUM type (tsgo's union of the members' literals carrying `EnumLiteral`,
 *    or a computed enum's `Enum` type): what a generated enum is named by;
 *  - [Interface] — a class's or interface's DECLARED type, its type
 *    parameters outer-then-local (tsgo's `TypeParameters()`);
 *  - [Reference] — an instantiation `Box<string>` (array types included), its
 *    arguments the target's parameters' worth of tsgo's `getTypeArguments`,
 *    which appends the `this` argument;
 *  - [Other] — a tuple, an intersection, an indexed access, a conditional,
 *    …, none of which maps.
 */

/** A type flag the mapper reads. */
internal enum class TypeFlags { Any, Unknown, String, Number, Boolean, BooleanLiteral, Void, Null, Undefined, Never, BigInt, ESSymbol, NonPrimitive }

/** tsgo's `SymbolFlags`, the bits the generator tests. */
@kotlin.jvm.JvmInline
internal value class SymbolFlags(val value: UInt) {
    fun hasAny(other: SymbolFlags): Boolean = value and other.value != 0u

    companion object {
        val Class: SymbolFlags = SymbolFlags(32u)
        val Enum: SymbolFlags = SymbolFlags(384u)
        val Alias: SymbolFlags = SymbolFlags(2097152u)
    }
}

/** A resolved type. [raw] is null only for the four primitives [Tree] synthesises. */
internal sealed class Type(internal val raw: RawType?) {

    class Intrinsic(raw: RawType?, val flags: Set<TypeFlags>, val intrinsicName: String) : Type(raw)

    class StringLiteral(raw: RawType) : Type(raw)

    class NumberLiteral(raw: RawType) : Type(raw)

    class BigIntLiteral(raw: RawType) : Type(raw)

    class TypeParam(raw: RawType, val symbol: Symbol?) : Type(raw)

    class Union(raw: RawType, types: () -> List<Type>) : Type(raw) {
        val types: List<Type> by lazy(LazyThreadSafetyMode.NONE, types)
    }

    /** An instantiation of a generic class or interface (or of the lib `Array`). */
    class Reference(raw: RawType, val target: Interface, arguments: () -> List<Type>?) : Type(raw) {
        val resolvedTypeArguments: List<Type>? by lazy(LazyThreadSafetyMode.NONE, arguments)
    }

    /** An object type carrying [symbol] — anonymous, mapped, a class's constructor side, an enum. */
    open class Object(raw: RawType, val symbol: Symbol?) : Type(raw)

    /** A class's or interface's declared type. */
    class Interface(raw: RawType, symbol: Symbol?, typeParameters: () -> List<Type>?) : Object(raw, symbol) {
        val typeParameters: List<Type>? by lazy(LazyThreadSafetyMode.NONE, typeParameters)
    }

    class Other(raw: RawType) : Type(raw)
}

/** A symbol: its name, flags, and the view nodes of its declarations (built on first ask). */
internal class Symbol(private val tree: Tree, internal val raw: RawSymbol) {
    val name: String by lazy(LazyThreadSafetyMode.NONE) { tree.symbolName(raw) }
    val flags: SymbolFlags get() = SymbolFlags(raw.flags.value)
    val declarations: List<Node> by lazy(LazyThreadSafetyMode.NONE) { tree.symbolDeclarations(raw) }
}

/**
 * The checker's answers the generator asks for — tsgo's `Checker`, asked AFTER
 * the check. A post-hoc question is exact here (tsgo's checker keeps no
 * walk-scoped state a later question would miss), which is what retired the
 * in-walk sink this generator used to be driven by.
 */
internal interface CheckedLens {

    /** The type at an expression (`getTypeAtLocation`). */
    fun typeOf(node: Expression): Type

    /** tsgo's own display of a type (`typeToString`, untruncated). */
    fun render(type: Type): String

    /** [render] of a type alias's BODY — the body spelled out, not the alias's own name (`InTypeAlias`). */
    fun renderAliasBody(type: Type): String

    /** What a written type annotation denotes (`getTypeFromTypeNode`). */
    fun typeOfTypeNode(node: TypeNode): Type

    /**
     * What a heritage base expression (`X`, `A.B`) — or any entity-name-shaped
     * expression — names (`getSymbolAtLocation`); possibly an import alias.
     */
    fun heritageBaseSymbol(base: Expression): Symbol?

    /** What an import alias names (`getAliasedSymbol`), or null when [symbol] is not one. */
    fun aliasTarget(symbol: Symbol): Symbol?

    /** The symbol a written type reference's name denotes, an import alias followed. */
    fun typeReferenceSymbol(node: TypeReference): Symbol?
}
