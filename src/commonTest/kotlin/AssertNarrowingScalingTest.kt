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
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Local corner-case tests for the P0 services-profile hang (round 385): flow-call
 * narrowing used to re-enter the flow walker per visited FlowCall with NO memoization.
 *
 * The re-entry shape: narrowing reference `x` walks the flow graph; every FlowCall
 * whose callee is a PropertyAccess chain (`utils.dbg.keep(...)`) resolves the callee
 * via [resolvePropertyMethodDecl] → getTypeOfExpression(`utils.dbg`) →
 * property-access narrowing → a FRESH flow walk for path "utils.dbg" from that
 * call's own flow position — which visits every FlowCall below it, each re-resolving
 * its callee, recursively. On N sequential calls whose arguments mention the walked
 * paths this is Θ(2^N). tsc's own services sources are exactly this
 * `Debug.assert(...)`-dense shape (the observed 30+ CPU-minute hang inside ONE
 * statement) — made worse by the fact that our parser currently ERASES
 * `asserts x is T` return types (parseType's AssertsKeyword stub), so all that
 * exponential resolution work discovered "not a predicate" every single time.
 *
 * The fix (mirroring tsc checker.ts): a per-outermost-request callee-decl memo
 * (tsc `links.effectsSignature`), a per-invocation flow-node memo (tsc
 * `sharedFlowNodes`), an argument-path pre-check before any callee resolution, and
 * live-depth (tsc `flowDepth === 2000`) + cumulative-visit budgets shared across
 * re-entrant walks.
 *
 * Sharp signals: completion at a depth where the pre-fix walker needs ~2^120 visits
 * (wall-clock completion IS the invariant), and a negative/positive control pair
 * proving the memoized path still APPLIES `x is T` predicate narrowing (a
 * bail-to-declared "fix" would pass the timing test but flip the positive control
 * back to TS2322).
 */
class AssertNarrowingScalingTest {

    /**
     * N sequential assert-SHAPED calls (their `asserts v is Dbg` return is parser-erased
     * today, exactly like tsc's `Debug.assert` calls compile for us), each mentioning
     * BOTH the outer walked path (`x`, so the argument-path pre-check does not
     * short-circuit the outer walk) AND the callee chain's own receiver path
     * (`utils.dbg`, so every re-entrant walk finds matching calls below itself and must
     * resolve them too — the exponential cascade). The final `if (utils.dbg.isStr(x))`
     * is a LIVE `x is string` predicate: the guarded var-decl compiles clean only if
     * narrowing still applies through the memoized machinery.
     */
    private fun callDenseSource(calls: Int): String = """
        // @strict: true
        interface Dbg {
            keep(v: unknown, w: unknown): asserts v is Dbg;
            isStr(w: unknown): w is string;
        }
        interface Utils { dbg: Dbg; }
        declare const utils: Utils;
        declare const x: unknown;
        ${List(calls) { "utils.dbg.keep(utils.dbg, x);" }.joinToString("\n        ")}
        if (utils.dbg.isStr(x)) {
            const y: string = x;
        }
    """.trimIndent()

    /**
     * Negative control: with NO predicate guard, `unknown` is not assignable to
     * `string` under strict — the checker must emit TS2322. This proves the
     * positive tests below are not vacuous (a checker that never compared the
     * assignment would "pass" them without any narrowing at all).
     */
    @Test fun unnarrowedUnknownToStringErrors() {
        val source = """
            // @strict: true
            declare const x: unknown;
            const y: string = x;
        """.trimIndent() + "\n"
        val result = TypeScriptCompiler().compile(source, "control.ts")
        have(result.diagnostics.any { it.code == 2322 })
    }

    /** Positive control at trivial depth: the `x is string` predicate narrows → clean. */
    @Test fun predicateNarrowingStillApplies() {
        val result = TypeScriptCompiler().compile(callDenseSource(calls = 2), "narrowed.ts")
        have(result.diagnostics.isEmpty())
    }

    /**
     * The P0 invariant: 120 sequential re-entry-triggering calls compile at all.
     * Pre-fix this needs on the order of 2^120 walker visits (the services profile
     * hung for 30+ CPU-minutes on far fewer); post-fix it is tens of thousands of
     * memoized visits (well under a second — the bound below is deliberately slack
     * for cold-JIT CI machines, while remaining ~infinitely far from exponential).
     * The compile must ALSO stay clean: the trailing predicate guard narrows through
     * the same memoized walk the dense calls exercised.
     */
    @Test fun callDenseNarrowingScalesNearLinearly() {
        // Warm-up at a smaller size (JIT + embedded-lib parse dominate the first compile).
        TypeScriptCompiler().compile(callDenseSource(calls = 30), "warmup.ts")
        val (result, elapsed) = measureTimedValue {
            TypeScriptCompiler().compile(callDenseSource(calls = 120), "dense.ts")
        }
        have(result.diagnostics.isEmpty())
        have(elapsed < 60.seconds, "superlinear re-entry is back")
    }
}
