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
import kotlin.test.Test

/**
 * Round 414 (M1.12): an INFINITE loop (`while(true)` / `for(;;)` / `do..while(true)`)
 * whose only early exits are `return`/`throw` never falls through — the function's
 * endpoint is unreachable, so TS2366/TS7030/TS2355 must NOT fire. The prior
 * `statementAlwaysReturns` used `!containsBreakOrReturn`, which wrongly counted a
 * `return` inside the loop as a fall-through exit (a return exits the FUNCTION, not
 * the loop), producing ~17 self-compile false positives on infinite-loop helpers
 * (tsc's own `unwrapInnermostStatementOfLabel`, `skipTrivia`, the scanner's char loops).
 *
 * The fix ([infiniteLoopFallsThrough]) excludes plain returns but keeps LABELED-break
 * detection (an unlabeled break at the loop level, or a labeled break anywhere inside
 * including nested loops, DOES let control reach past the loop) — reachabilityChecks5/6
 * f11's `do { do { break test; } while(true); } while(true)` still resolves correctly.
 */
class InfiniteLoopTerminationTest {

    private fun assertNoImplicitReturn(source: String, what: String) {
        have(
            diagnose(source).none { it.code == 2366 || it.code == 7030 || it.code == 2355 },
            "$what must not draw TS2366/TS7030/TS2355",
        )
    }

    private fun assertImplicitReturn(source: String, what: String) {
        have(diagnose(source).any { it.code == 2366 }, "$what must draw TS2366")
    }

    // --- the fixed false-positive cases (an infinite loop with only return exits) ---

    @Test fun whileTrueWithReturnDrawsNothing() {
        assertNoImplicitReturn(
            """
            function f(): number {
                while (true) {
                    return 1;
                }
            }
            """.trimIndent(),
            "while(true) whose only exit is a return",
        )
    }

    @Test fun whileTrueWithConditionalReturnDrawsNothing() {
        // The real tsc shape: unwrapInnermostStatementOfLabel — loop forever or return.
        assertNoImplicitReturn(
            """
            declare const c: boolean;
            function f(): number {
                while (true) {
                    if (c) {
                        return 1;
                    }
                }
            }
            """.trimIndent(),
            "while(true) that either loops forever or returns",
        )
    }

    @Test fun forEverWithReturnDrawsNothing() {
        assertNoImplicitReturn(
            """
            function f(): number {
                for (;;) {
                    return 1;
                }
            }
            """.trimIndent(),
            "for(;;) whose only exit is a return",
        )
    }

    @Test fun doWhileTrueWithReturnDrawsNothing() {
        assertNoImplicitReturn(
            """
            function f(): number {
                do {
                    return 1;
                } while (true);
            }
            """.trimIndent(),
            "do..while(true) whose only exit is a return",
        )
    }

    @Test fun whileTrueWithThrowDrawsNothing() {
        assertNoImplicitReturn(
            """
            declare const c: boolean;
            function f(): number {
                while (true) {
                    if (c) throw new Error();
                }
            }
            """.trimIndent(),
            "while(true) whose only exit is a throw",
        )
    }

    // --- negative controls: an escapable loop still falls through → TS2366 fires ---

    @Test fun whileTrueWithBreakStillFires() {
        // The break makes the endpoint reachable; the value return exercises the TS2366
        // path specifically (a break-only body with no value return draws TS2355 instead).
        assertImplicitReturn(
            """
            declare const c: boolean;
            declare const d: boolean;
            function f(): number {
                while (true) {
                    if (c) break;
                    if (d) return 1;
                }
            }
            """.trimIndent(),
            "while(true) with an unlabeled break (control reaches the end)",
        )
    }

    @Test fun whileTrueWithNestedLabeledBreakStillFires() {
        // f11 preservation: a labeled break in a nested loop escapes THIS loop too.
        assertImplicitReturn(
            """
            declare const c: boolean;
            declare const d: boolean;
            function f(): number {
                outer: while (true) {
                    while (true) {
                        if (c) break outer;
                        if (d) return 1;
                    }
                }
            }
            """.trimIndent(),
            "labeled break escaping a nested loop still lets control reach the end",
        )
    }

    @Test fun nonInfiniteLoopWithReturnStillFires() {
        // A conditional loop may run zero times → the endpoint is reachable.
        assertImplicitReturn(
            """
            declare const cond: boolean;
            function f(): number {
                while (cond) {
                    return 1;
                }
            }
            """.trimIndent(),
            "while(cond) may not execute, so the endpoint is reachable",
        )
    }

    // --- Pattern B: a trailing call to a `never`-returning function diverges ---

    @Test fun trailingNeverCallDrawsNothing() {
        // The real tsc shape: firstIterator — `for (…) return v; Debug.fail("empty");`
        assertNoImplicitReturn(
            """
            declare function fail(): never;
            declare const cond: boolean;
            function f(): number {
                if (cond) return 1;
                fail();
            }
            """.trimIndent(),
            "a trailing call to a never-returning function makes the endpoint unreachable",
        )
    }

    @Test fun trailingNamespaceMemberNeverCallDrawsNothing() {
        // `Debug.fail(...)` — a namespace-member never call (PropertyAccess callee).
        assertNoImplicitReturn(
            """
            declare namespace Debug {
                export function fail(message?: string): never;
            }
            declare const cond: boolean;
            function f(): number {
                if (cond) return 1;
                Debug.fail("nope");
            }
            """.trimIndent(),
            "a trailing Debug.fail(): never call makes the endpoint unreachable",
        )
    }

    @Test fun trailingVoidCallStillFires() {
        // Negative control: a non-never call does NOT diverge — the endpoint is reachable.
        assertImplicitReturn(
            """
            declare function noop(): void;
            declare const cond: boolean;
            function f(): number {
                if (cond) return 1;
                noop();
            }
            """.trimIndent(),
            "a trailing void call does not diverge, so the endpoint is reachable",
        )
    }
}
