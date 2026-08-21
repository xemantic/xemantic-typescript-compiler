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
 * (CHK.30) A returned LITERAL keeps its literal type against a literal target.
 *
 * The family `docs/kir-library-readiness.md` measured as the dominant one in
 * the `yaml` library — 24 rows in one file, every `case BOM: return
 * 'byte-order-mark'` in its CST reader. Two independent causes, both silent in
 * the false-POSITIVE direction, and both about a source type that was already
 * widened before anything compared it:
 *
 * 1. `checkReturnAssignabilityCore` retains the literal (17.70) and the ENGINE
 *    accepts it — and then falls through to the string fallback, which re-
 *    renders the source as its base primitive and compares TEXT. `(): T` passed
 *    only because that text comparison happens to accept an unresolved alias
 *    NAME, which is not a rule; `(): T | null` and `(): T | undefined` failed.
 * 2. `checkArrowConciseBodyReturnType` never had 17.70 at all, so `(): T => 'a'`
 *    and `(): T => { return 'a' }` — the same function, two spellings —
 *    disagreed.
 *
 * Every case here is clean under tsgo 7.0.2.
 */
class ReturnPositionLiteralRetentionTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "t.ts").diagnostics

    private val alias = """
        // @strict: true
        type T = 'a' | 'b'
    """

    @Test
    fun `a literal returned into a nullable literal union`() {
        compile(alias + "\nfunction f(): T | null { return 'a' }") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a literal returned into an optional literal union`() {
        compile(alias + "\nfunction f(): T | undefined { return 'a' }") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a literal returned into a mixed union`() {
        compile(alias + "\nfunction f(): T | number { return 'a' }") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a literal returned from inside a switch clause`() {
        compile(
            alias + """

            function f(k: string): T | null {
                switch (k) {
                    case 'x': return 'a'
                }
                return null
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an arrow's concise body keeps the literal, as its block form does`() {
        compile(alias + "\nconst f = (): T => 'a'\nconst g = (): T => { return 'a' }") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a literal outside the union still errors`() {
        compile(alias + "\nfunction f(): T | null { return 'z' }") should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the arrow form also still errors`() {
        compile(alias + "\nconst f = (): T => 'z'") should {
            have(any { it.code == 2322 })
        }
    }

}
