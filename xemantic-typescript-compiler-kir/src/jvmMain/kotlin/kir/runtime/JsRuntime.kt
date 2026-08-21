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

package com.xemantic.typescript.compiler.kir.runtime

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * The JS-semantics runtime the generated Kotlin IR links against.
 *
 * Every function here exists because a JavaScript behaviour has no JVM
 * equivalent and therefore cannot be expressed as a single IR node: number
 * formatting, `+` overloading, truthiness, sparse arrays. The backend resolves
 * these by `CallableId` and emits ordinary static calls to them, exactly as it
 * resolves `kotlin.io.println`.
 *
 * The bar for adding something here is that it is NOT expressible as IR the
 * Kotlin backend would already generate. `a - b` on two numbers is a `Double`
 * subtraction and belongs in the lowering, not in this file; `a + b` does not,
 * because whether it adds or concatenates depends on runtime types whenever the
 * checker could not settle it statically.
 */

/** JavaScript `undefined`, distinct from `null` — see `docs/kir-design.md` §3.1. */
public object Undefined {
    override fun toString(): String = "undefined"
}

/**
 * ECMAScript `Number::toString`. Kotlin's `Double.toString` is not it: JS prints
 * an integral double WITHOUT a decimal point (`1`, not `1.0`), spells infinity
 * `Infinity`, and uses exponential notation on different thresholds.
 *
 * This is a spike-grade approximation: exact for integral values in the
 * `±2^53` safe-integer range, and a textual cleanup of the JVM rendering
 * elsewhere. A conformant implementation is shortest-round-trip (Ryu/Grisu),
 * which is a separate piece of work.
 */
public fun jsNumberToString(value: Double): String = when {
    value.isNaN() -> "NaN"
    value == Double.POSITIVE_INFINITY -> "Infinity"
    value == Double.NEGATIVE_INFINITY -> "-Infinity"
    // Covers -0.0, which JS prints as "0".
    value == 0.0 -> "0"
    value == floor(value) && abs(value) < 9007199254740992.0 -> value.toLong().toString()
    else -> {
        val jvm = value.toString()
        if (jvm.endsWith(".0")) jvm.dropLast(2)
        else if (jvm.contains('E')) jvm.replace("E", "e+").replace("e+-", "e-")
        else jvm
    }
}

/**
 * A `bigint` literal — `0n` — as a `java.math.BigInteger`.
 *
 * The design doc's mapping, arrived at because a library that offers "integers
 * as BigInt" has the literal in its source whether or not the option is used:
 * refusing the TYPE would refuse the library. Arithmetic ON a bigint is not
 * lowered, so a program that computes with one refuses where it does so.
 */
public fun jsBigInt(digits: String): java.math.BigInteger = java.math.BigInteger(digits)

/** `BigInt(x)`. */
public fun jsBigIntOf(value: Any?): java.math.BigInteger = when (value) {
    is java.math.BigInteger -> value
    is Double -> java.math.BigInteger.valueOf(value.toLong())
    else -> java.math.BigInteger(jsToString(value))
}

/**
 * The `typeof` operator.
 *
 * One deliberate divergence, and it follows from design §3.1's collapse of
 * `undefined` and `null` onto one JVM value: `typeof null` answers
 * **"undefined"** where a JavaScript engine says "object". The collapse forces
 * a choice between the two guards, and `typeof x === 'undefined'` is the one
 * real code writes — while the `typeof x === 'object'` idiom is almost always
 * written as `x !== null && typeof x === 'object'`, which still works.
 */
public fun jsTypeOf(value: Any?): String = when (value) {
    null, Undefined -> "undefined"
    is Boolean -> "boolean"
    is Double -> "number"
    is String -> "string"
    is java.math.BigInteger -> "bigint"
    is Function<*> -> "function"
    else -> "object"
}

/** ECMAScript `ToString`, for the operand positions that coerce. */
public fun jsToString(value: Any?): String = when (value) {
    null -> "null"
    Undefined -> "undefined"
    is Double -> jsNumberToString(value)
    is String -> value
    is Boolean -> if (value) "true" else "false"
    is JsArray -> value.joinToJsString()
    is JsObject -> "[object Object]"
    is java.math.BigInteger -> value.toString()
    else -> value.toString()
}

/**
 * The `+` operator, for the operands the checker could not resolve to a single
 * type. Where it could — both `number`, or either statically `string` — the
 * lowering emits a `Double` add or a `String.plus` directly and never calls this.
 */
public fun jsAdd(left: Any?, right: Any?): Any? =
    if (left is String || right is String) jsToString(left) + jsToString(right)
    else jsToNumber(left) + jsToNumber(right)

/** ECMAScript `ToNumber`, restricted to the values the spike subset can produce. */
public fun jsToNumber(value: Any?): Double = when (value) {
    null -> 0.0
    Undefined -> Double.NaN
    is Double -> value
    is Boolean -> if (value) 1.0 else 0.0
    is String -> stringToNumber(value)
    is java.math.BigInteger -> value.toDouble()
    else -> Double.NaN
}

/**
 * ECMAScript `StringToNumber`, which is NOT `String.toDouble`.
 *
 * The three differences that bite: an empty or blank string is `0`, the
 * RADIX PREFIXES `0x` / `0o` / `0b` are understood (`+"0xDEADBEEF"` is
 * 3735928559 — the shape a TOML parser converts its integers with), and
 * `Infinity` is spelled out. Kotlin's `toDoubleOrNull` accepts none of the
 * three and additionally accepts things JavaScript rejects, such as a trailing
 * `d` or `f` suffix.
 */
private fun stringToNumber(value: String): Double {
    val text = value.trim()
    if (text.isEmpty()) return 0.0
    val negative = text.startsWith("-")
    val body = text.removePrefix("+").removePrefix("-")
    if (body == "Infinity") return if (negative) Double.NEGATIVE_INFINITY
    else Double.POSITIVE_INFINITY
    val radix = when {
        body.startsWith("0x") || body.startsWith("0X") -> 16
        body.startsWith("0o") || body.startsWith("0O") -> 8
        body.startsWith("0b") || body.startsWith("0B") -> 2
        else -> 10
    }
    if (radix != 10) {
        // A radix literal may not carry a sign in JavaScript, so a signed one
        // is `NaN` rather than a negated value.
        if (negative || text.startsWith("+")) return Double.NaN
        return body.drop(2).toLongOrNull(radix)?.toDouble() ?: Double.NaN
    }
    if (body.any { it == 'd' || it == 'D' || it == 'f' || it == 'F' }) return Double.NaN
    return text.toDoubleOrNull() ?: Double.NaN
}

/**
 * ECMAScript `ToBoolean`. Needed wherever a non-boolean reaches a condition:
 * `if (s)` on a string is legal TypeScript and is false for `""`.
 */
