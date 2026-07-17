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
 * (cta-m3d) round 570: fn-body-DIRECT statements of eligible
 * FunctionDeclaration chains emit from the spine anchor while the legacy arm
 * truncates its duplicates — the emit-twice-suppress-legacy contract requires
 * every diagnostic to appear EXACTLY ONCE (a gate mismatch shows as 0 — lost
 * to truncation without an anchor — or 2 — anchored without truncation).
 * Non-eligible chains (class methods, if-nesting, arrows) stay legacy-owned
 * and must also emit exactly once.
 */
class CtaFnBodyAnchorTest {

    private fun countTs2322(source: String): Int =
        diagnose(source).count { it.code == 2322 }

    @Test
    fun `top-level fn body var-decl mismatch emits exactly once`() {
        val n = countTs2322("""
            function f() {
                const x: string = 1;
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `namespace-nested fn body var-decl mismatch emits exactly once`() {
        val n = countTs2322("""
            namespace N {
                export function f() {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `fn-in-fn body var-decl mismatch emits exactly once`() {
        val n = countTs2322("""
            function outer() {
                function inner() {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `async fn return mismatch emits exactly once and Promise unwrap still holds`() {
        val n = countTs2322("""
            async function bad(): Promise<number> {
                return "s";
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
        diagnose("""
            async function good(): Promise<number> {
                return 1;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `class method body stays legacy-owned and emits exactly once`() {
        val n = countTs2322("""
            class C {
                m() {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }

    @Test
    fun `if-nested statement inside fn body stays legacy-owned and emits exactly once`() {
        val n = countTs2322("""
            declare const cond: boolean;
            function f() {
                if (cond) {
                    const x: string = 1;
                }
            }
        """)
        assert(n == 1) { "expected exactly 1 TS2322, got $n" }
    }
}
