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
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isNullableAny
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

    /**
     * `ToString` where the erased slot's `null` means `undefined` — see the
     * runtime's own KDoc, and `KirFileLowering.nullRendersAsUndefined` for who
     * decides between the two.
     */
    val jsToStringNullAsUndefined: IrSimpleFunctionSymbol by lazy {
        runtime("jsToStringNullAsUndefined")
    }

    /** `String(x)` where the lowering already proved `x` is a `number`. */
    val jsNumberToString: IrSimpleFunctionSymbol by lazy { runtime("jsNumberToString") }
    val jsToNumber: IrSimpleFunctionSymbol by lazy { runtime("jsToNumber") }
    val jsAdd: IrSimpleFunctionSymbol by lazy { runtime("jsAdd") }
    val jsTruthy: IrSimpleFunctionSymbol by lazy { runtime("jsTruthy") }
    val jsTruthyNumber: IrSimpleFunctionSymbol by lazy { runtime("jsTruthyNumber") }
    val jsTruthyString: IrSimpleFunctionSymbol by lazy { runtime("jsTruthyString") }
    val jsTruthyNumberOrNull: IrSimpleFunctionSymbol by lazy {
        runtime("jsTruthyNumberOrNull")
    }
    val jsTruthyStringOrNull: IrSimpleFunctionSymbol by lazy {
        runtime("jsTruthyStringOrNull")
    }
    val jsTruthyBooleanOrNull: IrSimpleFunctionSymbol by lazy {
        runtime("jsTruthyBooleanOrNull")
    }
    val jsStrictEquals: IrSimpleFunctionSymbol by lazy { runtime("jsStrictEquals") }

    /**
     * `===` specialized by the ERASED operand types — see `jsStrictEqualsNumbers`.
     *
     * Named individually rather than resolved as overloads because
     * [referenceFunction] selects a top-level function by NAME, and a name that
     * resolves to more than one declaration has no answer.
     */
    val jsStrictEqualsNumbers: IrSimpleFunctionSymbol by lazy { runtime("jsStrictEqualsNumbers") }
    val jsStrictEqualsStrings: IrSimpleFunctionSymbol by lazy { runtime("jsStrictEqualsStrings") }
    val jsStrictEqualsBooleans: IrSimpleFunctionSymbol by lazy {
        runtime("jsStrictEqualsBooleans")
    }
    val jsStrictEqualsAnyNumber: IrSimpleFunctionSymbol by lazy {
        runtime("jsStrictEqualsAnyNumber")
    }
    val jsStrictEqualsNumberAny: IrSimpleFunctionSymbol by lazy {
        runtime("jsStrictEqualsNumberAny")
    }
    val jsStrictEqualsAnyString: IrSimpleFunctionSymbol by lazy {
        runtime("jsStrictEqualsAnyString")
    }
    val jsStrictEqualsStringAny: IrSimpleFunctionSymbol by lazy {
        runtime("jsStrictEqualsStringAny")
    }

    val jsTypeOf: IrSimpleFunctionSymbol by lazy { runtime("jsTypeOf") }

    /** The runtime's array class — what every `T[]`, `Array<T>` and tuple erases to. */
    val jsArrayClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsArray")
    }

    val jsArrayType: IrType by lazy { jsArrayClass.owner.defaultType }

    /** An array literal's constructor: `jsArrayOf(vararg Any?)`. */
    val jsArrayOf: IrSimpleFunctionSymbol by lazy { runtime("jsArrayOf") }

    /** `jsForInKeys(subject)` — the keys `for…in` walks, as an array of strings. */
    val jsForInKeys: IrSimpleFunctionSymbol by lazy { runtime("jsForInKeys") }

    /**
     * The runtime carrier for a function value whose arity is NOT static — one
     * declared with a rest parameter, see `JsRuntime.JsVarargFunction`.
     *
     * It is deliberately not a `FunctionN`: there is no `N` to pick, because the
     * CALLER decides how many arguments there are. Every call of such a value
     * therefore goes through `jsCall`, which is the one place that knows the
     * actual count.
     */
    val jsVarargFunctionClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsVarargFunction")
    }

    val jsVarargFunctionType: IrType by lazy { jsVarargFunctionClass.owner.defaultType }

    /** `jsVarargFunction(fixed, impl)` — builds one. */
    val jsVarargFunction: IrSimpleFunctionSymbol by lazy { runtime("jsVarargFunction") }

    /** `jsVarargFixed(arguments, i)` — one NAMED parameter of a variadic body. */
    val jsVarargFixed: IrSimpleFunctionSymbol by lazy { runtime("jsVarargFixed") }

    /** `jsVarargRest(arguments, from)` — the REST parameter of a variadic body. */
    val jsVarargRest: IrSimpleFunctionSymbol by lazy { runtime("jsVarargRest") }

    /** The runtime's property bag — what an interface or object type erases to. */
    val jsObjectClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsObject")
    }

    val jsObjectType: IrType by lazy { jsObjectClass.owner.defaultType }

    /** `JsObject()` — what a generated SHAPE class delegates to. */
    val jsObjectConstructor: IrConstructorSymbol by lazy {
        runtimeConstructor(jsObjectClass) ?: error("JsObject has no no-argument constructor")
    }

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
     * A `number` member — `toString`, `toFixed` — as a runtime function.
     *
     * Kotlin's own are not usable for the reason the string ones are not:
     * `Double.toString()` prints `6.0` where JavaScript prints `6`.
     */
    fun numberMember(name: String, argumentCount: Int): IrSimpleFunctionSymbol? {
        val function = NUMBER_MEMBERS[name] ?: return null
        return try {
            builder.referenceFunction(irFile, runtimePackage, function) {
                it.owner.parameters.size == argumentCount + 1
            }
        } catch (_: IllegalStateException) {
            null
        }
    }

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
        // A match result IS an array — with `index` and `input` properties this
        // runtime does not carry, which a program reading them refuses on.
        "Array", "ReadonlyArray", "RegExpMatchArray", "RegExpExecArray" -> jsArrayClass
        "Map", "ReadonlyMap", "WeakMap" -> jsMapClass
        "Set", "ReadonlySet", "WeakSet" -> jsSetClass
        "RegExp" -> jsRegExpClass
        "Date" -> jsDateClass
        "Error" -> jsErrorClass
        else -> null
    }

    val jsErrorClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsError")
    }

    val jsDateClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsDate")
    }

    val jsRegExpClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsRegExp")
    }

    /** A regular-expression LITERAL: `jsRegExp(source, flags)`. */
    val jsRegExp: IrSimpleFunctionSymbol by lazy { runtime("jsRegExp") }

    val jsRegExpType: IrType by lazy { jsRegExpClass.owner.defaultType }

    /**
     * `bigint`, per the design doc's type table.
     *
     * `java.math.BigInteger` where there is one. Kotlin/Native has no such class
     * and no arbitrary-precision integer in its standard library, so the runtime
     * carries its own — the TYPE has to exist there, because a library offering
     * "integers as BigInt" has the literal in its source whether or not the
     * option is ever used, and refusing the type would refuse the library.
     */
    val bigIntegerType: IrType by lazy {
        val fqName =
            if (builder.pluginContext.platform?.isJvm() == true) "java.math.BigInteger"
            else "$runtimePackage.JsBigInt"
        builder.referenceClass(irFile, fqName).owner.defaultType
    }

    val jsBigInt: IrSimpleFunctionSymbol by lazy { runtime("jsBigInt") }

    val jsMapClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsMap")
    }

    val jsSetClass: IrClassSymbol by lazy {
        builder.referenceClass(irFile, "$runtimePackage.JsSet")
    }

    /** A call of a value whose erasure did not say its arity — `jsCall(f, …)`. */
    val jsCall: IrSimpleFunctionSymbol by lazy { runtime("jsCall") }

    /**
     * The same call, specialized to a small argument COUNT — `jsCall2(f, a, b)`.
     *
     * Same semantics as [jsCall], which includes still adapting to the CALLEE's
     * own arity; what it saves is the `vararg` array and all but one of the type
     * tests. Null above [MAX_SPECIALIZED_CALL_ARITY], where the caller falls
     * back to the general form.
     */
    fun jsCallSpecialized(argumentCount: Int): IrSimpleFunctionSymbol? =
        if (argumentCount > MAX_SPECIALIZED_CALL_ARITY) null
        else specializedCalls.getOrPut(argumentCount) { runtime("jsCall$argumentCount") }

    private val specializedCalls = HashMap<Int, IrSimpleFunctionSymbol>()

    val jsUnsignedShiftRight: IrSimpleFunctionSymbol by lazy {
        runtime("jsUnsignedShiftRight")
    }

    val jsLooseEquals: IrSimpleFunctionSymbol by lazy { runtime("jsLooseEquals") }

    /** The DYNAMIC member operations, for a receiver the checker typed `any`. */
    val jsGet: IrSimpleFunctionSymbol by lazy { runtime("jsGet") }

    val jsSet: IrSimpleFunctionSymbol by lazy { runtime("jsSet") }

    val jsInvoke: IrSimpleFunctionSymbol by lazy { runtime("jsInvoke") }

    val jsIndexGet: IrSimpleFunctionSymbol by lazy { runtime("jsIndexGet") }

    val jsIndexSet: IrSimpleFunctionSymbol by lazy { runtime("jsIndexSet") }

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
    fun stringMember(
        name: String,
        argumentCount: Int,
        firstArgumentIsRegExp: Boolean = false,
        lastArgumentIsFunction: Boolean = false
    ): IrSimpleFunctionSymbol? {
        val function = STRING_MEMBERS[name] ?: return null
        return try {
            builder.referenceFunction(irFile, runtimePackage, function) {
                val parameters = it.owner.parameters
                if (parameters.size != argumentCount + 1) return@referenceFunction false
                if (argumentCount == 0) return@referenceFunction true
                // `match`, `replace` and `split` each have a STRING form and a
                // REGEXP form of the same arity, so the argument's own erased
                // type is what tells them apart.
                val second = parameters.getOrNull(1)?.type?.classifierOrNull
                if ((second == jsRegExpClass) != firstArgumentIsRegExp) {
                    return@referenceFunction false
                }
                // `replace` has a further pair at the SAME arity and the same
                // pattern kind: a replacement STRING and a replacer FUNCTION,
                // which the runtime distinguishes by taking the latter as
                // `Any?`. No other member in `STRING_MEMBERS` ends in `Any?`,
                // so this test is inert for all of them.
                parameters.last().type.isNullableAny() == lastArgumentIsFunction
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
        jsRegExpClass -> jsRegExpClass
        jsDateClass -> jsDateClass
        jsErrorClass -> jsErrorClass
        else -> null
    }

    /**
     * The constructor of a runtime class taking [argumentCount] arguments.
     *
     * Distinct from [runtimeConstructor], which is the no-argument one: a
     * generated class extending `JsDate` delegates to `JsDate(Any?)` with the
     * value its own `super(…)` passed.
     */
    fun runtimeConstructorOfArity(
        owner: IrClassSymbol,
        argumentCount: Int
    ): IrConstructorSymbol? = owner.owner.declarations.filterIsInstance<IrConstructor>()
        .firstOrNull { constructor ->
            constructor.parameters.count { it.kind == IrParameterKind.Regular } == argumentCount
        }?.symbol

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
        GLOBAL_MEMBERS["$receiverTypeText.$memberName"]?.let { runtime(it) }

    /**
     * A GLOBAL free function — `isNaN`, `parseInt`, `String(x)` — by name and
     * arity.
     *
     * Asked only once a call has resolved to a declaration this backend did NOT
     * generate, i.e. a lib one, so a program's own `function parseInt` is
     * unaffected.
     */
    fun globalFunction(name: String, argumentCount: Int): IrSimpleFunctionSymbol? {
        val function = GLOBAL_FUNCTIONS[name] ?: return null
        return try {
            builder.referenceFunction(irFile, runtimePackage, function) {
                it.owner.parameters.size == argumentCount
            }
        } catch (_: IllegalStateException) {
            null
        }
    }

    private companion object {
        /** How many arguments [jsCallSpecialized] has a fixed-arity entry point for. */
        const val MAX_SPECIALIZED_CALL_ARITY: Int = 3

        /**
         * The `string` members this backend gives a runtime function.
         *
         * Everything absent from this table is REFUSED at the call site rather
         * than routed to Kotlin's same-named member, because "same name" is
         * exactly what makes the divergences invisible.
         */
        /**
         * The GLOBAL objects' members, by the checker's rendering of the
         * receiver plus the member name.
         *
         * Keyed by the checker's own answer rather than by the syntax
         * `Math.floor`, so a local variable called `Math` cannot be mistaken
         * for the global one. Everything absent is refused at the call site.
         */
        val GLOBAL_MEMBERS = mapOf(
            "Console.log" to "consoleLog",
            "Console.error" to "consoleLog",
            "Math.floor" to "jsMathFloor",
            "Math.ceil" to "jsMathCeil",
            "Math.round" to "jsMathRound",
            "Math.trunc" to "jsMathTrunc",
            "Math.abs" to "jsMathAbs",
            "Math.sign" to "jsMathSign",
            "Math.sqrt" to "jsMathSqrt",
            "Math.log" to "jsMathLog",
            "Math.log10" to "jsMathLog10",
            "Math.log2" to "jsMathLog2",
            "Math.pow" to "jsMathPow",
            "Math.min" to "jsMathMin",
            "Math.max" to "jsMathMax",
            "NumberConstructor.isInteger" to "jsNumberIsInteger",
            "NumberConstructor.isSafeInteger" to "jsNumberIsSafeInteger",
            "NumberConstructor.isFinite" to "jsNumberIsFinite",
            "NumberConstructor.isNaN" to "jsNumberIsNaN",
            "NumberConstructor.parseFloat" to "jsParseFloat",
            "ObjectConstructor.keys" to "jsObjectKeys",
            "ObjectConstructor.entries" to "jsObjectEntries",
            "ObjectConstructor.values" to "jsObjectValues",
            "ObjectConstructor.hasOwn" to "jsObjectHasOwn",
            "ObjectConstructor.defineProperty" to "jsObjectDefineProperty",
            "JSON.stringify" to "jsJsonStringify",
            "StringConstructor.fromCharCode" to "jsStrFromCharCode",
            "StringConstructor.fromCodePoint" to "jsStrFromCodePoint",
        )

        val GLOBAL_FUNCTIONS = mapOf(
            "isNaN" to "jsIsNaN",
            "isFinite" to "jsIsFinite",
            "parseInt" to "jsParseInt",
            "parseFloat" to "jsParseFloat",
            "String" to "jsStringOf",
            "Number" to "jsNumberOf",
            "BigInt" to "jsBigIntOf",
        )

        val NUMBER_MEMBERS = mapOf(
            "toString" to "jsNumToString",
            "toFixed" to "jsNumToFixed",
        )

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
            "trimStart" to "jsStrTrimStart",
            "trimEnd" to "jsStrTrimEnd",
            "padStart" to "jsStrPadStart",
            "padEnd" to "jsStrPadEnd",
            "repeat" to "jsStrRepeat",
            "replace" to "jsStrReplace",
            "replaceAll" to "jsStrReplaceAll",
            "split" to "jsStrSplit",
            "concat" to "jsStrConcat",
            "match" to "jsStrMatch",
            "charCodeAt" to "jsStrCharCodeAt",
            "codePointAt" to "jsStrCodePointAt",
        )
    }

    private fun runtime(name: String): IrSimpleFunctionSymbol =
        builder.referenceFunction(irFile, runtimePackage, name)

}
