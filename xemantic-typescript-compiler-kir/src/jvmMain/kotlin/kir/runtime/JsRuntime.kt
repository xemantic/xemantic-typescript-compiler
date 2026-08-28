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
 * [jsToString] for a slot whose nullish member is `undefined` rather than `null`.
 *
 * §3.1 collapses the two onto the JVM's `null` in every erased slot, so
 * `string | undefined` and `string | null` are both `String?` and the runtime
 * alone cannot tell a value of one from a value of the other. The decision is
 * therefore made by the LOWERING, which still holds the TypeScript type, and
 * this is the arm it picks when that type admits `undefined` and not `null` —
 * the overwhelmingly common case, since `undefined` is what an optional
 * parameter, a missing argument and an unwritten property all hold.
 *
 * The same collapse and the same choice are already made by [jsTypeOf], for
 * the same reason.
 */
public fun jsToStringNullAsUndefined(value: Any?): String =
    if (value == null) "undefined" else jsToString(value)

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
public fun jsTruthy(value: Any?): Boolean = when {
    value == null -> false
    value is Boolean -> value
    value is Double -> value != 0.0 && !value.isNaN()
    value is String -> value.isNotEmpty()
    value === Undefined -> false
    else -> true
}

/**
 * Truthiness of a value the lowering already PROVED is a `number`.
 *
 * The arm [jsTruthy] would have reached, without the box and without the walk
 * that finds it: `if (!state)` in a scanner asks this of a local the lowering
 * typed, once per character. `-0` is falsy and `NaN` is falsy, which is what
 * the two tests are; they are spelled exactly as the general form spells them,
 * because two copies of one rule diverge silently.
 */
public fun jsTruthyNumber(value: Double): Boolean = value != 0.0 && !value.isNaN()

/** Truthiness of a value the lowering already proved is a `string` — see [jsTruthyNumber]. */
public fun jsTruthyString(value: String): Boolean = value.isNotEmpty()

/**
 * Truthiness of an OPTIONAL primitive — `number | undefined` and its siblings.
 *
 * A missing value is falsy, which is the arm [jsTruthy] reaches through `null`.
 * These exist for the same reason as the non-null forms and one step further
 * out: an optional parameter tested once per character (`!banNewLines` in a
 * scanner) is a `Boolean?` at the JVM level, so the general form walked its
 * whole chain to answer a null check.
 */
public fun jsTruthyNumberOrNull(value: Double?): Boolean =
    value != null && jsTruthyNumber(value)

/** See [jsTruthyNumberOrNull]. */
public fun jsTruthyStringOrNull(value: String?): Boolean = !value.isNullOrEmpty()

/** See [jsTruthyNumberOrNull]. */
public fun jsTruthyBooleanOrNull(value: Boolean?): Boolean = value != null && value

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
 * `===` and `==` where the lowering PROVED both operands are `number`.
 *
 * The general [jsStrictEquals] takes `Any?`, so a comparison between two
 * statically-numeric operands — `str.charCodeAt(p) === 0x20`, the shape every
 * hand-written scanner is made of — boxes BOTH sides and then walks an
 * `instanceof` chain to discover what the lowering already knew. These
 * specializations exist so it does not: `docs/kir-lowering.md` §5 already
 * decides `+` by the erased operand types, and this is the same rule one
 * operator over.
 *
 * `==` on two statically-primitive `Double`s is IEEE-754 in Kotlin — `NaN`
 * is unequal to itself and `0.0 == -0.0` — which is exactly what JavaScript's
 * `===` specifies for numbers. Written as a function rather than emitted as an
 * IR `EQEQ` so that the semantics are the ones Kotlin's own source rules give,
 * and not a property of how a particular backend lowers a node.
 */
public fun jsStrictEqualsNumbers(left: Double, right: Double): Boolean = left == right

/** `===` where both operands are statically `string` — see [jsStrictEqualsNumbers]. */
public fun jsStrictEqualsStrings(left: String, right: String): Boolean = left == right

/** `===` where both operands are statically `boolean` — see [jsStrictEqualsNumbers]. */
public fun jsStrictEqualsBooleans(left: Boolean, right: Boolean): Boolean = left == right

/**
 * `===` where the RIGHT operand is statically `number` and the left is not.
 *
 * Both directions exist because `===` evaluates its LEFT operand first and the
 * lowering hands the operands over already lowered: swapping them to reach one
 * function would reorder two expressions that may both have effects.
 */
public fun jsStrictEqualsAnyNumber(left: Any?, right: Double): Boolean =
    left is Double && jsStrictEqualsNumbers(left, right)

/** `===` where the LEFT operand is statically `number` — see [jsStrictEqualsAnyNumber]. */
public fun jsStrictEqualsNumberAny(left: Double, right: Any?): Boolean =
    right is Double && jsStrictEqualsNumbers(left, right)

/** `===` where the RIGHT operand is statically `string` — see [jsStrictEqualsAnyNumber]. */
public fun jsStrictEqualsAnyString(left: Any?, right: String): Boolean =
    left is String && left == right

/** `===` where the LEFT operand is statically `string` — see [jsStrictEqualsAnyNumber]. */
public fun jsStrictEqualsStringAny(left: String, right: Any?): Boolean =
    right is String && left == right

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
 * ## The NOMINAL half rides on top of this class rather than beside it
 *
 * An object LITERAL whose property names are all statically known gets a
 * generated subclass with one real JVM field per name — see
 * `KirFileLowering.shapeClassFor` — and that subclass overrides [get], [set]
 * and [has] with a chain of name comparisons over its own fields. At a call
 * site whose receiver is monomorphic and whose name is a constant, which is
 * what `ctx.pos` in a scanner is, the JIT inlines the override and folds the
 * comparison away: the read becomes a `getfield`.
 *
 * **The subclass is a `JsObject`, so nothing about assignability changes.**
 * That is the whole reason this shape was chosen over changing what an object
 * type ERASES to: TypeScript's assignability is structural, a generated class
 * is not, and every value here still passes wherever a bag is expected. A
 * reader that does not know the shape — `o[k]` with a computed key, a value
 * that reached a parameter typed `any` — calls the same virtual [get] and is
 * answered by the same fields.
 *
 * ## Spilling, which is what keeps the dynamic half total
 *
 * A property the shape does not declare goes straight into [properties], and
 * so does everything once the object is SPILLED — which happens the first time
 * something is deleted, or the keys are enumerated. [spillNow] moves the
 * generated fields into the bag in declaration order and sets the flag that
 * makes every later access take the ordinary path, so `Object.keys` order
 * survives and `delete` needs no per-slot presence bit.
 *
 * The census that decided all of this is `docs/perf/kir-backend-levers.md` §2a:
 * 3,333 bag operations per benchmark parse, **93.6% of the reads on a bag of
 * exactly three keys**, and four attempts at making the bag itself cheaper that
 * produced no win between them.
 */