public fun jsTruthy(value: Any?): Boolean = when (value) {
    null, Undefined, false -> false
    true -> true
    is Double -> value != 0.0 && !value.isNaN()
    is String -> value.isNotEmpty()
    else -> true
}

/**
 * The `===` operator. Distinct from Kotlin's `===`: JS strict equality compares
 * strings and numbers BY VALUE, and `NaN !== NaN`. Reference types compare by
 * identity, which is the one part Kotlin's `===` would have given us.
 */
public fun jsStrictEquals(left: Any?, right: Any?): Boolean = when {
    left is Double && right is Double -> left == right
    left is String && right is String -> left == right
    left is Boolean && right is Boolean -> left == right
    else -> left === right
}

/**
 * A JavaScript array.
 *
 * Not `ArrayList`, and the differences are exactly why this class exists: an
 * out-of-range read yields `undefined` rather than throwing, a write past the
 * end GROWS the array with holes, and `length` is assignable (truncating).
 *
 * **Not generic**, deliberately. The element type is erased by
 * `docs/kir-design.md` §3 anyway, and a type parameter would force every
 * generated call site to carry a type argument for a substitution that can
 * only ever be `Any?` — complexity the emitted program never observes.
 *
 * Indices are `Double` because a JavaScript index IS a number, and the
 * lowering has no honest place to insert a narrowing to `Int`: `a[i]` where
 * `i` is `0.5` or `1e21` is legal TypeScript, and reads a hole.
 */
public class JsArray private constructor(
    private val backing: ArrayList<Any?>
) {

    public constructor() : this(ArrayList())

    public constructor(elements: List<Any?>) : this(ArrayList(elements))

    /**
     * A hole and an out-of-range read are both `null` here, not [Undefined].
     *
     * That follows the design's single decision to map `undefined` and `null`
     * onto one JVM value: were an array to yield [Undefined], `a[9] === undefined`
     * would compare an `Undefined` against the `null` the lowering emits for the
     * literal `undefined`, and answer false — a divergence with no diagnostic.
     */
    public var length: Double
        get() = backing.size.toDouble()
        set(newLength) {
            val target = slotOf(newLength) ?: 0
            while (backing.size > target) backing.removeAt(backing.size - 1)
            while (backing.size < target) backing.add(null)
        }

    /** Out-of-range is `undefined`, never an exception — the whole point. */
    public operator fun get(index: Double): Any? {
        val i = slotOf(index) ?: return null
        return if (i >= backing.size) null else backing[i]
    }

    public operator fun set(index: Double, value: Any?) {
        val i = slotOf(index) ?: return
        while (backing.size <= i) backing.add(null)
        backing[i] = value
    }

    public fun push(value: Any?): Double {
        backing.add(value)
        return backing.size.toDouble()
    }

    public fun pop(): Any? =
        if (backing.isEmpty()) null else backing.removeAt(backing.size - 1)

    public fun shift(): Any? =
        if (backing.isEmpty()) null else backing.removeAt(0)

    public fun unshift(value: Any?): Double {
        backing.add(0, value)
        return backing.size.toDouble()
    }

    /** `Array.prototype.indexOf`, comparing with `===` as the spec says. */
    public fun indexOf(value: Any?): Double {
        backing.forEachIndexed { index, element ->
            if (jsStrictEquals(element, value)) return index.toDouble()
        }
        return -1.0
    }

    public fun includes(value: Any?): Boolean = indexOf(value) >= 0.0

    /** `Array.prototype.slice()` — a shallow copy, whole-array form only. */
    public fun slice(): JsArray = JsArray(ArrayList(backing))

    public fun concat(other: JsArray): JsArray =
        JsArray(ArrayList(backing).also { it.addAll(other.backing) })

    /** `Array.prototype.splice(start, deleteCount)`, returning what it removed. */
    public fun splice(start: Double, deleteCount: Double): JsArray {
        val from = (slotOf(start) ?: 0).coerceIn(0, backing.size)
        val count = (slotOf(deleteCount) ?: 0).coerceIn(0, backing.size - from)
        val removed = ArrayList<Any?>(count)
        repeat(count) { removed.add(backing.removeAt(from)) }
        return JsArray(removed)
    }

    public fun join(separator: String): String = joinToJsString(separator)

    /**
     * `Array.prototype.forEach` / `map` / `filter`, ONE-ARGUMENT callbacks only.
     *
     * JavaScript passes `(element, index, array)`; a callback that wants the
     * index is a `Function2` where these declare a `Function1`, so the lowering
     * refuses it at the argument's coercion rather than handing the runtime a
     * lambda it would fail to cast — which is the difference between a
     * diagnostic naming the position and a `ClassCastException` inside the
     * runtime.
     */
    public fun forEach(callback: (Any?) -> Any?) {
        // Indexed rather than iterator-based: a callback may push or splice,
        // and JS visits the elements the array HAD when the walk reached them.
        var index = 0
        while (index < backing.size) {
            callback(backing[index])
            index++
        }
    }

    public fun map(callback: (Any?) -> Any?): JsArray {
        val result = ArrayList<Any?>(backing.size)
        var index = 0
        while (index < backing.size) {
            result.add(callback(backing[index]))
            index++
        }
        return JsArray(result)
    }

    public fun filter(callback: (Any?) -> Any?): JsArray {
        val result = ArrayList<Any?>()
        var index = 0
        while (index < backing.size) {
            val element = backing[index]
            if (jsTruthy(callback(element))) result.add(element)
            index++
        }
        return JsArray(result)
    }

    /** `Array.prototype.join` with the default separator, as `toString` uses it. */
    public fun joinToJsString(separator: String = ","): String =
        backing.joinToString(separator) {
            if (it == null || it == Undefined) "" else jsToString(it)
        }

    override fun toString(): String = joinToJsString()

    /** A JS index as a JVM one, or null when it names no slot at all. */
    private fun slotOf(index: Double): Int? {
        if (index.isNaN() || index < 0.0 || index > Int.MAX_VALUE.toDouble()) return null
        val truncated = index.toInt()
        return if (truncated.toDouble() == index) truncated else null
    }

}

/**
 * A JavaScript object: an ordered bag of named properties.
 *
 * This is the DYNAMIC half of `docs/kir-design.md` §3.3's hybrid, and it is
 * what an interface type, an anonymous object type and an object literal all
 * erase to. A JavaScript object IS a property bag — reading an absent property
 * yields `undefined` rather than failing — so modelling one as a generated JVM
 * class with fields is the optimization, not the faithful thing.
 *
 * The price is stated rather than hidden: a property read is a hash lookup, and
 * a compiled library's surface is not idiomatically callable from Kotlin. Both
 * are what the nominal half buys back, later, for the targets a whole-program
 * closure can name (`docs/kir-structural-typing.md` §7).
 */
