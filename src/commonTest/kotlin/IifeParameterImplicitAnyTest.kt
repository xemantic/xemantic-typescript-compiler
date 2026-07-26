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
 * (M3.0-gap-2, partial) An IMMEDIATELY-INVOKED function's parameters are
 * contextually typed from the call's arguments, so tsc reports no implicit-any
 * for them — including when the call passes none.
 *
 * We were reporting TS7019/TS7006 there. The suppression is wired into BOTH
 * emitters: the general parameter walker and the dedicated rest-parameter one;
 * the first patch hit only one of them and changed nothing observable, which is
 * why the rest-parameter cases carry their own pins here.
 *
 * Still missing (the rest of gap-2): the parameters are not actually TYPED from
 * the arguments, so the TS18048 family the reference baseline expects for
 * optional IIFE parameters does not fire yet.
 */
class IifeParameterImplicitAnyTest {

    private val strict = "// @target: es2015\n// @strictNullChecks: true"

    @Test
    fun `an IIFE rest parameter does not report TS7019`() {
        val diagnostics = diagnose("((...numbers) => numbers.every(n => n > 0))(5,6,7);", directives = strict)
        assert(diagnostics.none { it.code == 7019 })
    }

    @Test
    fun `an IIFE rest parameter with no arguments does not report TS7019`() {
        val diagnostics = diagnose("((...noNumbers) => noNumbers.some(n => n > 0))();", directives = strict)
        assert(diagnostics.none { it.code == 7019 })
    }

    @Test
    fun `an IIFE leading parameter beside a rest does not report TS7006`() {
        val diagnostics = diagnose(
            "((first, ...rest) => first ? [] : rest.map(n => n > 0))(8,9,10);",
            directives = strict,
        )
        assert(diagnostics.none { it.code == 7006 })
        assert(diagnostics.none { it.code == 7019 })
    }

    @Test
    fun `a parenthesized IIFE callee is still recognised`() {
        // The shape is routinely written with superfluous parentheses.
        val diagnostics = diagnose(
            """
            (function (x) { } ("!"));
            ((((function (y) { }))))("-");
            """,
            directives = strict,
        )
        assert(diagnostics.none { it.code == 7006 })
    }

    @Test
    fun `a function expression IIFE's parameters are suppressed too`() {
        val diagnostics = diagnose("(function (cats, ...more) { })(\"lol\");", directives = strict)
        assert(diagnostics.none { it.code == 7006 })
        assert(diagnostics.none { it.code == 7019 })
    }

    @Test
    fun `negative control - a NON-invoked arrow still reports implicit any`() {
        val diagnostics = diagnose("const f = (first, ...rest) => rest;", directives = strict)
        assert(diagnostics.any { it.code == 7006 })
        // NOTE: `rest` draws no TS7019 here, and did not before this change either —
        // a rest parameter in a var-initializer arrow does not reach the dedicated
        // rest walker. Unrelated pre-existing gap; the declaration form below is the
        // shape that pins TS7019 still firing outside IIFE position.
    }

    @Test
    fun `negative control - a plain function declaration's rest parameter still reports TS7019`() {
        val diagnostics = diagnose("function h(...rest) { return rest; }", directives = strict)
        assert(diagnostics.any { it.code == 7019 })
    }

    @Test
    fun `negative control - an arrow passed as an ARGUMENT is not an IIFE`() {
        // `g(x => x)` invokes g, not the arrow; the arrow's parameter is only
        // contextually typed if g's signature provides it, which it does not here.
        val diagnostics = diagnose(
            """
            declare function g(cb: any): void;
            g((...items) => items);
            """,
            directives = strict,
        )
        assert(diagnostics.any { it.code == 7019 })
    }
}