public open class JsObject {

    private var properties: LinkedHashMap<String, Any?> = LinkedHashMap()

    /** False while a generated subclass's FIELDS still hold its declared slots. */
    private var spilled: Boolean = false

    /**
     * Whether a generated subclass may still answer from its own fields.
     *
     * Read once at the top of every generated [get] and [set], and `final`, so
     * it is a field read the JIT folds into the caller.
     */
    public fun shapeActive(): Boolean = !spilled

    /** An absent property is `undefined`, i.e. `null` here — never an error. */
    public open fun get(name: String): Any? = properties[name]

    public open fun set(name: String, value: Any?) {
        properties[name] = value
    }

    /** `name in object`. */
    public open fun has(name: String): Boolean = properties.containsKey(name)

    /**
     * `delete object.name`, whose result is `true` for a configurable property.
     *
     * A generated subclass SPILLS first, which is why no slot needs a presence
     * bit: after a delete the object is an ordinary bag and the deletion is an
     * ordinary removal.
     */
    public open fun delete(name: String): Boolean {
        properties.remove(name)
        return true
    }

    /** `Object.keys(object)`, in insertion order as JavaScript specifies. */
    public open fun keys(): JsArray = JsArray(properties.keys.toList())

    // ---- what a generated subclass calls, all of it final --------------------

    /** [get] for a name the shape does not declare, or after a spill. */
    public fun bagGet(name: String): Any? = properties[name]

    /** [set] for a name the shape does not declare, or after a spill. */
    public fun bagSet(name: String, value: Any?) {
        properties[name] = value
    }

    /** [has] for a name the shape does not declare, or after a spill. */
    public fun bagHas(name: String): Boolean = properties.containsKey(name)

    /** [delete], which a generated subclass reaches only after spilling. */
    public fun bagDelete(name: String): Boolean {
        properties.remove(name)
        return true
    }

    /** [keys], which a generated subclass reaches only after spilling. */
    public fun bagKeys(): JsArray = JsArray(properties.keys.toList())

    /**
     * Moves a generated shape's fields into the bag, in DECLARATION order.
     *
     * The order is the reason the extras are set aside and put back rather than
     * written into: a literal's own properties come first in `Object.keys`, and
     * anything assigned afterwards follows them.
     */
    public fun spillNow() {
        if (spilled) return
        val extras = properties
        properties = LinkedHashMap()
        spilled = true
        spill()
        properties.putAll(extras)
    }

    /** Overridden by a generated shape to call [spillSlot] once per field. */
    public open fun spill() {}

