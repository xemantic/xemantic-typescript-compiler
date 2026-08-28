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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.71)(b) A BLOCK-scoped declaration inside a NESTED function shadows an
 * ENCLOSING function's local of the same name — and it was the one shadow shape
 * nothing covered.
 *
 * `currentLocalTypes` is flat and first-decl-wins, so a function body enters on a
 * COPY of its enclosing scope and three mechanisms exist to keep a shadowing
 * declaration from reading the inherited binding:
 *
 *  * round 351's [Checker.applyBodyLocalShadowing] — a declaration at the nested
 *    function's TOP level (the `m2` control);
 *  * round 460's [Checker.applyAmbiguousBlockScopedLocals] — two declarations of one
 *    name in ONE body, registered as `any` (the `m3` control);
 *  * round 455's [Checker.applyNestedGlobalShadow] — a BLOCK-scoped declaration
 *    shadowing a GLOBAL or file-level binding.
 *
 * The fourth combination — a BLOCK-scoped declaration inside a NESTED function
 * shadowing an ENCLOSING FUNCTION's local — was none of them, and the flat map kept
 * the enclosing binding. The shipped consequence is a false TS2322 at every
 * assignment to the inner name, reported against the WRONG declaration's type; tsc
 * 7.0.2 is silent on all of it.
 *
 * The site that names it is tsc's own `moduleNameResolver.ts`: `secondaryLookup`'s
 * `let result: Resolved | undefined` inside an `if` block resolved to
 * `resolveTypeReferenceDirective`'s `result`. That row is invisible on today's 8-profile
 * grid only because the OUTER declaration's initializer is an optional chain and
 * answers `any` — so this pin, not the dashboard, is the instrument, and (CHK.71)(a)
 * (the optional-chain receiver half) is what makes it visible there.
 *
 * The `m4` case was WRITTEN as a control and measured as a POSITIVE: on the parent the
 * inner assignment reports `Type 'Inner' is not assignable to type 'Outer'` — against the
 * ENCLOSING declaration — so the first TS2322 in the file already carries the wrong
 * source type, and the arm that reverts the fix reddens it. Recorded as a positive.
 *
 * `p2` is what stops the fix degenerating into blanket suppression: an ANNOTATED
 * declaration records its OWN annotation, so a genuinely wrong assignment to the inner
 * name is still caught. Un-annotated declarations record `any` deliberately — a
 * block-scoped inferred type must not be claimed for reads outside the block.
 */
class NestedFunctionBlockLocalShadowTest {

    private val prelude = """
        interface Outer { o: number; }
        interface Inner { i: number; }
        declare function mkO(): Outer | undefined;
        declare function mkI(): Inner | undefined;
        declare function mkNum(): number;
        declare const flag: boolean;
    """.trimIndent() + "\n"

    private fun d(body: String) = diagnose(prelude + body.trimIndent())

    // ---- positives ------------------------------------------------------------

    @Test
    fun `a nested function's BLOCK-scoped local shadows the enclosing function's local`() {
        val ds = d(
            """
            function m1() {
              let result = mkO();
              function inner(): Inner | undefined {
                if (flag) {
                  let result: Inner | undefined;
                  result = mkI();
                  return result;
                }
                return undefined;
              }
              inner();
              return result;
            }
            """,
        )
        assert(ds.none { it.code == 2322 })
    }

    @Test
    fun `the shadowing declaration's OWN annotation is what a wrong assignment is judged against`() {
        val ds = d(
            """
            function p2() {
              let result = mkO();
              function inner(): void {
                if (flag) {
                  let result: Inner | undefined;
                  result = mkNum();
                }
              }
              inner();
              return result;
            }
            """,
        )
        // `number` against the INNER declaration's type — not against the enclosing
        // `Outer`, and not suppressed away. tsgo 7.0.2 prints this message verbatim.
        val m = ds.firstOrNull { it.code == 2322 }?.message
        assert(m == "Type 'number' is not assignable to type 'Inner'.")
    }

    // ---- controls: green on BOTH binaries -------------------------------------

    @Test
    fun `CONTROL a nested function's TOP-LEVEL local was already handled`() {
        val ds = d(
            """
            function m2() {
              let result = mkO();
              function inner(): Inner | undefined {
                let result: Inner | undefined;
                result = mkI();
                return result;
              }
              inner();
              return result;
            }
            """,
        )
        assert(ds.none { it.code == 2322 })
    }

    @Test
    fun `CONTROL a plain inner BLOCK of the SAME function was already handled`() {
        val ds = d(
            """
            function m3() {
              let result = mkO();
              if (flag) {
                let result: Inner | undefined;
                result = mkI();
              }
              return result;
            }
            """,
        )
        assert(ds.none { it.code == 2322 })
    }

    @Test
    fun `the ENCLOSING function's own binding is untouched outside the nested one`() {
        val ds = d(
            """
            function m4() {
              let result = mkO();
              function inner(): void {
                if (flag) {
                  let result: Inner | undefined;
                  result = mkI();
                }
              }
              inner();
              result = mkNum();
              return result;
            }
            """,
        )
        // The outer `result` still carries the OUTER declaration's type, so the outer
        // assignment errors — and against `Outer`, never against `Inner`. tsgo 7.0.2
        // prints this message verbatim.
        val m = ds.firstOrNull { it.code == 2322 }?.message
        assert(m == "Type 'number' is not assignable to type 'Outer'.")
    }
}
