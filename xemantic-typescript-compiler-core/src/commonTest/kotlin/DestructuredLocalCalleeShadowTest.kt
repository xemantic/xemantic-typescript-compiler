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
 * Round 440 (M3.1, self-compile burn-down): a function-body destructured-const local
 * `const { watchFile } = createWatchFactory()` shadows any same-named top-level/cross-file
 * `function watchFile(...)`. The binder does not bind function-body block-scoped bindings
 * (B83.5), so the arg-check callee resolver `getCalleeType` fell through to the merged
 * `globals` and resolved the CALLEE to tsbuildPublic's `function watchFile<T>(state:
 * SolutionBuilderState<T>, file: string, ...)`, FP-checking the call's args against ITS
 * params (an arrow arg vs `file: string`). `getCalleeType` now consults the
 * currentParamBindingNames side set (already populated by applyCallTypesBodyLocalShadowing)
 * → anyType (suppression-only), mirroring getTypeOfIdentifier.
 */
class DestructuredLocalCalleeShadowTest {

    @Test
    fun `destructured-const local shadows a top-level function callee`() {
        diagnose(
            """
            interface Factory { watchFile: (file: string, cb: (n: string, k: number) => void) => number; }
            declare function makeFactory(): Factory;
            function watchFile(state: number, file: string, cb: (n: string) => void): number { return 0; }
            export function use() {
                const { watchFile } = makeFactory();
                return watchFile("f", (n, k) => { });
            }
            """
        ) should {
            have(none { it.code == 2345 })
            have(none { it.code == 2769 })
        }
    }

    @Test
    fun `simple destructured-array local shadows a top-level function callee`() {
        diagnose(
            """
            declare function makePair(): [(x: string) => void, number];
            function apply(fn: number): void {}
            export function use() {
                const [apply] = makePair();
                apply("hello");
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `direct call to a top-level function still type-checks args - negative control`() {
        // No shadowing local — the direct call to a top-level function must still fire
        // TS2345 on a wrong argument (the shadow fix must not suppress genuine checks).
        diagnose(
            """
            function takesNumber(state: number): number { return 0; }
            export function use() {
                return takesNumber("not a number");
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