    /** One declared slot, appended in order — see [spillNow]. */
    public fun spillSlot(name: String, value: Any?) {
        properties[name] = value
    }

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
 * The keys `for (k in subject)` walks, as an array of STRINGS.
 *
 * `for…in` is lowered as an indexed walk over this, which is what makes it the
 * same loop `for…of` already is. JavaScript enumerates own enumerable string
 * keys plus the prototype chain's; nothing this backend builds has an
 * enumerable prototype, so own keys is the whole answer.
 *
 * An ARRAY enumerates its INDICES — as strings, which is the part a program
 * notices: `for (i in [7,8]) s += i` builds `"01"`, not `15`. `null` and
 * `undefined` enumerate nothing rather than throwing, which is `for…in`'s own
 * exception to JavaScript's usual treatment of them.
 */
public fun jsForInKeys(subject: Any?): JsArray = when (subject) {
    null, Undefined -> JsArray()
    is JsObject -> subject.keys()
    is JsArray -> JsArray((0 until subject.length.toInt()).map { it.toString() })
    is String -> JsArray(subject.indices.map { it.toString() })
    // A `Map`'s entries are not properties, and neither are a `Set`'s — JS
    // enumerates nothing for either, which is why iterating one needs `for…of`.
    else -> JsArray()
}

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
 * A function value whose arity is NOT fixed — one declared with a rest parameter.
 *
 * Every other function value erases to a `kotlin.FunctionN`, which is possible
 * only because the arity is a static fact. `...rest` removes that fact: the
 * caller decides how many arguments there are, so there is no `N` to pick, and
 * a `FunctionN` chosen from the DECLARATION would silently drop the surplus —
 * `cronstrue`'s replacer callback would then be handed one capture group where
 * the match produced three.
 *
 * So such a value carries its arguments as an array instead, and [fixed] says
 * how many of them the declaration named before the rest: the body reads
 * `arguments[0 until fixed]` as its own parameters and the remainder as the
 * rest array. The packing happens in [jsCall] and its specializations, which is
 * the one place that knows the ACTUAL argument count.
 *
 * @property fixed the number of parameters declared before the rest parameter.
 * @property impl the body, taking every actual argument as one array.
 */
public class JsVarargFunction(
    public val fixed: Int,
    public val impl: (JsArray) -> Any?,
) {

    public operator fun invoke(arguments: JsArray): Any? = impl(arguments)

    override fun toString(): String = "function"

}

/** Builds a [JsVarargFunction]; what the lowering emits for `function (a, ...rest)`. */
public fun jsVarargFunction(fixed: Int, impl: (JsArray) -> Any?): JsVarargFunction =
    JsVarargFunction(fixed, impl)

/**
 * One NAMED parameter of a [JsVarargFunction], read out of the packed arguments.
 *
 * Out of range is `null`, which is what an omitted argument is everywhere else
 * in this runtime — a variadic function called with fewer arguments than it
 * named is ordinary JavaScript, not an error.
 */
public fun jsVarargFixed(arguments: JsArray, index: Int): Any? =
    arguments[index.toDouble()]

/**
 * The REST parameter of a [JsVarargFunction]: every argument from [from] on.
 *
 * Always a fresh array, never a view — the body may push to it, and JavaScript's
 * rest parameter is a genuine new array rather than a slice of `arguments`.
 */
public fun jsVarargRest(arguments: JsArray, from: Int): JsArray {
    val rest = JsArray()
    var index = from
    val length = arguments.length.toInt()
    while (index < length) {
        rest.push(arguments[index.toDouble()])
        index++
    }
    return rest
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
    is JsVarargFunction -> callee.impl(JsArray())
    null -> throw JsTypeError("undefined is not a function")
    else -> throw JsTypeError("${jsToString(callee)} is not a function")
}

@Suppress("UNCHECKED_CAST")
public fun jsCall1(callee: Any?, a0: Any?): Any? = when (callee) {
    is Function1<*, *> -> (callee as Function1<Any?, Any?>)(a0)
    is Function0<*> -> callee()
    is Function2<*, *, *> -> (callee as Function2<Any?, Any?, Any?>)(a0, null)
    is Function3<*, *, *, *> -> (callee as Function3<Any?, Any?, Any?, Any?>)(a0, null, null)
    is JsVarargFunction -> callee.impl(jsArrayOf(a0))
    null -> throw JsTypeError("undefined is not a function")
    else -> throw JsTypeError("${jsToString(callee)} is not a function")
}

@Suppress("UNCHECKED_CAST")
public fun jsCall2(callee: Any?, a0: Any?, a1: Any?): Any? = when (callee) {
    is Function2<*, *, *> -> (callee as Function2<Any?, Any?, Any?>)(a0, a1)
    is Function1<*, *> -> (callee as Function1<Any?, Any?>)(a0)
    is Function0<*> -> callee()
    is Function3<*, *, *, *> -> (callee as Function3<Any?, Any?, Any?, Any?>)(a0, a1, null)
    is JsVarargFunction -> callee.impl(jsArrayOf(a0, a1))
    null -> throw JsTypeError("undefined is not a function")
    else -> throw JsTypeError("${jsToString(callee)} is not a function")
}

@Suppress("UNCHECKED_CAST")
public fun jsCall3(callee: Any?, a0: Any?, a1: Any?, a2: Any?): Any? = when (callee) {
    is Function3<*, *, *, *> -> (callee as Function3<Any?, Any?, Any?, Any?>)(a0, a1, a2)
    is Function2<*, *, *> -> (callee as Function2<Any?, Any?, Any?>)(a0, a1)
    is Function1<*, *> -> (callee as Function1<Any?, Any?>)(a0)
    is Function0<*> -> callee()
    is JsVarargFunction -> callee.impl(jsArrayOf(a0, a1, a2))
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
        // The one callee whose arity is decided HERE rather than at its
        // declaration: every actual argument is packed, so nothing is dropped
        // and nothing is invented.
        is JsVarargFunction -> callee.impl(JsArray(arguments.toList()))
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

    private val pattern: java.util.regex.Pattern = compiledPattern(source, flags)

    /**
     * The fast matcher for this pattern, or null when the pattern is outside
     * the regular subset it covers — see [JsRegexProgram].
     */
    private val program: JsRegexProgram? = compiledRegexProgram(source, flags)

    public val global: Boolean = 'g' in flags

    /**
     * The matcher [test] and [exec] reuse, or null until the first of them runs.
     *
     * A `Matcher` is a scratch buffer — two `int` arrays sized by the pattern's
     * groups and locals — and `Pattern.matcher` allocates a fresh one per call.
     * Neither of the two methods that reuse this one lets any other code run
     * between starting a match and reading its groups out, so there is nothing
     * that could observe the sharing. [matcherFor] deliberately does NOT reuse:
     * its callers keep the matcher ACROSS iterations.
     */
    private var reusable: java.util.regex.Matcher? = null

    private fun matcher(input: String): java.util.regex.Matcher {
        val existing = reusable
        if (existing != null) return existing.reset(input)
        val fresh = pattern.matcher(input)
        reusable = fresh
        return fresh
    }

    /** The REFERENCE engine's answer, and the differential oracle's. */
    private fun oracleTest(input: String): Boolean = matcher(input).find()

    /**
     * `RegExp.prototype.test`, from the fast matcher where there is one.
     *
     * (KIR.PERF.2). `java.util.regex` is 20% of a `smol-toml` parse and
     * `kotlin.text.Regex` a further 5.2x, so this one call is worth an engine.
     * [JsRegexProgram] answers the regular subset from a DFA and REFUSES
     * everything else, and the reference engine stays live underneath — both
     * as that fallback and, under [jsRegexVerify], as the oracle every answer
     * is checked against.
     */
    public fun test(input: String): Boolean {
        val fast = program ?: return oracleTest(input)
        val answer = fast.test(input)
        if (answer == JsRegexProgram.UNKNOWN) return oracleTest(input)
        val fastAnswer = answer == JsRegexProgram.MATCH
        if (jsRegexVerify && fastAnswer != oracleTest(input)) {
            throw IllegalStateException(
                "regex engines disagree on /$source/$flags for <$input>: " +
                    "fast=$fastAnswer reference=${!fastAnswer}"
            )
        }
        return fastAnswer
    }

    /**
     * `RegExp.prototype.exec`: the whole match then each group, or null.
     *
     * An unmatched optional group is `undefined` in JavaScript, i.e. `null`
     * here — which is what a program testing `match[2]` is asking about.
     */
    public fun exec(input: String): JsArray? {
        val matcher = matcher(input)
        if (!matcher.find()) return null
        val groups = ArrayList<Any?>(matcher.groupCount() + 1)
        for (index in 0..matcher.groupCount()) groups.add(matcher.group(index))
        return JsArray(groups)
    }

    internal fun matcherFor(input: String): java.util.regex.Matcher = pattern.matcher(input)

    /**
     * The plain string this expression matches, when it is one — see
     * [JsRegexProgram.literal].
     *
     * `value.replace(/_/g, '')` is 16 calls per `smol-toml` document and the
     * pattern is one character, so the whole regex machinery is asked to do
     * what `String.replace` does directly.
     */
    internal val literal: String? get() = program?.literal

    /** `String.prototype.split`'s fields — see [jsStrSplit]. */
    internal fun splitOf(input: String): List<Any?> =
        pattern.split(input, -1).toList()

    override fun toString(): String = "/$source/$flags"

}

/**
 * Every distinct `(source, flags)` a program uses, compiled once.
 *
 * A regular-expression LITERAL evaluates to a fresh object every time control
 * reaches it — `value.replace(/_/g, '')` inside a function makes one per call —
 * and `Pattern.compile` re-parses the source each time, which a JavaScript
 * engine does not. A `Pattern` is immutable and carries no match state, so
 * sharing one between two `JsRegExp` objects is invisible: the mutable half is
 * the `Matcher`, and each object still has its own.
 */
private val compiledPatterns =
    java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern>()

private fun compiledPattern(source: String, flags: String): java.util.regex.Pattern =
    compiledPatterns.computeIfAbsent("$flags\u0000$source") {
        java.util.regex.Pattern.compile(
            jsEndAnchorTranslated(source, flags),
            (if ('i' in flags) java.util.regex.Pattern.CASE_INSENSITIVE else 0) or
                (if ('m' in flags) java.util.regex.Pattern.MULTILINE else 0) or
                (if ('s' in flags) java.util.regex.Pattern.DOTALL else 0)
        )
    }

/**
 * The reference engine's pattern text, because JavaScript's `$` is not Java's.
 *
 * Without `m`, JavaScript's `$` matches ONLY at the end of the input while
 * `java.util.regex`'s also matches before a final line terminator — so
 * `/^\d+$/.test("12\n")` is `false` in a JavaScript engine and was `true`
 * here. `\z` is Java's spelling of the JavaScript meaning.
 *
 * This is the ONE place the pattern is not passed through verbatim, and it is
 * a divergence being CLOSED rather than a rewrite being risked: it fires only
 * on a trailing anchor, and the fast matcher — which handles `$` structurally
 * and always meant the JavaScript thing — is what made the disagreement
 * visible. It also makes [jsRegexVerify] a plain equality, which it could not
 * otherwise be.
 */
internal fun jsEndAnchorTranslated(source: String, flags: String): String {
    if ('m' in flags) return source
    if (!source.endsWith("$") || jsRegexEndsWithEscapedDollar(source)) return source
    return source.dropLast(1) + "\\z"
}

/**
 * Is the final `$` of [source] escaped?
 *
 * The PARITY of the run of backslashes before it decides: `\$` is a literal
 * dollar and `\\$` is an escaped backslash followed by the anchor.
 */
internal fun jsRegexEndsWithEscapedDollar(source: String): Boolean {
    var backslashes = 0
    var index = source.length - 2
    while (index >= 0 && source[index] == '\\') {
        backslashes++
        index--
    }
    return backslashes % 2 == 1
}

/** A regular-expression literal: `/pattern/flags`. */
public fun jsRegExp(source: String, flags: String): JsRegExp = JsRegExp(source, flags)

/** `String.prototype.match` with a regular expression — `exec`, or null. */
public fun jsStrMatch(value: String, expression: JsRegExp): JsArray? = expression.exec(value)

/** `String.prototype.replace` with a regular expression. */
public fun jsStrReplace(value: String, expression: JsRegExp, replacement: String): String {
    // A LITERAL pattern's match set is the occurrences of that string,
    // leftmost-first and non-overlapping, which is what `String.replace` walks
    // — and the replacement is taken literally by both, so the two agree by
    // construction. `value.replace(/_/g, '')` is the shape this exists for.
    val literal = expression.literal
    if (literal != null) {
        if (expression.global) return value.replace(literal, replacement)
        val at = value.indexOf(literal)
        return if (at < 0) value else
            value.substring(0, at) + replacement + value.substring(at + literal.length)
    }
    // `Matcher.quoteReplacement`, because JavaScript's replacement string has
    // its own escape language (`$1`, `$&`) and Java's is a DIFFERENT one — so a
    // replacement containing `$` or `\` would otherwise mean something else.
    val quoted = java.util.regex.Matcher.quoteReplacement(replacement)
    val matcher = expression.matcherFor(value)
    return if (expression.global) matcher.replaceAll(quoted) else matcher.replaceFirst(quoted)
}

/**
 * `String.prototype.replace` with a REPLACER FUNCTION.
 *
 * The argument list is `(match, p1, …, pn, offset, whole)`, so its LENGTH is a
 * property of the pattern rather than of the call site — which is why this and
 * the variadic function carrier are one piece of work: `cronstrue`'s own
 * replacer is declared `function (substring, ...args)` and would otherwise be
 * handed one argument where the match produced four.
 *
 * The call goes through [jsCall] for that reason too: the replacer may be an
 * arrow of any arity, and JavaScript pads and drops rather than refusing.
 *
 * A group that did not participate is `null` in `java.util.regex` and
 * `undefined` in JavaScript, which are one value here — so it needs no
 * translation. The result is `ToString`-ed, not required to be a string:
 * `replace(re, () => 1)` substitutes `"1"`.
 */
public fun jsStrReplace(value: String, expression: JsRegExp, replacer: Any?): String {
    val matcher = expression.matcherFor(value)
    val out = StringBuilder()
    var last = 0
    while (matcher.find()) {
        out.append(value, last, matcher.start())
        val arguments = ArrayList<Any?>(matcher.groupCount() + 3)
        arguments.add(matcher.group())
        for (group in 1..matcher.groupCount()) arguments.add(matcher.group(group))
        // The offset is a NUMBER, i.e. a `Double` here — a replacer that
        // returns it would otherwise print an `Int`, which JavaScript has not.
        arguments.add(matcher.start().toDouble())
        arguments.add(value)
        out.append(jsToString(jsCall(replacer, *arguments.toTypedArray())))
        last = matcher.end()
        // Without `g` only the FIRST match is replaced, which is the whole
        // difference the flag makes.
        if (!expression.global) break
    }
    out.append(value, last, value.length)
    return out.toString()
}

/**
 * `String.prototype.replace` with a STRING pattern and a REPLACER FUNCTION.
 *
 * One match, at the first occurrence, and the argument list is the same shape
 * with no capture groups: `(match, offset, whole)`.
 */
public fun jsStrReplace(value: String, search: String, replacer: Any?): String {
    val at = value.indexOf(search)
    if (at < 0) return value
    val replacement = jsToString(jsCall(replacer, search, at.toDouble(), value))
    return value.substring(0, at) + replacement + value.substring(at + search.length)
}

/**
 * `String.prototype.split` with a regular expression.
 *
 * The limit is `-1` because JavaScript keeps a trailing empty field and Java's
 * own default drops it, and the pattern comes from the CACHE rather than from a
 * fresh `Regex(source)` — which recompiled on every call and, being built from
 * the source alone, silently ignored the expression's own flags.
 */
public fun jsStrSplit(value: String, expression: JsRegExp): JsArray =
    JsArray(expression.splitOf(value))

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
 * `Object.entries` — the same keys as [jsObjectKeys], each paired with its value.
 *
 * Each entry is a two-element array, which is what makes
 * `for (const [k, v] of Object.entries(o))` an ordinary destructuring walk.
 */
public fun jsObjectEntries(value: Any?): JsArray {
    val entries = JsArray()
    val keys = jsObjectKeys(value)
    var index = 0
    val length = keys.length.toInt()
    while (index < length) {
        val key = keys[index.toDouble()]
        entries.push(jsArrayOf(key, jsGet(value, jsToString(key))))
        index++
    }
    return entries
}

/** `Object.values` — [jsObjectEntries] without the keys. */
public fun jsObjectValues(value: Any?): JsArray {
    val values = JsArray()
    val keys = jsObjectKeys(value)
    var index = 0
    val length = keys.length.toInt()
    while (index < length) {
        values.push(jsGet(value, jsToString(keys[index.toDouble()])))
        index++
    }
    return values
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

// ---------------------------------------------------------------------------
// The regular-subset matcher — (KIR.PERF.2)
// ---------------------------------------------------------------------------

/*
 * `java.util.regex` costs 9.5 us per `smol-toml` document — 20% of the whole
 * JVM parse, and 42% of Node's ENTIRE parse budget — and it is the pattern
 * SHAPE that costs, not the number of calls: `^\d+$` is 14.7 ns while
 * `^\d(?:_?\d)*$` is 94 ns, because a repetition whose body is not a single
 * deterministic character compiles to a backtracking `Loop` node. TOML's digit
 * separators are literally `(_?\d)*`, so every numeric value walks that
 * machinery once per character. `kotlin.text.Regex` on Kotlin/Native is a
 * further 5.2x. `docs/perf/kir-backend-levers.md` §5.
 *
 * So: a matcher for the REGULAR subset those patterns live in — no
 * backreferences, no lookaround, no `\b` — compiled once per (source, flags)
 * beside the `Pattern` cache, and answering `test` from a LAZY DFA, which is
 * one array read per character with no backtracking at all.
 *
 * Three properties make this an optimisation rather than a second, divergent
 * semantics:
 *
 * - it answers `test` and NOTHING else. Existence of a match is the one
 *   question on which leftmost-longest — what a DFA over a state SET decides —
 *   and JavaScript's leftmost-first agree by construction, so alternation
 *   order and greedy-versus-lazy, the two things a DFA cannot represent,
 *   cannot be observed. `exec`, `replace` and `split` keep the reference
 *   engine.
 * - anything outside the subset is REFUSED at compile time and falls back to
 *   the reference engine. A refusal is a performance outcome and never a
 *   behavioural one, which is what lets the subset stay small and honest.
 * - the reference engine stays LIVE as a differential oracle: with
 *   [jsRegexVerify] on, every `test` runs BOTH and a disagreement throws. The
 *   round-792 shape — the specification is kept runnable and is never demoted
 *   to a legality gate.
 */

/**
 * Run the reference engine beside the fast one and throw on a disagreement.
 *
 * Off in production, on in the gate. A `var` rather than a build flag because
 * the population that matters is the one a real library presents, and that is
 * reached by compiling and running the library.
 */
public var jsRegexVerify: Boolean = false

/** A set of code units, as ranges plus a negation. */
internal class JsCharSet(private val ranges: IntArray, private val negated: Boolean) {

    /** True iff [ranges] cannot separate one code unit at or above 256 from another. */
    val wideUniform: Boolean = run {
        var index = 1
        var uniform = true
        while (index < ranges.size) {
            if (ranges[index] >= 256) { uniform = false; break }
            index += 2
        }
        uniform
    }

    /** The one code unit this set holds, or null if it is not exactly one. */
    val singleCode: Int?
        get() = if (!negated && ranges.size == 2 && ranges[0] == ranges[1]) ranges[0] else null

    fun contains(ch: Int): Boolean {
        var index = 0
        while (index < ranges.size) {
            if (ch >= ranges[index] && ch <= ranges[index + 1]) return !negated
            index += 2
        }
        return negated
    }
}

/** Raised while compiling a pattern this matcher does not cover. */
internal class JsRegexUnsupported : RuntimeException()

/** A parsed pattern: the regular operators and nothing else. */
internal sealed class JsRegexNode {

    internal object Empty : JsRegexNode()

    internal class Chars(val set: JsCharSet) : JsRegexNode()

    internal class Cat(val left: JsRegexNode, val right: JsRegexNode) : JsRegexNode()

    internal class Alt(val left: JsRegexNode, val right: JsRegexNode) : JsRegexNode()

    /**
     * [max] below zero is unbounded.
     *
     * Greedy and lazy compile identically: the LANGUAGE is the same, and
     * existence of a match is all [JsRegexProgram] is ever asked.
     */
    internal class Repeat(val body: JsRegexNode, val min: Int, val max: Int) : JsRegexNode()
}

/**
 * The pattern parser, which REFUSES rather than approximates.
 *
 * Every construct outside the regular subset — a backreference, a lookaround, a
 * named group, `\b`, a `\u`/`\x` escape, a `^`/`$` anywhere but the pattern's
 * own edges — throws [JsRegexUnsupported] and the caller falls back to the
 * reference engine. That is the property that makes the matcher safe to add at
 * all: an unrecognised pattern costs a compile-time refusal, never a wrong
 * answer at run time.
 */
internal class JsRegexParser(private val src: String, private val dotAll: Boolean) {

    private var pos = 0

    fun parse(): JsRegexNode {
        val node = parseAlternation()
        if (pos != src.length) throw JsRegexUnsupported()
        return node
    }

    private fun parseAlternation(): JsRegexNode {
        var node = parseConcat()
        while (pos < src.length && src[pos] == '|') {
            pos++
            node = JsRegexNode.Alt(node, parseConcat())
        }
        return node
    }

    private fun parseConcat(): JsRegexNode {
        var node: JsRegexNode = JsRegexNode.Empty
        while (pos < src.length && src[pos] != '|' && src[pos] != ')') {
            val term = parseTerm()
            node = if (node === JsRegexNode.Empty) term else JsRegexNode.Cat(node, term)
        }
        return node
    }

    private fun parseTerm(): JsRegexNode = quantify(parseAtom())

    private fun parseAtom(): JsRegexNode = when (val ch = src[pos]) {
        '(' -> {
            pos++
            if (pos < src.length && src[pos] == '?') {
                // `(?:` is a group; `(?=` `(?!` `(?<` are the lookarounds and
                // the named group, none of which is in the subset.
                if (pos + 1 >= src.length || src[pos + 1] != ':') throw JsRegexUnsupported()
                pos += 2
            }
            val inner = parseAlternation()
            if (pos >= src.length || src[pos] != ')') throw JsRegexUnsupported()
            pos++
            inner
        }
        '[' -> parseCharClass()
        '.' -> {
            pos++
            JsRegexNode.Chars(if (dotAll) ANY_CHAR else ANY_CHAR_BUT_LINE_TERMINATOR)
        }
        '\\' -> {
            pos++
            JsRegexNode.Chars(parseEscape())
        }
        // A quantifier with nothing to quantify, and the two anchors in a
        // position this matcher handles structurally rather than as an
        // assertion.
        '*', '+', '?', '{', '^', '$' -> throw JsRegexUnsupported()
        else -> {
            pos++
            JsRegexNode.Chars(singleChar(ch.code))
        }
    }

    private fun quantify(atom: JsRegexNode): JsRegexNode {
        if (pos >= src.length) return atom
        val node = when (src[pos]) {
            '*' -> { pos++; JsRegexNode.Repeat(atom, 0, -1) }
            '+' -> { pos++; JsRegexNode.Repeat(atom, 1, -1) }
            '?' -> { pos++; JsRegexNode.Repeat(atom, 0, 1) }
            '{' -> parseCountedRepeat(atom)
            else -> return atom
        }
        // A trailing `?` is the LAZY spelling, which is the same language.
        if (pos < src.length && src[pos] == '?') pos++
        // `a**` is a syntax error in JavaScript, so refusing it narrows nothing.
        if (pos < src.length && (src[pos] == '*' || src[pos] == '+' || src[pos] == '{')) {
            throw JsRegexUnsupported()
        }
        return node
    }

    /** `{n}` / `{n,}` / `{n,m}`. A brace that is not one of those is refused. */
    private fun parseCountedRepeat(atom: JsRegexNode): JsRegexNode {
        val close = src.indexOf('}', pos)
        if (close < 0) throw JsRegexUnsupported()
        val text = src.substring(pos + 1, close)
        val comma = text.indexOf(',')
        val minText = if (comma < 0) text else text.substring(0, comma)
        val maxText = if (comma < 0) text else text.substring(comma + 1)
        val min = minText.toIntOrNull() ?: throw JsRegexUnsupported()
        val max = when {
            comma < 0 -> min
            maxText.isEmpty() -> -1
            else -> maxText.toIntOrNull() ?: throw JsRegexUnsupported()
        }
        if (min > REPEAT_LIMIT || max > REPEAT_LIMIT) throw JsRegexUnsupported()
        if (max >= 0 && max < min) throw JsRegexUnsupported()
        pos = close + 1
        return JsRegexNode.Repeat(atom, min, max)
    }

    private fun parseCharClass(): JsRegexNode {
        pos++
        val negated = pos < src.length && src[pos] == '^'
        if (negated) pos++
        val ranges = ArrayList<Int>()
        while (true) {
            if (pos >= src.length) throw JsRegexUnsupported()
            if (src[pos] == ']') { pos++; break }
            val low = parseClassAtom(ranges) ?: continue
            if (pos + 1 < src.length && src[pos] == '-' && src[pos + 1] != ']') {
                pos++
                val before = ranges.size
                val high = parseClassAtom(ranges)
                // `[\d-x]` — a class ESCAPE as a range endpoint. JavaScript's
                // legacy reading of that is not the subset's, so it is refused.
                if (high == null || ranges.size != before) throw JsRegexUnsupported()
                if (high < low) throw JsRegexUnsupported()
                ranges.add(low)
                ranges.add(high)
            } else {
                ranges.add(low)
                ranges.add(low)
            }
        }
        return JsRegexNode.Chars(JsCharSet(ranges.toIntArray(), negated))
    }

    /**
     * One class member: a code unit, or — for `\d`/`\w`/`\s` — a set appended
     * to [ranges] directly, which is what the null return means.
     */
    private fun parseClassAtom(ranges: ArrayList<Int>): Int? {
        val ch = src[pos]
        if (ch != '\\') {
            pos++
            return ch.code
        }
        pos++
        if (pos >= src.length) throw JsRegexUnsupported()
        val escape = src[pos]
        // A NEGATED class escape inside a class is a set complement this
        // representation cannot union, so it is refused rather than widened.
        if (escape == 'D' || escape == 'W' || escape == 'S') throw JsRegexUnsupported()
        if (escape == 'd' || escape == 'w' || escape == 's') {
            pos++
            appendClassEscape(escape, ranges)
            return null
        }
        // `\b` is a BACKSPACE inside a class, where outside it is an assertion.
        if (escape == 'b') {
            pos++
            return 0x08
        }
        return simpleEscape()
    }

    private fun parseEscape(): JsCharSet {
        if (pos >= src.length) throw JsRegexUnsupported()
        val escape = src[pos]
        if (escape == 'd' || escape == 'w' || escape == 's' ||
            escape == 'D' || escape == 'W' || escape == 'S'
        ) {
            pos++
            val ranges = ArrayList<Int>()
            appendClassEscape(escape.lowercaseChar(), ranges)
            return JsCharSet(ranges.toIntArray(), escape.isUpperCase())
        }
        return singleChar(simpleEscape())
    }

    /** The escapes that denote ONE code unit, and only those. */
    private fun simpleEscape(): Int {
        val escape = src[pos]
        pos++
        return when (escape) {
            'n' -> 0x0A
            'r' -> 0x0D
            't' -> 0x09
            'f' -> 0x0C
            'v' -> 0x0B
            // `\0` is NUL only when no digit follows; otherwise it is a legacy
            // octal escape, which is not in the subset.
            '0' -> if (pos < src.length && src[pos] in '0'..'9') throw JsRegexUnsupported() else 0
            // A backreference, a word boundary, a named backreference, a
            // unicode property, a control escape, and the two hex escapes,
            // which mean different things with and without the `u` flag.
            in '1'..'9', 'b', 'B', 'k', 'p', 'P', 'c', 'x', 'u' -> throw JsRegexUnsupported()
            // JavaScript allows an identity escape only of a punctuator, and a
            // letter escape it does not know is a syntax error under `u` — so
            // an unknown LETTER is refused and a punctuator is taken literally.
            else -> if (escape.isLetterOrDigit()) throw JsRegexUnsupported() else escape.code
        }
    }

    private fun appendClassEscape(escape: Char, ranges: ArrayList<Int>) {
        when (escape) {
            'd' -> { ranges.add('0'.code); ranges.add('9'.code) }
            'w' -> {
                ranges.add('0'.code); ranges.add('9'.code)
                ranges.add('A'.code); ranges.add('Z'.code)
                ranges.add('_'.code); ranges.add('_'.code)
                ranges.add('a'.code); ranges.add('z'.code)
            }
            's' -> for (value in WHITESPACE) ranges.add(value)
            else -> throw JsRegexUnsupported()
        }
    }

    private fun singleChar(code: Int): JsCharSet =
        JsCharSet(intArrayOf(code, code), negated = false)

    internal companion object {

        /** The bound that keeps `{n,m}` expansion from exploding the NFA. */
        const val REPEAT_LIMIT: Int = 64

        /** ECMAScript `WhiteSpace` together with `LineTerminator`, i.e. `\s`. */
        private val WHITESPACE = intArrayOf(
            0x09, 0x0D, 0x20, 0x20, 0xA0, 0xA0, 0x1680, 0x1680,
            0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x202F,
            0x205F, 0x205F, 0x3000, 0x3000, 0xFEFF, 0xFEFF,
        )

        /** `.` under the `s` flag: every code unit. */
        private val ANY_CHAR = JsCharSet(IntArray(0), negated = true)

        /** `.` without `s`: every code unit but the four line terminators. */
        private val ANY_CHAR_BUT_LINE_TERMINATOR = JsCharSet(
            intArrayOf(0x0A, 0x0A, 0x0D, 0x0D, 0x2028, 0x2029), negated = true
        )
    }
}

/**
 * Thompson's construction, in three growable parallel arrays.
 *
 * A state is a CHAR state when [charSets] holds a set for it — one transition,
 * to [next1] — and an EPSILON state otherwise, with up to two targets and `-1`
 * for an absent one. That is the whole representation; Thompson's construction
 * never needs more.
 *
 * A fragment is a start state plus the OUTS it has not connected yet, each
 * encoded as `state * 2 + which`, so patching is an index write rather than a
 * graph walk.
 */
internal class JsRegexNfaBuilder {

    val charSets: ArrayList<JsCharSet?> = ArrayList()
    val next1: ArrayList<Int> = ArrayList()
    val next2: ArrayList<Int> = ArrayList()

    class Fragment(val start: Int, val outs: IntArray)

    fun build(node: JsRegexNode): Fragment = when (node) {
        is JsRegexNode.Empty -> {
            val state = newSplit()
            Fragment(state, intArrayOf(state * 2))
        }
        is JsRegexNode.Chars -> {
            val state = newState(node.set)
            Fragment(state, intArrayOf(state * 2))
        }
        is JsRegexNode.Cat -> {
            val left = build(node.left)
            val right = build(node.right)
            patch(left.outs, right.start)
            Fragment(left.start, right.outs)
        }
        is JsRegexNode.Alt -> {
            val left = build(node.left)
            val right = build(node.right)
            val split = newSplit()
            next1[split] = left.start
            next2[split] = right.start
            Fragment(split, left.outs + right.outs)
        }
        is JsRegexNode.Repeat -> buildRepeat(node)
    }

    /**
     * `{n,m}` is EXPANDED — `a{2,4}` becomes `aa a? a?`.
     *
     * The same language, which is all that is asked, and it keeps the NFA free
     * of counters. The expansion is what [JsRegexParser.REPEAT_LIMIT] exists to
     * stop from exploding.
     */
    private fun buildRepeat(node: JsRegexNode.Repeat): Fragment {
        val min = node.min
        val max = node.max
        if (min == 0 && max < 0) return buildStar(node.body)
        if (min == 1 && max < 0) return buildPlus(node.body)
        if (min == 0 && max == 1) return buildOptional(node.body)
        var head: Fragment? = null
        for (index in 0 until min) {
            val part = build(node.body)
            head = concat(head, part)
        }
        var tail: Fragment? = null
        if (max < 0) {
            tail = buildStar(node.body)
        } else {
            for (index in min until max) tail = concat(tail, buildOptional(node.body))
        }
        val whole = concat(head, tail)
        // `a{0}` and `a{0,0}` match the empty string and nothing else.
        return whole ?: build(JsRegexNode.Empty)
    }

    private fun concat(left: Fragment?, right: Fragment?): Fragment? {
        if (left == null) return right
        if (right == null) return left
        patch(left.outs, right.start)
        return Fragment(left.start, right.outs)
    }

    private fun buildStar(body: JsRegexNode): Fragment {
        val split = newSplit()
        val inner = build(body)
        next1[split] = inner.start
        patch(inner.outs, split)
        return Fragment(split, intArrayOf(split * 2 + 1))
    }

    private fun buildPlus(body: JsRegexNode): Fragment {
        val inner = build(body)
        val split = newSplit()
        next1[split] = inner.start
        patch(inner.outs, split)
        return Fragment(inner.start, intArrayOf(split * 2 + 1))
    }

    private fun buildOptional(body: JsRegexNode): Fragment {
        val split = newSplit()
        val inner = build(body)
        next1[split] = inner.start
        return Fragment(split, inner.outs + intArrayOf(split * 2 + 1))
    }

    fun patch(outs: IntArray, target: Int) {
        for (out in outs) {
            val state = out / 2
            if (out % 2 == 0) next1[state] = target else next2[state] = target
        }
    }

    /** The accept state: an epsilon state with no target, so nothing leaves it. */
    fun newAccept(): Int = newSplit()

    private fun newSplit(): Int = newState(null)

    private fun newState(set: JsCharSet?): Int {
        if (charSets.size >= NFA_LIMIT) throw JsRegexUnsupported()
        charSets.add(set)
        next1.add(-1)
        next2.add(-1)
        return charSets.size - 1
    }

    internal companion object {
        /** The NFA size past which a pattern is refused rather than compiled. */
        const val NFA_LIMIT: Int = 400
    }
}

/**
 * A pattern compiled to a Thompson NFA plus a LAZY DFA over it.
 *
 * The DFA's states are sets of NFA states, discovered on demand and capped at
 * [MAX_DFA_STATES]; past the cap [test] answers [UNKNOWN] and the caller asks
 * the reference engine. Its per-state row is one slot per code unit below 256
 * plus one for every code unit at or above it, which is exact whenever no
 * character set in the pattern separates two such units — and when one does,
 * [wideUniform] is false and a wide code unit simply takes the uncached path.
 *
 * `^` and `$` are handled STRUCTURALLY rather than as assertions, which is what
 * keeps a DFA state independent of the position it was reached at: `^` means
 * "do not union the start closure back in at each step" and `$` means "test
 * acceptance only at the end of the input". The price is that either anchor
 * anywhere but the pattern's own edge, and either under the `m` flag, is
 * outside the subset — which the parser refuses.
 *
 * The row cache is written without synchronization, which is sound because
 * every write is IDEMPOTENT: a racing reader either sees the computed target or
 * sees the `-1` the row was filled with before publication, and recomputes the
 * same value. Nothing is resized, so there is no array to publish unsafely.
 */
internal class JsRegexProgram private constructor(
    private val charSets: Array<JsCharSet?>,
    private val next1: IntArray,
    private val next2: IntArray,
    nfaStart: Int,
    private val nfaAccept: Int,
    private val startAnchored: Boolean,
    private val endAnchored: Boolean,
    private val wideUniform: Boolean,
) {

    private val stateSets = arrayOfNulls<IntArray>(MAX_DFA_STATES)
    private val stateRows = arrayOfNulls<IntArray>(MAX_DFA_STATES)
    private val stateAccepting = BooleanArray(MAX_DFA_STATES)
    private val stateIndex = HashMap<String, Int>()
    private var stateCount = 1

    /**
     * The plain string this pattern matches, when it is one.
     *
     * Null for every pattern with an operator or an anchor in it. A caller that
     * has one can do the whole job with `String.indexOf`, because a literal
     * pattern's match set IS the occurrences of that string, leftmost-first and
     * non-overlapping — which is exactly what `String.replace` walks.
     */
    var literal: String? = null
        private set

    /** The set an unanchored step unions in, so a match may start anywhere. */
    private val startClosure: IntArray = closureOf(intArrayOf(nfaStart), 1)

    private val startState: Int = internState(startClosure)

    /**
     * [MATCH], [NO_MATCH] or [UNKNOWN].
     *
     * An `Int` rather than a `Boolean?` because this is the hot path and a
     * boxed answer would allocate once per call.
     */
    fun test(input: String): Int {
        var state = startState
        if (state == UNKNOWN) return UNKNOWN
        if (!endAnchored && stateAccepting[state]) return MATCH
        var index = 0
        val length = input.length
        while (index < length) {
            val ch = input[index].code
            var next = -1
            val row = stateRows[state]
            if (row != null) {
                val slot = if (ch < 256) ch else if (wideUniform) WIDE_SLOT else -1
                if (slot >= 0) next = row[slot]
            }
            if (next < 0) {
                next = step(state, ch)
                if (next == UNKNOWN) return UNKNOWN
            }
            // Nothing leaves the empty set, so an ANCHORED pattern that has
            // fallen out of every alternative is finished. An unanchored one
            // unions the start closure back in at every step and can never be
            // here, so this is a branch it never takes.
            if (next == DEAD_STATE) return NO_MATCH
            state = next
            index++
            if (!endAnchored && stateAccepting[state]) return MATCH
        }
        return if (stateAccepting[state]) MATCH else NO_MATCH
    }

    /** The uncached transition, which also fills the row it was missing from. */
    private fun step(state: Int, ch: Int): Int {
        val set = stateSets[state] ?: return DEAD_STATE
        var targets = IntArray(8)
        var count = 0
        for (nfaState in set) {
            val charSet = charSets[nfaState] ?: continue
            if (!charSet.contains(ch)) continue
            if (count == targets.size) targets = targets.copyOf(count * 2)
            targets[count++] = next1[nfaState]
        }
        var reached = closureOf(targets, count)
        if (!startAnchored) reached = union(reached, startClosure)
        val next = internState(reached)
        if (next == UNKNOWN) return UNKNOWN
        val slot = if (ch < 256) ch else if (wideUniform) WIDE_SLOT else -1
        if (slot >= 0) {
            var row = stateRows[state]
            if (row == null) {
                row = IntArray(WIDE_SLOT + 1) { -1 }
                stateRows[state] = row
            }
            row[slot] = next
        }
        return next
    }

    /**
     * The epsilon closure of the first [count] entries of [states], SORTED —
     * which is what lets the set be used as a DFA state key.
     */
    private fun closureOf(states: IntArray, count: Int): IntArray {
        if (count == 0) return EMPTY
        val seen = HashSet<Int>()
        val stack = ArrayList<Int>(count)
        for (index in 0 until count) if (seen.add(states[index])) stack.add(states[index])
        val result = ArrayList<Int>(count)
        while (stack.isNotEmpty()) {
            val state = stack.removeAt(stack.size - 1)
            if (charSets[state] != null || state == nfaAccept) {
                result.add(state)
                continue
            }
            val first = next1[state]
            if (first >= 0 && seen.add(first)) stack.add(first)
            val second = next2[state]
            if (second >= 0 && seen.add(second)) stack.add(second)
        }
        val sorted = result.toIntArray()
        sorted.sort()
        return sorted
    }

    private fun union(left: IntArray, right: IntArray): IntArray {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val merged = HashSet<Int>(left.size + right.size)
        for (state in left) merged.add(state)
        for (state in right) merged.add(state)
        val result = merged.toIntArray()
        result.sort()
        return result
    }

    /** The DFA state for [set], minting it if this is its first sighting. */
    private fun internState(set: IntArray): Int {
        if (set.isEmpty()) return DEAD_STATE
        val key = set.joinToString(",")
        val existing = stateIndex[key]
        if (existing != null) return existing
        if (stateCount >= MAX_DFA_STATES) return UNKNOWN
        val index = stateCount++
        stateSets[index] = set
        stateAccepting[index] = set.contains(nfaAccept)
        stateIndex[key] = index
        return index
    }

    internal companion object {

        const val NO_MATCH: Int = 0
        const val MATCH: Int = 1
        const val UNKNOWN: Int = -1

        /**
         * The empty state set, which an ANCHORED pattern can reach and stay in.
         *
         * An unanchored one unions the start closure back in at every step, so
         * its set is never empty and this state is unreachable for it.
         */
        private const val DEAD_STATE = 0

        private const val WIDE_SLOT = 256
        private const val MAX_DFA_STATES = 512

        private val EMPTY = IntArray(0)

        /** The compiled program for [source]/[flags], or null if outside the subset. */
        fun compile(source: String, flags: String): JsRegexProgram? = try {
            build(source, flags)
        } catch (_: JsRegexUnsupported) {
            null
        }

        private fun build(source: String, flags: String): JsRegexProgram {
            for (flag in flags) if (flag != 'g' && flag != 'm' && flag != 's' && flag != 'd') {
                throw JsRegexUnsupported()
            }
            var body = source
            var startAnchored = false
            var endAnchored = false
            if (body.startsWith("^")) {
                startAnchored = true
                body = body.substring(1)
            }
            if (body.endsWith("$") && !jsRegexEndsWithEscapedDollar(body)) {
                endAnchored = true
                body = body.substring(0, body.length - 1)
            }
            // The two anchors are structural here, so their MULTILINE meaning —
            // which is a property of the position rather than of the pattern —
            // is outside the subset.
            if ('m' in flags && (startAnchored || endAnchored)) throw JsRegexUnsupported()
            val tree = JsRegexParser(body, dotAll = 's' in flags).parse()
            val builder = JsRegexNfaBuilder()
            val fragment = builder.build(tree)
            val accept = builder.newAccept()
            builder.patch(fragment.outs, accept)
            val program = JsRegexProgram(
                charSets = builder.charSets.toTypedArray(),
                next1 = builder.next1.toIntArray(),
                next2 = builder.next2.toIntArray(),
                nfaStart = fragment.start,
                nfaAccept = accept,
                startAnchored = startAnchored,
                endAnchored = endAnchored,
                wideUniform = builder.charSets.all { it == null || it.wideUniform },
            )
            if (!startAnchored && !endAnchored) program.literal = literalOf(tree)
            return program
        }

        /**
         * The plain string [node] matches, or null if it is not just a string.
         *
         * A left-leaning `Cat` chain of one-code-unit sets and nothing else —
         * which is what `/_/` and `/, /` are, and what the concatenation cannot
         * be once any quantifier, class, alternation or negation appears.
         */
        private fun literalOf(node: JsRegexNode): String? {
            val text = StringBuilder()
            var pending: JsRegexNode? = node
            val stack = ArrayList<JsRegexNode>()
            while (pending != null || stack.isNotEmpty()) {
                val current = pending ?: stack.removeAt(stack.size - 1)
                pending = null
                when (current) {
                    is JsRegexNode.Cat -> {
                        stack.add(current.right)
                        pending = current.left
                    }
                    is JsRegexNode.Chars -> {
                        val code = current.set.singleCode ?: return null
                        text.append(code.toChar())
                    }
                    else -> return null
                }
            }
            return if (text.isEmpty()) null else text.toString()
        }

    }
}

/** Every distinct `(source, flags)` a program uses, compiled once — or refused once. */
private val compiledRegexPrograms =
    java.util.concurrent.ConcurrentHashMap<String, Any>()

/** Cached like a program, so a pattern outside the subset is not re-parsed either. */
private val REGEX_REFUSED = Any()

internal fun compiledRegexProgram(source: String, flags: String): JsRegexProgram? {
    val cached = compiledRegexPrograms.getOrPut("$flags $source") {
        JsRegexProgram.compile(source, flags) ?: REGEX_REFUSED
    }
    return cached as? JsRegexProgram
}
