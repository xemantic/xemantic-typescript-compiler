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
 * Round 470 (M1.11): a `let`/`const` in a NESTED block that collides with a PARAM
 * is a genuine block-scoped shadow — reads inside the block must NOT resolve to
 * the param's type (the flat first-decl-wins map can't distinguish the bindings →
 * anyType, suppression-only). The tsc shape is importFixes.ts's param
 * `namedImports: readonly Import[]` vs the else-block's `const namedImports =
 * factory.createNamedImports(…)` passed as a Node arg. A TOP-LEVEL body `var`
 * keeps the param-wins redeclaration rule (functionArgShadowing).
 */
class NestedBlockConstShadowsParamTest {

    private val prelude = """
        interface Node2 { kind: number }
        interface NamedImports extends Node2 { elements: string[] }
        interface Import { name: string }
        declare function createNamedImports(specs: string[]): NamedImports;
        declare function replaceNode(orig: Node2, repl: Node2): void;
    """.trimIndent()

    @Test
    fun `a nested-block const shadowing a param does not carry the param type into args`() {
        diagnose(
            prelude + """

            function addSpecifiers(clause: { namedBindings?: Node2 }, newSpecifiers: string[], namedImports: readonly Import[]) {
                if (namedImports.length) {}
                if (newSpecifiers.length) {
                    const namedImports = createNamedImports(newSpecifiers);
                    if (clause.namedBindings) {
                        replaceNode(clause.namedBindings, namedImports);
                    }
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - the param itself as a wrong-typed arg still fires`() {
        diagnose(
            prelude + """

            declare function wantsString(s: string): void;
            function f(count: number) {
                wantsString(count);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a top-level body var keeps the param type - redeclaration`() {
        // functionArgShadowing rule: `function foo(x: A) { var x: B; … }` keeps x: A.
        diagnose(
            prelude + """

            declare function wantsString(s: string): void;
            function f(x: number) {
                var x;
                wantsString(x);
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
