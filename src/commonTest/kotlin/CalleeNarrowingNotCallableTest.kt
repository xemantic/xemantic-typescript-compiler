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

    private fun diagnosticsOf(source: String, name: String = "callee.ts") =
        TypeScriptCompiler().compile(source, name).diagnostics

    private fun assertNo2349(source: String) {
        diagnosticsOf(source) should {
            have(none { it.code == 2349 })
        }
    }

    private fun assert2349(source: String) {
        diagnosticsOf(source) should {
            have(any { it.code == 2349 })
        }
    }

    /** `if (fn) fn()` — truthiness guard on an optional-callable identifier param. */
    @Test fun truthinessGuardedIdentifierCall() {
        assertNo2349(
            """
            // @strict: true
            function run(fn: (() => void) | undefined) {
                if (fn) {
                    fn();
                }
            }
            """.trimIndent() + "\n",
        )
    }

    /** `typeof fn === "function"` then call — the positive typeof-function guard
     *  narrows an optional callback to the callable (round 408, narrowByTypeOfGuard). */
    @Test fun typeofFunctionGuard() {
        assertNo2349(
            """
            // @strict: true
            let onEvent: ((s: string) => void) | undefined;
            function fire(s: string) {
                if (typeof onEvent === "function") {
                    onEvent(s);
                }
            }
            """.trimIndent() + "\n",
        )
    }

    /** `typeof x !== "function"` else-branch: the else keeps only the function member. */
    @Test fun typeofFunctionNegatedElse() {
        assertNo2349(
            """
            // @strict: true
            function run(fn: string | (() => void)) {
                if (typeof fn !== "function") {
                    return;
                }
                fn();
            }
            """.trimIndent() + "\n",
        )
    }

    /** `typeof x === "string" ? x : x()` — the false branch narrows to the callable. */
    @Test fun typeofStringTernaryFalseBranch() {
        assertNo2349(
            """
            // @strict: true
            function pick(v: string | (() => string)): string {
                return typeof v === "string" ? v : v();
            }
            """.trimIndent() + "\n",
        )
    }

    /** `fn?.()` — an optional call short-circuits on the nullish member. */
    @Test fun optionalCallDropsNullish() {
        assertNo2349(
            """
            // @strict: true
            function run(fn: (() => void) | undefined) {
                fn?.();
            }
            """.trimIndent() + "\n",
        )
    }

    /** `&&`-chained truthiness guard then call. */
    @Test fun andChainGuard() {
        assertNo2349(
            """
            // @strict: true
            function run(ok: boolean, fn: (() => void) | undefined) {
                if (ok && fn) {
                    fn();
                }
            }
            """.trimIndent() + "\n",
        )
    }

    /**
     * Negative control: an UNGUARDED possibly-undefined callable must still fire
     * TS2349 — the fix only suppresses when narrowing actually removes the
     * non-callable member.
     */
    @Test fun unguardedPossiblyUndefinedStillFires() {
        assert2349(
            """
            // @strict: true
            function run(fn: (() => void) | undefined) {
                fn();
            }
            """.trimIndent() + "\n",
        )
    }

    /**
     * Negative control: a union whose narrowed member is genuinely NON-callable
     * (a string) must still fire — `if (typeof v === "string") v()` narrows to
     * the string, which has no call signatures.
     */
    @Test fun narrowedToNonCallableStillFires() {
        assert2349(
            """
            // @strict: true
            function pick(v: string | (() => string)) {
                if (typeof v === "string") {
                    v();
                }
            }
            """.trimIndent() + "\n",
        )
    }
}