public class JsObject private constructor(
    private val properties: LinkedHashMap<String, Any?>
) {

    public constructor() : this(LinkedHashMap())

    /** An absent property is `undefined`, i.e. `null` here — never an error. */
    public fun get(name: String): Any? = properties[name]

    public fun set(name: String, value: Any?) {
        properties[name] = value
    }

    /** `name in object`. */
    public fun has(name: String): Boolean = properties.containsKey(name)

    /** `delete object.name`, whose result is `true` for a configurable property. */
    public fun delete(name: String): Boolean {
        properties.remove(name)
        return true
    }

    /** `Object.keys(object)`, in insertion order as JavaScript specifies. */
    public fun keys(): JsArray = JsArray(properties.keys.toList())

    override fun toString(): String = "[object Object]"

}

/**
 * An object literal: `{ a: 1, b: "x" }`, as a FLAT name/value sequence.
 *
 * Flat rather than a list of pairs because the generated IR would otherwise
 * have to construct a `kotlin.Pair` per property — a second allocation and a
 * second symbol to resolve, for a call the lowering makes at every literal.
 */
public fun jsObjectOf(vararg entries: Any?): JsObject {
    require(entries.size % 2 == 0) { "an object literal needs one value per name" }
    val result = JsObject()
    var index = 0
    while (index < entries.size) {
        result.set(entries[index] as String, entries[index + 1])
        index += 2
    }
    return result
}

/** An array literal: `[a, b, c]`. */
public fun jsArrayOf(vararg elements: Any?): JsArray = JsArray(elements.toList())

/**
 * A JavaScript `Map`.
 *
 * Key equality is the JVM's `equals`, which coincides with SameValueZero for
 * every value this backend produces: `Double` and `String` compare by value,
 * and a [JsObject] has no `equals` of its own, so it compares by identity —
 * which is what JavaScript does with an object key.
 */
public class JsMap {

    private val entries = LinkedHashMap<Any?, Any?>()

    public val size: Double get() = entries.size.toDouble()

    /** An absent key is `undefined`, i.e. `null` here. */
    public fun get(key: Any?): Any? = entries[key]

    /** Returns the map, as JavaScript does, so `m.set(a, 1).set(b, 2)` chains. */
    public fun set(key: Any?, value: Any?): JsMap {
        entries[key] = value
        return this
    }

    public fun has(key: Any?): Boolean = entries.containsKey(key)

    public fun delete(key: Any?): Boolean {
        val had = entries.containsKey(key)
        entries.remove(key)
        return had
    }

    public fun clear() {
        entries.clear()
    }

    /** An ARRAY where JavaScript answers an iterator — see the runtime's note. */
    public fun keys(): JsArray = JsArray(entries.keys.toList())

    public fun values(): JsArray = JsArray(entries.values.toList())

    override fun toString(): String = "[object Map]"

}

/**
 * A JavaScript `Date`, over `java.time`.
 *
 * OPEN, because a TypeScript program may extend it — `class TomlDate extends
 * Date` is how the `smol-toml` library models a TOML datetime — and a JVM class
 * a generated one extends has to be extensible.
 *
 * An INVALID date is `NaN` time rather than an exception, which is what
 * JavaScript does: `new Date('nonsense').getTime()` is `NaN`, and every
 * consumer tests for it with `isNaN`.
 */
public open class JsDate {

    private val millis: Double

    public constructor(value: Any?) {
        millis = when (value) {
            is Double -> value
            is String -> parseIso(value)
            is JsDate -> value.millis
            null -> Double.NaN
            else -> jsToNumber(value)
        }
    }

    public constructor() : this(System.currentTimeMillis().toDouble())

    public fun getTime(): Double = millis

    public fun valueOf(): Double = millis

    /** `toISOString`, which JavaScript spells with milliseconds and a `Z`. */
    public open fun toISOString(): String {
        if (millis.isNaN()) throw JsTypeError("Invalid time value")
        val instant = java.time.Instant.ofEpochMilli(millis.toLong())
        return java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(instant)
    }

    override fun toString(): String = if (millis.isNaN()) "Invalid Date" else toISOString()

    private companion object {
        /**
         * The subset of date strings JavaScript's `Date` accepts that this
         * runtime does: ISO-8601, with or without a time, with or without an
         * offset. Anything else is `NaN`, which is a value a program can test —
         * where an exception would be one it cannot.
         */
        fun parseIso(text: String): Double = try {
            when {
                text.isEmpty() -> Double.NaN
                text.endsWith("Z") || Regex("[+-]\\d\\d:?\\d\\d$").containsMatchIn(text) ->
                    java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli().toDouble()
                text.contains('T') ->
                    java.time.LocalDateTime.parse(text)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli().toDouble()
                else ->
                    java.time.LocalDate.parse(text)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                        .toEpochMilli().toDouble()
            }
        } catch (_: java.time.format.DateTimeParseException) {
            Double.NaN
        }
    }

}

/** A JavaScript `Set`, insertion-ordered as the specification requires. */
public class JsSet {

    private val elements = LinkedHashSet<Any?>()

    public val size: Double get() = elements.size.toDouble()

    public fun add(value: Any?): JsSet {
        elements.add(value)
        return this
    }

    public fun has(value: Any?): Boolean = elements.contains(value)

    public fun delete(value: Any?): Boolean = elements.remove(value)

    public fun clear() {
        elements.clear()
    }

    public fun values(): JsArray = JsArray(elements.toList())

    override fun toString(): String = "[object Set]"

}

/**
 * Calling a value whose static type did not say how many parameters it takes.
 *
 * The dynamic arm of the call lowering, and it exists because a UNION of
 * function types has no single erasure: mitt's own `Handler | WildcardHandler`
 * is `Function1` on one side and `Function2` on the other, and JavaScript calls
 * either with whatever arguments the site supplies. So the adaptation JavaScript
 * performs is performed here — missing arguments are `undefined`, extra ones are
 * dropped — rather than refused, because refusing it would refuse the shape at
 * the centre of most event-emitter and callback code.
 */
/*
 * ARITY-SPECIALIZED entry points, and why they exist.
 *
 * [jsCall] is the general form and it costs two things at every call site: the
 * `vararg` allocates an `Object[]`, and the `when` walks an `instanceof` chain
 * whose first arm is rarely the answer. Measured on the `mitt` benchmark
 * (2026-08-21), `jsCall` alone was ~60% of the JVM arm's leaf samples with
 * `TypeIntrinsics.isFunctionOfArity` behind it — in the arm that WINS against
 * Node.
 *
 * These take their arguments positionally, so there is no array, and each tests
 * the arity it was called with FIRST, so the common case is one type test. What
 * they must NOT do is assume that arity: JavaScript pads a missing argument with
 * `undefined` and drops a surplus one, and that adaptivity is load-bearing here
 * rather than defensive — `mitt` registers a one-parameter wildcard handler and
 * `emit` calls it with two arguments. Dropping the fallbacks would compile and
 * then fail on the library this backend exists to run.
 */

