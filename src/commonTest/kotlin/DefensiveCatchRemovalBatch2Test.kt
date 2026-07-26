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
 * (CATCH.1) batch 2 — the `getTypeOfSymbol` / `resolveStructuredTypeMembers`
 * cluster.
 *
 * Unlike batch 1's dispatchers these two ARE deep resolvers, but each already
 * carries the guard its defensive call-site catches were nominally standing in
 * for: `getTypeOfSymbol` has a per-symbol in-progress sentinel that degrades a
 * re-entrant resolution to `anyType`, and `resolveStructuredTypeMembersCore` has
 * a cycle guard for heritage that re-enters before the member table is planted.
 * These pins drive the shapes those guards exist for and assert the sharp
 * signal: the compile completes and never reports TS2589, the marker the `init`
 * boundary guard turns an escaped stack overflow into.
 */
class DefensiveCatchRemovalBatch2Test {

    private fun List<Diagnostic>.hasNoDepthBail() = none { it.code == 2589 }

    @Test
    fun `mutually recursive interface heritage resolves its members`() {
        val diagnostics = diagnose(
            """
            interface A extends B { a: number }
            interface B extends A { b: string }
            declare const x: A;
            const n: number = x.a;
            const s: string = x.b;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a self-referential initializer cycle degrades instead of recursing`() {
        val diagnostics = diagnose(
            """
            declare const cond: boolean;
            var x = cond ? y : 0;
            var y = x;
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a directly recursive interface member resolves`() {
        val diagnostics = diagnose(
            """
            interface Tree { left: Tree; right: Tree; value: number }
            declare const t: Tree;
            const v: number = t.left.right.left.value;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a recursive generic interface resolves its instantiated members`() {
        val diagnostics = diagnose(
            """
            interface Box<T> { value: T; nested: Box<Box<T>> }
            declare const b: Box<number>;
            const v: number = b.value;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a mutually recursive class heritage pair does not blow the member walk`() {
        val diagnostics = diagnose(
            """
            class A extends B { a = 1; }
            class B extends A { b = "x"; }
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a circular type alias resolves without a depth bail`() {
        val diagnostics = diagnose(
            """
            type A = B;
            type B = A;
            declare const a: A;
            a;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a recursive object type relates against itself`() {
        val diagnostics = diagnose(
            """
            interface Node1 { next: Node1; kind: "a" }
            interface Node2 { next: Node2; kind: "a" }
            declare const n1: Node1;
            const n2: Node2 = n1;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `negative control - a genuinely absent member of a recursive interface still reports TS2339`() {
        val diagnostics = diagnose(
            """
            interface Tree { left: Tree; value: number }
            declare const t: Tree;
            const v = t.missing;
            """,
        )
        assert(diagnostics.any { it.code == 2339 })
    }
}
