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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 463: the lazy recursive-alias cycle-break (B57-era, previously gated to
 * TypeLiteral alias bodies) extends to UNION bodies — tsc's
 * `WrappedExpression<T> = OuterExpression & { expression: WrappedExpression<T> }
 * | T` is a recursive generic UNION alias whose eager instantiation expanded to
 * the depth-10 bail → spurious TS2589 "Type instantiation is excessively deep"
 * (utilities.ts:5553, the NamedEvaluation alias). tsc resolves the recursive
 * position lazily and never errors on this shape.
 */
class RecursiveUnionAliasTest {

    @Test
    fun `a recursive generic union alias instantiates without TS2589`() {
        diagnose("""
            interface Expression { expr: number }
            interface ParenthesizedExpression extends Expression { inner: string }
            type WrappedExpression<T extends Expression> =
                | ParenthesizedExpression & { readonly expression: WrappedExpression<T>; }
                | T;
            interface ArrowFunction extends Expression { arrow: true }
            type AnonymousFunctionDefinition = ArrowFunction;
            type NamedEvaluation =
                | { readonly initializer: WrappedExpression<AnonymousFunctionDefinition>; };
            declare const n: NamedEvaluation;
            const x = n;
        """) should {
            have(none { it.code == 2589 })
        }
    }

    @Test
    fun `negative control - a genuinely divergent indexed-access alias still hits the depth bail`() {
        diagnose("""
            type Deep<T> = { rec: Deep<{ v: T }> }["rec"];
            declare const d: Deep<number>;
        """) should {
            have(any { it.code == 2589 })
        }
    }
}