@Suppress("UNCHECKED_CAST")
public fun jsCall0(callee: Any?): Any? = when (callee) {
    is Function0<*> -> callee()
    is Function1<*, *> -> (callee as Function1<Any?, Any?>)(null)
    is Function2<*, *, *> -> (callee as Function2<Any?, Any?, Any?>)(null, null)
    is Function3<*, *, *, *> -> (callee as Function3<Any?, Any?, Any?, Any?>)(null, null, null)
    null -> throw JsTypeError("undefined is not a function")
    else -> throw JsTypeError("${jsToString(callee)} is not a function")
}

@Suppress("UNCHECKED_CAST")
public fun jsCall1(callee: Any?, a0: Any?): Any? = when (callee) {
    is Function1<*, *> -> (callee as Function1<Any?, Any?>)(a0)
    is Function0<*> -> callee()
    is Function2<*, *, *> -> (callee as Function2<Any?, Any?, Any?>)(a0, null)
    is Function3<*, *, *, *> -> (callee as Function3<Any?, Any?, Any?, Any?>)(a0, null, null)
    null -> throw JsTypeError("undefined is not a function")
    else -> throw JsTypeError("${jsToString(callee)} is not a function")
}

@Suppress("UNCHECKED_CAST")
public fun jsCall2(callee: Any?, a0: Any?, a1: Any?): Any? = when (callee) {
    is Function2<*, *, *> -> (callee as Function2<Any?, Any?, Any?>)(a0, a1)
    is Function1<*, *> -> (callee as Function1<Any?, Any?>)(a0)
    is Function0<*> -> callee()
    is Function3<*, *, *, *> -> (callee as Function3<Any?, Any?, Any?, Any?>)(a0, a1, null)
    null -> throw JsTypeError("undefined is not a function")
    else -> throw JsTypeError("${jsToString(callee)} is not a function")
}

@Suppress("UNCHECKED_CAST")
public fun jsCall3(callee: Any?, a0: Any?, a1: Any?, a2: Any?): Any? = when (callee) {
    is Function3<*, *, *, *> -> (callee as Function3<Any?, Any?, Any?, Any?>)(a0, a1, a2)
    is Function2<*, *, *> -> (callee as Function2<Any?, Any?, Any?>)(a0, a1)
    is Function1<*, *> -> (callee as Function1<Any?, Any?>)(a0)
    is Function0<*> -> callee()
    null -> throw JsTypeError("undefined is not a function")
    else -> throw JsTypeError("${jsToString(callee)} is not a function")
}

public fun jsCall(callee: Any?, vararg arguments: Any?): Any? {
    fun argument(index: Int): Any? = if (index < arguments.size) arguments[index] else null
    return when (callee) {
        is Function0<*> -> callee()
        is Function1<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            (callee as Function1<Any?, Any?>)(argument(0))
        }
        is Function2<*, *, *> -> {
            @Suppress("UNCHECKED_CAST")
            (callee as Function2<Any?, Any?, Any?>)(argument(0), argument(1))
        }
        is Function3<*, *, *, *> -> {
            @Suppress("UNCHECKED_CAST")
            (callee as Function3<Any?, Any?, Any?, Any?>)(argument(0), argument(1), argument(2))
        }
        // Arities 4 and 5 are here because their ABSENCE was a runtime
        // `JsTypeError` on an ordinary program: a four-parameter method of an
        // object literal is dynamic like any other bag member, and the chain
        // that stopped at three reported it as "not a function". Nothing above
        // five is claimed — a call that needs it still refuses loudly, which is
        // the correct end of `docs/kir-lowering.md` §8.
        is Function4<*, *, *, *, *> -> {
            @Suppress("UNCHECKED_CAST")
            (callee as Function4<Any?, Any?, Any?, Any?, Any?>)(
                argument(0), argument(1), argument(2), argument(3)
            )
        }
        is Function5<*, *, *, *, *, *> -> {
            @Suppress("UNCHECKED_CAST")
            (callee as Function5<Any?, Any?, Any?, Any?, Any?, Any?>)(
                argument(0), argument(1), argument(2), argument(3), argument(4)
            )
        }
        null -> throw JsTypeError("undefined is not a function")
        else -> throw JsTypeError("${jsToString(callee)} is not a function")
    }
}

/**
 * A JavaScript `Error`, which a program may extend.
 *
 * A `RuntimeException` so that `throw new TomlError(…)` needs no wrapper and an
 * ordinary JVM `catch` sees it — the two languages agree here, and matching
 * them costs nothing. OPEN because extending `Error` is how every library
 * declares its own error type.
 *
 * The second constructor parameter is JavaScript's `ErrorOptions` bag, whose
 * only standard member is `cause`; it is accepted and ignored rather than
 * refused, because a call site passing one is otherwise ordinary.
 */
public open class JsError : RuntimeException {

    public var name: String = "Error"

    public constructor(message: Any?) : super(jsToString(message))

    public constructor(message: Any?, options: Any?) : super(jsToString(message))

    public constructor() : super("")

    override fun toString(): String = "$name: $message"

}

/** What a JavaScript engine throws where a value is used as something it is not. */
public class JsTypeError(message: String) : RuntimeException(message)

/**
 * The carrier for a thrown VALUE that is not a `Throwable`.
 *
 * JavaScript throws anything — a string, a number, an object literal — and the
 * JVM throws only `Throwable`s. Wrapping preserves the value exactly, so a
 * program that throws a string and catches it sees the string; throwing some
 * `Error` subclass instead would be more idiomatic on the JVM and would lose
 * precisely that.
 */
public class JsThrown(public val value: Any?) : RuntimeException(jsToString(value))

/** `throw e` for any value. */
public fun jsThrow(value: Any?): Nothing =
    if (value is Throwable) throw value else throw JsThrown(value)

/** The value a caught exception carries — the inverse of [jsThrow]. */
public fun jsCaught(thrown: Throwable): Any? =
    if (thrown is JsThrown) thrown.value else thrown

/**
 * `==` — ECMAScript ABSTRACT equality, over the values this backend produces.
 *
 * Not `===` with a shrug: `1 == "1"` is true, `null == undefined` is true, and
 * `null == 0` is false. The one rule this cannot honour is the null/undefined
 * distinction, which design §3.1 collapsed onto the JVM's single `null` — so
 * `x == null` answers what BOTH spellings answer, which is what the vast
 * majority of `== null` checks in real code mean anyway.
 */
