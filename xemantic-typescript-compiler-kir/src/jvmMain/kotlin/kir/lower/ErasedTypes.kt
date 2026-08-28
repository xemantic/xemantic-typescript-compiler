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

package com.xemantic.typescript.compiler.kir.lower

import com.xemantic.typescript.compiler.ArrowFunction
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.ConstructorType
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.FunctionExpression
import com.xemantic.typescript.compiler.FunctionType
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.IndexSignature
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeFlags
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.types.makeNullable

/**
 * TypeScript [Type] → Kotlin [IrType], implementing `docs/kir-design.md` §3.
 *
 * The whole of union erasure lives in [map] and nowhere else, which is what the
 * design doc asks for: a second copy of the rule would diverge, and the way it
 * would diverge is by widening — the failure mode with no symptom.
 *
 * [classForDeclaration] is how a TypeScript `class` reaches the [IrClass] the
 * lowering generated for it. It is a function rather than a map because the
 * declare pass fills that table WHILE types are already being mapped.
 */
internal class ErasedTypes(
    private val irBuiltIns: IrBuiltIns,
    private val classForDeclaration: (Node) -> IrClass?,
    private val jsArrayType: () -> IrType,
    private val jsObjectType: () -> IrType,
    /** The runtime class a LIBRARY type of this name erases to, if any. */
    private val libraryType: (String) -> IrType?,
    /**
     * Whether an object type's declaration is one THIS program wrote.
     *
     * The gate that keeps the property-bag erasure honest: an anonymous object
     * type and an interface the program declares are property bags, and a
     * LIBRARY type — `Map`, `Date`, `RegExp` — is not. Without it every lib
     * type would erase to an empty bag whose members all read `undefined`,
     * which is the one failure mode `docs/kir-lowering.md` §8 exists to
     * prevent: silent, and shaped exactly like a working program.
     */
    private val isOwnStructuralDeclaration: (Node) -> Boolean,
    /**
     * What an ENUM (or one of its members) erases to: the type its VALUES have.
     *
     * An enum has no runtime object in this backend — a member access is
     * replaced by its constant — so the type of an enum-typed slot is the type
     * of those constants, `Double` or `String`. A mixed enum answers null and
     * is refused, because there is no one JVM type its members share.
     */
    private val enumErasure: (Node) -> IrType?,
    /** `bigint` — `java.math.BigInteger`, per the design doc's type table. */
    private val bigIntegerType: () -> IrType,
) {

    /** `number`, and every numeric literal type — JS numbers are IEEE-754 doubles. */
    val double: IrType get() = irBuiltIns.doubleType

    val string: IrType get() = irBuiltIns.stringType

    val boolean: IrType get() = irBuiltIns.booleanType

    val unit: IrType get() = irBuiltIns.unitType

    /** What `any`, `unknown` and a heterogeneous union all erase to. */
    val anyNullable: IrType get() = irBuiltIns.anyNType

    /**
     * The type of `null` and of `undefined` — both, deliberately (§3.1).
     *
     * `Nothing?` rather than `Any?` because it is a subtype of every nullable
     * type, so a null constant needs no coercion wherever it is legal.
     */
    val nothingNullable: IrType get() = irBuiltIns.nothingNType

    /**
     * The erasure of [type], or null when this backend has no mapping for it.
     *
     * Null is not a degraded answer to be papered over with `Any?`: the caller
     * must refuse, per `docs/kir-lowering.md` §8. `Any?` is reserved for the
     * types that genuinely erase to it — `any`, `unknown`, and a union whose
     * members do not agree.
     */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun map(type: Type): IrType? {
        val flags = type.flags
        return when {
            flags.hasAny(TypeFlags.NumberLike) -> double
            flags.hasAny(TypeFlags.StringLike) -> string
            flags.hasAny(TypeFlags.BooleanLike) -> boolean
            flags.hasAny(TypeFlags.BigIntLike) -> bigIntegerType()
            flags.hasAny(TypeFlags.Void) -> unit
            flags.hasAny(TypeFlags.Never) -> irBuiltIns.nothingType
            flags.hasAny(TypeFlags.Undefined or TypeFlags.Null) -> nothingNullable
            flags.hasAny(TypeFlags.Any or TypeFlags.Unknown) -> anyNullable
            type is Type.Union -> mapUnion(type)
            type is Type.Intersection -> mapIntersection(type)
            type is Type.Object -> mapObject(type)
            else -> null
        }
    }

    /**
     * §3.2: a union has no runtime representation.
     *
     * All non-nullish members erasing to the same `T` gives `T`, nullable iff a
     * nullish member was present; anything else gives `Any`, nullable on the
     * same condition. Everywhere the program relies on knowing WHICH member it
     * holds is a place the checker has already proven a narrowing, and the
     * lowering pays for it with a cast there rather than with a wrapper here.
     */
    private fun mapUnion(type: Type.Union): IrType? {
        val nullable = type.types.any {
            it.flags.hasAny(TypeFlags.Undefined or TypeFlags.Null or TypeFlags.Void)
        }
        val present = type.types.filterNot {
            it.flags.hasAny(TypeFlags.Undefined or TypeFlags.Null or TypeFlags.Void)
        }
        if (present.isEmpty()) return nothingNullable
        val mapped = present.map { map(it) }
        // A member this backend cannot map does NOT poison the union, as long as
        // some member does: a heterogeneous union erases to `Any` anyway, so the
        // unmappable member costs no information HERE. What it costs is a cast
        // at the use site — and every operation on such a value (a `bigint`
        // literal, a lib type with no runtime class) refuses on its own, which
        // is where the refusal belongs. A union NOTHING maps still refuses.
        if (mapped.all { it == null }) return null
        val erasures = mapped.filterNotNull()
        val first = erasures.first()
        val uniform = erasures.size == mapped.size &&
            erasures.all { it.classifierOrNull == first.classifierOrNull }
        val erased = if (uniform) first else irBuiltIns.anyType
        return if (nullable) erased.makeNullable() else erased
    }

    /**
     * A declared `class` becomes the JVM class generated for it.
     *
     * Interfaces, object literals and every library type OTHER than the array
     * family are out of the spike subset (design doc §3.3 takes the nominal
     * half of the hybrid, starting from declared classes), so they answer null
     * and the caller refuses.
     */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun mapObject(type: Type.Object): IrType? {
        if (isArrayLike(type)) return jsArrayType()
        // A LIBRARY type first, and by NAME: `Map` reaches a runtime class or
        // nothing at all, and must never fall through to the property bag —
        // see [isOwnStructuralDeclaration].
        libraryName(type)?.let { name -> libraryType(name)?.let { return it } }
        val symbol = type.symbol
        val declaration = symbol?.valueDeclaration ?: symbol?.declarations?.firstOrNull()
        // An ENUM erases to the type its members' VALUES have — it has no
        // runtime object here, so `Kind` and `Kind.A` are one JVM type.
        declaration?.let { enumErasure(it) }?.let { return it }
        declaration?.let { classForDeclaration(it) }?.let { return it.defaultType }
        mapCallable(type)?.let { return it }
        // An anonymous object type — an object literal's own type, or a type
        // literal written inline — is a property bag by construction.
        if (declaration == null) return jsObjectType()
        return if (isOwnStructuralDeclaration(declaration)) jsObjectType() else null
    }

    /**
     * An INTERSECTION of object shapes is a property bag, like each of them.
     *
     * `ErrorOptions & { toml: string; ptr: number }` — the branded-options shape
     * every library writes — has no runtime witness of its own: what flows
     * through it is one object carrying the union of the members' properties,
     * which is what a bag is.
     *
     * Refused the moment a member erases to something NOMINAL — a `Date & {…}`
     * is a `JsDate` at run time and calling its methods on a bag would fail
     * inside the runtime. A member this backend cannot map at all is treated as
     * a pure type-level constraint (`ErrorOptions` is one), which is the only
     * reading that keeps branded types usable.
     */
    private fun mapIntersection(type: Type.Intersection): IrType? {
        val bag = jsObjectType()
        val mapped = type.types.map { map(it) }
        if (mapped.any { it != null && it.classifierOrNull != bag.classifierOrNull }) return null
        return bag
    }

    /**
     * A callable of [arity] parameters: `kotlin.FunctionN<Any?, …, Any?>`.
     *
     * UNIFORM in its type arguments, and that is the decision worth stating.
     * Giving a lambda its parameters' own erased types would be more faithful
     * to what the checker knows, and would then reject every place TypeScript's
     * BIVARIANT function assignability accepts a handler whose parameter is
     * narrower than the position wants — `Function1<in P, out R>` is not
     * bivariant, so the JVM would refuse what the program's own type system
     * allowed. All-`Any?` makes any function value fit any position of the same
     * arity, and pays for it with the cast at the use site, which is where
     * union erasure already pays.
     */
    fun function(arity: Int): IrType =
        irBuiltIns.functionN(arity).symbol.typeWith(List(arity + 1) { anyNullable })

    /** The arity of an erased function type, or null when [type] is not one. */
    fun functionArity(type: IrType): Int? {
        val classifier = type.classifierOrNull ?: return null
        return functionClassifiers[classifier]
    }

    private val functionClassifiers: Map<Any, Int> by lazy {
        (0..MAX_FUNCTION_ARITY).associateBy(
            { irBuiltIns.functionN(it).symbol },
            { it }
        )
    }

    /**
     * `T[]`, `Array<T>`, `ReadonlyArray<T>` and every tuple — one JVM shape.
     *
     * A tuple is an ordinary [Type.Object] carrying `tupleElementTypes`, which
     * is the only thing separating `[string, number]` from an anonymous object
     * type; the element types are static and have no runtime witness, exactly
     * as a literal type has none, so the erasure keeps none of them.
     */
    private fun isArrayLike(type: Type.Object): Boolean =
        type.tupleElementTypes != null ||
            (type is Type.Reference && type.target.symbol?.name in ARRAY_TARGETS)

    /**
     * A generic library instantiation — `Map<K, V>` — is named by its TARGET.
     *
     * A [Type.Reference]'s own symbol is the target's, so this and the plain
     * `symbol.name` route agree; it is spelled out because a reference's
     * `resolvedTypeArguments` are the part the erasure DROPS, and dropping them
     * silently is the thing worth being explicit about.
     */
    private fun libraryName(type: Type.Object): String? =
        (type as? Type.Reference)?.target?.symbol?.name ?: type.symbol?.name

    /**
     * A TypeScript function type, by its call signature's arity.
     *
     * `callSignatures` is resolved LAZILY by the checker, so a null here means
     * "nothing ever asked this type for its members", not "it has none" — which
     * is why the answer is a refusal at the call site rather than a silent
     * `Any?`.
     */
    private fun mapCallable(type: Type.Object): IrType? {
        val signature = type.callSignatures?.firstOrNull() ?: return null
        // A VARIADIC signature has no arity to erase to: `...rest` hands the
        // count to the caller, so no `FunctionN` is the right shape and the
        // value stays dynamic. Every call of it then goes through `jsCall`,
        // which is where the actual argument count is known and where the
        // runtime's `JsVarargFunction` is unpacked.
        if (isVariadic(signature.declaration)) return anyNullable
        return function(signature.parameters.size)
    }

    private companion object {
        val ARRAY_TARGETS = setOf("Array", "ReadonlyArray")

        /** `kotlin.FunctionN` exists up to 22 parameters; nothing here needs more. */
        const val MAX_FUNCTION_ARITY = 22
    }

}

