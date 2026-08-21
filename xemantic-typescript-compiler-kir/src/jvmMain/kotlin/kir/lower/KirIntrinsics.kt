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

@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.xemantic.typescript.compiler.kir.lower

import com.xemantic.typescript.compiler.kir.emit.IrProgramBuilder
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.defaultType

/**
 * Every function outside the generated program that the lowering may call.
 *
 * A TABLE, deliberately, rather than a special case per call site. Two
 * populations live here and they are different in kind:
 *
 * - the **runtime**, which exists because a JavaScript behaviour is not
 *   expressible as IR the Kotlin backend would already generate — number
 *   formatting, `+` overloading, truthiness, strict equality;
 * - the **library intrinsics**, which are TypeScript declarations this backend
 *   never lowers because they live in a lib `.d.ts`. `console.log` is the only
 *   one in the spike subset, and it is named by the checker's own rendering of
 *   the receiver's type plus the member name — not by matching the syntax
 *   `console.log`, which a local variable called `console` would fool.
 *
 * Resolution is lazy: a program that never concatenates should not fail to
 * compile because `String.plus` could not be found.
 */
internal class KirIntrinsics(
    private val builder: IrProgramBuilder,
    private val irFile: IrFile,
) {

    private val runtimePackage = "com.xemantic.typescript.compiler.kir.runtime"

    val consoleLog: IrSimpleFunctionSymbol by lazy { runtime("consoleLog") }
    val jsToString: IrSimpleFunctionSymbol by lazy { runtime("jsToString") }
    val jsToNumber: IrSimpleFunctionSymbol by lazy { runtime("jsToNumber") }
    val jsAdd: IrSimpleFunctionSymbol by lazy { runtime("jsAdd") }
    val jsTruthy: IrSimpleFunctionSymbol by lazy { runtime("jsTruthy") }
    val jsStrictEquals: IrSimpleFunctionSymbol by lazy { runtime("jsStrictEquals") }

    /** The runtime's array class — what every `T[]`, `Array<T>` and tuple erases to. */
    val jsArrayClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsArray")
    }

    val jsArrayType: IrType by lazy { jsArrayClass.owner.defaultType }

    /** An array literal's constructor: `jsArrayOf(vararg Any?)`. */
    val jsArrayOf: IrSimpleFunctionSymbol by lazy { runtime("jsArrayOf") }

    /** The runtime's property bag — what an interface or object type erases to. */
    val jsObjectClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsObject")
    }

    val jsObjectType: IrType by lazy { jsObjectClass.owner.defaultType }

    /** An object literal's constructor: `jsObjectOf(vararg Any?)`, name/value flat. */
    val jsObjectOf: IrSimpleFunctionSymbol by lazy { runtime("jsObjectOf") }

    val jsObjectGet: IrSimpleFunctionSymbol by lazy {
        runtimeMember(jsObjectClass, "get", 1) ?: error("JsObject.get is missing")
    }

    val jsObjectSet: IrSimpleFunctionSymbol by lazy {
        runtimeMember(jsObjectClass, "set", 2) ?: error("JsObject.set is missing")
    }

    /**
     * The member [name] of a runtime class, selected by ARITY.
     *
     * This is how a TypeScript `a.push(x)` reaches `JsArray.push` — by the
     * receiver's ERASED type rather than by the checker's rendering of its
     * TypeScript one, because that rendering carries the element type
     * (`string[]`, `number[]`, `Cover[]`) and would need one table row per
     * element type in the program.
     */
    fun runtimeMember(
        owner: IrClassSymbol,
        name: String,
        argumentCount: Int
    ): IrSimpleFunctionSymbol? = owner.owner.declarations
        .filterIsInstance<IrSimpleFunction>()
        .firstOrNull { candidate ->
            candidate.name.asString() == name &&
                candidate.parameters.count { it.kind == IrParameterKind.Regular } ==
                    argumentCount
        }?.symbol

    /**
     * The GETTER of a runtime class's property, e.g. `JsArray.length`.
     *
     * A property is not among a class's functions — it is an `IrProperty` whose
     * accessors hang off it — so a lookup that only searched functions would
     * report `length` as absent, which reads as "the backend does not support
     * it" rather than as "the lookup asked the wrong table".
     */
    fun runtimePropertyGetter(owner: IrClassSymbol, name: String): IrSimpleFunctionSymbol? =
        owner.owner.declarations.filterIsInstance<IrProperty>()
            .firstOrNull { it.name.asString() == name }?.getter?.symbol

    /**
     * The runtime classes a LIBRARY type erases to, by the library type's name.
     *
     * Deliberately a short, explicit table. Every other lib type answers null
     * and is refused, because a lib type silently erased to something shaped
     * roughly like it is exactly the failure `docs/kir-lowering.md` §8 forbids.
     */
    fun libraryClass(name: String): IrClassSymbol? = when (name) {
        "Array", "ReadonlyArray" -> jsArrayClass
        "Map", "ReadonlyMap", "WeakMap" -> jsMapClass
        "Set", "ReadonlySet", "WeakSet" -> jsSetClass
        else -> null
    }

    val jsMapClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsMap")
    }

    val jsSetClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsSet")
    }

    /** A call of a value whose erasure did not say its arity — `jsCall(f, …)`. */
    val jsCall: IrSimpleFunctionSymbol by lazy { runtime("jsCall") }

    val jsUnsignedShiftRight: IrSimpleFunctionSymbol by lazy {
        runtime("jsUnsignedShiftRight")
    }

    val jsLooseEquals: IrSimpleFunctionSymbol by lazy { runtime("jsLooseEquals") }

    /** `throw <any value>` — the JVM can only throw a `Throwable`, so it carries. */
    val jsThrow: IrSimpleFunctionSymbol by lazy { runtime("jsThrow") }

    /** The VALUE a caught JVM exception carries, unwrapped. */
    val jsCaught: IrSimpleFunctionSymbol by lazy { runtime("jsCaught") }

    /**
     * A `string` member, as a runtime function taking the receiver first.
     *
     * Kotlin's own `String` members are deliberately NOT used: `length` is an
     * `Int` where JavaScript has a number, `charAt` throws where JavaScript
     * answers `""`, and `slice` rejects the negative indices JavaScript counts
     * from the end. Selected by arity, since `slice`/`substring` have a
     * one-argument and a two-argument form.
     */
    fun stringMember(name: String, argumentCount: Int): IrSimpleFunctionSymbol? {
        val function = STRING_MEMBERS[name] ?: return null
        return try {
            builder.referenceFunction(irFile, runtimePackage, function) {
                it.owner.parameters.size == argumentCount + 1
            }
        } catch (_: IllegalStateException) {
            // The member exists at another arity — a `slice(a, b, c)` say. A
            // refusal naming the member is the honest answer, and the caller
            // makes it from the null.
            null
        }
    }

    /** The bitwise operators, each `ToInt32` on both sides and back to a number. */
    fun bitwise(name: String): IrSimpleFunctionSymbol = runtime(name)

    /** The runtime class an erased [type] IS, or null when it is not one. */
    fun runtimeClassOf(type: IrType): IrClassSymbol? = when (type.classifierOrNull) {
        jsArrayClass -> jsArrayClass
        jsMapClass -> jsMapClass
        jsSetClass -> jsSetClass
        else -> null
    }

    /** The no-argument constructor of a runtime class — `new Map()`. */
    fun runtimeConstructor(owner: IrClassSymbol): IrConstructorSymbol? =
        owner.owner.declarations.filterIsInstance<IrConstructor>()
            .firstOrNull { constructor ->
                constructor.parameters.none { it.kind == IrParameterKind.Regular }
            }?.symbol

    /**
     * `FunctionN.invoke` — how a call of a function VALUE reaches its target.
     *
     * Resolved off `IrBuiltIns` rather than through the declaration finder,
     * because `kotlin.FunctionN` is a builtin the compiler synthesizes rather
     * than a class on any classpath.
     */
    fun invoke(arity: Int): IrSimpleFunctionSymbol =
        builder.irBuiltIns.functionN(arity).declarations
            .filterIsInstance<IrSimpleFunction>()
            .single { it.name.asString() == "invoke" }
            .symbol

    /** `String.plus(Any?)` — the only `plus` a `String` receiver has. */
    val stringPlus: IrSimpleFunctionSymbol by lazy {
        builder.referenceMemberFunction(irFile, "kotlin.String", "plus")
    }

    /**
     * The `Double` arithmetic operators, each selected by its OPERAND type.
     *
     * `Double.plus` has an overload per numeric type and picking the wrong one
     * links against a method that does not exist at the call site's argument
     * type — a failure that surfaces at class-load time, far from here. Hence
     * the explicit predicate rather than `single()`.
     */
    /**
     * A UNARY `Double` operator — one parameter, the dispatch receiver.
     *
     * Separate from [doubleOperator] because that one selects by "two
     * parameters, the second a `Double`", which no unary operator satisfies: it
     * matched nothing and failed with a candidate list of one, at the first
     * `-x` the corpus ever contained.
     */
    fun doubleUnaryOperator(name: String): IrSimpleFunctionSymbol =
        builder.referenceMemberFunction(irFile, "kotlin.Double", name) {
            it.owner.parameters.size == 1
        }

    fun doubleOperator(name: String, doubleType: IrType): IrSimpleFunctionSymbol =
        builder.referenceMemberFunction(irFile, "kotlin.Double", name) {
            it.owner.parameters.size == 2 && it.owner.parameters.last().type == doubleType
        }

    /**
     * The library member [memberName] on a receiver the checker renders as
     * [receiverTypeText], or null when it is not one this backend knows.
     */
    fun libraryMember(receiverTypeText: String?, memberName: String?): IrSimpleFunctionSymbol? =
        when ("$receiverTypeText.$memberName") {
            "Console.log" -> consoleLog
            else -> null
        }

    private companion object {
        /**
         * The `string` members this backend gives a runtime function.
         *
         * Everything absent from this table is REFUSED at the call site rather
         * than routed to Kotlin's same-named member, because "same name" is
         * exactly what makes the divergences invisible.
         */
        val STRING_MEMBERS = mapOf(
            "length" to "jsStrLength",
            "charAt" to "jsStrCharAt",
            "indexOf" to "jsStrIndexOf",
            "lastIndexOf" to "jsStrLastIndexOf",
            "includes" to "jsStrIncludes",
            "startsWith" to "jsStrStartsWith",
            "endsWith" to "jsStrEndsWith",
            "slice" to "jsStrSlice",
            "substring" to "jsStrSubstring",
            "toUpperCase" to "jsStrToUpperCase",
            "toLowerCase" to "jsStrToLowerCase",
            "trim" to "jsStrTrim",
            "repeat" to "jsStrRepeat",
            "replace" to "jsStrReplace",
            "replaceAll" to "jsStrReplaceAll",
            "split" to "jsStrSplit",
            "concat" to "jsStrConcat",
        )
    }

    private fun runtime(name: String): IrSimpleFunctionSymbol =
        builder.referenceFunction(irFile, runtimePackage, name)

}
