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
 * Round 468: a for-of loop VAR's binding names — including destructured elements —
 * shadow same-named outer/file-level functions in the arity walker (M1.11's rule
 * at the for-of site). tsc mapCode.ts: `for (const { parse, body } of nodeKinds)
 * { const sourceFile = parse(); … }` where the FILE also declares a 2-param
 * `function parse` — the call FP'd TS2554 "Expected 2 arguments, but got 0".
 */
class ForOfDestructuredShadowArityTest {

    @Test
    fun `a destructured for-of element shadows a same-named file-level function for arity`() {
        diagnose(
            """
            function parse(a: string, b: number): string { return a + b; }
            export function run(kinds: { parse: () => string; body: string }[]): string {
                let out = "";
                for (const { parse, body } of kinds) {
                    out += parse() + body;
                }
                return out + parse("x", 1);
            }
            """,
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `a plain for-of loop var shadows a same-named file-level function for arity`() {
        diagnose(
            """
            function step(a: string, b: number): string { return a + b; }
            export function run(steps: (() => string)[]): string {
                let out = "";
                for (const step of steps) {
                    out += step();
                }
                return out;
            }
            """,
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - a call to the file-level function outside the loop still checks arity`() {
        diagnose(
            """
            function parse(a: string, b: number): string { return a + b; }
            export function run(kinds: { parse: () => string }[]): string {
                for (const { parse } of kinds) {
                    parse();
                }
                return parse();
            }
            """,
        ) should {
            // The final call is OUTSIDE the loop — the 2-param file fn, 0 args.
            have(any { it.code == 2554 })
        }
    }
}
