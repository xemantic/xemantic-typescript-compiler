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
 * (P18.292) STATICTP — a STATIC generic method of a GENERIC class minted its own type
 * parameters FRESH and UNCONSTRAINED in the ccet frame (B74.5 drops the class's scope for
 * a static member), so an explicit type argument naming one — `new B<D3>(…)` — related
 * an unconstrained `D3` to `B`'s constraint: a false TS2344 (zod v3 `ZodDiscriminatedUnion
 * .create`). The method's parameters now go through the fn-decl arm's interned,
 * constraint-materializing scope, and their AST nodes reach the frame too, so a method
 * type parameter SHADOWS a same-named class one. Rows measured against tsgo 7.0.2.
 */
class StaticMethodTypeParamConstraintTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private val b = "export class B<D extends string> { constructor(public d: D) {} }\n"

    @Test
    fun `a constrained static method type parameter satisfies the class constraint`() {
        val r = rows(b + """
            export class C<X> {
              static mk<D3 extends string>(d: D3) { return new B<D3>(d) }
              static ok<D3 extends "a" | "b">(d: D3) { return new B<D3>(d) }
            }
        """.trimIndent())
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a violating constraint is still TS2344`() {
        val r = rows(b + """
            export class C<X> {
              static mk<D3 extends number>(d: D3) { return new B<D3>(d as any) }
            }
        """.trimIndent())
        assert(r == listOf("3:54 TS2344 Type 'D3' does not satisfy the constraint 'string'."))
    }

    @Test
    fun `a method type parameter shadows a same-named class type parameter`() {
        val r = rows(b + """
            export class C<D> {
              static mk<D extends string>(d: D) { const b: B<D> = new B<D>(d); const n: number = b.d; return b }
            }
        """.trimIndent())
        assert(r == listOf("3:74 TS2322 Type 'D' is not assignable to type 'number'."))
    }

    @Test
    fun `a dependent constraint between two static method type parameters`() {
        val r = rows("""
            export class B<D extends string, E extends D> { constructor(public d: D, public e: E) {} }
            export class C<X> {
              static mk<P extends string, Q extends P>(p: P, q: Q) { return new B<P, Q>(p, q) }
            }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a class type parameter in a static member is still TS2302`() {
        val r = rows(b + """
            export class C<X extends string> {
              static mk(d: X) { return new B<X>(d) }
            }
        """.trimIndent())
        assert(r == listOf(
            "3:16 TS2302 Static members cannot reference class type parameters.",
            "3:34 TS2302 Static members cannot reference class type parameters.",
        ))
    }
}