public fun jsLooseEquals(left: Any?, right: Any?): Boolean {
    val leftNullish = left == null || left === Undefined
    val rightNullish = right == null || right === Undefined
    if (leftNullish || rightNullish) return leftNullish && rightNullish
    return when {
        left is Double && right is Double -> left == right
        left is String && right is String -> left == right
        left is Boolean && right is Boolean -> left == right
        // A boolean compares as its NUMBER, on either side, before anything else.
        left is Boolean -> jsLooseEquals(jsToNumber(left), right)
        right is Boolean -> jsLooseEquals(left, jsToNumber(right))
        left is Double && right is String -> left == jsToNumber(right)
        left is String && right is Double -> jsToNumber(left) == right
        // An object compares by identity against another object, and by its
        // primitive value against a primitive.
        left is JsArray || left is JsObject || left is JsMap || left is JsSet ->
            if (right is String || right is Double) jsLooseEquals(jsToString(left), right)
            else left === right
        right is JsArray || right is JsObject || right is JsMap || right is JsSet ->
            jsLooseEquals(right, left)
        else -> left == right
    }
}

/** `&`, `|`, `^`, `<<`, `>>` — each `ToInt32` on both sides, then back. */
public fun jsBitAnd(left: Any?, right: Any?): Double =
    (toInt32(left) and toInt32(right)).toDouble()

public fun jsBitOr(left: Any?, right: Any?): Double =
    (toInt32(left) or toInt32(right)).toDouble()

public fun jsBitXor(left: Any?, right: Any?): Double =
    (toInt32(left) xor toInt32(right)).toDouble()

public fun jsBitNot(value: Any?): Double = toInt32(value).inv().toDouble()

public fun jsShiftLeft(left: Any?, right: Any?): Double =
    (toInt32(left) shl (toUint32(right) % 32u).toInt()).toDouble()

public fun jsShiftRight(left: Any?, right: Any?): Double =
    (toInt32(left) shr (toUint32(right) % 32u).toInt()).toDouble()

private fun toInt32(value: Any?): Int {
    val number = jsToNumber(value)
    if (number.isNaN() || number.isInfinite()) return 0
    return number.toLong().toInt()
}

/**
 * `>>>` — the one operator whose whole meaning is a coercion.
 *
 * `ToUint32` on both operands, then an unsigned shift, then back to a `Double`.
 * `a.indexOf(x) >>> 0` is the idiom it exists for: `-1 >>> 0` is 4294967295,
 * which is how JavaScript code turns "not found" into "past the end".
 */
public fun jsUnsignedShiftRight(left: Any?, right: Any?): Double {
    val value = toUint32(left)
    val shift = (toUint32(right) % 32u).toInt()
    return (value shr shift).toDouble()
}

private fun toUint32(value: Any?): UInt {
    val number = jsToNumber(value)
    if (number.isNaN() || number.isInfinite()) return 0u
    return number.toLong().toUInt()
}

/**
 * A JavaScript regular expression, over `java.util.regex`.
 *
 * The two engines agree on the syntax this compiler has met — character
 * classes, groups, alternation, the usual escapes — and they disagree on things
 * a program notices only when it uses them (named groups' syntax, lookbehind
 * semantics, `\d` under Unicode). The pattern is therefore passed through
 * UNTRANSLATED and a pattern Java rejects fails loudly at construction, rather
 * than being silently rewritten into something that matches almost the same
 * strings.
 *
 * `lastIndex` is deliberately absent: it is only observable through `/g/y` with
 * `exec` in a loop, and a `JsRegExp` that silently ignored it would give a
 * program that shape an infinite loop rather than a wrong answer.
 */
public class JsRegExp(public val source: String, public val flags: String) {

    private val pattern: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        source,
        (if ('i' in flags) java.util.regex.Pattern.CASE_INSENSITIVE else 0) or
            (if ('m' in flags) java.util.regex.Pattern.MULTILINE else 0) or
            (if ('s' in flags) java.util.regex.Pattern.DOTALL else 0)
    )

    public val global: Boolean = 'g' in flags

    public fun test(input: String): Boolean = pattern.matcher(input).find()

    /**
     * `RegExp.prototype.exec`: the whole match then each group, or null.
     *
     * An unmatched optional group is `undefined` in JavaScript, i.e. `null`
     * here — which is what a program testing `match[2]` is asking about.
     */
    public fun exec(input: String): JsArray? {
        val matcher = pattern.matcher(input)
        if (!matcher.find()) return null
        val groups = ArrayList<Any?>(matcher.groupCount() + 1)
        for (index in 0..matcher.groupCount()) groups.add(matcher.group(index))
        return JsArray(groups)
    }

    internal fun matcherFor(input: String): java.util.regex.Matcher = pattern.matcher(input)

    override fun toString(): String = "/$source/$flags"

}

/** A regular-expression literal: `/pattern/flags`. */
public fun jsRegExp(source: String, flags: String): JsRegExp = JsRegExp(source, flags)

/** `String.prototype.match` with a regular expression — `exec`, or null. */
public fun jsStrMatch(value: String, expression: JsRegExp): JsArray? = expression.exec(value)

/** `String.prototype.replace` with a regular expression. */
public fun jsStrReplace(value: String, expression: JsRegExp, replacement: String): String {
    // `Matcher.quoteReplacement`, because JavaScript's replacement string has
    // its own escape language (`$1`, `$&`) and Java's is a DIFFERENT one — so a
    // replacement containing `$` or `\` would otherwise mean something else.
    val quoted = java.util.regex.Matcher.quoteReplacement(replacement)
    val matcher = expression.matcherFor(value)
    return if (expression.global) matcher.replaceAll(quoted) else matcher.replaceFirst(quoted)
}

/** `String.prototype.split` with a regular expression. */
public fun jsStrSplit(value: String, expression: JsRegExp): JsArray =
    JsArray(value.split(Regex(expression.source)).toList())

// ---------------------------------------------------------------------------
// String members
//
// A TypeScript `string` erases to a Kotlin `String`, and it is tempting to let
// the generated code call Kotlin's own members. It must not: the two disagree
// exactly where a program notices. `length` is a NUMBER in JavaScript and an
// `Int` in Kotlin; `indexOf` answers -1 as a number; `charAt` out of range is
// the empty string rather than an exception; `slice` accepts negative indices
// counting from the end and clamps rather than throwing. So each member is a
// runtime function taking the receiver, and the JavaScript semantics live here
// where they can be read.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Number members. Kotlin's own are NOT usable for the same reason the string
// ones are not: `Double.toString()` prints `6.0` where JavaScript prints `6`,
// and a program that builds a line number out of one notices immediately.
// ---------------------------------------------------------------------------

public fun jsNumToString(value: Double): String = jsNumberToString(value)

/** `Number.prototype.toString(radix)`, for the integral values a radix means. */
public fun jsNumToString(value: Double, radix: Double): String {
    val base = radix.toInt()
    if (base == 10) return jsNumberToString(value)
    if (value.isNaN() || value.isInfinite()) return jsNumberToString(value)
    return value.toLong().toString(base)
}

public fun jsNumToFixed(value: Double, digits: Double): String =
    if (value.isNaN() || value.isInfinite()) jsNumberToString(value)
    else String.format("%.${digits.toInt()}f", value)

