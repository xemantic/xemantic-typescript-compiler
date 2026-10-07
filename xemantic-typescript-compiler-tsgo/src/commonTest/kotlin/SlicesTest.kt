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
import com.xemantic.typescript.tsgo.go.slices.binarySearch
import com.xemantic.typescript.tsgo.go.slices.clone
import com.xemantic.typescript.tsgo.go.slices.concat
import com.xemantic.typescript.tsgo.go.slices.delete
import com.xemantic.typescript.tsgo.go.slices.deleteFunc
import com.xemantic.typescript.tsgo.go.slices.grow
import com.xemantic.typescript.tsgo.go.slices.insert
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import kotlin.test.Test

/** Expected values are the output of the equivalent Go program (go1.27.1), quoted per case. */
class SlicesTest {

    private fun ints(vararg v: Int): GoSlice<Int> = GoSlice.of(GoElem.INT, *v.toTypedArray())

    @Test
    fun `Delete works in place and zeroes the vacated tail`() {
        // Go: [1 4 5] [1 4 5 0 0] 3 5
        val s = ints(1, 2, 3, 4, 5)
        val d = delete(s, 1, 3)
        assert(d.toList() == listOf(1, 4, 5))
        assert(s.toList() == listOf(1, 4, 5, 0, 0))
        assert(d.len == 3 && d.cap == 5)
    }

    @Test
    fun `DeleteFunc compacts in place and zeroes the tail`() {
        // Go: [1 3 5] [1 3 5 0 0 0]
        val s = ints(1, 2, 3, 4, 5, 6)
        val d = deleteFunc(s) { it % 2 == 0 }
        assert(d.toList() == listOf(1, 3, 5))
        assert(s.toList() == listOf(1, 3, 5, 0, 0, 0))
    }

    @Test
    fun `Insert writes in place when capacity allows and copies otherwise`() {
        // Go: [1 8 9 2 3] [1 8 9 2 3]  then  [1 8 9 2 3] [1 2 3]
        val s3 = GoSlice.make(GoElem.INT, 3, 10)
        s3[0] = 1; s3[1] = 2; s3[2] = 3
        val i3 = insert(s3, 1, 8, 9)
        assert(i3.toList() == listOf(1, 8, 9, 2, 3))
        assert(s3.slice(0, 5).toList() == listOf(1, 8, 9, 2, 3))
        val s4 = ints(1, 2, 3)
        val i4 = insert(s4, 1, 8, 9)
        assert(i4.toList() == listOf(1, 8, 9, 2, 3))
        assert(s4.toList() == listOf(1, 2, 3))
    }

    @Test
    fun `Concat Clone and Grow keep Go's nil and capacity results`() {
        // Go: Concat(empty, nil) == nil -> true; Clone(empty) == nil -> false; Clone(nil) == nil -> true
        assert(concat(GoSlice.make(GoElem.INT, 0), GoElem.INT.nilSlice).isNil)
        assert(!clone(GoSlice.make(GoElem.INT, 0)).isNil)
        assert(clone(GoElem.INT.nilSlice).isNil)
        // Go: len 1, cap >= 6
        val g = grow(ints(1), 5)
        assert(g.len == 1 && g.cap >= 6)
        // Go: 1 true
        val (idx, found) = binarySearch(ints(1, 3, 3, 5), 3)
        assert(idx == 1 && found)
    }

}
