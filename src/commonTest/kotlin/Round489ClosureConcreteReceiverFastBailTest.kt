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
 * Round 489 (M5.1 perf): `emitTs18048ForClosureCapturedUndefinedReceiver` runs for EVERY
 * property-access with an Identifier receiver and used to scan ALL of the flow graph's
 * `closureStarts` (hundreds on a big source like checker.ts) BEFORE resolving the receiver
 * type. The emitter can only fire when the (narrowed) receiver type is a union containing
 * `undefined`, and narrowing only ever SUBSETS the raw type, so the round-489 fast path
 * resolves the receiver type first and bails before the O(closureStarts) scan whenever the
 * raw type is concrete (not a `T | undefined` union). The captured-`var` case still resolves
 * to `anyType` (B467) and must NOT bail early — it needs the closure to recover its type.
 *
 * This pins that the fast bail is behavior-preserving: a captured CONCRETE receiver draws no
 * TS18048 (skips the scan), while a captured `T | undefined` receiver still fires (the fast
 * path must not bail on undefined-bearing unions).
 */
class Round489ClosureConcreteReceiverFastBailTest {

    private val prelude = """
        declare function outer(cb: () => void): void;
        declare function inner(cb: () => void): void;
    """.trimIndent()

    @Test
    fun `a captured concrete-typed receiver in a closure draws no TS18048 (fast bail)`() {
        // `x` is a captured param typed `string` (no `undefined`). The fast path resolves
        // its type, sees it can never be `undefined`, and bails before the closure scan.
        diagnose(prelude + "\n" + """
            export function f(x: string): void {
                outer(() => {
                    inner(() => {
                        console.log(x.length);
                    });
                });
            }
        """.trimIndent()) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun `a captured maybe-undefined receiver still fires (fast path must not bail on unions)`() {
        // `x` is `string | undefined` — the fast path must NOT bail; the closure scan runs
        // and the unguarded capture is flagged possibly-undefined.
        diagnose(prelude + "\n" + """
            export function g(x: string | undefined): void {
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
    fun `a captured maybe-undefined receiver guarded inside the closure suppresses`() {
        // `x` is `string | undefined` (fast path does NOT bail), but the inner closure
        // guards it — the closure scan + narrowing run and correctly suppress. Confirms the
        // fast path does not short-circuit a case that needs the full walk.
        diagnose(prelude + "\n" + """
            export function k(x: string | undefined): void {
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
}