public fun jsStrLength(value: String): Double = value.length.toDouble()

public fun jsStrCharAt(value: String, index: Double): String {
    val i = index.toInt()
    return if (i < 0 || i >= value.length) "" else value[i].toString()
}

public fun jsStrIndexOf(value: String, search: String): Double =
    value.indexOf(search).toDouble()

/** `indexOf` from a starting position, which clamps rather than throwing. */
public fun jsStrIndexOf(value: String, search: String, from: Double): Double =
    value.indexOf(search, from.toInt().coerceIn(0, value.length)).toDouble()

public fun jsStrLastIndexOf(value: String, search: String): Double =
    value.lastIndexOf(search).toDouble()

public fun jsStrIncludes(value: String, search: String): Boolean = value.contains(search)

public fun jsStrStartsWith(value: String, search: String): Boolean = value.startsWith(search)

public fun jsStrEndsWith(value: String, search: String): Boolean = value.endsWith(search)

/** `String.prototype.slice`: negative indices count from the end, and it clamps. */
public fun jsStrSlice(value: String, start: Double): String =
    jsStrSlice(value, start, value.length.toDouble())

public fun jsStrSlice(value: String, start: Double, end: Double): String {
    val from = sliceIndex(start, value.length)
    val to = sliceIndex(end, value.length)
    return if (from >= to) "" else value.substring(from, to)
}

/** `String.prototype.substring`: clamps AND swaps a reversed pair, unlike slice. */
public fun jsStrSubstring(value: String, start: Double): String =
    jsStrSubstring(value, start, value.length.toDouble())

public fun jsStrSubstring(value: String, start: Double, end: Double): String {
    val from = start.toInt().coerceIn(0, value.length)
    val to = end.toInt().coerceIn(0, value.length)
    return value.substring(minOf(from, to), maxOf(from, to))
}

public fun jsStrToUpperCase(value: String): String = value.uppercase()

public fun jsStrToLowerCase(value: String): String = value.lowercase()

public fun jsStrTrim(value: String): String = value.trim()

public fun jsStrTrimStart(value: String): String = value.trimStart()

public fun jsStrTrimEnd(value: String): String = value.trimEnd()

public fun jsStrPadEnd(value: String, length: Double, filler: String): String {
    if (value.length >= length.toInt()) return value
    val out = StringBuilder(value)
    while (out.length < length.toInt() && filler.isNotEmpty()) out.append(filler)
    out.setLength(maxOf(length.toInt(), value.length))
    return out.toString()
}

public fun jsStrPadStart(value: String, length: Double, filler: String): String =
    if (value.length >= length.toInt()) value
    else buildString {
        while (this.length < length.toInt() - value.length) append(filler)
        setLength((length.toInt() - value.length).coerceAtLeast(0))
        append(value)
    }

public fun jsStrRepeat(value: String, count: Double): String = value.repeat(count.toInt())

/** `String.prototype.replace` with a STRING pattern: the FIRST match only. */
public fun jsStrReplace(value: String, search: String, replacement: String): String =
    value.replaceFirst(search, replacement)

public fun jsStrReplaceAll(value: String, search: String, replacement: String): String =
    value.replace(search, replacement)

public fun jsStrSplit(value: String, separator: String): JsArray =
    JsArray(if (separator.isEmpty()) value.map { it.toString() } else value.split(separator))

public fun jsStrConcat(value: String, other: String): String = value + other

private fun sliceIndex(raw: Double, length: Int): Int {
    val index = raw.toInt()
    return if (index < 0) (length + index).coerceAtLeast(0) else index.coerceAtMost(length)
}

// ---------------------------------------------------------------------------
// The global objects: `Math`, `Number`, `Object`, `JSON`, and the free
// functions. Each is a runtime function taking its arguments positionally, and
// each is reached through the intrinsic table by the checker's own rendering of
// the receiver plus the member name — so a local variable called `Math` cannot
// be mistaken for the global one.
// ---------------------------------------------------------------------------

public fun jsMathFloor(value: Any?): Double = floor(jsToNumber(value))

public fun jsMathCeil(value: Any?): Double = ceil(jsToNumber(value))

public fun jsMathRound(value: Any?): Double {
    // JavaScript rounds HALF UP, including for negatives (`Math.round(-0.5)` is
    // `-0`), where Kotlin's `round` rounds half away from zero.
    val number = jsToNumber(value)
    if (number.isNaN() || number.isInfinite()) return number
    return floor(number + 0.5)
}

public fun jsMathTrunc(value: Any?): Double {
    val number = jsToNumber(value)
    return if (number < 0) ceil(number) else floor(number)
}

public fun jsMathAbs(value: Any?): Double = abs(jsToNumber(value))

public fun jsMathSign(value: Any?): Double {
    val number = jsToNumber(value)
    return when {
        number.isNaN() -> Double.NaN
        number > 0 -> 1.0
        number < 0 -> -1.0
        else -> number
    }
}

public fun jsMathSqrt(value: Any?): Double = kotlin.math.sqrt(jsToNumber(value))

public fun jsMathLog(value: Any?): Double = kotlin.math.ln(jsToNumber(value))

public fun jsMathLog10(value: Any?): Double = kotlin.math.log10(jsToNumber(value))

public fun jsMathLog2(value: Any?): Double = kotlin.math.log2(jsToNumber(value))

public fun jsMathPow(base: Any?, exponent: Any?): Double =
    jsToNumber(base).pow(jsToNumber(exponent))

public fun jsMathMin(left: Any?, right: Any?): Double =
    minOf(jsToNumber(left), jsToNumber(right))

public fun jsMathMax(left: Any?, right: Any?): Double =
    maxOf(jsToNumber(left), jsToNumber(right))

/** `Number.isInteger` — false for anything that is not a number at all. */
public fun jsNumberIsInteger(value: Any?): Boolean =
    value is Double && !value.isNaN() && !value.isInfinite() && value == floor(value)

/** `Number.isSafeInteger`: an integer within ±(2^53 − 1). */
public fun jsNumberIsSafeInteger(value: Any?): Boolean =
    jsNumberIsInteger(value) && abs(value as Double) <= 9007199254740991.0

public fun jsNumberIsFinite(value: Any?): Boolean =
    value is Double && !value.isNaN() && !value.isInfinite()

public fun jsNumberIsNaN(value: Any?): Boolean = value is Double && value.isNaN()

/** The GLOBAL `isNaN`, which COERCES first — unlike `Number.isNaN`. */
public fun jsIsNaN(value: Any?): Boolean = jsToNumber(value).isNaN()

public fun jsIsFinite(value: Any?): Boolean {
    val number = jsToNumber(value)
    return !number.isNaN() && !number.isInfinite()
}

/** `parseInt` with no radix — decimal, as JavaScript's default is. */
public fun jsParseInt(value: Any?): Double = jsParseInt(value, 10.0)

