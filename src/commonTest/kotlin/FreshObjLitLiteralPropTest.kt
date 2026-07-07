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
 */

package com.xemantic.typescript.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 435: a FRESH object literal's literal-valued props keep their literal
 * types when checked against a target member that contains literal types (tsc
 * contextual literal types) — `getTypeOfObjectLiteral` widens (`kind: "paths"`
 * → string), so the relation engine's [propertiesRelatedTo] retries with the
 * un-widened literal recovered from the member symbol's PropertyAssignment,
 * gated to the CURRENT fresh literal's source range (`freshObjLitRange`, set by
 * the var-decl / assignment / conditional-return consumers).
 *
 * tsc's own shapes: checker.ts's IterationTypesResolver tables (var-decl),
 * esDecorators.ts's `top = { kind: "class", … }` (assignment to a discriminated
 * union), moduleSpecifiers.ts's return ternary of `{ kind: "paths", … }`.
 *
 * The freshness gate is LOAD-BEARING: a WIDENED var reference
 * (`const x = { kind: "a" }; const y: T = x`) must still fail — tsc widens
 * non-contextual const-decl literals, so the reference is genuinely `string`.
 */
class FreshObjLitLiteralPropTest {

    private fun ts2322s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2322 }

    private val resolverIface = """
        interface IterationTypesResolver {
            iterableCacheKey: "iterationTypesOfAsyncIterable" | "iterationTypesOfIterable";
            iteratorSymbolName: "asyncIterator" | "iterator";
        }
    """.trimIndent()

    /** The tsc checker.ts IterationTypesResolver var-decl shape. */
    @Test fun freshLiteralPropsAgainstLiteralUnionMembersAreLegal() {
        val diags = ts2322s(
            """
            $resolverIface
            var asyncResolver: IterationTypesResolver = {
                iterableCacheKey: "iterationTypesOfAsyncIterable",
                iteratorSymbolName: "asyncIterator",
            };
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: a literal absent from the target union still fails. */
    @Test fun literalNotInUnionStillFires() {
        val diags = ts2322s(
            """
            $resolverIface
            var bad: IterationTypesResolver = {
                iterableCacheKey: "iterationTypesOfAsyncIterable",
                iteratorSymbolName: "wrongLiteral",
            };
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for a literal outside the union")
    }

    /** NEGATIVE control (the tsc freshness rule): a WIDENED var reference is not
     *  fresh — assigning it must still fail even though its initializer's literals
     *  would have matched. */
    @Test fun widenedVarReferenceStillFires() {
        val diags = ts2322s(
            """
            $resolverIface
            const notFresh = {
                iterableCacheKey: "iterationTypesOfAsyncIterable",
                iteratorSymbolName: "asyncIterator",
            };
            var viaRef: IterationTypesResolver = notFresh;
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for the widened (non-fresh) reference")
    }

    /** The esDecorators shape: assignment of a fresh literal to a let typed as a
     *  discriminated union — the `kind` literal picks the matching member. */
    @Test fun assignmentToDiscriminatedUnionIsLegal() {
        val diags = ts2322s(
            """
            interface AEntry { kind: "a"; n: number; }
            interface BEntry { kind: "b"; s: string; }
            type Entry = AEntry | BEntry;
            let top: Entry | undefined;
            function enter(n: number) {
                top = { kind: "a", n };
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: a fresh literal matching NO union member still fails. */
    @Test fun assignmentToDiscriminatedUnionWrongKindStillFires() {
        val diags = ts2322s(
            """
            interface AEntry { kind: "a"; n: number; }
            interface BEntry { kind: "b"; s: string; }
            type Entry = AEntry | BEntry;
            let top: Entry | undefined;
            function enter(n: number) {
                top = { kind: "c", n };
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for a kind outside the union")
    }

    /** The moduleSpecifiers shape: return ternary of fresh literals, each branch's
     *  `kind` literal against the target's literal-union member. */
    @Test fun returnTernaryBranchLiteralsAreLegal() {
        val diags = ts2322s(
            """
            interface MSR { kind: "paths" | "redirect" | "relative"; specs: readonly string[]; }
            declare const p: string[];
            declare const cond: boolean;
            function pick(): MSR {
                return cond ? { kind: "paths", specs: p } :
                    { kind: "relative", specs: p };
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** A ternary-of-literals PROP VALUE also keeps its literal union (the
     *  literalTypeOfExpression ConditionalExpression arm). */
    @Test fun ternaryOfLiteralsPropValueIsLegal() {
        val diags = ts2322s(
            """
            interface Opt { polarity: "plus" | "minus" | "zero"; }
            declare const neg: boolean;
            var o: Opt = { polarity: neg ? "minus" : "plus" };
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }
}
