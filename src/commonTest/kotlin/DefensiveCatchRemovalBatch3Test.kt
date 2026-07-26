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
 * (CATCH.1) batch 3 — the `getTypeFromTypeNode` cluster.
 *
 * This one differs from batches 1 and 2: `getTypeFromTypeNodeCore`'s in-progress
 * sentinel (B202.2) covers only the CACHEABLE path. A resolution under an active
 * `currentTypeParamScope`, a non-empty `inferenceNamespaceStack`, or
 * `currentTypeAliasArgs` deliberately bypasses the cache and therefore the
 * sentinel too, so the cycle protection there is the alias-substitution depth
 * bail instead. These pins drive the bypassing contexts specifically.
 *
 * Note the sharp signal is NOT uniformly "no TS2589" here — a genuinely
 * infinitely-expanding alias is SUPPOSED to bail with it, which the last pin
 * holds in place.
 */
class DefensiveCatchRemovalBatch3Test {

    private fun List<Diagnostic>.hasNoDepthBail() = none { it.code == 2589 }

    @Test
    fun `a recursive generic alias resolves in an annotation position`() {
        // `currentTypeAliasArgs` is non-null while the body resolves, so this
        // bypasses the node cache and its in-progress sentinel.
        val diagnostics = diagnose(
            """
            type List<T> = { head: T; tail: List<T> | null };
            declare const l: List<number>;
            const n: number = l.head;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a mutually recursive generic alias pair resolves`() {
        val diagnostics = diagnose(
            """
            type A<T> = { a: T; b: B<T> | null };
            type B<T> = { b: T; a: A<T> | null };
            declare const x: A<string>;
            const s: string = x.a;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a generic interface member annotation resolves under the enclosing type-param scope`() {
        val diagnostics = diagnose(
            """
            interface Box<T> { value: T; map<U>(f: (t: T) => U): Box<U> }
            declare const b: Box<number>;
            const m: Box<string> = b.map(n => String(n));
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a namespace-local type reference resolves through the inference-namespace stack`() {
        val diagnostics = diagnose(
            """
            namespace M {
                export interface Base { a: number }
                export interface Derived extends Base { b: string }
                export declare const d: Derived;
            }
            const n: number = M.d.a;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `a conditional type with infer resolves in an annotation`() {
        val diagnostics = diagnose(
            """
            type Unwrap<T> = T extends Array<infer E> ? E : T;
            declare const u: Unwrap<number[]>;
            const n: number = u;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
    }

    @Test
    fun `a self-referential non-generic alias degrades instead of recursing`() {
        val diagnostics = diagnose(
            """
            type Loop = { next: Loop };
            declare const l: Loop;
            const x: Loop = l.next.next;
            """,
        )
        assert(diagnostics.hasNoDepthBail())
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `negative control - an infinitely expanding alias still bails with TS2589`() {
        // The alias-substitution DEPTH bail is the cycle protection on the
        // cache-bypassing path; this pin keeps it firing.
        val diagnostics = diagnose(
            """
            type Nest<T, K extends string> = T | { [P in K]: Nest<T, K> }[K];
            declare const n: Nest<number, "a">;
            n;
            """,
        )
        assert(diagnostics.any { it.code == 2589 })
    }
}
