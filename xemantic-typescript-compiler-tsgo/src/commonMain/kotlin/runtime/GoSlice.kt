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

// Parts of this file are translated from the Go standard library (go1.27.1), Copyright The Go
// Authors, used under Go's BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.runtime

/**
 * A Go slice `[]T`: a window `[offset, offset + len)` of a shared backing [array] whose
 * capacity extends to `offset + cap`.
 *
 * Go aliasing is preserved exactly: sub-slicing shares the backing array, `append` writes in place
 * while the capacity allows and reallocates (copying) beyond it, and a write through one slice is
 * visible through every other slice of the same array.
 *
 * A slot of the backing array holding Kotlin `null` while [elem] has a non-null zero is a
 * never-written slot — Go's implicit zero. It is materialized on first read (and stored back, so a
 * struct zero read through one alias is the same object through every alias).
 *
 * A nil slice is a non-null [GoSlice] with [isNil] `true` (shared per element kind:
 * [GoElem.nilSlice]); `len`, `range` and `append` need no null checks.
 *
 * Identity semantics: Go slices are not comparable except against nil, so [equals] is identity.
 */
class GoSlice<T> internal constructor(
    @PublishedApi internal val array: Array<Any?>,
    @PublishedApi internal val offset: Int,
    /** `len(s)`. */
    val len: Int,
    /** `cap(s)`. */
    val cap: Int,
    /** The element kind (zero value and value copy). */
    val elem: GoElem<T>,
    /** `s == nil`. */
    val isNil: Boolean,
) {

    /** `s[i]`; panics with Go's index error when out of range. */
    operator fun get(i: Int): T {
        if (i < 0 || i >= len) goPanicIndex(i, len)
        return load(offset + i)
    }

    /** `s[i] = v`; panics with Go's index error when out of range. */
    operator fun set(i: Int, v: T) {
        if (i < 0 || i >= len) goPanicIndex(i, len)
        array[offset + i] = v
    }

    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun load(absolute: Int): T {
        val v = array[absolute]
        if (v == null) {
            val zero = elem.zero ?: return null as T
            val z = zero()
            array[absolute] = z
            return z
        }
        return v as T
    }

    /** `s[low:high]` (high defaults to `len(s)`). */
    fun slice(low: Int = 0, high: Int = len): GoSlice<T> {
        if (high < 0 || high > cap) goPanicSlice("[:$high] with capacity $cap")
        if (low < 0 || low > high) goPanicSlice("[$low:$high]")
        if (isNil) return this
        return GoSlice(array, offset + low, high - low, cap - low, elem, false)
    }

    /** `s[low:high:max]`. */
    fun slice3(low: Int, high: Int, max: Int): GoSlice<T> {
        if (max < 0 || max > cap) goPanicSlice("[::$max] with capacity $cap")
        if (high < 0 || high > max) goPanicSlice("[:$high:$max]")
        if (low < 0 || low > high) goPanicSlice("[$low:$high:]")
        if (isNil) return this
        return GoSlice(array, offset + low, high - low, max - low, elem, false)
    }

    /** `append(s, elems...)`. */
    fun append(vararg elems: T): GoSlice<T> {
        @Suppress("UNCHECKED_CAST")
        val raw = elems as Array<Any?>
        return appendRaw(raw, 0, raw.size, copyValues = false)
    }

    /** `append(s, v)` without a vararg array. */
    fun append1(v: T): GoSlice<T> {
        val newLen = len + 1
        if (newLen <= cap) {
            array[offset + len] = v
            return GoSlice(array, offset, newLen, cap, elem, false)
        }
        val grown = grow(newLen)
        grown.array[len] = v
        return grown
    }

    /** `append(s, other...)`: appends Go value COPIES of [other]'s elements. */
    fun appendSlice(other: GoSlice<T>): GoSlice<T> {
        if (other.len == 0) return this
        // `other` may alias `this` (append(s[:1], s...)): snapshot the source window first —
        // Go's memmove handles the overlap, and a snapshot is the same. Null slots stay null:
        // they are implicit zeros in the destination as well.
        val src = other.array.copyOfRange(other.offset, other.offset + other.len)
        return appendRaw(src, 0, src.size, copyValues = true)
    }

    @PublishedApi
    internal fun appendRaw(src: Array<Any?>, from: Int, n: Int, copyValues: Boolean): GoSlice<T> {
        if (n == 0) return this
        val newLen = len + n
        val target: GoSlice<T> = if (newLen <= cap) {
            GoSlice(array, offset, newLen, cap, elem, false)
        } else {
            grow(newLen)
        }
        val copier = if (copyValues) elem.copy else null
        val base = target.offset + len
        if (copier == null) {
            src.copyInto(target.array, base, from, from + n)
        } else {
            for (k in 0 until n) {
                @Suppress("UNCHECKED_CAST")
                val v = src[from + k] as T
                target.array[base + k] = if (v == null) null else copier(v)
            }
        }
        return target
    }

    /** A new backing array of Go's grown capacity, holding a value copy of this slice's elements, length [newLen]. */
    private fun grow(newLen: Int): GoSlice<T> {
        val newCap = goNextSliceCap(newLen, cap)
        val newArray = arrayOfNulls<Any?>(newCap)
        val copier = elem.copy
        if (copier == null) {
            array.copyInto(newArray, 0, offset, offset + len)
        } else {
            for (k in 0 until len) {
                @Suppress("UNCHECKED_CAST")
                val v = array[offset + k] as T
                newArray[k] = if (v == null) null else copier(v)
            }
        }
        return GoSlice(newArray, 0, newLen, newCap, elem, false)
    }

    /** `&s[i]`: a pointer to the element slot (equal to any other pointer to the same slot). */
    fun addr(i: Int): GoPtr<T> {
        if (i < 0 || i >= len) goPanicIndex(i, len)
        return GoElemPtr(this, offset + i)
    }

    /** The elements as a list (a snapshot; for `range` and debugging). */
    fun toList(): List<T> = List(len) { load(offset + it) }

    /** The elements as an array, for spreading into a Kotlin vararg (`f(s...)`). */
    @Suppress("UNCHECKED_CAST")
    fun toArray(): Array<Any?> = Array(len) { load(offset + it) }

    /** Go's `for i, v := range s` evaluates `s` once; the body sees the element at each index. */
    inline fun forEachIndexed(body: (Int, T) -> Unit) {
        val n = len
        for (i in 0 until n) body(i, load(offset + i))
    }

    override fun toString(): String = if (isNil) "[]" else toList().joinToString(" ", "[", "]")

    internal class GoElemPtr<T>(private val slice: GoSlice<T>, private val absolute: Int) : GoPtr<T> {
        override var value: T
            get() = slice.load(absolute)
            set(v) {
                slice.array[absolute] = v
            }

        override fun equals(other: Any?): Boolean =
            other is GoElemPtr<*> && other.slice.array === slice.array && other.absolute == absolute

        override fun hashCode(): Int = slice.array.hashCode() * 31 + absolute
    }

    companion object {

        /** `make([]T, len, cap)`. */
        fun <T> make(elem: GoElem<T>, len: Int, cap: Int = len): GoSlice<T> {
            if (len < 0) goPanic(GoRuntimeError("makeslice: len out of range"))
            if (cap < len) goPanic(GoRuntimeError("makeslice: cap out of range"))
            return GoSlice(arrayOfNulls(cap), 0, len, cap, elem, false)
        }

        /** A composite literal `[]T{a, b, c}` (the values are taken as given; the lowering copies structs). */
        fun <T> of(elem: GoElem<T>, vararg values: T): GoSlice<T> {
            @Suppress("UNCHECKED_CAST")
            val a = (values as Array<Any?>).copyOf()
            return GoSlice(a, 0, a.size, a.size, elem, false)
        }

        /** Wraps an existing array (shared, not copied) as a full slice. */
        fun <T> wrap(elem: GoElem<T>, array: Array<Any?>): GoSlice<T> =
            GoSlice(array, 0, array.size, array.size, elem, false)

        /** The nil slice of [elem]. */
        fun <T> nil(elem: GoElem<T>): GoSlice<T> = elem.nilSlice
    }

}

