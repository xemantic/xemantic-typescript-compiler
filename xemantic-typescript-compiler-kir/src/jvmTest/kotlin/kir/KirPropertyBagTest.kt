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
import com.xemantic.typescript.compiler.kir.runtime.jsObjectOf
import kotlin.test.Test

/**
 * The property bag's contract, stated so that a change of REPRESENTATION is
 * graded rather than argued.
 *
 * `JsObject` is a `LinkedHashMap` today and has been two other things for the
 * length of one measurement each — parallel arrays promoted by size (refused,
 * +21% on a document parse) and parallel arrays promoted at the first property
 * the literal did not declare (refused, +31%; `docs/perf/kir-backend-levers.md`
 * §2). Neither attempt changed a single line here, and that is what these cases
 * are for: every one asserts something a CALLER can observe, so a representation
 * that dropped an entry, reordered one or lost a `delete` fails here and a
 * representation that is merely slower does not.
 *
 * Both construction routes are exercised deliberately, because they are not the
 * same route: an object literal lowers to [jsObjectOf] and everything else
 * reaches [JsObject.set]. A suite that built every fixture one way would
 * exercise one path and read as covering two — which is exactly what happened
 * before, and it left a whole representation untested.
 *
 * Order is the load-bearing property: `Object.keys` and `JSON.stringify` are
 * specified in terms of insertion order, so `delete` shifts rather than swaps,
 * and a deleted-then-reinserted key moves to the end.
 */
class KirPropertyBagTest {

    /** A bag built one property at a time, which is what an assignment does. */
    private fun grownBag(count: Int): JsObject {
        val bag = JsObject()
        for (i in 0 until count) bag.set("k$i", i.toDouble())
        return bag
    }

    /** A bag built in one call, which is what an object LITERAL lowers to. */
    private fun literalBag(count: Int): JsObject {
        val entries = arrayOfNulls<Any?>(count * 2)
        for (i in 0 until count) {
            entries[i * 2] = "k$i"
            entries[i * 2 + 1] = i.toDouble()
        }
        return jsObjectOf(*entries)
    }

    private fun keysOf(bag: JsObject): List<String> {
        val keys = bag.keys()
        return (0 until keys.length.toInt()).map { keys.get(it.toDouble()) as String }
    }

    private fun bothForms(count: Int): List<JsObject> =
        listOf(literalBag(count), grownBag(count))

    @Test
    fun `reads and writes agree by both construction routes, at every size`() {
        for (count in listOf(0, 1, 4, 9, 40)) {
            for (bag in bothForms(count)) {
                for (i in 0 until count) assert(bag.get("k$i") == i.toDouble())
                assert(bag.get("absent") == null)
                assert(keysOf(bag).size == count)
            }
        }
    }

    @Test
    fun `insertion order is the order the properties arrived in`() {
        for (bag in bothForms(20)) assert(keysOf(bag) == (0 until 20).map { "k$it" })
    }

    @Test
    fun `overwriting a key keeps its original position`() {
        for (bag in bothForms(5)) {
            bag.set("k1", 99.0)
            assert(bag.get("k1") == 99.0)
            assert(keysOf(bag) == listOf("k0", "k1", "k2", "k3", "k4"))
        }
    }

    @Test
    fun `a property added after the literal lands at the end`() {
        val bag = literalBag(3)
        bag.set("later", 9.0)
        assert(bag.get("later") == 9.0)
        assert(bag.get("k0") == 0.0)
        assert(keysOf(bag) == listOf("k0", "k1", "k2", "later"))
    }

    @Test
    fun `delete removes the key and closes the gap, by both routes`() {
        for (count in listOf(5, 20)) {
            for (bag in bothForms(count)) {
                assert(bag.delete("k1"))
                assert(bag.get("k1") == null)
                assert(!bag.has("k1"))
                val expected = (0 until count).map { "k$it" }.filter { it != "k1" }
                assert(keysOf(bag) == expected)
            }
        }
    }

    @Test
    fun `a deleted key reinserted moves to the end, as JavaScript specifies`() {
        for (bag in bothForms(4)) {
            bag.delete("k1")
            bag.set("k1", 7.0)
            assert(keysOf(bag) == listOf("k0", "k2", "k3", "k1"))
            assert(bag.get("k1") == 7.0)
        }
    }

    @Test
    fun `deleting every key leaves an empty bag that still accepts writes`() {
        val bag = literalBag(3)
        for (i in 0 until 3) assert(bag.delete("k$i"))
        assert(keysOf(bag).isEmpty())
        bag.set("fresh", 1.0)
        assert(keysOf(bag) == listOf("fresh"))
        assert(bag.get("fresh") == 1.0)
    }

    @Test
    fun `a key that is EQUAL but not identical resolves to the same property`() {
        // Emitted property names are constant-pool strings, so any lookup that
        // opened with an identity check would answer them. A key built at run
        // time is not interned and must still hit, by either route.
        val built = StringBuilder("ke").append("y").toString()
        val literal = jsObjectOf("key", 1.0)
        assert(literal.get(built) == 1.0)
        assert(literal.has(built))
        val grown = JsObject()
        grown.set("ke" + "y", 1.0)
        assert(grown.get(built) == 1.0)
        assert(grown.has(built))
    }

    @Test
    fun `has distinguishes an absent property from one holding undefined`() {
        for (bag in listOf(jsObjectOf("present", null), JsObject().also { it.set("present", null) })) {
            assert(bag.has("present"))
            assert(!bag.has("absent"))
            assert(bag.get("present") == null)
        }
    }

    @Test
    fun `a literal naming one property twice keeps its FIRST position and its LAST value`() {
        // What JavaScript does with `{ a: 1, b: 2, a: 3 }`, and the only reason
        // construction scans at all.
        val bag = jsObjectOf("a", 1.0, "b", 2.0, "a", 3.0)
        assert(keysOf(bag) == listOf("a", "b"))
        assert(bag.get("a") == 3.0)
        assert(bag.get("b") == 2.0)
    }

}
