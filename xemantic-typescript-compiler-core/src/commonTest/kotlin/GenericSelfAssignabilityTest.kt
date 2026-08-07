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
 * A generic reference must relate to ITSELF, and to a union that CONTAINS it.
 *
 * Round 721, burning down (LIB.1)'s remaining real-lib false positives. Three of the
 * eight TS2322 have a giveaway shape — the reported source and target are the same
 * text:
 *
 *   parser.ts:3583      Type 'NodeArray<T>' is not assignable to type 'NodeArray<T>'.
 *   watchPublic.ts:371  Type 'WatchCompilerHostOfFilesAndCompilerOptions<T>' is not
 *                       assignable to type 'WatchCompilerHostOfFilesAndCompilerOptions<T>
 *                       | WatchCompilerHostOfConfigFile<T>'.
 *
 * In both the source is the target (or is literally a member of the target union) and
 * a TYPE PARAMETER is involved, which is the suspicious part: two resolutions of
 * `Ref<T>` in different positions may mint different instances for `T`, so identity
 * fails and the structural fallback then has to get everything right.
 *
 * Each case carries a CONTROL that must still error, so a probe that has simply gone
 * blind fails loudly instead of reading as a fix.
 */
class GenericSelfAssignabilityTest {

    private val prelude = """
        interface Node { kind: number }
        interface Box<T> { value: T; all: T[] }
        interface Other<T> { different: T }

    """.trimIndent()

    @Test
    fun `a generic reference is assignable to itself`() {
        val diagnostics = diagnose(
            prelude + """
                function f<T>(x: Box<T>): Box<T> {
                    return x
                }
            """
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `a constrained generic reference is assignable to itself`() {
        val diagnostics = diagnose(
            prelude + """
                function f<T extends Node>(x: Box<T>): Box<T> {
                    return x
                }
            """
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `a generic reference is assignable to a union that contains it`() {
        val diagnostics = diagnose(
            prelude + """
                function g<T extends Node>(x: Box<T>): Box<T> | Other<T> {
                    return x
                }
            """
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `a generic reference returned through a local is assignable to itself`() {
        val diagnostics = diagnose(
            prelude + """
                declare function make<T extends Node>(): Box<T>
                function h<T extends Node>(): Box<T> {
                    const result = make<T>()
                    return result
                }
            """
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `control - an unrelated generic reference is still rejected`() {
        val diagnostics = diagnose(
            prelude + """
                function bad<T extends Node>(x: Other<T>): Box<T> {
                    return x
                }
            """
        )
        // If this goes quiet the cases above prove nothing — the probe would simply
        // be unable to see TS2322 in a return position.
        assert(diagnostics.any { it.code == 2322 })
    }
}
