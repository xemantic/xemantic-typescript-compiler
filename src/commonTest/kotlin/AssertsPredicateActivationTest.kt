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

    private fun diagnosticsOf(source: String, name: String = "asserts.ts") =
        TypeScriptCompiler().compile(source, name).diagnostics

    private fun assertNone(source: String, vararg codes: Int) {
        val diags = diagnosticsOf(source)
        val hits = diags.filter { it.code in codes.toSet() }
        assertTrue(
            hits.isEmpty(),
            "expected none of TS${codes.joinToString("/TS")}, got: " +
                hits.joinToString { "TS${it.code}: ${it.message}" }
        )
    }

    private fun assertSome(source: String, vararg codes: Int) {
        val diags = diagnosticsOf(source)
        assertTrue(
            diags.any { it.code in codes.toSet() },
            "negative control lost — expected one of TS${codes.joinToString("/TS")}, got: " +
                diags.joinToString { "TS${it.code}" }
        )
    }

    /**
     * Negative controls: without any assertion, (a) the union → member var-decl
     * assignment errors TS2322, and (b) passing a maybe-undefined union to a
     * `string` parameter errors TS2345 (the B469 flow-narrowed call-arg consumer —
     * the var-decl path is lenient for undefined-unions, so the nullish-shaped
     * positive tests below use the call-arg signal instead).
     */
    @Test fun withoutAssertUnionToMemberErrors() {
        assertSome(
            """
            // @strict: true
            function h(x: number | string) {
                const y: number = x;
            }
            """.trimIndent() + "\n",
            2322,
        )
        assertSome(
            """
            // @strict: true
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                takesString(x);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /** `asserts x is T` with a concrete T narrows the union after the call. */
    @Test fun assertsIsTNarrowsUnionAfterCall() {
        assertNone(
            """
            // @strict: true
            declare function assertNum(x: unknown): asserts x is number;
            function h(x: number | string) {
                assertNum(x);
                const y: number = x;
            }
            """.trimIndent() + "\n",
            2322,
        )
    }

    /** Bare `asserts x` with the reference passed directly narrows by truthiness. */
    @Test fun bareAssertsNarrowsDirectArgByTruthiness() {
        assertNone(
            """
            // @strict: true
            declare function assert(x: unknown): asserts x;
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                assert(x);
                takesString(x);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /**
     * The tsc `Debug.assert(x !== undefined)` shape: a bare `asserts cond` predicate
     * narrows by the asserted CONDITION expression — the argument only MENTIONS the
     * walked reference (this is what the round-385 pre-check had to widen to
     * path-containment for).
     */
    @Test fun bareAssertsNarrowsByConditionArg() {
        assertNone(
            """
            // @strict: true
            declare function assert(cond: unknown): asserts cond;
            declare function takesString(s: string): void;
            function f(x: string | undefined) {
                assert(x !== undefined);
                takesString(x);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /** Condition-arg narrowing composes with typeof narrowing. */
    @Test fun bareAssertsNarrowsByTypeofCondition() {
        assertNone(
            """
            // @strict: true
            declare function assert(cond: unknown): asserts cond;
            function f(x: string | number) {
                assert(typeof x === "string");
                const y: string = x;
            }
            """.trimIndent() + "\n",
            2322,
        )
    }

    /**
     * The tsc `Debug.assertIsDefined(x)` shape end-to-end: a NAMESPACE-member assert
     * function (resolved via the namespace symbol's exports, not receiver typing)
     * whose target is `NonNullable<T>` (unmodeled utility → special-cased as
     * null/undefined exclusion).
     */
    @Test fun namespaceAssertIsDefinedNonNullableNarrows() {
        assertNone(
            """
            // @strict: true
            declare namespace Debug {
                function assertIsDefined<T>(value: T, message?: string): asserts value is NonNullable<T>;
            }
            declare function takesString(s: string): void;
            function g(x: string | undefined) {
                Debug.assertIsDefined(x);
                takesString(x);
            }
            """.trimIndent() + "\n",
            2345,
        )
    }

    /**
     * An assertion signature returns void: a bodied assert function with NO return
     * statement must draw none of the implicit-return family (pre-fix, `asserts x is
     * string` parsed as bare `string` → a return-less body was flagged).
     */
    @Test fun assertsBodyNeedsNoReturn() {
        assertNone(
            """
            // @strict: true
            function assertIsString(x: unknown): asserts x is string {
                if (typeof x !== "string") throw new Error("not a string");
            }
            assertIsString("ok");
            """.trimIndent() + "\n",
            2355, 2366, 7030,
        )
    }

    /** Control: a non-asserts `x is T` guard still requires value-returning paths. */
    @Test fun plainPredicateStillRequiresReturns() {
        assertSome(
            """
            // @strict: true
            function isString(x: unknown): x is string {
            }
            isString("ok");
            """.trimIndent() + "\n",
            2355, 2366, 7030,
        )
    }
}
