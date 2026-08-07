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
 * (CATCH.1) batch 5 — the tail: the relation engine, the type printer, alias and
 * heritage resolution, widening, and the per-helper singletons.
 *
 * Each of these carries its own guard, which is why the call-site catches were
 * redundant: `checkTypeRelatedTo` has `relationComparisonStack` plus the
 * `isDeeplyNested` occurrence heuristic, `typeToString` has
 * `typeToStringInProgress`, `resolveAlias` a visited set,
 * `getDeclaredTypeOfSymbol` the cache-before-recurse sentinel, and
 * `resolveBaseTypesLazy` rides the member-resolution cycle guard. These pins
 * drive each of those cycles.
 */
class DefensiveCatchRemovalBatch5Test {

    private fun List<Diagnostic>.hasNoDepthBail() = none { it.code == 2589 }

    @Test
    fun `an infinitely expanding generic pair is cut by the relation's deeply-nested heuristic`() {
        // The documented shape: neither side's instantiations ever repeat
        // identically, so only the occurrence-count heuristic terminates it.
        val diagnostics = diagnose(
            """
            interface A<T> { x: A<() => T> }
            interface B<T> { x: B<() => T> }
            declare const a: A<number>;
            const b: B<number> = a;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `mutually recursive interfaces relate without a depth bail`() {
        val diagnostics = diagnose(
            """
            interface A { c: C; v: number }
            interface C { a: A; v: number }
            interface A2 { c: C2; v: number }
            interface C2 { a: A2; v: number }
            declare const a: A;
            const a2: A2 = a;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a recursive type is printed in a diagnostic without a depth bail`() {
        // Forces typeToString over a self-referential shape via a mismatch.
        val diagnostics = diagnose(
            """
            interface Loop { next: Loop; v: number }
            declare const l: Loop;
            const s: string = l;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `a circular import-equals alias chain resolves without recursing`() {
        val diagnostics = diagnose(
            """
            namespace N { export const v = 1; }
            import X = Y;
            import Y = X;
            X;
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `circular class heritage resolves its base types lazily without recursing`() {
        val diagnostics = diagnose(
            """
            class P extends Q { p = 1; }
            class Q extends P { q = 2; }
            declare const p: P;
            p;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a recursive interface's properties widen without a depth bail`() {
        val diagnostics = diagnose(
            """
            interface R { self: R; n: number }
            declare const r: R;
            const copy = { ...r, extra: 1 };
            copy;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `negative control - a real mismatch between recursive shapes still reports`() {
        val diagnostics = diagnose(
            """
            interface A { c: A; v: number }
            interface B { c: B; v: string }
            declare const a: A;
            const b: B = a;
            """,
        )
        assert(diagnostics.any { it.code == 2322 || it.code == 2739 || it.code == 2740 })
    }
}
