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

package com.xemantic.typescript.compiler.kir

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.runtime.JsObject
import kotlin.test.Test

/**
 * The property bag's contract, ACROSS the representation boundary.
 *
 * `JsObject` holds a small bag in parallel arrays and promotes to a hash map on
 * its ninth key. That is a cost change, so every one of these asserts something
 * a caller can observe, and each is run on BOTH sides of the boundary — because
 * a promotion that dropped an entry, reordered one, or lost a `delete` would be
 * invisible to a test that never crossed it.
 *
 * Order is the load-bearing one: `Object.keys` and `JSON.stringify` are
 * specified in terms of insertion order, so both representations preserve it,
 * `delete` shifts rather than swaps, and a deleted-then-reinserted key moves to
 * the end.
 */
class KirPropertyBagTest {

    private fun bagOf(count: Int): JsObject {
        val bag = JsObject()
        for (i in 0 until count) bag.set("k$i", i.toDouble())
        return bag
    }

    private fun keysOf(bag: JsObject): List<String> {
        val keys = bag.keys()
        return (0 until keys.length.toInt()).map { keys.get(it.toDouble()) as String }
    }

    @Test
    fun `reads and writes agree below, at and above the promotion boundary`() {
        for (count in listOf(0, 1, 8, 9, 40)) {
            val bag = bagOf(count)
            for (i in 0 until count) assert(bag.get("k$i") == i.toDouble())
            assert(bag.get("absent") == null)
            assert(keysOf(bag).size == count)
        }
    }

    @Test
    fun `insertion order survives promotion`() {
        val bag = bagOf(20)
        assert(keysOf(bag) == (0 until 20).map { "k$it" })
    }

    @Test
    fun `overwriting a key keeps its original position`() {
        val bag = bagOf(5)
        bag.set("k1", 99.0)
        assert(bag.get("k1") == 99.0)
        assert(keysOf(bag) == listOf("k0", "k1", "k2", "k3", "k4"))
    }

    @Test
    fun `delete removes the key and closes the gap, on both sides of the boundary`() {
        for (count in listOf(5, 20)) {
            val bag = bagOf(count)
            assert(bag.delete("k1"))
            assert(bag.get("k1") == null)
            assert(!bag.has("k1"))
            val expected = (0 until count).map { "k$it" }.filter { it != "k1" }
            assert(keysOf(bag) == expected)
        }
    }

    @Test
    fun `a deleted key reinserted moves to the end, as JavaScript specifies`() {
        val bag = bagOf(4)
        bag.delete("k1")
        bag.set("k1", 7.0)
        assert(keysOf(bag) == listOf("k0", "k2", "k3", "k1"))
        assert(bag.get("k1") == 7.0)
    }

    @Test
    fun `a key that is EQUAL but not identical resolves to the same property`() {
        // The scan compares by identity first because emitted property names are
        // interned literals; a key built at run time is not, and must still hit.
        val bag = JsObject()
        bag.set("ke" + "y", 1.0)
        val built = StringBuilder("ke").append("y").toString()
        assert(bag.get(built) == 1.0)
        assert(bag.has(built))
    }

    @Test
    fun `has distinguishes an absent property from one holding undefined`() {
        val bag = JsObject()
        bag.set("present", null)
        assert(bag.has("present"))
        assert(!bag.has("absent"))
        assert(bag.get("present") == null)
    }

}
