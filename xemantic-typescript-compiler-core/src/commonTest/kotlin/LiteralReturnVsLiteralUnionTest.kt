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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 436c: `return <literal>` against an annotation whose top-level union
 * syntactically contains that literal member is ALWAYS legal. The engine
 * relation passed but did not early-return for non-nullish sources, so control
 * fell to the STRING fallback, which re-widened the literal ('boolean'/'string')
 * and FP'd against the union display — tsc parser.ts's `return false;` against
 * `JSDocTypeTag | … | false` ×4, and the completely unpinned general shape
 * `function f(): "a" | "b" { return "a"; }`.
 */
class LiteralReturnVsLiteralUnionTest {

    @Test
    fun `string literal return against its own union is legal`() {
        diagnose("""function g(): "a" | "b" { return "a"; }""") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `return false against an interface union with false is legal`() {
        // The tsc parser.ts shape: `return false` vs an interface union with `| false`.
        diagnose(
            """
            interface TagA { kind: "a"; x: number }
            interface TagB { kind: "b"; y: number }
            declare function tryParse(): TagA | TagB | false;
            function f(c: boolean): TagA | TagB | false {
                if (c) { return tryParse(); }
                return false;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `numeric literal and negative forms match the union`() {
        diagnose(
            """
            function f(c: boolean): 0 | -1 | "x" {
                if (c) { return 0; }
                return -1;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `literal return against an alias union is legal`() {
        // Alias form: the union body lives behind a type alias.
        diagnose(
            """
            type Mode = "read" | "write" | false;
            function f(c: boolean): Mode {
                if (c) { return "read"; }
                return false;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a literal not in the union still fires`() {
        diagnose("""function g(): "a" | "b" { return "c"; }""") should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - return true when only false is in the union still fires`() {
        diagnose(
            """
            interface TagA { kind: "a" }
            function g(): TagA | false { return true; }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
