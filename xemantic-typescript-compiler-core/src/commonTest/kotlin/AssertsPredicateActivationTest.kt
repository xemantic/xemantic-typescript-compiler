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
 * Local corner-case tests for M1.5: `asserts` predicates activated end-to-end.
 *
 * Before this fix, `parseType()`'s AssertsKeyword branch ERASED `asserts x is T`
 * to bare `T` and `asserts x` to `void` — `TypePredicate.assertsModifier` was never
 * constructed, so `narrowByAssertCall` (round 43) was dead code: tsc-style
 * `Debug.assert(...)` / `Debug.assertIsDefined(...)` guards narrowed NOTHING, and a
 * bodied assert function's "return type" was the bare predicate target (a bodied
 * `asserts x is string` with no return even drew implicit-return errors).
 *
 * Sharp signals per case: the exact diagnostic code that fires without the
 * activation (TS2322 through the flow-narrowing var-decl consumer; TS2355/TS2366/
 * TS7030 through the implicit-return classifier), asserted absent — with negative
 * controls proving each assertion is not vacuous.
 */
class AssertsPredicateActivationTest {

    @Test
    fun `negative control - without an assertion a union assigned to a member errors TS2322`() {
        diagnose(
            """
            function h(x: number | string) {
                const y: number = x;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    /**
     * The B469 flow-narrowed call-arg consumer — the var-decl path is lenient for
     * undefined-unions, so the nullish-shaped positive tests below use this signal.
     */
    @Test
    fun `negative control - without an assertion a maybe-undefined arg errors TS2345`() {
        diagnose(
            """
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                takesString(x);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `asserts x is T with a concrete T narrows the union after the call`() {
        diagnose(
            """
            declare function assertNum(x: unknown): asserts x is number;
            function h(x: number | string) {
                assertNum(x);
                const y: number = x;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `bare asserts x with the reference passed directly narrows by truthiness`() {
        diagnose(
            """
            declare function assert(x: unknown): asserts x;
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                assert(x);
                takesString(x);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    /**
     * The tsc `Debug.assert(x !== undefined)` shape: a bare `asserts cond` predicate
     * narrows by the asserted CONDITION expression — the argument only MENTIONS the
     * walked reference (this is what the round-385 pre-check had to widen to
     * path-containment for).
     */
    @Test
    fun `bare asserts cond narrows by the asserted condition argument`() {
        diagnose(
            """
            declare function assert(cond: unknown): asserts cond;
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                assert(x !== undefined);
                takesString(x);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `condition-argument narrowing composes with typeof narrowing`() {
        diagnose(
            """
            declare function assert(cond: unknown): asserts cond;
            function f(x: string | number) {
                assert(typeof x === "string");
                const y: string = x;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /**
     * The tsc `Debug.assertIsDefined(x)` shape end-to-end: a NAMESPACE-member assert
     * function (resolved via the namespace symbol's exports, not receiver typing)
     * whose target is `NonNullable<T>` (unmodeled utility → special-cased as
     * null/undefined exclusion).
     */
    @Test
    fun `a namespace-member assert with a NonNullable target narrows away undefined`() {
        diagnose(
            """
            declare namespace Debug {
                function assertIsDefined<T>(value: T, message?: string): asserts value is NonNullable<T>;
            }
            declare function takesString(s: string): void;
            function g(x: string | undefined) {
                Debug.assertIsDefined(x);
                takesString(x);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    /**
     * An assertion signature returns void: a bodied assert function with NO return
     * statement must draw none of the implicit-return family (pre-fix, `asserts x is
     * string` parsed as bare `string` → a return-less body was flagged).
     */
    @Test
    fun `an asserts body needs no return statement`() {
        diagnose(
            """
            function assertIsString(x: unknown): asserts x is string {
                if (typeof x !== "string") throw new Error("not a string");
            }
            assertIsString("ok");
            """
        ) should {
            have(none { it.code in setOf(2355, 2366, 7030) })
        }
    }

    @Test
    fun `negative control - a non-asserts x is T guard still requires value-returning paths`() {
        diagnose(
            """
            function isString(x: unknown): x is string {
            }
            isString("ok");
            """
        ) should {
            have(any { it.code in setOf(2355, 2366, 7030) })
        }
    }
}