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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.go.slices.clone
import com.xemantic.typescript.tsgo.go.slices.insert
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoPanic
import com.xemantic.typescript.tsgo.runtime.GoSlice
import kotlin.test.Test

private class Cell(var v: Int = 0) {
    fun goCopy(): Cell = Cell(v)
}

private val CELL = GoElem({ Cell() }, { it.goCopy() })

private fun panicMessage(block: () -> Unit): String? = try {
    block()
    null
} catch (p: GoPanic) {
    p.message
}

/**
 * The owned-header operations the porter emits for an owned slice field (docs/goport-lowering.md § 3,
 * "Owned slice fields") and the array-direct `slices.Insert`/`Clone`. The invariant under test: an owned
 * operation gives the field the same Go VALUE as the plain one, never changes a header anyone else holds
 * (an earlier [GoSlice.ownedCopy], the shared nil slice), and never replaces the backing array in place.
 */
class OwnedSliceTest {

    private fun ints(vararg v: Int): GoSlice<Int> = GoSlice.of(GoElem.INT, *v.toTypedArray())

    @Test
    fun `an in-capacity owned append updates the header in place and an earlier copy keeps its length`() {
        var f = GoSlice.make(GoElem.INT, 0, 4)
        f = f.append1Owned(1)
        val before = f.ownedCopy()
        val same = f
        f = f.append1Owned(2)
        assert(f === same)
        assert(f.toList() == listOf(1, 2))
        // Go: a header value copied out before the append keeps len 1, over the same array.
        assert(before.len == 1 && before.toList() == listOf(1))
        assert(before.slice(0, 2).toList() == listOf(1, 2))
    }

    @Test
    fun `a full or nil owned header grows into a new header and leaves the old one unchanged`() {
        val nil = GoElem.INT.nilSlice
        val grown = nil.append1Owned(7)
        assert(grown !== nil && nil.isNil && nil.len == 0)
        assert(grown.toList() == listOf(7))
        val full = ints(1, 2)
        val g2 = full.append1Owned(3)
        assert(g2 !== full && full.len == 2 && g2.toList() == listOf(1, 2, 3))
        // A nil header is never resliced in place either.
        assert(nil.sliceOwned(0, 0) === nil && nil.len == 0 && nil.cap == 0)
    }

    @Test
    fun `an owned reslice moves the window and keeps the array - an element pointer still names its slot`() {
        var f = ints(10, 20, 30, 40)
        val p = f.addr(2)
        f = f.sliceOwned(1, 3)
        assert(f.toList() == listOf(20, 30) && f.cap == 3)
        p.value = 33
        assert(f[1] == 33)
        f = f.slice3Owned(0, 1, 2)
        assert(f.toList() == listOf(20) && f.cap == 2)
        // Pushing after a pop writes the popped slot again, as Go's append does.
        f = f.append1Owned(99)
        assert(f.toList() == listOf(20, 99) && p.value == 99)
    }

    @Test
    fun `owned reslice bounds panic with the messages of the plain slice expression`() {
        val f = GoSlice.make(GoElem.INT, 2, 3)
        val plain = panicMessage { f.slice(0, 4) }
        val owned = panicMessage { f.ownedCopy().sliceOwned(0, 4) }
        assert(plain != null && owned == plain)
        assert(panicMessage { f.ownedCopy().sliceOwned(2, 1) } == panicMessage { f.slice(2, 1) })
        assert(panicMessage { f.ownedCopy().slice3Owned(0, 1, 4) } == panicMessage { f.slice3(0, 1, 4) })
    }

    @Test
    fun `owned multi-append and spread append copy values and survive self-aliasing`() {
        var f = GoSlice.make(GoElem.INT, 0, 8)
        f = f.appendOwned(1, 2)
        f = f.appendSliceOwned(f)
        assert(f.toList() == listOf(1, 2, 1, 2) && f.cap == 8)
        var c = GoSlice.make(CELL, 0, 4)
        val cell = Cell(5)
        c = c.appendSliceOwned(GoSlice.of(CELL, cell))
        cell.v = 6
        assert(c[0].v == 5)
    }

    @Test
    fun `Insert keeps Go's values and copies struct elements on both paths`() {
        // Go: Insert([]int{1,2,3}, 0, 9) = [9 1 2 3]; Insert(s, 3, 9) = [1 2 3 9]
        assert(insert(ints(1, 2, 3), 0, 9).toList() == listOf(9, 1, 2, 3))
        assert(insert(ints(1, 2, 3), 3, 9).toList() == listOf(1, 2, 3, 9))
        assert(insert(GoElem.INT.nilSlice, 0, 4, 5).toList() == listOf(4, 5))
        assert(panicMessage { insert(ints(1), 2, 9) } != null)
        val a = Cell(1)
        val b = Cell(2)
        val inPlace = GoSlice.make(CELL, 0, 4).append(a, b)
        val r1 = insert(inPlace, 1, Cell(9))
        assert(r1.toList().map { it.v } == listOf(1, 9, 2))
        val full = GoSlice.of(CELL, a, b)
        val r2 = insert(full, 1, Cell(9))
        assert(r2.toList().map { it.v } == listOf(1, 9, 2) && r2.cap >= 3)
        r2[0].v = 100
        assert(a.v == 1)
    }

    @Test
    fun `Clone has capacity len and independent struct values`() {
        val a = Cell(1)
        val s = GoSlice.make(CELL, 0, 5).append(a)
        val c = clone(s)
        assert(c.len == 1 && c.cap == 1)
        c[0].v = 2
        assert(a.v == 1)
        assert(clone(ints(3, 4)).toList() == listOf(3, 4))
    }
}
