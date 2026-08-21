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
import kotlin.math.floor

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

/** ECMAScript `ToString`, for the operand positions that coerce. */
public fun jsToString(value: Any?): String = when (value) {
    null -> "null"
    Undefined -> "undefined"
    is Double -> jsNumberToString(value)
    is String -> value
    is Boolean -> if (value) "true" else "false"
    is JsArray -> value.joinToJsString()
    is JsObject -> "[object Object]"
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
    is String -> value.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
    else -> Double.NaN
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
        null -> throw JsTypeError("undefined is not a function")
        else -> throw JsTypeError("${jsToString(callee)} is not a function")
    }
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

public fun jsStrLength(value: String): Double = value.length.toDouble()

public fun jsStrCharAt(value: String, index: Double): String {
    val i = index.toInt()
    return if (i < 0 || i >= value.length) "" else value[i].toString()
}

public fun jsStrIndexOf(value: String, search: String): Double =
    value.indexOf(search).toDouble()

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

/** `console.log`: space-separated `ToString` of every argument, then a newline. */
public fun consoleLog(vararg values: Any?) {
    println(values.joinToString(" ") { jsToString(it) })
}
