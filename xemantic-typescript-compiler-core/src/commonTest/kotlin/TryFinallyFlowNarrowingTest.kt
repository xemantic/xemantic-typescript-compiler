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
 * Round 458: try/finally flow-graph fix. The finally block runs on EVERY exit
 * path — including an early throw from the try/catch, before their normal
 * completion. The binder previously made the finally block's entry flow the join
 * of only the try/catch NORMAL completion, so a try that always `return`s (or
 * throws) left that completion unreachable → every read in the finally washed to
 * `never` → spurious TS2339 on cleanup code (tsc's checker.ts
 * `checkGrammarRegularExpressionLiteral` resets `scanner` in its finally).
 *
 * The fix joins the PRE-TRY flow into the finally block's entry (the exceptional
 * early-exit path). The flow that continues AFTER the whole statement stays the
 * normal completion — the finally's exceptional-inclusive flow must not widen away
 * the try/catch narrowing for the statements that follow.
 */
class TryFinallyFlowNarrowingTest {

    private val prelude = """
        interface Scanner { setText(s: string): void; scan(): void; }
        declare function createScanner(): Scanner;

    """.trimIndent()

    @Test
    fun `a finally after a try that always returns does not wash reads to never`() {
        diagnose(
            prelude + """
            var scanner: Scanner | undefined;
            function f(): boolean {
                scanner ??= createScanner();
                scanner.setText("x");
                try {
                    scanner.scan();
                    return true;
                } finally {
                    scanner.setText("");
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a finally after a try that always throws does not wash reads to never`() {
        diagnose(
            prelude + """
            var scanner: Scanner | undefined;
            function g(): void {
                scanner ??= createScanner();
                try {
                    throw new Error("boom");
                } finally {
                    scanner.setText("");
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a finally sees pre-try narrowing even when the try always returns`() {
        // `x` narrowed to the object member before the try; with the try always
        // returning, the finally must still see that narrowing (not a `never` wash),
        // so `x.foo()` in the finally does not fire TS2339.
        diagnose(
            """
            function h(x: { foo(): void } | number): void {
                if (typeof x === "number") return;
                try {
                    return;
                } finally {
                    x.foo();
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `statements after a try-finally see the try's normal completion - not the exceptional widening`() {
        // A pre-`return` guard inside the try narrows `x`; after the try/finally the
        // narrowing must survive (the finally's exceptional entry must not leak into
        // the post-statement flow). Assigning the narrowed `x` back to a `string`
        // local must not fire TS2322.
        diagnose(
            """
            function k(x: string | undefined): string {
                try {
                    if (x === undefined) return "";
                } finally {
                    // cleanup
                }
                const s: string = x;
                return s;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
