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
 * Round 479: a closure that is an ARGUMENT of a call whose callee chain is
 * rooted at `root?.` executes only when `root` is non-nullish, so a captured
 * read of `root` inside it draws no TS18048 —
 * `program?.getSourceFiles().slice().sort().forEach(f => program.x(f))`
 * (tsc harness incrementalUtils.ts getProgramStructure).
 */
class OptionalChainClosureGuardTest {

    private val prelude = """
        interface Program {
            getSourceFiles(): string[];
            forEachResolvedModule(f: string): void;
        }
    """.trimIndent()

    @Test
    fun `optional-chain-rooted callback capture is non-nullish`() {
        diagnose(prelude + """

            export function getProgramStructure(program: Program | undefined): string[] {
                const baseline: string[] = [];
                program?.getSourceFiles().slice().sort().forEach(f => {
                    baseline.push(f);
                    program.forEachResolvedModule(f);
                });
                return baseline;
            }
        """.trimIndent()) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `negative control - a sibling call without the chain still fires`() {
        diagnose(prelude + """

            declare function run(cb: () => void): void;
            export function f(program: Program | undefined): void {
                run(() => {
                    program.forEachResolvedModule("x");
                });
            }
        """.trimIndent()) should {
            have(any { it.code == 18048 && "'program'" in it.message })
        }
    }
}
