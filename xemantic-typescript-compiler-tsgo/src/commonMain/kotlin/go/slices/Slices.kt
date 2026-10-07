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

package com.xemantic.typescript.tsgo.go.slices

import com.xemantic.typescript.tsgo.go.cmp.compare as cmpCompare
import com.xemantic.typescript.tsgo.go.cmp.less as cmpLess
import com.xemantic.typescript.tsgo.go.iter.Seq
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goClear
import com.xemantic.typescript.tsgo.runtime.goCopy
import com.xemantic.typescript.tsgo.runtime.goEq
import com.xemantic.typescript.tsgo.runtime.goPanic

// Go's `slices` package over GoSlice, with Go's aliasing (in-place edits share the backing
// array) and Go's nil/non-nil results.

/** `slices.Clone(s)`: nil stays nil, otherwise a fresh array of value copies. */
fun <T> clone(s: GoSlice<T>): GoSlice<T> {
    if (s.isNil) return s
    return GoSlice.make(s.elem, 0, 0).appendSlice(s)
}

/** `slices.Clip(s)`. */
fun <T> clip(s: GoSlice<T>): GoSlice<T> = s.slice3(0, s.len, s.len)

/** `slices.Grow(s, n)`. */
fun <T> grow(s: GoSlice<T>, n0: Int): GoSlice<T> {
    if (n0 < 0) goPanic("cannot be negative")
    val n = n0 - (s.cap - s.len)
    if (n > 0) {
        val full = s.slice(0, s.cap)
        return full.appendSlice(GoSlice.make(s.elem, n)).slice(0, s.len)
    }
    return s
}

/** `slices.Concat(slices...)`: nil when the total length is 0. */
fun <T> concat(vararg slices: GoSlice<T>): GoSlice<T> {
    var size = 0
    for (s in slices) {
        size += s.len
        if (size < 0) goPanic("len out of range")
    }
    if (slices.isEmpty()) TODO("shim: slices.Concat() with no arguments needs the element kind")
    var out = grow(slices[0].elem.nilSlice, size)
    for (s in slices) out = out.appendSlice(s)
    return out
}

/** `slices.Contains(s, v)`. */
fun <T> contains(s: GoSlice<T>, v: T): Boolean = index(s, v) >= 0

/** `slices.ContainsFunc(s, f)`. */
fun <T> containsFunc(s: GoSlice<T>, f: (T) -> Boolean): Boolean = indexFunc(s, f) >= 0

/** `slices.Index(s, v)`. */
fun <T> index(s: GoSlice<T>, v: T): Int {
    for (i in 0 until s.len) if (goEq(v, s[i])) return i
    return -1
}

/** `slices.IndexFunc(s, f)`. */
fun <T> indexFunc(s: GoSlice<T>, f: (T) -> Boolean): Int {
    for (i in 0 until s.len) if (f(s[i])) return i
    return -1
}

/** `slices.Equal(s1, s2)` (float elements with Go's `==`: NaN unequal, -0 == +0). */
fun <T> equal(s1: GoSlice<T>, s2: GoSlice<T>): Boolean {
    if (s1.len != s2.len) return false
    for (i in 0 until s1.len) if (!goEq(s1[i], s2[i])) return false
    return true
}

/** `slices.EqualFunc(s1, s2, eq)`. */
fun <A, B> equalFunc(s1: GoSlice<A>, s2: GoSlice<B>, eq: (A, B) -> Boolean): Boolean {
    if (s1.len != s2.len) return false
    for (i in 0 until s1.len) if (!eq(s1[i], s2[i])) return false
    return true
}

/** `slices.Compare(s1, s2)`. */
fun <T : Comparable<T>> compare(s1: GoSlice<T>, s2: GoSlice<T>): Int {
    for (i in 0 until s1.len) {
        if (i >= s2.len) return 1
        val c = cmpCompare(s1[i], s2[i])
        if (c != 0) return c
    }
    return if (s1.len < s2.len) -1 else 0
}

/** `slices.CompareFunc(s1, s2, cmp)`. */
fun <A, B> compareFunc(s1: GoSlice<A>, s2: GoSlice<B>, cmp: (A, B) -> Int): Int {
    for (i in 0 until s1.len) {
        if (i >= s2.len) return 1
        val c = cmp(s1[i], s2[i])
        if (c != 0) return c
    }
    return if (s1.len < s2.len) -1 else 0
}

/** `slices.Delete(s, i, j)`: in place; the vacated tail is zeroed. */
fun <T> delete(s: GoSlice<T>, i: Int, j: Int): GoSlice<T> {
    s.slice3(i, j, s.len) // bounds check
    if (i == j) return s
    val oldLen = s.len
    val r = s.slice(0, i).appendSlice(s.slice(j))
    goClear(r.slice(r.len, oldLen))
    return r
}

