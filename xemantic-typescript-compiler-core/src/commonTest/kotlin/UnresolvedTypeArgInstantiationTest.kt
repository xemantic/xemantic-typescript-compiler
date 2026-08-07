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
 * A type argument we cannot resolve must NOT degrade the whole reference to the
 * RAW UNINSTANTIATED generic.
 *
 * Round 726, burning down (LIB.1)'s real-lib false positives. `getTypeFromTypeReference`
 * used to instantiate `Iface<A>` into a `Type.Reference` only when EVERY type argument
 * resolved, and otherwise fell through to `getDeclaredTypeOfSymbol(Iface)` — the OPEN
 * generic, which carries its OWN type parameter and is a different type from any
 * instantiation of it. Nothing in the relation engine relates a `Type.Reference` to
 * that, so every comparison against such an annotation reported a false TS2322.
 *
 * On the real libs the unresolved argument was a function's own type parameter (a
 * return annotation is resolved with no `currentTypeParamScope` installed, so its `T`
 * came back as errorType), which is what produced the giveaway
 * `Type 'NodeArray<T>' is not assignable to type 'NodeArray<T>'` — identical text,
 * because the display renders the ANNOTATION while the comparison used the raw generic.
 *
 * WHAT MAKES THE SHAPE VISIBLE: the degradation only shows up once the interface has
 * members that mention the type parameter through a GENERIC BASE (`extends
 * ReadonlyArray<T>` — real `NodeArray`'s shape). A flat `interface Box<T> { v: T }`
 * relates to its own raw form anyway, so a probe built on one is silent BEFORE and
 * AFTER the fix and proves nothing.
 *
 * Every target carries controls that must still error, so a probe that has simply gone
 * blind fails loudly instead of reading as a fix.
 */
class UnresolvedTypeArgInstantiationTest {

    private val prelude = """
        interface Nd { kind: number }
        interface Listish<T> extends ReadonlyArray<T> { pos: number }
        interface ListishC<T extends Nd> extends ReadonlyArray<T> { pos: number }
        declare const nums: Listish<number>;
        declare const strs: Listish<string>;
        declare const nds: ListishC<Nd>;

    """.trimIndent()

    @Test
    fun `an unresolvable type argument does not make the reference unassignable`() {
        val diagnostics = diagnose(
            prelude + """
                const t: Listish<Missing> = nums;
            """
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `an unresolvable type argument of a constrained generic keeps it assignable`() {
        val diagnostics = diagnose(
            prelude + """
                const t: ListishC<Missing> = nds;
            """
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `an unresolvable type argument is still reported as an unknown name`() {
        val diagnostics = diagnose(
            prelude + """
                const t: Listish<Missing> = nums;
            """
        )
        assert(diagnostics.any { it.code == 2304 })
    }

    @Test
    fun `negative control - a resolvable but wrong type argument still errors`() {
        val diagnostics = diagnose(
            prelude + """
                const t: Listish<number> = strs;
            """
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `negative control - an unresolvable type argument does not mask an unrelated source`() {
        val diagnostics = diagnose(
            prelude + """
                const t: Listish<Missing> = 5;
            """
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `negative control - an unresolvable type argument does not mask missing members`() {
        val diagnostics = diagnose(
            prelude + """
                declare const partial: { pos: number };
                const t: Listish<Missing> = partial;
            """
        )
        assert(diagnostics.any { it.code == 2739 || it.code == 2740 })
    }

    @Test
    fun `negative control - a fully resolvable matching instantiation stays silent`() {
        val diagnostics = diagnose(
            prelude + """
                const t: Listish<number> = nums;
            """
        )
        assert(diagnostics.none { it.code == 2322 })
    }
}
