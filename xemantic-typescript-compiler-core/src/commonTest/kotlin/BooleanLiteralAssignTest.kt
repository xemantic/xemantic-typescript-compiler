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
 * Round 450 (self-compile burn-down): a `true`/`false` literal assigned to a
 * variable whose declared type is a literal-boolean type (`true | undefined`,
 * `false`, ...) must NOT FP-fire TS2322.
 *
 * ROOT CAUSE: a boolean literal `true`/`false` parses as an `Identifier`, so it
 * was not covered by the `tryCatchFinallyControlFlow` guard that skips the legacy
 * varTypes string-fallback for numeric/string/bigint literal RHS. The legacy path
 * widens `true`→"boolean" and `isAssignableTo("boolean", "true | undefined")`
 * fails — even though the type engine already validated the assignment (keeping
 * the literal `true` via `propTypeContainsLiteral`). tsc's own completions.ts
 * `let isSnippet: true | undefined; isSnippet = true;` was the family.
 */
class BooleanLiteralAssignTest {

    @Test
    fun `true assigned to a true-or-undefined local does not FP`() {
        diagnose(
            """
            function f(cond: boolean) {
                let isSnippet: true | undefined;
                if (cond) {
                    isSnippet = true;
                }
                return isSnippet;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `false assigned to a false-or-undefined local does not FP`() {
        diagnose(
            """
            function f() {
                let flag: false | undefined;
                flag = false;
                return flag;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - true assigned to a false-typed local still fires`() {
        diagnose(
            """
            function f() {
                let a: false | undefined;
                a = true;
                return a;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a boolean variable assigned to a true-typed local still fires`() {
        diagnose(
            """
            function f(cond: boolean) {
                let b: true | undefined;
                b = cond;
                return b;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
