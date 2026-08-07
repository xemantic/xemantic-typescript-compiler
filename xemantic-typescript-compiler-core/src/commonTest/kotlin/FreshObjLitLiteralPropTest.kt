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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

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

    private val resolverIface = """
        interface IterationTypesResolver {
            iterableCacheKey: "iterationTypesOfAsyncIterable" | "iterationTypesOfIterable";
            iteratorSymbolName: "asyncIterator" | "iterator";
        }
    """.trimIndent()

    @Test
    fun `fresh literal props against literal-union members are legal`() {
        // The tsc checker.ts IterationTypesResolver var-decl shape.
        diagnose(
            resolverIface + """

            var asyncResolver: IterationTypesResolver = {
                iterableCacheKey: "iterationTypesOfAsyncIterable",
                iteratorSymbolName: "asyncIterator",
            };
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a literal absent from the target union still fails`() {
        diagnose(
            resolverIface + """

            var bad: IterationTypesResolver = {
                iterableCacheKey: "iterationTypesOfAsyncIterable",
                iteratorSymbolName: "wrongLiteral",
            };
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a widened var reference is not fresh and still fails`() {
        // The tsc freshness rule: assigning the reference must still fail even
        // though its initializer's literals would have matched.
        diagnose(
            resolverIface + """

            const notFresh = {
                iterableCacheKey: "iterationTypesOfAsyncIterable",
                iteratorSymbolName: "asyncIterator",
            };
            var viaRef: IterationTypesResolver = notFresh;
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `assignment of a fresh literal to a discriminated union is legal`() {
        // The esDecorators shape: the `kind` literal picks the matching member.
        diagnose(
            """
            interface AEntry { kind: "a"; n: number; }
            interface BEntry { kind: "b"; s: string; }
            type Entry = AEntry | BEntry;
            let top: Entry | undefined;
            function enter(n: number) {
                top = { kind: "a", n };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a fresh literal matching no union member still fails`() {
        diagnose(
            """
            interface AEntry { kind: "a"; n: number; }
            interface BEntry { kind: "b"; s: string; }
            type Entry = AEntry | BEntry;
            let top: Entry | undefined;
            function enter(n: number) {
                top = { kind: "c", n };
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `return ternary branch literals are legal`() {
        // The moduleSpecifiers shape: return ternary of fresh literals, each
        // branch's `kind` literal against the target's literal-union member.
        diagnose(
            """
            interface MSR { kind: "paths" | "redirect" | "relative"; specs: readonly string[]; }
            declare const p: string[];
            declare const cond: boolean;
            function pick(): MSR {
                return cond ? { kind: "paths", specs: p } :
                    { kind: "relative", specs: p };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `ternary-of-literals prop value keeps its literal union`() {
        // The literalTypeOfExpression ConditionalExpression arm.
        diagnose(
            """
            interface Opt { polarity: "plus" | "minus" | "zero"; }
            declare const neg: boolean;
            var o: Opt = { polarity: neg ? "minus" : "plus" };
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
