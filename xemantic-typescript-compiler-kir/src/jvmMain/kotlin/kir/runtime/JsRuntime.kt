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

/** `console.log`: space-separated `ToString` of every argument, then a newline. */
public fun consoleLog(vararg values: Any?) {
    println(values.joinToString(" ") { jsToString(it) })
}
