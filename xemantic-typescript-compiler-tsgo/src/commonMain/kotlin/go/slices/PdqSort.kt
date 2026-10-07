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

// A port of Go 1.27.1's pattern-defeating quicksort (src/slices/zsortanyfunc.go and sort.go:
// pdqsortCmpFunc, insertion sort, heap sort, partitioning, pattern breaking with Go's xorshift).
// `slices.Sort`/`SortFunc` are NOT stable, so for elements that compare equal only this exact
// algorithm reproduces Go's output order.

private const val INCREASING_HINT = 1
private const val DECREASING_HINT = 2
private const val UNKNOWN_HINT = 0

/** Sorts `data[0 until n]` (absolute window `[base, base + n)`) with Go's pdqsort and [less]. */
internal class Pdq<T>(private val data: Array<Any?>, private val base: Int, private val less: (T, T) -> Boolean) {

    @Suppress("UNCHECKED_CAST")
    private fun at(i: Int): T = data[base + i] as T

    private fun lt(i: Int, j: Int): Boolean = less(at(i), at(j))

    private fun swap(i: Int, j: Int) {
        val t = data[base + i]
        data[base + i] = data[base + j]
        data[base + j] = t
    }

    fun sort(n: Int) {
        pdqsort(0, n, 64 - n.toULong().countLeadingZeroBits())
    }

    private fun insertionSort(a: Int, b: Int) {
        for (i in a + 1 until b) {
            var j = i
            while (j > a && lt(j, j - 1)) {
                swap(j, j - 1)
                j--
            }
        }
    }

    private fun siftDown(lo: Int, hi: Int, first: Int) {
        var root = lo
        while (true) {
            var child = 2 * root + 1
            if (child >= hi) break
            if (child + 1 < hi && lt(first + child, first + child + 1)) child++
            if (!lt(first + root, first + child)) return
            swap(first + root, first + child)
            root = child
        }
    }

    private fun heapSort(a: Int, b: Int) {
        val first = a
        val lo = 0
        val hi = b - a
        for (i in (hi - 1) / 2 downTo 0) siftDown(i, hi, first)
        for (i in hi - 1 downTo 0) {
            swap(first, first + i)
            siftDown(lo, i, first)
        }
    }

    private fun pdqsort(a0: Int, b0: Int, limit0: Int) {
        var a = a0
        var b = b0
        var limit = limit0
        val maxInsertion = 12
        var wasBalanced = true
        var wasPartitioned = true
        while (true) {
            val length = b - a
            if (length <= maxInsertion) {
                insertionSort(a, b)
                return
            }
            if (limit == 0) {
                heapSort(a, b)
                return
            }
            if (!wasBalanced) {
                breakPatterns(a, b)
                limit--
            }
            var (pivot, hint) = choosePivot(a, b)
            if (hint == DECREASING_HINT) {
                reverseRange(a, b)
                pivot = (b - 1) - (pivot - a)
                hint = INCREASING_HINT
            }
            if (wasBalanced && wasPartitioned && hint == INCREASING_HINT) {
                if (partialInsertionSort(a, b)) return
            }
            if (a > 0 && !lt(a - 1, pivot)) {
                a = partitionEqual(a, b, pivot)
                continue
            }
            val (mid, alreadyPartitioned) = partition(a, b, pivot)
            wasPartitioned = alreadyPartitioned
            val leftLen = mid - a
            val rightLen = b - mid
            val balanceThreshold = length / 8
            if (leftLen < rightLen) {
                wasBalanced = leftLen >= balanceThreshold
                pdqsort(a, mid, limit)
                a = mid + 1
            } else {
                wasBalanced = rightLen >= balanceThreshold
                pdqsort(mid + 1, b, limit)
                b = mid
            }
        }
    }

    private fun partition(a: Int, b: Int, pivot: Int): Pair<Int, Boolean> {
        swap(a, pivot)
        var i = a + 1
        var j = b - 1
        while (i <= j && lt(i, a)) i++
        while (i <= j && !lt(j, a)) j--
        if (i > j) {
            swap(j, a)
            return j to true
        }
        swap(i, j)
        i++
        j--
        while (true) {
            while (i <= j && lt(i, a)) i++
            while (i <= j && !lt(j, a)) j--
            if (i > j) break
            swap(i, j)
            i++
            j--
        }
        swap(j, a)
        return j to false
    }

    private fun partitionEqual(a: Int, b: Int, pivot: Int): Int {
        swap(a, pivot)
        var i = a + 1
        var j = b - 1
        while (true) {
            while (i <= j && !lt(a, i)) i++
            while (i <= j && lt(a, j)) j--
            if (i > j) break
            swap(i, j)
            i++
            j--
        }
        return i
    }

    private fun partialInsertionSort(a: Int, b: Int): Boolean {
        val maxSteps = 5
        val shortestShifting = 50
        var i = a + 1
        repeat(maxSteps) {
            while (i < b && !lt(i, i - 1)) i++
            if (i == b) return true
            if (b - a < shortestShifting) return false
            swap(i, i - 1)
            if (i - a >= 2) {
                var j = i - 1
                while (j >= 1) {
                    if (!lt(j, j - 1)) break
                    swap(j, j - 1)
                    j--
                }
            }
            if (b - i >= 2) {
                var j = i + 1
                while (j < b) {
                    if (!lt(j, j - 1)) break
                    swap(j, j - 1)
                    j++
                }
            }
        }
        return false
    }

    private fun breakPatterns(a: Int, b: Int) {
        val length = b - a
        if (length >= 8) {
            var random = length.toULong()
            val modulus = 1uL shl (64 - length.toULong().countLeadingZeroBits())
            var idx = a + (length / 4) * 2 - 1
            while (idx <= a + (length / 4) * 2 + 1) {
                random = random xor (random shl 13)
                random = random xor (random shr 7)
                random = random xor (random shl 17)
                var other = (random and (modulus - 1uL)).toInt()
                if (other >= length) other -= length
                swap(idx, a + other)
                idx++
            }
        }
    }

    private fun choosePivot(a: Int, b: Int): Pair<Int, Int> {
        val shortestNinther = 50
        val maxSwaps = 4 * 3
        val l = b - a
        val swaps = IntArray(1)
        var i = a + l / 4 * 1
        var j = a + l / 4 * 2
        var k = a + l / 4 * 3
        if (l >= 8) {
            if (l >= shortestNinther) {
                i = medianAdjacent(i, swaps)
                j = medianAdjacent(j, swaps)
                k = medianAdjacent(k, swaps)
            }
            j = median(i, j, k, swaps)
        }
        return when (swaps[0]) {
            0 -> j to INCREASING_HINT
            maxSwaps -> j to DECREASING_HINT
            else -> j to UNKNOWN_HINT
        }
    }

    // Go's order2 returns the index pair swapped when data[b] < data[a]; only the middle index
    // survives, so the indices that are never read again are not tracked.
    private fun median(a0: Int, b0: Int, c0: Int, swaps: IntArray): Int {
        var a = a0
        var b = b0
        if (lt(b, a)) { swaps[0]++; val t = a; a = b; b = t }
        if (lt(c0, b)) { swaps[0]++; b = c0 }
        if (lt(b, a)) { swaps[0]++; b = a }
        return b
    }

    private fun medianAdjacent(a: Int, swaps: IntArray): Int = median(a - 1, a, a + 1, swaps)

    private fun reverseRange(a: Int, b: Int) {
        var i = a
        var j = b - 1
        while (i < j) {
            swap(i, j)
            i++
            j--
        }
    }
}
