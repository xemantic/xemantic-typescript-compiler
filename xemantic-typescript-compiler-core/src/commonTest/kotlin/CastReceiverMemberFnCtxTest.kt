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
 * Round 481: `(result as CompileFilesResult).repeat = newOptions => …` — an
 * as-cast receiver whose TYPE declares the assigned member as a METHOD gives
 * the RHS arrow a contextual signature in tsc, even when OUR resolution of the
 * cast type poisons to any (harnessIO's namespace-nested alias intersects a
 * barrel-unresolvable qualified name). The AST-side member scan signals
 * ctx-unknowable so TS7006 is suppressed; a member the cast type does NOT
 * declare keeps firing.
 */
class CastReceiverMemberFnCtxTest {

    @Test
    fun `an as-cast receiver whose unresolvable alias declares the member method gives fn context`() {
        diagnose(
            """
            // @filename: main.ts
            import * as compiler from "./missing.js";
            export namespace Harness {
                export type R = compiler.Result & { repeat(newOptions: string): R; };
                export function f(result: object): R {
                    (result as R).repeat = newOptions => f({});
                    return result as R;
                }
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - a member absent from the cast type keeps TS7006`() {
        diagnose(
            """
            // @filename: main.ts
            import * as compiler from "./missing.js";
            export namespace Harness {
                export type R = compiler.Result & { repeat(newOptions: string): R; };
                export function f(result: object): R {
                    (result as R).other = newOptions => f({});
                    return result as R;
                }
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(any { it.code == 7006 })
        }
    }
}