/**
 * Do these two erased types have the same JVM class, ignoring nullability?
 *
 * The one predicate the coercion decision is built from — see [coercionFor].
 */
internal fun IrType.sameClassifierAs(other: IrType): Boolean =
    classifierOrNull != null && classifierOrNull == other.classifierOrNull

/** Is this the erasure of a value that may be anything — `Any` or `Any?`. */
internal fun IrType.isErasedAny(irBuiltIns: IrBuiltIns): Boolean =
    classifierOrNull == irBuiltIns.anyClass

/** Is this `Nothing` or `Nothing?` — the type of a `null`/`undefined` constant. */
internal fun IrType.isErasedNothing(irBuiltIns: IrBuiltIns): Boolean =
    classifierOrNull == irBuiltIns.nothingClass

/**
 * Whether [this] value's erased type needs work to become [target].
 *
 * `docs/kir-lowering.md` §6 insists this decision live in ONE place, and the
 * reason is that its failure mode is invisible: a missing cast is a
 * `ClassCastException` at a position nothing in the lowering names, and a
 * missing box is silently invalid bytecode. So the whole of it is these five
 * rules, and every operand position in the backend goes through them.
 *
 * Boxing is deliberately absent from the list: the Kotlin backend inserts it
 * given a correct IR type, so the lowering's job is to state the expression's
 * type honestly and stay out of the way.
 */
