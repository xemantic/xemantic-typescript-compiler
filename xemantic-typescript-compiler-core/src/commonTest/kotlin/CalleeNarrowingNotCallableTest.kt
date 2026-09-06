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
 * Round 408 (M3.4 slice): a callee reference typed `F | undefined` that a
 * truthiness / typeof / optional-call (`?.()`) guard narrows to the callable `F`
 * must NOT fire TS2349 "This expression is not callable."
 *
 * `getCalleeType` resolves an Identifier callee via `currentLocalTypes` (the
 * DECLARED type) without consulting flow narrowing, so `if (fn) fn()` and
 * `fn?.()` FP-fired the union "Not all constituents ... are callable." verdict.
 * The fix re-narrows the callee reference (and drops nullish members under an
 * optional call) before the callability check — both operations only REMOVE
 * constituents, so the negative controls (an UNGUARDED possibly-undefined callee,
 * and a genuinely non-callable narrowed member) must still fire.
 */
class CalleeNarrowingNotCallableTest {

    /** `if (fn) fn()` — truthiness guard on an optional-callable identifier param. */
    @Test
    fun `a truthiness-guarded identifier call is callable`() {
        diagnose(
            """
            function run(fn: (() => void) | undefined) {
                if (fn) {
                    fn();
                }
            }
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    /**
     * `typeof fn === "function"` then call — the positive typeof-function guard
     * narrows an optional callback to the callable (round 408, narrowByTypeOfGuard).
     */
    @Test
    fun `a typeof function guard narrows an optional callback to the callable`() {
        diagnose(
            """
            let onEvent: ((s: string) => void) | undefined;
            function fire(s: string) {
                if (typeof onEvent === "function") {
                    onEvent(s);
                }
            }
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    /** `typeof x !== "function"` else-branch: the else keeps only the function member. */
    @Test
    fun `a negated typeof function guard keeps only the function member`() {
        diagnose(
            """
            function run(fn: string | (() => void)) {
                if (typeof fn !== "function") {
                    return;
                }
                fn();
            }
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    /** `typeof x === "string" ? x : x()` — the false branch narrows to the callable. */
    @Test
    fun `a typeof string ternary false branch narrows to the callable`() {
        diagnose(
            """
            function pick(v: string | (() => string)): string {
                return typeof v === "string" ? v : v();
            }
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    /** `fn?.()` — an optional call short-circuits on the nullish member. */
    @Test
    fun `an optional call drops the nullish member`() {
        diagnose(
            """
            function run(fn: (() => void) | undefined) {
                fn?.();
            }
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    /** `&&`-chained truthiness guard then call. */
    @Test
    fun `an and-chained truthiness guard narrows the callee`() {
        diagnose(
            """
            function run(ok: boolean, fn: (() => void) | undefined) {
                if (ok && fn) {
                    fn();
                }
            }
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    /**
     * Negative control: an UNGUARDED possibly-undefined callable must still fire —
     * the fix only suppresses when narrowing actually removes the non-callable member.
     *
     * (CHK.97) the CODE moved from TS2349 to **TS2722**: tsc checks nullability before
     * it asks for signatures (checker.ts:37038 above :37054), so a nullish member is
     * "Cannot invoke an object which is possibly 'undefined'." and never the case-b
     * "Not all constituents … are callable". Measured identical on tsgo 7.0.2 and
     * pristine `typescript@6.0.3`; the old expectation pinned a divergence.
     */
    @Test
    fun `negative control - an unguarded possibly-undefined callee still fires`() {
        diagnose(
            """
            function run(fn: (() => void) | undefined) {
                fn();
            }
            """
        ) should {
            have(any { it.code == 2722 })
            have(none { it.code == 2349 })
        }
    }

    /**
     * Negative control: a union whose narrowed member is genuinely NON-callable
     * (a string) must still fire — `if (typeof v === "string") v()` narrows to
     * the string, which has no call signatures.
     */
    @Test
    fun `negative control - a callee narrowed to a non-callable member still fires`() {
        diagnose(
            """
            function pick(v: string | (() => string)) {
                if (typeof v === "string") {
                    v();
                }
            }
            """
        ) should {
            have(any { it.code == 2349 })
        }
    }
}
