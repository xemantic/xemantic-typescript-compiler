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
 * Round 482 (M5.1 perf): `emitTs18048ForClosureCapturedUndefinedReceiver` selects
 * the INNERMOST closure (largest `container.pos`) lexically containing the receiver,
 * because the capture / own-local decision and the flowed-in narrowing are all keyed
 * on that closure. The round-482 refactor replaced a `.filter{}.maxByOrNull{}` (which
 * allocated a throwaway list on every property-access with an Identifier receiver — a
 * niche 1.6%-self emitter on the harness JFR) with an allocation-free single-pass
 * max-`container.pos` scan. This pins the invariant the single pass must preserve:
 * with NESTED closures the emitter must resolve against the inner one.
 */
class ClosureCapturedInnermostSelectionTest {

    private val prelude = """
        declare function outer(cb: () => void): void;
        declare function inner(cb: () => void): void;
    """.trimIndent()

    @Test
    fun `a captured maybe-undefined receiver read in the innermost of two nested closures fires`() {
        // `x` is a param of `f` — NOT a local of either closure — so both closures
        // CAPTURE it. The inner closure has no guard, so `x.length` is possibly
        // undefined. Selecting the innermost closure is what makes `x` count as
        // captured (it is not among the inner closure's own locals).
        diagnose(prelude + "\n" + """
            export function f(x: string | undefined): void {
                outer(() => {
                    inner(() => {
                        console.log(x.length);
                    });
                });
            }
        """.trimIndent()) should {
            have(any { it.code == 18048 && "'x'" in it.message })
        }
    }

    @Test
    fun `an inner-closure guard on the captured receiver suppresses`() {
        // The guard lives in the INNER closure; only innermost selection sees it.
        diagnose(prelude + "\n" + """
            export function g(x: string | undefined): void {
                outer(() => {
                    inner(() => {
                        if (x === undefined) return;
                        console.log(x.length);
                    });
                });
            }
        """.trimIndent()) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `a receiver that is the innermost closure's own local never fires`() {
        // Here `x` is DECLARED inside the inner closure — its own local, not a
        // capture — so the emitter must bail (root in closure.localNames).
        diagnose(prelude + "\n" + """
            export function h(): void {
                outer(() => {
                    inner(() => {
                        const x: string = "ok";
                        console.log(x.length);
                    });
                });
            }
        """.trimIndent()) should {
            have(none { it.code == 18048 })
        }
    }
}