internal enum class Coercion {
    /** The value already has the target's shape — widening included. */
    NONE,
    /** A real `checkcast`, plus unboxing where the target is primitive. */
    CAST,
    /** The two types have nothing to do with each other: a lowering bug. */
    IMPOSSIBLE,
}

internal fun coercionFor(from: IrType, target: IrType, irBuiltIns: IrBuiltIns): Coercion = when {
    from == target -> Coercion.NONE
    // A null constant is a subtype of everything nullable and reaches nothing
    // else, so it never needs help.
    from.isErasedNothing(irBuiltIns) -> Coercion.NONE
    // Widening to `Any?` is free; to a non-null `Any` it is free only from a
    // non-null source.
    target.isErasedAny(irBuiltIns) && (target.isNullable() || !from.isNullable()) -> Coercion.NONE
    // Same class, and the value is at least as specific as the target wants.
    from.sameClassifierAs(target) ->
        if (!from.isNullable() || target.isNullable()) Coercion.NONE else Coercion.CAST
    // Narrowing out of `Any`/`Any?` — the price union erasure charges, and the
    // place the checker's proof is spent.
    from.isErasedAny(irBuiltIns) -> Coercion.CAST
    else -> Coercion.IMPOSSIBLE
}

/** Convenience for the common `(expression, target)` shape. */
internal fun coercionOf(value: IrExpression, target: IrType, irBuiltIns: IrBuiltIns): Coercion =
    coercionFor(value.type, target, irBuiltIns)