/** `slices.DeleteFunc(s, del)`. */
fun <T> deleteFunc(s: GoSlice<T>, del: (T) -> Boolean): GoSlice<T> {
    var i = indexFunc(s, del)
    if (i == -1) return s
    for (j in i + 1 until s.len) {
        val v = s[j]
        if (!del(v)) {
            s[i] = v
            i++
        }
    }
    goClear(s.slice(i))
    return s.slice(0, i)
}

/** `slices.Insert(s, i, v...)`. */
fun <T> insert(s: GoSlice<T>, i: Int, vararg v: T): GoSlice<T> {
    s.slice(i) // bounds check
    val m = v.size
    if (m == 0) return s
    val n = s.len
    val vs = GoSlice.of(s.elem, *v)
    if (i == n) return s.appendSlice(vs)
    if (n + m > s.cap) {
        val s2 = s.slice(0, i).appendSlice(GoSlice.make(s.elem, n + m - i))
        goCopy(s2.slice(i), vs)
        goCopy(s2.slice(i + m), s.slice(i))
        return s2
    }
    val t = s.slice(0, n + m)
    goCopy(t.slice(i + m), s.slice(i, n))
    goCopy(t.slice(i), vs)
    return t
}

/** `slices.Reverse(s)`. */
fun <T> reverse(s: GoSlice<T>) {
    var i = 0
    var j = s.len - 1
    while (i < j) {
        val t = s[i]
        s[i] = s[j]
        s[j] = t
        i++
        j--
    }
}

/** `slices.Sort(x)`: Go's pdqsort with `cmp.Less` (NaNs first). Not stable — Go's exact order. */
fun <T : Comparable<T>> sort(x: GoSlice<T>) {
    for (i in 0 until x.len) x[i] // materialize implicit zeros
    Pdq<T>(x.array, x.offset) { a, b -> cmpLess(a, b) }.sort(x.len)
}

/** `slices.SortFunc(x, cmp)`: Go's pdqsort. Not stable — Go's exact order. */
fun <T> sortFunc(x: GoSlice<T>, cmp: (T, T) -> Int) {
    for (i in 0 until x.len) x[i]
    Pdq<T>(x.array, x.offset) { a, b -> cmp(a, b) < 0 }.sort(x.len)
}

/**
 * `slices.SortStableFunc(x, cmp)`. Any stable sort yields the same order for a consistent
 * comparator, so this uses Kotlin's (stable) sort rather than Go's insertion+symMerge.
 */
fun <T> sortStableFunc(x: GoSlice<T>, cmp: (T, T) -> Int) {
    val list = x.toList().sortedWith { a, b -> cmp(a, b) }
    for (i in list.indices) x[i] = list[i]
}

/** `slices.IsSorted(x)`. */
fun <T : Comparable<T>> isSorted(x: GoSlice<T>): Boolean {
    for (i in x.len - 1 downTo 1) if (cmpLess(x[i], x[i - 1])) return false
    return true
}

/** `slices.BinarySearch(x, target)` → (index, found). */
fun <T : Comparable<T>> binarySearch(x: GoSlice<T>, target: T): Tuple2<Int, Boolean> =
    binarySearchFunc(x, target) { a, b -> cmpCompare(a, b) }

/** `slices.BinarySearchFunc(x, target, cmp)` → (index, found). */
fun <E, T> binarySearchFunc(x: GoSlice<E>, target: T, cmp: (E, T) -> Int): Tuple2<Int, Boolean> {
    val n = x.len
    var i = 0
    var j = n
    while (i < j) {
        val h = ((i + j).toUInt() shr 1).toInt()
        if (cmp(x[h], target) < 0) i = h + 1 else j = h
    }
    return Tuple2(i, i < n && cmp(x[i], target) == 0)
}

/** `slices.Values(s)`. */
fun <T> values(s: GoSlice<T>): Seq<T> = { yield ->
    for (i in 0 until s.len) if (!yield(s[i])) break
}

/** `slices.All(s)`. */
fun <T> all(s: GoSlice<T>): com.xemantic.typescript.tsgo.go.iter.Seq2<Int, T> = { yield ->
    for (i in 0 until s.len) if (!yield(i, s[i])) break
}

/** `slices.AppendSeq(s, seq)`. */
fun <T> appendSeq(s: GoSlice<T>, seq: Seq<T>): GoSlice<T> {
    var out = s
    seq { v ->
        out = out.append1(v)
        true
    }
    return out
}

/** `slices.Collect(seq)` (the element kind is needed for the result). */
fun <T> collect(elem: com.xemantic.typescript.tsgo.runtime.GoElem<T>, seq: Seq<T>): GoSlice<T> =
    appendSeq(elem.nilSlice, seq)
