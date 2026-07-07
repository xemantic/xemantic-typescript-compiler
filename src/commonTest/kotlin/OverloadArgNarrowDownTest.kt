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
 * Round 439 (M3.4): the overload arg-check narrows a bare Identifier/PropertyAccess
 * argument DOWN from a NON-union declared type to a type-guard subtype, mirroring
 * round 438's single-sig call-arg fix C. tsc's utilities.ts `isSameEntityName`:
 * `if (isLiteralLikeAccess(name)) getElementOrPropertyAccessName(name)` — the guard
 * narrows `name: Expression` to the overloaded callee's first-overload param.
 */
class OverloadArgNarrowDownTest {

    private val prelude = """
        interface Node6 { kind: number }
        interface AccessExpr6 extends Node6 { expr: Node6 }
        interface PropAccess6 extends AccessExpr6 { propName: string }
        declare function isAccess(n: Node6): n is PropAccess6;
        declare function accessName(node: PropAccess6): string;
        declare function accessName(node: AccessExpr6): string | undefined;
    """.trimIndent()

    @Test
    fun `guard-narrowed non-union arg matches the specific overload`() {
        diagnose(
            prelude + """
            function f(name: Node6): string {
                if (isAccess(name)) {
                    return accessName(name);
                }
                return "x";
            }
            """.trimIndent(),
        ) should {
            // Was TS2769 'Node6' matches neither overload param.
            have(none { it.code == 2769 })
        }
    }

    @Test
    fun `negative control - an un-narrowed wide arg still fails both overloads`() {
        diagnose(
            prelude + """
            function g(name: Node6): string | undefined {
                return accessName(name);
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2769 })
        }
    }

    @Test
    fun `typeof narrows an unknown overload arg to string`() {
        // tsc moduleNameResolver's `target: unknown` string arm feeding an overloaded
        // `getPathComponents(target)`. The `typeof` guard narrows `unknown` → `string`,
        // which matches the plain-string overload.
        diagnose(
            """
            declare function toParts(p: string & { __brand: any }): string[];
            declare function toParts(p: string): string[];
            function h(target: unknown): string[] {
                if (typeof target === "string") {
                    return toParts(target);
                }
                return [];
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2769 })
        }
    }
}
