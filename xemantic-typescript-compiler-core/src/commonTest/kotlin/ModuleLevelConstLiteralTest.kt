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
 * (WIDEN.1)(a) A MODULE-level `const` keeps its initializer's literal type.
 *
 * Round 781 landed the const rule where `currentLocalTypes` is recorded, which
 * is BODY-scoped — so a `const` declared at module level still widened to its
 * base primitive at every consumer, and passing it where a literal union is
 * expected was a false TS2345. The `yaml` library is made of exactly that shape
 * (`export const FOLD_QUOTED = 'quoted'`, passed to a
 * `'flow' | 'block' | 'quoted'` parameter); tsgo 7.0.2 is clean on all of it.
 *
 * The body-scoped case is the control: it worked before and must keep working,
 * because a rule that only fires at one scope is how this defect happened.
 */
class ModuleLevelConstLiteralTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "t.ts").diagnostics

    private val prelude = """
        // @strict: true
        type Mode = 'flow' | 'block' | 'quoted'
        declare function take(m: Mode): string
        declare function takeNumber(n: 1 | 2 | 3): number
    """

    @Test
    fun `a module-level const reaches a literal-union parameter`() {
        compile(prelude + "\nconst MODE = 'quoted'\nconsole.log(take(MODE))") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a module-level numeric const reaches a numeric literal union`() {
        compile(prelude + "\nconst COUNT = 3\nconsole.log(takeNumber(COUNT))") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a module-level const initializes an annotated one`() {
        compile(prelude + "\nconst MODE = 'quoted'\nconst named: Mode = MODE") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a body-local const still does - the control`() {
        compile(
            prelude + """

            function f(): string {
                const mode = 'quoted'
                return take(mode)
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a LET widens, as tsc widens it`() {
        compile(prelude + "\nlet mode = 'quoted'\nconsole.log(take(mode))") should {
            // tsc reports this too: a mutable binding's initializer widens, which
            // is the whole point of the immutability gate.
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a const outside the union still errors`() {
        compile(prelude + "\nconst MODE = 'nope'\nconsole.log(take(MODE))") should {
            have(any { it.code == 2345 })
        }
    }

}
