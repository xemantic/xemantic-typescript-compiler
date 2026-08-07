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
 * Round 450 (self-compile burn-down, TS2454 -1): a `for (const/let X of/in …)` loop
 * variable X shadows an outer same-named uninitialized `let X` and is bound every
 * iteration, so a body read of X refers to the (always-assigned) loop binding, not the
 * outer var. The flow-based used-before-assigned pass descended into the for-of/for-in
 * body without dropping the loop variable, FP-firing TS2454 (generators.ts
 * `let variable; if (…) for (const variable of decls) { …variable.name… }`).
 */
class ForOfShadowTS2454Test {

    @Test
    fun `for-of loop variable shadowing an outer uninitialized var does not FP`() {
        diagnose(
            """
            interface Expr { name: string; }
            interface VarDeclList { declarations: { name: Expr }[]; }
            declare function isVDL(x: unknown): x is VarDeclList;
            declare function hoist(e: Expr): void;
            export function f(initializer: unknown): void {
                let variable: Expr;
                if (isVDL(initializer)) {
                    for (const variable of initializer.declarations) {
                        hoist(variable.name);
                    }
                }
            }
            """,
        ) should {
            have(none { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - outer var read outside the shadow still fires`() {
        diagnose(
            """
            interface Expr { name: string; }
            declare function hoist(e: Expr): void;
            export function f(arr: Expr[]): Expr {
                let variable: Expr;
                for (const variable of arr) {
                    hoist(variable);
                }
                return variable;
            }
            """,
        ) should {
            have(any { it.code == 2454 })
        }
    }

    @Test
    fun `negative control - a non-shadowing for-of leaves the outer var flagged`() {
        diagnose(
            """
            interface Expr { name: string; }
            declare function hoist(e: Expr): void;
            export function f(arr: Expr[]): void {
                let outer: Expr;
                for (const x of arr) {
                    hoist(outer);
                }
            }
            """,
        ) should {
            have(any { it.code == 2454 })
        }
    }
}
