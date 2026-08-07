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
 * A type reference that OMITS the type arguments of a generic whose every parameter
 * carries a DEFAULT denotes the DEFAULTED INSTANTIATION, not the raw open generic.
 *
 * Found round 754 while closing (PERF.HW.a). `getTypeFromTypeReference` built a
 * `Type.Reference` only when the reference SUPPLIED type arguments; a bare
 * `EvaluatorResult` fell through to the raw `Type.Interface`, whose `value` member
 * still has the un-substituted type `T`. So `EvaluatorResult<number>` did not relate
 * to a bare-`EvaluatorResult` annotation and TS2322 fired on correct code — the same
 * "nothing relates a `Type.Reference` to the open generic" trap round 726 hit from
 * the other side.
 *
 * The bug was invisible on the compiler profile only because a resolution-ORDER
 * accident cached that same `value` member as `any` there, which made the relation
 * vacuously true. Reorder the files — which is exactly what `--partitionCheck N` and
 * `--workers N` do — and the accident stops happening, which is why the parallel path
 * emitted 16 of these and the sequential path none.
 *
 * The negative controls are the point of the file: filling the defaults must not make
 * the relation permissive. Each of them fails identically before and after the fix.
 */
class DefaultedGenericReferenceRelationTest {

    private val prelude = """
        interface Box<T = number> { value: T }
    """.trimIndent()

    @Test
    fun `an explicit instantiation matching the default relates to the bare reference`() {
        diagnose(prelude + """
            declare const bn: Box<number>
            const b: Box = bn
        """) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `the bare reference relates to the explicit instantiation of its default`() {
        diagnose(prelude + """
            declare const b: Box
            const bn: Box<number> = b
        """) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - an instantiation that contradicts the default is still rejected`() {
        diagnose(prelude + """
            declare const bs: Box<string>
            const b: Box = bs
        """) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `negative control - the defaulted form is not assignable to a different instantiation`() {
        diagnose(prelude + """
            declare const b: Box
            const bs: Box<string> = b
        """) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a return statement against a bare defaulted annotation accepts the defaulted value`() {
        diagnose(
            """
            interface EvaluatorResult<T extends string | number | undefined = string | number | undefined> {
                value: T
                isSyntacticallyString: boolean
            }

            function evaluatorResult<T extends string | number | undefined>(
                value: T,
                isSyntacticallyString = false,
            ): EvaluatorResult<T> {
                return { value, isSyntacticallyString }
            }

            export function evaluate(n: number): EvaluatorResult {
                return evaluatorResult(-n)
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a return of the wrong instantiation is still rejected`() {
        diagnose(prelude + """
            declare const bs: Box<string>
            export function f(): Box {
                return bs
            }
        """) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a trailing defaulted parameter is filled when the leading ones are supplied`() {
        diagnose(
            """
            interface Pair<A, B = string> { a: A; b: B }
            declare const p: Pair<number, string>
            const q: Pair<number> = p
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a generic with no default still requires its type argument`() {
        diagnose(
            """
            interface Raw<T> { v: T }
            declare const r: Raw<number>
            const s: Raw = r
            """,
        ) should { have(any { it.code == 2314 }) }
    }

    /**
     * A default that NAMES an earlier parameter is filled with the parameter itself,
     * not with the earlier default — tsc's `fillMissingTypeArguments` instantiates
     * each default through the mapper of the args before it, and this does not. The
     * pin exists because the shape must not start emitting: the surviving bare
     * `Type.TypeParam` in the filled argument list rides the relation's TypeParam
     * lenience, so the answer is right for a weaker reason than tsc's.
     */
    @Test
    fun `a default that names an earlier parameter does not reject the bare reference`() {
        diagnose(
            """
            interface Cell<T = number, U = T> { first: T; second: U }
            declare const c: Cell<number, number>
            const d: Cell = c
            """,
        ) should { have(none { it.code == 2322 }) }
    }
}
