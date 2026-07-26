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
 * (CATCH.1) batch 4 — the `getTypeOfExpression` cluster.
 *
 * `getTypeOfExpression` holds no sentinel of its own: it is a `when` over
 * expression kinds that delegates to the guarded resolvers (`getTypeOfSymbol`'s
 * per-symbol in-progress set) and to the deliberately ITERATIVE walkers
 * (`getTypeOfBinaryExpression`'s left-spine fold). An expression tree is finite
 * and acyclic, so the only way to recurse unboundedly here is through a
 * declaration cycle — which is what these pins drive. Deep operator chains are
 * pinned separately and at greater depth by `DeepExpressionChainTest`.
 */
class DefensiveCatchRemovalBatch4Test {

    private fun List<Diagnostic>.hasNoDepthBail() = none { it.code == 2589 }

    @Test
    fun `a two-variable initializer cycle degrades instead of recursing`() {
        val diagnostics = diagnose(
            """
            var a = b;
            var b = a;
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a three-variable initializer cycle through arithmetic degrades`() {
        val diagnostics = diagnose(
            """
            var a = b + 1;
            var b = c + 1;
            var c = a + 1;
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a self-recursive function's inferred return type degrades instead of recursing`() {
        val diagnostics = diagnose(
            """
            function fact(n: number) {
                return n <= 1 ? 1 : n * fact(n - 1);
            }
            const v = fact(5);
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `mutually recursive functions with inferred return types degrade`() {
        val diagnostics = diagnose(
            """
            function even(n: number) { return n === 0 ? true : odd(n - 1); }
            function odd(n: number) { return n === 0 ? false : even(n - 1); }
            const v = even(4);
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a long property and call chain types without a depth bail`() {
        val diagnostics = diagnose(
            """
            interface Chain { next(): Chain; value: number }
            declare const c: Chain;
            const v: number = c.next().next().next().next().next().next().value;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `an object literal spreading a self-referential local degrades`() {
        val diagnostics = diagnose(
            """
            var o = { ...o, a: 1 };
            o;
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a deeply nested conditional expression types without a depth bail`() {
        val diagnostics = diagnose(
            """
            declare const p: boolean;
            const v: number = p ? 1 : p ? 2 : p ? 3 : p ? 4 : p ? 5 : p ? 6 : p ? 7 : 8;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `negative control - a genuine type mismatch in a long chain still reports TS2322`() {
        val diagnostics = diagnose(
            """
            interface Chain { next(): Chain; value: number }
            declare const c: Chain;
            const v: string = c.next().next().value;
            """,
        )
        assert(diagnostics.any { it.code == 2322 })
    }
}
