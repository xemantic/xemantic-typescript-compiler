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
 * (M3.0-gap-1) The two enum-member rules the conformance case exposed.
 *
 * TS18033 previously fired only for a STRING-typed computed member; a
 * FUNCTION-valued one drew nothing. And the TS2332 walker skipped arrow bodies
 * along with function bodies — but an arrow does NOT rebind `this`, so its `this`
 * is the enum's and is just as illegal.
 */
class EnumMemberFunctionAndThisTest {

    @Test
    fun `an arrow-valued computed enum member reports TS18033 with its function type`() {
        val diagnostics = diagnose("enum E { x = () => 4 }", directives = "// @strict: false")
        assert(diagnostics.any { it.code == 18033 })
        assert(diagnostics.any { it.message.contains("Type '() => number' is not assignable") })
    }

    @Test
    fun `a function-expression-valued computed enum member reports TS18033`() {
        val diagnostics = diagnose(
            "enum E { x = function () { return 4; } }",
            directives = "// @strict: false",
        )
        assert(diagnostics.any { it.code == 18033 })
    }

    @Test
    fun `this inside an arrow in an enum member initializer reports TS2332`() {
        val diagnostics = diagnose(
            """
            enum E {
                y = (() => this).length
            }
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.any { it.code == 2332 })
    }

    @Test
    fun `this inside an arrow's block body in an enum member initializer reports TS2332`() {
        val diagnostics = diagnose(
            """
            enum E {
                y = (() => { return this; }).length
            }
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.any { it.code == 2332 })
    }

    @Test
    fun `negative control - this inside a FUNCTION expression rebinds and stays silent`() {
        // A function expression DOES rebind `this`, so the enum rule must not reach it.
        val diagnostics = diagnose(
            """
            enum E {
                y = (function () { return this; })().length
            }
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.none { it.code == 2332 })
    }

    @Test
    fun `negative control - an ordinary numeric computed member stays silent`() {
        val diagnostics = diagnose(
            """
            declare const n: number;
            enum E { a = n, b = 1 + 2 }
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.none { it.code == 18033 })
        assert(diagnostics.none { it.code == 2332 })
    }
}
