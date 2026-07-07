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
 * Round 414 (M1.12, Pattern C1): a `switch` with a `default` (or otherwise exhaustive)
 * clause "always returns" when every clause, FOLLOWING FALL-THROUGH, reaches a
 * return/throw before control escapes the switch. The prior `switchAlwaysReturns`
 * checked each clause in isolation, so a NON-empty clause that completes normally and
 * falls through to a returning clause was missed — ~6 self-compile TS2366 FPs on tsc's
 * own `parseSimpleUnaryExpression` (`case AwaitKeyword: if (…) return …; /* falls
 * through */ default: return …`) and similar.
 */
class SwitchFallThroughTerminationTest {

    @Test fun caseFallsThroughToReturningDefaultDrawsNothing() {
        // The parseSimpleUnaryExpression shape.
        diagnose(
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
            """,
        ) should {
            have(none { it.code == 2366 || it.code == 7030 || it.code == 2355 })
        }
    }

    @Test fun emptyCaseFallThroughChainDrawsNothing() {
        // case 1: case 2: case 3: return x; — stacked empty labels.
        diagnose(
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
            """,
        ) should {
            have(none { it.code == 2366 || it.code == 7030 || it.code == 2355 })
        }
    }

    @Test fun allCasesReturnDrawsNothing() {
        // Regression sanity: the previously-working case must still pass.
        diagnose(
            """
            declare const t: number;
            function f(): number {
                switch (t) {
                    case 1: return 1;
                    case 2: return 2;
                    default: return 0;
                }
            }
            """,
        ) should {
            have(none { it.code == 2366 || it.code == 7030 || it.code == 2355 })
        }
    }

    // --- negative controls ---

    @Test fun caseThatBreaksOutStillFires() {
        // A `break` escapes the switch → control reaches the end with no return.
        diagnose(
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
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test fun nonExhaustiveSwitchWithoutDefaultStillFires() {
        // No default over a `number` discriminant → not exhaustive → endpoint reachable.
        diagnose(
            """
            declare const t: number;
            function f(): number {
                switch (t) {
                    case 1: return 1;
                    case 2: return 2;
                }
            }
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test fun defaultThatFallsThroughToNothingStillFires() {
        // The default is the LAST clause and completes normally (no return) → escapes.
        diagnose(
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
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }
}
