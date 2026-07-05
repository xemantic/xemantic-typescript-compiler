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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 414 (M1.12, Pattern C1): a `switch` with a `default` (or otherwise exhaustive)
 * clause "always returns" when every clause, FOLLOWING FALL-THROUGH, reaches a
 * return/throw before control escapes the switch. The prior `switchAlwaysReturns`
 * checked each clause in isolation, so a NON-empty clause that completes normally and
 * falls through to a returning clause was missed — ~6 self-compile TS2366 FPs on tsc's
 * own `parseSimpleUnaryExpression` (`case AwaitKeyword: if (…) return …; /* falls
 * through */ default: return …`) and similar.
 */
class SwitchFallThroughTerminationTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n$source", "t.ts")

    private fun assertNoImplicitReturn(source: String, what: String) {
        val r = compile(source)
        assertTrue(
            r.diagnostics.none { it.code == 2366 || it.code == 7030 || it.code == 2355 },
            "$what must not draw TS2366/TS7030/TS2355: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    private fun assertImplicitReturn(source: String, what: String) {
        val r = compile(source)
        assertTrue(
            r.diagnostics.any { it.code == 2366 },
            "$what must draw TS2366: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun caseFallsThroughToReturningDefaultDrawsNothing() {
        // The parseSimpleUnaryExpression shape.
        assertNoImplicitReturn(
            """
            declare const t: number;
            declare const c: boolean;
            declare function a(): number;
            declare function b(): number;
            function f(): number {
                switch (t) {
                    case 1:
                        if (c) {
                            return a();
                        }
                        // falls through
                    default:
                        return b();
                }
            }
            """.trimIndent(),
            "a non-empty case that falls through to a returning default",
        )
    }

    @Test fun emptyCaseFallThroughChainDrawsNothing() {
        // case 1: case 2: case 3: return x; — stacked empty labels.
        assertNoImplicitReturn(
            """
            declare const t: number;
            function f(): number {
                switch (t) {
                    case 1:
                    case 2:
                    case 3:
                        return 1;
                    default:
                        return 0;
                }
            }
            """.trimIndent(),
            "stacked empty case labels falling through to a return",
        )
    }

    @Test fun allCasesReturnDrawsNothing() {
        // Regression sanity: the previously-working case must still pass.
        assertNoImplicitReturn(
            """
            declare const t: number;
            function f(): number {
                switch (t) {
                    case 1: return 1;
                    case 2: return 2;
                    default: return 0;
                }
            }
            """.trimIndent(),
            "every clause returns with a default",
        )
    }

    // --- negative controls ---

    @Test fun caseThatBreaksOutStillFires() {
        // A `break` escapes the switch → control reaches the end with no return.
        assertImplicitReturn(
            """
            declare const t: number;
            declare const c: boolean;
            function f(): number {
                switch (t) {
                    case 1:
                        if (c) return 1;
                        break;
                    default:
                        return 0;
                }
            }
            """.trimIndent(),
            "a case that breaks out of the switch (control reaches the end)",
        )
    }

    @Test fun nonExhaustiveSwitchWithoutDefaultStillFires() {
        // No default over a `number` discriminant → not exhaustive → endpoint reachable.
        assertImplicitReturn(
            """
            declare const t: number;
            function f(): number {
                switch (t) {
                    case 1: return 1;
                    case 2: return 2;
                }
            }
            """.trimIndent(),
            "a switch with no default over a non-literal discriminant",
        )
    }

    @Test fun defaultThatFallsThroughToNothingStillFires() {
        // The default is the LAST clause and completes normally (no return) → escapes.
        assertImplicitReturn(
            """
            declare const t: number;
            declare function log(): void;
            function f(): number {
                switch (t) {
                    case 1: return 1;
                    default:
                        log();
                }
            }
            """.trimIndent(),
            "a default clause that completes normally as the last clause",
        )
    }
}
