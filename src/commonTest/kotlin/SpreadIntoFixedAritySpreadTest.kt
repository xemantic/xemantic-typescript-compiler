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
 * Local corner-case tests for TS2556, "A spread argument must either have a tuple type
 * or be passed to a rest parameter".
 *
 * An unbounded array spread into a fixed-arity signature cannot be arity-checked at
 * all, which is why tsc rejects it outright. The rule is narrow in three ways that each
 * have a pin here, because each is a way to turn it into a false positive:
 *
 *  - a TUPLE spread is legal (its length is known);
 *  - an ARRAY LITERAL spread is legal for the same reason — tsc counts `...[6, 7]` as
 *    two arguments, so this must not preempt a too-many report;
 *  - spreading INTO a rest parameter is the other half of the message and always legal.
 *
 * Precedence also matters: when the fixed arguments already exceed the maximum, tsc
 * reports the count, not TS2556 (pinned in Inv4SpineBatch26Test's trailing-spread case,
 * and by the `functionParameterArityMismatch` corpus baseline).
 */
class SpreadIntoFixedAritySpreadTest {

    private fun ts2556(source: String): List<Diagnostic> =
        diagnose(source).filter { it.code == 2556 }

    @Test
    fun `a readonly array rest parameter spread into a fixed-arity call is TS2556`() {
        val diags = ts2556(
            """
            function f0(a: string, b: string) { }
            function f1(...args: readonly string[]) { f0(...args); }
            """
        )
        assert(diags.size == 1)
    }

    @Test
    fun `a plain array value spread into a fixed-arity call is TS2556`() {
        val diags = ts2556(
            """
            function f0(a: string, b: string) { }
            declare const arr: string[];
            function g() { f0(...arr); }
            """
        )
        assert(diags.size == 1)
    }

    @Test
    fun `negative control - a TUPLE spread is legal`() {
        val diags = ts2556(
            """
            function f0(a: string, b: string) { }
            function f2(...args: readonly [string, string]) { f0(...args); }
            """
        )
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - an ARRAY LITERAL spread has a known length`() {
        val diags = ts2556(
            """
            function f0(a: string, b: string) { }
            function g() { f0(...["a", "b"]); }
            """
        )
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - spreading INTO a rest parameter is legal`() {
        val diags = ts2556(
            """
            function f1(...args: readonly string[]) { }
            declare const arr: string[];
            function g() { f1(...arr); }
            """
        )
        assert(diags.isEmpty())
    }

    @Test
    fun `a TUPLE-typed rest parameter has FIXED arity`() {
        // The "a rest parameter accepts anything" exemption does not apply to a tuple:
        // f2 takes exactly two, and the spread contributes its two elements, so this call
        // passes three. The squiggle anchors on the SPREAD, because that is the argument
        // the third one lives inside — an expanded COUNT is not an argument index, and
        // passing one as the index made the emitter return silently.
        val diags = diagnose(
            """
            function f2(...args: readonly [string, string]) { f2('abc', ...args); }
            """
        ).filter { it.code == 2554 }
        assert(diags.size == 1)
        assert(diags[0].message == "Expected 2 arguments, but got 3.")
        assert(diags[0].length == 7)
    }

    @Test
    fun `negative control - an exact tuple spread into its own tuple rest is legal`() {
        val diags = diagnose(
            """
            function f2(...args: readonly [string, string]) { f2(...args); f2('a', 'b'); }
            """
        ).filter { it.code == 2554 || it.code == 2556 }
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - an ARRAY rest parameter stays unbounded`() {
        val diags = diagnose(
            """
            function f1(...args: readonly string[]) { f1('a', 'b', 'c', 'd'); }
            """
        ).filter { it.code == 2554 || it.code == 2556 }
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - an already-too-many call reports the COUNT and not TS2556`() {
        val diags = diagnose(
            """
            function f(a: number) { }
            declare const xs: number[];
            function g() { f(1, 2, ...xs); }
            """
        )
        assert(diags.none { it.code == 2556 })
        assert(diags.count { it.code == 2554 } == 1)
    }
}
