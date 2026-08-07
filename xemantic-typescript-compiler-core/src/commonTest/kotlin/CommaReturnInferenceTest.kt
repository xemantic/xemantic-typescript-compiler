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
 * Local corner-case tests for the inferred return type of `return x, y`.
 *
 * A comma expression evaluates to its RIGHT operand, so a function whose body is
 * `return x, y` returns `y`'s type. `combineBinaryTypes` already knew this; what
 * did not was [inferReturnTypeFromBody], so the return type came out `any` and every
 * call site went unchecked (round 695 isolated this by contrast: the same function
 * with an explicit `: string` annotation errored correctly).
 *
 * The operand is typed from the OWNING function's parameter annotations rather than
 * by resolving the identifier, because this inference runs in the CALLER's scope —
 * resolving a callee's parameter by name there would hit the documented shadowing
 * hazard, and it is why the function's plain-Identifier arm resolves nothing but
 * `true`/`false`. These pins cover that boundary from both sides: an annotated
 * parameter must be inferred, an UN-annotated one must stay silent rather than
 * guess, and a same-named outer binding must not leak in.
 */
class CommaReturnInferenceTest {

    @Test
    fun `a comma return infers the right operand's parameter type`() {
        val diags = diagnose(
            """
            function foo(x: number, y: string) { return x, y; }
            var bad: number = foo(1, "123");
            """
        ).filter { it.code == 2322 }
        assert(diags.size == 1)
        assert(diags[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `a chained comma return infers the RIGHTMOST operand`() {
        val diags = diagnose(
            """
            function three(a: number, b: boolean, c: string) { return a, b, c; }
            var bad: number = three(1, true, "s");
            """
        ).filter { it.code == 2322 }
        assert(diags.size == 1)
        assert(diags[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `a comma return whose right operand is a literal infers the literal's type`() {
        val diags = diagnose(
            """
            function lit(x: number) { return x, 42; }
            var bad: string = lit(1);
            """
        ).filter { it.code == 2322 }
        assert(diags.size == 1)
        assert(diags[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `a correct call against a comma return draws nothing`() {
        val diags = diagnose(
            """
            function foo(x: number, y: string) { return x, y; }
            var ok: string = foo(1, "123");
            """
        ).filter { it.code == 2322 }
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - an UN-annotated right operand infers nothing`() {
        // The inference must not guess: with no annotation to read, the return type
        // stays `any` and the call site is unchecked, exactly as before this change.
        val diags = diagnose(
            """
            function unann(x: number, y) { return x, y; }
            var whatever: number = unann(1, 2);
            """
        ).filter { it.code == 2322 }
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - a same-named outer binding is not used for the operand`() {
        // `y` here is NOT a parameter of the callee; resolving it through the ambient
        // scope would type the return as the outer `string` and manufacture an error.
        val diags = diagnose(
            """
            var y: string = "outer";
            function usesOuter(x: number) { return x, y; }
            var target: number = usesOuter(1);
            """
        ).filter { it.code == 2322 }
        assert(diags.isEmpty())
    }
}
