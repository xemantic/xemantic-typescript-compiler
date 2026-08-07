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
 * Round 480 (tsc): a block body with NO return statements whose every path
 * throws infers `never`, not void — `import: _id => { throw new Error("…"); }`
 * satisfies `import(id: string): Promise<unknown>` (evaluatorImpl.ts
 * SystemModuleContext). A body with a bare `return;` keeps void.
 */
class ThrowOnlyBodyNeverReturnTest {

    @Test
    fun `throw-only arrow body infers never and satisfies any return`() {
        diagnose(
            """
            interface Ctx {
                import(id: string): Promise<unknown>;
                meta: { url: string };
            }
            export function f(url: string): Ctx {
                const context: Ctx = {
                    import: _id => {
                        throw new Error("Dynamic import not implemented.");
                    },
                    meta: { url },
                };
                return context;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a void body vs a value-returning member still fires`() {
        diagnose(
            """
            interface Ctx {
                make(id: string): number;
            }
            export const c: Ctx = {
                make: _id => {
                    void _id;
                },
            };
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