/**
 * Go's `nextslicecap` (runtime/slice.go): the capacity `append` grows to.
 *
 * APPROXIMATION: Go then rounds the byte size up to a malloc size class (`roundupsize`), which
 * depends on the element size; this does not. Capacities after growth can therefore be smaller
 * than Go's, which changes WHETHER a later `append` reallocates — observable only by code whose
 * result depends on append aliasing (docs/goport-runtime.md § Approximations).
 */
internal fun goNextSliceCap(newLen: Int, oldCap: Int): Int {
    var newcap = oldCap
    val doublecap = newcap + newcap
    if (newLen > doublecap) return newLen
    val threshold = 256
    if (oldCap < threshold) return doublecap
    while (true) {
        newcap += (newcap + 3 * threshold) shr 2
        if (newcap.toUInt() >= newLen.toUInt()) break
    }
    if (newcap <= 0) return newLen
    return newcap
}

/** `copy(dst, src)`: element value copies with memmove semantics; returns the count copied. */
fun <T> goCopy(dst: GoSlice<T>, src: GoSlice<T>): Int {
    val n = minOf(dst.len, src.len)
    if (n == 0) return 0
    val copier = dst.elem.copy
    if (copier == null) {
        src.array.copyInto(dst.array, dst.offset, src.offset, src.offset + n)
    } else {
        // Snapshot first: overlapping windows of one array copy as memmove does.
        val tmp = Array<Any?>(n) { src.load(src.offset + it) }
        for (k in 0 until n) {
            @Suppress("UNCHECKED_CAST")
            val v = tmp[k] as T
            dst.array[dst.offset + k] = if (v == null) null else copier(v)
        }
    }
    return n
}

