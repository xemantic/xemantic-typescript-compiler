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
 * Round 479: a class METHOD body's local must shadow a same-named leaked
 * module-file variable in the property-access pass (applyBodyLocalShadowing was
 * applied only at FunctionDeclaration/arrow/fn-expr bodies — the round-447
 * trap). fourslashImpl's `let refactors = this.worker(...)` otherwise resolved
 * through merged globals to refactorProvider.ts's `const refactors =
 * new Map<string, Refactor>()`, and the forEach callback param typed as the
 * Map's VALUE type `Refactor` → FP TS2339 on `.actions`.
 */
class MethodBodyLocalShadowingTest {

    @Test
    fun `method-body local shadows a leaked module var`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: provider.ts
            export interface Refactor {
                kinds?: string[];
            }
            const refactors = new Map<string, Refactor>();
            export function registered(): number { return refactors.size; }
            // @filename: impl.ts
            export interface Info { name: string; actions: string[]; }
            export class TestState {
                private worker(): readonly Info[] { return []; }
                public verify(name: string): void {
                    let refactors = this.worker().filter(r => r.name === name);
                    refactors.forEach(r => { void r.actions; });
                }
            }
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a genuinely missing member on the local's type still fires`() {
        diagnose(
            """
            interface Info { name: string; }
            export class TestState {
                public verify(info: Info): string {
                    return info.bogusMember;
                }
            }
            """,
        ) should {
            have(any { it.code == 2339 && "bogusMember" in it.message })
        }
    }
}