/**
 * The declared parameter list of a function-like node, or null when [node] is
 * not one.
 *
 * There is no common supertype to ask — each function-like AST class declares
 * its own `parameters` — so the arms are enumerated here ONCE and every
 * consumer shares them. A missing arm reads as "not function-like", i.e. as a
 * non-variadic signature, which is the safe direction: the lowering then keeps
 * its existing `FunctionN` erasure rather than inventing a dynamic one.
 */
internal fun functionLikeParameters(node: Node?): List<Parameter>? = when (node) {
    is FunctionDeclaration -> node.parameters
    is FunctionExpression -> node.parameters
    is ArrowFunction -> node.parameters
    is MethodDeclaration -> node.parameters
    is Constructor -> node.parameters
    is GetAccessor -> node.parameters
    is SetAccessor -> node.parameters
    is IndexSignature -> node.parameters
    is FunctionType -> node.parameters
    is ConstructorType -> node.parameters
    else -> null
}

/**
 * Does [node] declare a rest parameter?
 *
 * `Signature` carries no such flag — [com.xemantic.typescript.compiler.Signature]
 * has `parameters` and `minArgumentCount` and nothing that separates `f(a, b)`
 * from `f(a, ...b)` — so the fact is read back off the declaration, which is the
 * only place it exists.
 */
internal fun isVariadic(node: Node?): Boolean =
    functionLikeParameters(node)?.lastOrNull()?.dotDotDotToken == true