/** `copy(dst []byte, src string)`. */
fun goCopyString(dst: GoSlice<Int>, src: String): Int {
    val n = minOf(dst.len, src.length)
    for (k in 0 until n) dst.array[dst.offset + k] = src[k].code
    return n
}

/** `append(b []byte, s...)` for a string `s`. */
fun goAppendString(dst: GoSlice<Int>, s: String): GoSlice<Int> {
    if (s.isEmpty()) return dst
    val src = arrayOfNulls<Any?>(s.length)
    for (k in s.indices) src[k] = s[k].code
    return dst.appendRaw(src, 0, src.size, copyValues = false)
}

/** `clear(s)`: every element within `len(s)` back to its zero value. */
fun <T> goClear(s: GoSlice<T>) {
    s.array.fill(null, s.offset, s.offset + s.len)
}

/**
 * A Go array `[N]T`: a value type of fixed length. Assignment copies ([goCopy]); `a[:]` ([slice])
 * returns a slice SHARING this array's storage, as in Go.
 */
class GoArray<T>(val size: Int, val elem: GoElem<T>) {

    @PublishedApi
    internal val storage: GoSlice<T> = GoSlice.make(elem, size)

    operator fun get(i: Int): T = storage[i]

    operator fun set(i: Int, v: T) {
        storage[i] = v
    }

    /** `a[low:high]`, sharing storage. */
    fun slice(low: Int = 0, high: Int = size): GoSlice<T> = storage.slice(low, high)

    /** A Go value copy (elements copied through [elem]). */
    fun goCopy(): GoArray<T> {
        val c = GoArray(size, elem)
        goCopy(c.storage, storage)
        return c
    }

    /** Go's `==` on arrays of comparable elements. */
    fun goEquals(other: GoArray<T>): Boolean {
        if (size != other.size) return false
        for (i in 0 until size) if (storage[i] != other.storage[i]) return false
        return true
    }

    fun goHash(): Int {
        var h = 1
        for (i in 0 until size) h = 31 * h + (storage[i]?.hashCode() ?: 0)
        return h
    }

}
