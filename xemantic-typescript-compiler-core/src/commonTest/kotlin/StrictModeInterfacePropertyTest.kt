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
 * M1.12 (self-compile burn-down): a property/method NAME is NEVER subject to the strict-mode
 * `arguments`/`eval` restriction (TS1100). tsc's `checkStrictModeEvalOrArguments` fires ONLY
 * for binding names — variable/parameter/function names and assignment LHS — never a property
 * or method name. tsc's own `src/compiler/types.ts` trips exactly this: `interface CallExpression
 * { readonly arguments: NodeArray<Expression>; }` and `interface NewExpression { readonly
 * arguments?: NodeArray<Expression>; }` — both module-strict, both legal, both FP'd TS1100 from
 * the `InterfaceDeclaration -> PropertyDeclaration` name-check arm before the fix removed it.
 *
 * Negative controls pin the FP-safety boundary: a genuine strict-mode binding named
 * `arguments`/`eval` must STILL fire TS1100.
 */
class StrictModeInterfacePropertyTest {

    @Test
    fun `module interface property named arguments - no TS1100`() {
        // The exact types.ts shape (module file = auto strict).
        diagnose(
            """
            export interface CallExpression {
                readonly arguments: number[];
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `module interface optional property named arguments - no TS1100`() {
        diagnose(
            """
            export interface NewExpression {
                readonly arguments?: number[];
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `module interface property named eval - no TS1100`() {
        diagnose(
            """
            export interface I {
                eval: string;
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `interface method named arguments - no TS1100`() {
        // A METHOD name is also exempt (only params are checked in the interface branch).
        diagnose(
            """
            export interface I {
                arguments(): void;
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 1100 })
        }
    }

    @Test
    fun `strict-mode var named arguments STILL fires TS1100 - negative control`() {
        // FP-safety boundary: a real binding name in strict code must still be flagged.
        diagnose(
            """
            "use strict";
            function f() {
                "use strict";
                var arguments = 1;
            }
            """,
            directives = "",
        ) should {
            have(any { it.code == 1100 })
        }
    }
}
