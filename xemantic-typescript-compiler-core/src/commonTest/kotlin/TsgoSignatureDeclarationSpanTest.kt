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
 * (LEGACY.0b) step 3, family F8 — TS8017 `Signature declarations can only be used in
 * TypeScript files.` spans the DECLARATION.
 *
 * TypeScript 7 builds the diagnostic for the declaration node, so it covers any modifiers,
 * the name, the parameter list and the terminating `;`; tsc 6 squiggled the NAME alone for a
 * function and a method, and the `constructor` keyword alone for a constructor. Nothing else
 * about the row moves — the code, the message and the reported (line, column) are unchanged
 * for the constructor and the method, and the function's column moves from the name to the
 * `function` keyword because that is where its node starts.
 *
 * Every expectation was read off `tools/tsgo-7.0.2/lib/tsc --pretty`. Baselines closed:
 * `jsFileCompilationFunctionOverloadSyntax`, `jsFileCompilationConstructorOverloadSyntax`,
 * `jsFileCompilationMethodOverloadSyntax`.
 */
class TsgoSignatureDeclarationSpanTest {

    private val directives =
        "// @target: es2015\n// @allowJs: true\n// @checkJs: true\n// @noEmit: true"

    private fun signatureDeclarations(source: String): List<Diagnostic> =
        diagnose(source, directives = directives, fileName = "a.js").filter { it.code == 8017 }

    /** `function foo();` — tsgo `a.js(1,1)`, fifteen characters including the `;`. */
    @Test
    fun `a bodiless function declaration spans the whole declaration`() {
        val row = signatureDeclarations("function foo();").single()
        assert(row.message == "Signature declarations can only be used in TypeScript files.")
        assert(row.line == 1)
        assert(row.character == 1)
        assert(row.length == 15)
    }

    /** `  constructor();` — tsgo `a.js(2,3)`, fourteen characters. */
    @Test
    fun `a bodiless constructor spans the whole declaration`() {
        val row = signatureDeclarations(
            """
            class A {
              constructor();
            }
            """
        ).single()
        assert(row.line == 2)
        assert(row.character == 3)
        assert(row.length == 14)
    }

    /** `  foo();` — tsgo `a.js(2,3)`, six characters. */
    @Test
    fun `a bodiless method spans the whole declaration`() {
        val row = signatureDeclarations(
            """
            class A {
              foo();
            }
            """
        ).single()
        assert(row.line == 2)
        assert(row.character == 3)
        assert(row.length == 6)
    }

    /**
     * `  static bar();` — the span starts at the MODIFIER, tsgo `a.js(2,3)` width 13. This is
     * what separates "the declaration node" from "the name plus the rest of the line".
     */
    @Test
    fun `a bodiless static method spans its modifier too`() {
        val row = signatureDeclarations(
            """
            class A {
              static bar();
            }
            """
        ).single()
        assert(row.line == 2)
        assert(row.character == 3)
        assert(row.length == 13)
    }

    /**
     * Negative control: a method WITH a body is not a signature declaration, so the widened
     * span cannot be reached by simply being in a `.js` file.
     */
    @Test
    fun `negative control - a method with a body reports no TS8017`() {
        assert(
            signatureDeclarations(
                """
                class A {
                  foo() { }
                }
                """
            ).isEmpty()
        )
    }
}
