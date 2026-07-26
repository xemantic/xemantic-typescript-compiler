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
 * Local corner-case tests for type-parameter resolution in a function BODY.
 *
 * A parameter's annotation is resolved while building the signature, with the
 * function's type parameters in scope; a body variable's annotation was not, so
 * `function f<T>() { var x: T }` gave `x` the type `any`. The frame already computed
 * the scope (`CtaFrame.fnTpScope`) — nothing read it.
 *
 * The first test is the observable consequence, verified by A/B against the unchanged
 * compiler: without the scope, the whole snippet drew NOTHING, because `y` was `any`
 * and so was `y.v`.
 *
 * The remaining tests pin the chain forms that resolving the target newly puts under
 * the type engine's control. They matter because the engine had no TypeParam-target
 * chain at all on the return path — the text used to come from the string fallback —
 * and because "which form" depends on two things that are easy to get wrong: an
 * UNRESOLVED constraint displays as `'any'` (B58.1) and must count as no constraint,
 * and tsc names the APPARENT constraint, so a chain of parameters is followed to its
 * first concrete link.
 */
class BodyVarTypeParamScopeTest {

    @Test
    fun `a body variable annotated with a constrained type parameter resolves its members`() {
        val diags = diagnose(
            """
            interface Box { v: number; }
            function g<T extends Box>(p: T) {
                var y: T = p;
                var z: string = y.v;
            }
            """
        ).filter { it.code == 2322 }
        assert(diags.size == 1)
        assert(diags[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `an UNCONSTRAINED type parameter target takes the arbitrary-type chain form`() {
        // strictNullChecks matters here and the default is deliberate: under
        // `@strict: false` a `null` return is assignable to anything, so there is no
        // diagnostic to carry a chain at all (my first version of this pin asserted
        // against non-strict and failed for that reason, not a compiler one).
        val diags = diagnose(
            """
            function f5<T>(): T { return null; }
            """
        ).filter { it.code == 2322 }
        assert(diags.size == 1)
        assert(
            diags[0].messageChain == listOf(
                "  'T' could be instantiated with an arbitrary type which could be unrelated to 'null'."
            )
        )
    }

    @Test
    fun `a chained constraint reports the APPARENT constraint, not the immediate one`() {
        // `V extends U extends A` — tsc names 'A'. Reporting 'U' (or dropping to the
        // arbitrary form because `A & B` does not relate to a bare `U`) is the failure
        // this pins.
        val diags = diagnose(
            """
            interface A { a: number; }
            interface B { b: number; }
            function f<U extends A, V extends U>() {
                var v: V;
                var a_and_b: A & B;
                v = a_and_b;
            }
            """,
            "// @strict: false",
        ).filter { it.code == 2322 }
        assert(diags.size == 1)
        assert(
            diags[0].messageChain == listOf(
                "  'A & B' is assignable to the constraint of type 'V', but 'V' could be " +
                    "instantiated with a different subtype of constraint 'A'."
            )
        )
    }
}