/** `parseInt` with an explicit radix. */
public fun jsParseInt(value: Any?, radix: Any?): Double {
    val text = jsToString(value).trim()
    val base = jsToNumber(radix).let { if (it.isNaN() || it == 0.0) 10 else it.toInt() }
    var end = 0
    val start = if (text.startsWith("+") || text.startsWith("-")) 1 else 0
    var index = start
    while (index < text.length && Character.digit(text[index], base) >= 0) {
        index++
        end = index
    }
    if (end <= start) return Double.NaN
    return text.substring(0, end).toLong(base).toDouble()
}

public fun jsParseFloat(value: Any?): Double {
    val text = jsToString(value).trim()
    val match = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").find(text)
        ?: return Double.NaN
    return match.value.toDouble()
}

/** `String(x)` and `Number(x)` used as CONVERSIONS rather than constructors. */
public fun jsStringOf(value: Any?): String = jsToString(value)

public fun jsNumberOf(value: Any?): Double = jsToNumber(value)

public fun jsStrFromCharCode(code: Any?): String = jsToNumber(code).toInt().toChar().toString()

public fun jsStrFromCodePoint(code: Any?): String {
    val builder = StringBuilder()
    builder.appendCodePoint(jsToNumber(code).toInt())
    return builder.toString()
}

public fun jsStrCharCodeAt(value: String, index: Double): Double {
    val i = index.toInt()
    return if (i < 0 || i >= value.length) Double.NaN else value[i].code.toDouble()
}

public fun jsStrCodePointAt(value: String, index: Double): Any? {
    val i = index.toInt()
    return if (i < 0 || i >= value.length) null else value.codePointAt(i).toDouble()
}

/** `Object.keys` — insertion order, as JavaScript specifies for string keys. */
public fun jsObjectKeys(value: Any?): JsArray = when (value) {
    is JsObject -> value.keys()
    is JsArray -> JsArray((0 until value.length.toInt()).map { it.toString() })
    else -> JsArray()
}

/**
 * `Object.defineProperty`, over a property BAG.
 *
 * The attributes are accepted and ignored: this runtime's objects have no
 * property descriptors, so every property is already enumerable, configurable
 * and writable. What the call still does — and what the one real use of it in
 * library code needs — is CREATE the property, which is how a program defends
 * a `__proto__` key against a prototype chain this runtime does not have.
 */
public fun jsObjectDefineProperty(target: Any?, name: Any?, descriptor: Any?): Any? {
    val key = jsToString(name)
    val value = (descriptor as? JsObject)?.get("value")
    when (target) {
        is JsObject -> if (value != null || !target.has(key)) target.set(key, value)
        else -> jsSet(target, key, value)
    }
    return target
}

public fun jsObjectHasOwn(value: Any?, name: Any?): Boolean = when (value) {
    is JsObject -> value.has(jsToString(name))
    is JsArray -> jsToNumber(name).let { it >= 0 && it < value.length }
    else -> false
}

/** `JSON.stringify`, over the values this backend produces. */
public fun jsJsonStringify(value: Any?): Any? = when (value) {
    // A function or `undefined` STRINGIFIES TO NOTHING at the top level, which
    // JavaScript reports as `undefined` rather than as the string "undefined".
    null -> null
    else -> jsonText(value)
}

private fun jsonText(value: Any?): String = when (value) {
    null -> "null"
    Undefined -> "null"
    is Boolean -> if (value) "true" else "false"
    is Double -> if (value.isNaN() || value.isInfinite()) "null" else jsNumberToString(value)
    is String -> jsonQuote(value)
    is JsArray -> (0 until value.length.toInt())
        .joinToString(",", "[", "]") { jsonText(value[it.toDouble()]) }
    is JsObject -> value.keys().let { keys ->
        (0 until keys.length.toInt()).joinToString(",", "{", "}") { index ->
            val key = jsToString(keys[index.toDouble()])
            jsonQuote(key) + ":" + jsonText(value.get(key))
        }
    }
    else -> jsonQuote(jsToString(value))
}

private fun jsonQuote(value: String): String {
    val out = StringBuilder("\"")
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
        }
    }
    return out.append('"').toString()
}

// ---------------------------------------------------------------------------
// The DYNAMIC member operations.
//
// `docs/kir-structural-typing.md` §7 measured `any`-typed sources as the
// largest dynamic population in real TypeScript — an order of magnitude larger
// than the nominal closure — and this is their mechanism. A member read, write
// or call whose receiver's erased type is `Any?` is dispatched HERE, on what
// the value turns out to be, which is what a JavaScript engine does with it.
//
// The cost is a name lookup per operation, and it is paid only where the
// checker itself knew nothing. Everything the checker DID type reaches its
// member directly.
// ---------------------------------------------------------------------------

/** A property READ on a receiver whose type was not known. */
public fun jsGet(receiver: Any?, name: String): Any? = when (receiver) {
    null -> throw JsTypeError("cannot read '$name' of null")
    is JsObject -> receiver.get(name)
    is JsArray -> if (name == "length") receiver.length else jsMemberOfArray(receiver, name)
    is String -> if (name == "length") jsStrLength(receiver) else null
    is JsMap -> if (name == "size") receiver.size else null
    is JsSet -> if (name == "size") receiver.size else null
    else -> reflectiveGet(receiver, name)
}

/**
 * `a[i]` where the receiver's type was not known.
 *
 * The index decides nothing on its own: JavaScript's `a[0]` and `o["k"]` are
 * the same syntax over different containers, so the RECEIVER selects the
 * meaning and the index is coerced to what that container is keyed by.
 */
public fun jsIndexGet(receiver: Any?, index: Any?): Any? = when (receiver) {
    null -> throw JsTypeError("cannot read an index of null")
    is JsArray -> receiver[jsToNumber(index)]
    is JsObject -> receiver.get(jsToString(index))
    is JsMap -> receiver.get(index)
    is String -> jsStrCharAt(receiver, jsToNumber(index)).ifEmpty { null }
    else -> jsGet(receiver, jsToString(index))
}

public fun jsIndexSet(receiver: Any?, index: Any?, value: Any?) {
    when (receiver) {
        null -> throw JsTypeError("cannot write an index of null")
        is JsArray -> receiver[jsToNumber(index)] = value
        is JsObject -> receiver.set(jsToString(index), value)
        is JsMap -> receiver.set(index, value)
        else -> jsSet(receiver, jsToString(index), value)
    }
}

/** A property WRITE on a receiver whose type was not known. */
public fun jsSet(receiver: Any?, name: String, value: Any?) {
    when (receiver) {
        null -> throw JsTypeError("cannot set '$name' of null")
        is JsObject -> receiver.set(name, value)
        else -> reflectiveSet(receiver, name, value)
    }
}

