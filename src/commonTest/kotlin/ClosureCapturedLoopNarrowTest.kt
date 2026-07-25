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
 * M3.4 (round 416): a captured variable narrowed by a closure-LOCAL guard BEFORE a loop, then
 * used INSIDE the loop, FP'd TS18048 — the closure-capture emitter
 * (`emitTs18048ForClosureCapturedUndefinedReceiver`) used the non-loop-following narrowing
 * variant, which washes the reference back to its declared type at the loop's FlowLoopLabel
 * (back-edge safety). tsc's own checker.ts:8207 trips this: an `expandedParams: readonly Symbol[]
 * | undefined` parameter captured in a `pushFakeScope` callback, guarded `if (!expandedParams)
 * return;`, then read in `for (…; pIndex < expandedParams.length; …)`.
 *
 * Fix: use `getNarrowedTypeForReferenceFollowLoopEntry` (the sibling
 * emitTs18048ForOptionalPropertyAccessReceiver already does, B81.1c), which follows the
 * loop-ENTRY antecedent so a read inside the loop sees the pre-loop narrowing. FP-safe: it only
 * ever narrows MORE (suppresses); an un-guarded captured var still fires (negative control).
 */
class ClosureCapturedLoopNarrowTest {

    @Test
    fun `captured param guarded before a for-loop - used inside - no TS18048`() {
        diagnose(
            """
            export function f(expandedParams: readonly number[] | undefined): void {
                const cb = (add: number) => {
                    if (!expandedParams) return;
                    for (let i = 0; i < expandedParams.length; i++) { const p = expandedParams[i]; }
                };
                cb(1);
            }
            """,
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `captured param guarded before a while-loop - used inside - no TS18048`() {
        diagnose(
            """
            export function f(state: { count: number } | undefined): void {
                const cb = () => {
                    if (!state) return;
                    while (state.count > 0) { state.count = state.count - 1; }
                };
                cb();
            }
            """,
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `un-guarded captured possibly-undefined var in a loop STILL fires - negative control`() {
        diagnose(
            """
            export function f(expandedParams: readonly number[] | undefined): void {
                const cb = () => {
                    for (let i = 0; i < expandedParams.length; i++) {}
                };
                cb();
            }
            """,
        ) should {
            have(any { it.code == 18048 })
        }
    }
}
