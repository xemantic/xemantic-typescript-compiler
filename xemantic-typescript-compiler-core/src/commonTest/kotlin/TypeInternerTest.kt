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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (INV.0) Pins for the extracted [TypeInterner]: identity semantics only —
 * normalization (flattening, `never` removal, the flags sort) stays with the
 * callers and is pinned by the whole corpus. Identity comparisons are safe in
 * power-assert here because [Type] variants are plain classes whose
 * `toString` is shallow (round 471's deep-`hashCode` hazard is about AST data
 * classes, not types).
 */
class TypeInternerTest {

    private val string = Type.Intrinsic(TypeFlags.String, "string")
    private val number = Type.Intrinsic(TypeFlags.Number, "number")
    private val boolean = Type.Intrinsic(TypeFlags.Boolean, "boolean")

    @Test
    fun `a reference interns one instance per distinct instantiation`() {
        val interner = TypeInterner()
        val target = Type.Interface()
        val one = interner.reference(target, listOf(string))
        val again = interner.reference(target, listOf(string))
        val other = interner.reference(target, listOf(number))
        assert(one === again)
        assert(one !== other)
        assert(one.target === target)
    }

    @Test
    fun `null args and an empty list deliberately share one instance`() {
        // The historical string key built "id|" for BOTH shapes; the packed key
        // reproduces that conflation on purpose - first toucher wins.
        val interner = TypeInterner()
        val target = Type.Interface()
        val bare = interner.reference(target, null)
        val empty = interner.reference(target, emptyList())
        assert(bare === empty)
    }

    @Test
    fun `a wide reference keeps argument order in its identity`() {
        // Two-plus arguments take the string-key path; order is identity.
        val interner = TypeInterner()
        val target = Type.Interface()
        val forward = interner.reference(target, listOf(string, number))
        val forwardAgain = interner.reference(target, listOf(string, number))
        val backward = interner.reference(target, listOf(number, string))
        assert(forward === forwardAgain)
        assert(forward !== backward)
    }

    @Test
    fun `unions intern by ordered member identity on both key paths`() {
        val interner = TypeInterner()
        val packed = interner.union(listOf(string, number))
        val packedAgain = interner.union(listOf(string, number))
        val reversed = interner.union(listOf(number, string))
        val wide = interner.union(listOf(string, number, boolean))
        val wideAgain = interner.union(listOf(string, number, boolean))
        assert(packed === packedAgain)
        assert(packed !== reversed)
        assert(wide === wideAgain)
    }

    @Test
    fun `intersections intern by ordered member identity on both key paths`() {
        val interner = TypeInterner()
        val packed = interner.intersection(listOf(string, number))
        val packedAgain = interner.intersection(listOf(string, number))
        val wide = interner.intersection(listOf(string, number, boolean))
        val wideAgain = interner.intersection(listOf(string, number, boolean))
        assert(packed === packedAgain)
        assert(wide === wideAgain)
    }

    @Test
    fun `two interners share nothing`() {
        // The caches must die with their checker - Type.ids are a per-checker
        // per-thread sequence (INV.6(6c0)), so a process-global interner would
        // conflate types across checkers by id collision.
        val target = Type.Interface()
        val a = TypeInterner().reference(target, listOf(string))
        val b = TypeInterner().reference(target, listOf(string))
        assert(a !== b)
    }

}
