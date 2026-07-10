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
 * Pins the `localTypeAliasIndex` (Tier-1 eager per-file index) that replaced
 * `findLocalTypeAlias`'s per-call whole-file AST rescan (the self-compile's hottest
 * method, ~10% of samples). The consumer is the TS2488 exhaustive-switch-default
 * never-destructure walker: it must resolve a discriminated-union type alias by name
 * wherever the alias sits in the file — top level or nested in containers the binder
 * never binds (B83.5) — so these tests pin index coverage of the nested-DFS positions
 * the old scan reached, plus a negative control proving the lookup did not go
 * over-eager.
 */
class LocalTypeAliasIndexTest {

    private fun exhaustiveSwitchBody(aliasName: String) = """
        function f(x: $aliasName) {
            const { kind, payload } = x;
            switch (kind) {
                case "a": return 0;
                case "b": return 1;
                default:
                    const [n] = payload;
                    return n;
            }
        }
    """.trimIndent()

    private val dataAlias =
        """type Data = { kind: "a"; payload: [number] } | { kind: "b"; payload: [string] };"""

    @Test
    fun `top-level local type alias resolves - exhaustive default destructure fires TS2488`() {
        diagnose(dataAlias + "\n" + exhaustiveSwitchBody("Data")) should {
            have(any { it.code == 2488 })
        }
    }

    @Test
    fun `alias nested in an enclosing function body resolves through the index`() {
        diagnose("""
            function outer() {
                type Data = { kind: "a"; payload: [number] } | { kind: "b"; payload: [string] };
        """.trimIndent() + "\n" + exhaustiveSwitchBody("Data") + "\n}") should {
            have(any { it.code == 2488 })
        }
    }

    @Test
    fun `alias nested in a namespace block resolves through the index`() {
        // The switch-scanning walker itself does not descend namespace bodies, so the
        // consuming FUNCTION stays top-level; only the ALIAS sits in the namespace —
        // the index (like the replaced scan) is whole-file by name.
        val nsAlias = """
            namespace NS {
                type Data = { kind: "a"; payload: [number] } | { kind: "b"; payload: [string] };
            }
        """.trimIndent()
        diagnose(nsAlias + "\n" + exhaustiveSwitchBody("Data")) should {
            have(any { it.code == 2488 })
        }
    }

    @Test
    fun `negative control - non-exhaustive switch draws no TS2488`() {
        val body = """
            function f(x: Data) {
                const { kind, payload } = x;
                switch (kind) {
                    case "a": return 0;
                    default:
                        const [n] = payload;
                        return n;
                }
            }
        """.trimIndent()
        diagnose(dataAlias + "\n" + body) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `negative control - unknown alias name draws no TS2488`() {
        diagnose(exhaustiveSwitchBody("Missing")) should {
            have(none { it.code == 2488 })
        }
    }
}