/**
 * A member CALL on a receiver whose type was not known.
 *
 * Ordered by what the receiver IS, because that is the only thing there is to
 * go on: a string's members are the runtime's string functions, a bag's member
 * is a PROPERTY holding a function (so it is read and then called), and a
 * generated class's is a JVM method found by name and arity.
 */
public fun jsInvoke(receiver: Any?, name: String, vararg arguments: Any?): Any? =
    when (receiver) {
        null -> throw JsTypeError("cannot call '$name' of null")
        is String -> jsInvokeOnString(receiver, name, arguments)
        is Double -> jsInvokeOnNumber(receiver, name, arguments)
        is Boolean -> if (name == "toString") jsToString(receiver)
        else throw JsTypeError("'$name' is not a Boolean member this runtime provides")
        is JsArray -> jsInvokeOnArray(receiver, name, arguments)
        is JsObject -> jsCall(receiver.get(name), *arguments)
        else -> reflectiveInvoke(receiver, name, arguments)
    }

private fun jsInvokeOnNumber(receiver: Double, name: String, arguments: Array<out Any?>): Any? =
    when (name) {
        "toString" -> if (arguments.isEmpty()) jsNumToString(receiver)
        else jsNumToString(receiver, jsToNumber(arguments[0]))
        "toFixed" -> jsNumToFixed(receiver, jsToNumber(arguments.getOrNull(0)))
        "valueOf" -> receiver
        else -> throw JsTypeError("'$name' is not a Number member this runtime provides")
    }

private fun jsInvokeOnString(receiver: String, name: String, arguments: Array<out Any?>): Any? {
    fun argument(index: Int): Any? = arguments.getOrNull(index)
    return when (name) {
        "charAt" -> jsStrCharAt(receiver, jsToNumber(argument(0)))
        "charCodeAt" -> jsStrCharCodeAt(receiver, jsToNumber(argument(0)))
        "codePointAt" -> jsStrCodePointAt(receiver, jsToNumber(argument(0)))
        "indexOf" -> if (arguments.size < 2) jsStrIndexOf(receiver, jsToString(argument(0)))
        else jsStrIndexOf(receiver, jsToString(argument(0)), jsToNumber(argument(1)))
        "lastIndexOf" -> jsStrLastIndexOf(receiver, jsToString(argument(0)))
        "includes" -> jsStrIncludes(receiver, jsToString(argument(0)))
        "startsWith" -> jsStrStartsWith(receiver, jsToString(argument(0)))
        "endsWith" -> jsStrEndsWith(receiver, jsToString(argument(0)))
        "toUpperCase" -> jsStrToUpperCase(receiver)
        "toLowerCase" -> jsStrToLowerCase(receiver)
        "trim" -> jsStrTrim(receiver)
        "trimStart" -> jsStrTrimStart(receiver)
        "trimEnd" -> jsStrTrimEnd(receiver)
        "padStart" -> jsStrPadStart(receiver, jsToNumber(argument(0)), jsToString(argument(1)))
        "padEnd" -> jsStrPadEnd(receiver, jsToNumber(argument(0)), jsToString(argument(1)))
        "repeat" -> jsStrRepeat(receiver, jsToNumber(argument(0)))
        "concat" -> jsStrConcat(receiver, jsToString(argument(0)))
        "slice" -> if (arguments.size < 2) jsStrSlice(receiver, jsToNumber(argument(0)))
        else jsStrSlice(receiver, jsToNumber(argument(0)), jsToNumber(argument(1)))
        "substring" -> if (arguments.size < 2) jsStrSubstring(receiver, jsToNumber(argument(0)))
        else jsStrSubstring(receiver, jsToNumber(argument(0)), jsToNumber(argument(1)))
        "split" -> when (val separator = argument(0)) {
            is JsRegExp -> jsStrSplit(receiver, separator)
            else -> jsStrSplit(receiver, jsToString(separator))
        }
        "replace" -> when (val pattern = argument(0)) {
            is JsRegExp -> jsStrReplace(receiver, pattern, jsToString(argument(1)))
            else -> jsStrReplace(receiver, jsToString(pattern), jsToString(argument(1)))
        }
        "replaceAll" -> jsStrReplaceAll(receiver, jsToString(argument(0)), jsToString(argument(1)))
        "match" -> (argument(0) as? JsRegExp)?.let { jsStrMatch(receiver, it) }
            ?: throw JsTypeError("String.match needs a regular expression")
        else -> throw JsTypeError("'$name' is not a String member this runtime provides")
    }
}

private fun jsInvokeOnArray(receiver: JsArray, name: String, arguments: Array<out Any?>): Any? {
    fun argument(index: Int): Any? = arguments.getOrNull(index)
    @Suppress("UNCHECKED_CAST")
    fun callback(index: Int): (Any?) -> Any? = { value -> jsCall(argument(index), value) }
    return when (name) {
        "push" -> receiver.push(argument(0))
        "pop" -> receiver.pop()
        "shift" -> receiver.shift()
        "unshift" -> receiver.unshift(argument(0))
        "indexOf" -> receiver.indexOf(argument(0))
        "includes" -> receiver.includes(argument(0))
        "slice" -> receiver.slice()
        "concat" -> receiver.concat(argument(0) as JsArray)
        "join" -> receiver.join(jsToString(argument(0)))
        "splice" -> receiver.splice(jsToNumber(argument(0)), jsToNumber(argument(1)))
        "forEach" -> receiver.forEach(callback(0))
        "map" -> receiver.map(callback(0))
        "filter" -> receiver.filter(callback(0))
        else -> throw JsTypeError("'$name' is not an Array member this runtime provides")
    }
}

private fun jsMemberOfArray(receiver: JsArray, name: String): Any? =
    // An index written as a property (`a["0"]`) is still an index.
    name.toDoubleOrNull()?.let { receiver[it] }

private fun reflectiveGet(receiver: Any, name: String): Any? {
    receiver.javaClass.fields.firstOrNull { it.name == name }?.let { return it.get(receiver) }
    receiver.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.let { return it.invoke(receiver) }
    throw JsTypeError("'$name' is not a member of ${receiver.javaClass.simpleName}")
}

private fun reflectiveSet(receiver: Any, name: String, value: Any?) {
    receiver.javaClass.fields.firstOrNull { it.name == name }?.let {
        it.set(receiver, value)
        return
    }
    throw JsTypeError("'$name' is not a settable member of ${receiver.javaClass.simpleName}")
}

private fun reflectiveInvoke(receiver: Any, name: String, arguments: Array<out Any?>): Any? {
    val method = receiver.javaClass.methods.firstOrNull {
        it.name == name && it.parameterCount == arguments.size
    } ?: throw JsTypeError("'$name' is not a member of ${receiver.javaClass.simpleName}")
    return method.invoke(receiver, *arguments)
}

/** `console.log`: space-separated `ToString` of every argument, then a newline. */
public fun consoleLog(vararg values: Any?) {
    println(values.joinToString(" ") { jsToString(it) })
}
