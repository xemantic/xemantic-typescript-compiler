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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * M3.4 (round 413) — tsc-faithful flow-walk budget consumption: [narrowTypeFromFlow]
 * (and its loop-entry mirror) now follow LINEAR pass-through antecedents (array
 * mutations, and assignments/calls that don't narrow the walked reference) ITERATIVELY,
 * WITHOUT consuming `NARROW_MAX_DEPTH` — only branch / condition / switch / assertion
 * recursion consumes depth, mirroring tsc's `getTypeAtFlowNode` `while(true)` loop.
 *
 * The blocked shape (tsc's `builder.ts` `createBuilderProgram`, a ~3000-flow-node body):
 * a narrowing established at the TOP of a function (an `asserts` call or a `typeof`
 * guard) followed by a long straight-line statement chain and then a read of the
 * narrowed reference. Pre-fix the walk from the read recursed one level per statement
 * and hit `NARROW_MAX_DEPTH` (2000) before reaching the top-of-function narrowing, so the
 * reference washed back to its declared type and produced a false positive. tsc compiles
 * `builder.ts` clean precisely because its `flowDepth` stays under 2000 on that path — the
 * pass-through assignments cost it no depth.
 *
 * Sharp signal: the narrowing must survive a chain LONGER than `NARROW_MAX_DEPTH`
 * (`CHAIN = 3000 > 2000`). A regression (reverting the loop, or a too-lax "might narrow"
 * gate that iterates past a real narrowing) re-truncates and flips the guarded read back
 * to a TS2322/TS2345. Negative + trivial-depth positive controls prove non-vacuity.
 */
class LinearFlowDepthNarrowingTest {

    /** Longer than NARROW_MAX_DEPTH (2000) so the pre-fix per-statement recursion truncates. */
    private val chain = 3000

    /** N straight-line `let` declarations of DISTINCT names — each a FlowAssignment that is
     *  pass-through for the walked reference `x` (writes a different variable). Joined at the
     *  interpolation site with the template's own indentation, so trimIndent still finds a
     *  non-zero common indent (a column-0 interpolated block would silently disable it). */
    private fun filler(n: Int): List<String> = List(n) { "let v$it = $it;" }

    /** The [assertNarrowingSurvivesDeepLinearChain] shape at a parameterized depth. */
    private fun assertChainSource(fillerLines: Int): String = """
        // @strict: true
        declare function assertIsString(v: unknown): asserts v is string;
        function f(x: unknown): void {
            assertIsString(x);
            ${filler(fillerLines).joinToString("\n            ")}
            const y: string = x;
        }
    """.trimIndent()

    /**
     * An `asserts v is string` at the top narrows `x`; then [chain] pass-through
     * assignments; then a var-decl read of the narrowed `x`. The var-decl assignability
     * path consults flow narrowing, so it compiles clean ONLY if the walk reached the
     * top-of-function assert past the deep chain.
     */
    @Test fun assertNarrowingSurvivesDeepLinearChain() {
        val source = assertChainSource(chain)
        // Warm up (JIT + embedded-lib parse dominate the first compile).
        TypeScriptCompiler().compile(assertChainSource(30), "warmup.ts")
        val (result, elapsed) = measureTimedValue {
            TypeScriptCompiler().compile(source, "deepAssert.ts")
        }
        have(
            result.diagnostics.none { it.code == 2322 || it.code == 2345 },
            "assert narrowing lost past a $chain-node linear chain (NARROW_MAX_DEPTH=2000)",
        )
        // Iterating a linear chain is trivial work — a truncation-storm regression would
        // be visibly slower, but the slack bound just guards against pathology.
        have(elapsed < 60.seconds, "deep-chain narrowing took $elapsed — non-linear walk")
    }

    /**
     * Same shape with a `typeof x === "string"` guard at the top of a block instead of an
     * assert — the FlowCondition sits above the deep pass-through chain.
     */
    @Test fun conditionNarrowingSurvivesDeepLinearChain() {
        val source = """
            // @strict: true
            declare const x: unknown;
            if (typeof x === "string") {
                ${filler(chain).joinToString("\n                ")}
                const y: string = x;
            }
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "deepCond.ts")
        have(
            result.diagnostics.none { it.code == 2322 || it.code == 2345 },
            "condition narrowing lost past a $chain-node linear chain",
        )
    }

    /**
     * A deep chain of pass-through CALLS (whose arguments never mention `x`) between the
     * top narrowing and the read — these follow tsc's `getTypeAtFlowCall`-returns-undefined
     * iteration and must not consume depth either.
     */
    @Test fun assertNarrowingSurvivesDeepCallChain() {
        val source = """
            // @strict: true
            declare function assertIsString(v: unknown): asserts v is string;
            declare function sink(n: number): void;
            function f(x: unknown): void {
                assertIsString(x);
                ${List(chain) { "sink($it);" }.joinToString("\n                ")}
                const y: string = x;
            }
        """.trimIndent()
        TypeScriptCompiler().compile(
            "// @strict: true\ndeclare function sink(n: number): void;\nsink(1);\n", "warmup2.ts",
        )
        val result = TypeScriptCompiler().compile(source, "deepCall.ts")
        have(
            result.diagnostics.none { it.code == 2322 || it.code == 2345 },
            "assert narrowing lost past a $chain-call pass-through chain",
        )
    }

    /**
     * Negative control: with NO narrowing at the top, `unknown` → `string` under strict is
     * a genuine TS2322 — proves the positives above are not vacuous (a walk that silently
     * bailed to declared type would still "pass" them).
     */
    @Test fun unnarrowedReadAfterChainStillErrors() {
        val source = """
            // @strict: true
            function f(x: unknown): void {
                ${filler(chain).joinToString("\n                ")}
                const y: string = x;
            }
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "controlDeep.ts")
        have(
            result.diagnostics.any { it.code == 2322 },
            "negative control lost: unnarrowed unknown → string must be TS2322",
        )
    }

    /**
     * Trivial-depth positive control: the same assert narrowing with a short chain must be
     * clean — pins that the machinery narrows at all (not just that deep chains truncate).
     */
    @Test fun assertNarrowingAtTrivialDepth() {
        val result = TypeScriptCompiler().compile(assertChainSource(3), "shallow.ts")
        have(result.diagnostics.none { it.code == 2322 })
    }
}
