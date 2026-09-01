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
 * (INV.0) step 3 — the pure half of the instantiation seam, pinned without a
 * checker: the positional [TypeMapper] every instantiator is driven by. The
 * instantiators themselves are graded by the corpus (a relocation's invariant is
 * "nothing changed", which ~13k baselines pin better than any hand-written case),
 * exactly as step 2 recorded.
 */
class TypeInstantiatorTest {

    @Test
    fun `the positional mapper maps by index and by identity`() {
        val t = Type.TypeParam()
        val u = Type.TypeParam()
        val mapper = createTypeMapper(listOf(t, u), listOf(stringType, numberType))
        val mappedT = mapper.map(t)
        val mappedU = mapper.map(u)
        assert(mappedT === stringType)
        assert(mappedU === numberType)
    }

    @Test
    fun `a parameter past the argument list or outside it answers null`() {
        val t = Type.TypeParam()
        val u = Type.TypeParam()
        val other = Type.TypeParam()
        val mapper = createTypeMapper(listOf(t, u), listOf(stringType))
        val pastArgs = mapper.map(u)
        val outside = mapper.map(other)
        assert(pastArgs == null)
        assert(outside == null)
    }

    @Test
    fun `negative control - a structurally identical but distinct parameter is not mapped`() {
        // Identity, not shape: two fresh parameters with the same (absent) constraint
        // are different parameters, and only the one in the list maps.
        val t = Type.TypeParam()
        val twin = Type.TypeParam()
        val mapper = createTypeMapper(listOf(t), listOf(stringType))
        val twinMapped = mapper.map(twin)
        assert(twinMapped == null)
    }

}
