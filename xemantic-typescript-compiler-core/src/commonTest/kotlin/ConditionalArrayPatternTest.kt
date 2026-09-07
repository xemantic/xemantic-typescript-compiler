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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.107): an array pattern whose initializer is a CONDITIONAL OF ARRAY LITERALS types
 * each branch at its own flow position.
 *
 * ## The mechanism
 *
 * tsc gives the pattern's implied contextual type (`getTypeFromBindingPattern`) to the
 * initializer, and a contextual type propagates into BOTH branches of a conditional
 * (`checkConditionalExpression` types each branch against the same one), so
 * `const [s, e] = c ? [n, undefined] : [o.pos, o.end]` is
 * `[number, undefined] | [number, number]` and `bindingElementType`'s union arm gives
 * `number` / `number | undefined`. (CHK.96) stage 2 REFUSED the shape — every leaf
 * `anyType` — because the two obvious reconstructions are both wrong: the conditional's
 * own type gives each branch's un-contextual `(number | undefined)[]`, and unioning the
 * branch tuples typed by `getTypeOfExpression` gives slot 0
 * `number | { pos: number; end: number; }`, since that function NEVER flow-narrows and
 * `positionOrRange` is `number` only inside its own `typeof` guard.
 *
 * What closes it is reading each element AT ITS OWN FLOW POSITION —
 * `getNarrowedTypeForReference` for a bare reference, the ordinary type otherwise —
 * confined to the conditional caller so the plain array-literal path is untouched.
 *
 * ## The grid is a CONTROL here, and the item said otherwise
 *
 * The shipped instance is `services.ts:3264` (`getRefactorContext`), 1 site on 3 of the 8
 * profiles. The item predicted `removed=1` there; measured, the grid is
 * `added=0 removed=0` on all eight — the refusal made both leaves `any`, which is SILENT,
 * and the profile has no wrong-typed USE of them (they feed a `RefactorContext` whose
 * members are exactly `number` and `number | undefined`). So the grade is the fixture,
 * whose shape is that function verbatim, and every expectation here was read from pristine
 * `typescript@6.0.3`.
 */
class ConditionalArrayPatternTest {

    private val prelude = """
        interface Range { pos: number; end: number }
        declare function takeStr(s: string): void;
    """.trimIndent()

    @Test
    fun `a typeof guard over the conditional types both slots`() {
        val d = diagnose(
            prelude + """

            function f(por: number | Range) {
              const [s, e] = typeof por === "number"
                ? [por, undefined]
                : [por.pos, por.end];
              takeStr(s);
              takeStr(e);
            }
            """
        )
        assert(d.count { it.code == 2345 } == 2)
        assert(
            d.filter { it.code == 2345 }.map { it.message } == listOf(
                "Argument of type 'number' is not assignable to parameter of type 'string'.",
                "Argument of type 'number | undefined' is not assignable to parameter of type 'string'.",
            )
        )
    }

    @Test
    fun `the same shape with the guard inverted types both slots`() {
        val d = diagnose(
            prelude + """

            function g(p: number | Range) {
              const [a, b] = typeof p !== "number"
                ? [p.pos, p.end]
                : [p, undefined];
              takeStr(a);
              takeStr(b);
            }
            """
        )
        assert(d.count { it.code == 2345 } == 2)
        assert(
            d.filter { it.code == 2345 }.map { it.message } == listOf(
                "Argument of type 'number' is not assignable to parameter of type 'string'.",
                "Argument of type 'number | undefined' is not assignable to parameter of type 'string'.",
            )
        )
    }

    @Test
    fun `a nested conditional unions every branch`() {
        val d = diagnose(
            prelude + """

            function i(c: boolean, d: boolean) {
              const [x] = c ? [1, 2] : d ? [3, 4] : [5, 6];
              takeStr(x);
            }
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'number' is not assignable to parameter of type 'string'."
        )
    }

    @Test
    fun `negative control - a branch that is not an array literal keeps the old path`() {
        val d = diagnose(
            prelude + """

            declare const other: [number, number];
            function h(c: boolean) {
              const [m] = c ? [1, 2] : other;
              takeStr(m);
            }
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'number' is not assignable to parameter of type 'string'."
        )
    }

    @Test
    fun `negative control - a branch carrying a spread still refuses`() {
        diagnose(
            prelude + """

            declare const xs: number[];
            function j(c: boolean) {
              const [m] = c ? [...xs] : [1, 2];
              takeStr(m);
            }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `negative control - a plain array literal initializer is unchanged`() {
        diagnose(
            prelude + """

            function k() {
              const [x] = [1, "a"];
              const wq: number = x;
            }
            """
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `an unguarded reference element keeps its declared union`() {
        val d = diagnose(
            prelude + """

            function m(p: number | Range, c: boolean) {
              const [z] = c ? [p] : [p];
              takeStr(z);
            }
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'number | Range' is not assignable to parameter of type 'string'."
        )
    }
}
