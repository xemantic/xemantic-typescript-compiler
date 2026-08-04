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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Local corner-case tests for the faithful TS2563 depth-trip semantics
 * (round 426, replacing the round-385/B399 per-file flow-node-count proxy).
 *
 * tsc (checker.ts `getTypeAtFlowNode`) reports TS2563 ONLY when a flow WALK
 * recurses 2000 levels deep (`flowDepth === 2000`): it sets
 * `flowAnalysisDisabled`, reports once at the containing function-or-module
 * block's first token, and returns errorType for the rest of that container's
 * flow queries — so definite-assignment analysis there yields NO TS2454
 * (TS2563 OR TS2454, never both, PER CONTAINER). Linear pass-through
 * antecedents are followed iteratively (the `while(true)` loop) without
 * consuming depth, so a straight-line statement chain of ANY length never
 * trips — only recursion through branch joins / conditions does.
 *
 * Sharp signals pinned here, each with a control proving the emitter fires on
 * the same shape when small:
 *  - a deep BRANCH chain trips: exactly ONE TS2563, zero TS2454 in the
 *    tripped container;
 *  - the disable is PER-CONTAINER, not per-file: a sibling function's TS2454
 *    survives a trip in the big function (the old per-file proxy killed it);
 *  - a same-length STRAIGHT-LINE chain does NOT trip (fast-forward consumes
 *    no depth): no TS2563, and the TS2454 still fires.
 */
class CfaTooLargeBailTest {

    /**
     * `let v: number` never assigned, read inside a conditional body — a TS2454
     * shape — behind [ifs] conditional assignments to an UNRELATED var. Each
     * `if` adds a 2-antecedent branch join, and joins consume walk depth
     * (tsc `flowDepth`): 100 stays far under the 2000-recursion trip,
     * 3000 sails past it.
     */
    private fun branchChainSource(ifs: Int): String = """
        // @strict: true
        declare const b: boolean;
        let p: number;
        let v: number;
        ${List(ifs) { "if (b) { p = 1; }" }.joinToString("\n        ")}
        if (b) { const w: number = v; }
    """.trimIndent()

    /**
     * Control: small CFG — 100 branch joins stay under the 2000-recursion trip,
     * so no TS2563, and the TS2454 emitter DOES fire.
     */
    @Test
    fun `a small CFG keeps definite-assignment analysis`() {
        val result = TypeScriptCompiler().compile(branchChainSource(ifs = 100), "small.ts")
        result.diagnostics should {
            have(none { it.code == 2563 })
            have(any { it.code == 2454 })
        }
    }

    /** A 3000-join walk trips the depth limit: exactly ONE TS2563 (one-shot per
     *  container), and every TS2454 in the tripped container is suppressed
     *  (tsc's flow analysis returns errorType there). */
    @Test
    fun `a deep branch chain trips the depth limit`() {
        val result = TypeScriptCompiler().compile(branchChainSource(ifs = 3000), "big.ts")
        assert(result.diagnostics.count { it.code == 2563 } == 1)
        // tsc emits TS2563 OR TS2454, never both — the tripped container is silent.
        result.diagnostics should {
            have(none { it.code == 2454 })
        }
        // tsc reports at the containing block's first token — module container ⇒ the
        // first statement (line 1; line 0 is the directive comment).
        assert(result.diagnostics.first { it.code == 2563 }.line == 1)
    }

    /** The disable is PER-CONTAINER: a trip inside `big()` reports TS2563 at ITS
     *  body's first statement and suppresses only ITS TS2454 — the sibling
     *  function's TS2454 still fires (the old per-file proxy suppressed it). */
    @Test
    fun `the trip is per-container not per-file`() {
        val source = """
            // @strict: true
            declare const b: boolean;
            function big() {
              let p: number;
              let v: number;
              ${List(3000) { "if (b) { p = 1; }" }.joinToString("\n              ")}
              if (b) { const w: number = v; }
            }
            function small() {
              let q: number;
              if (b) { const r: number = q; }
            }
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "percontainer.ts")
        assert(result.diagnostics.count { it.code == 2563 } == 1)
        val ts2563 = result.diagnostics.first { it.code == 2563 }
        assert(ts2563.line == 3)
        // The surviving TS2454 must be small()'s 'q'.
        result.diagnostics.filter { it.code == 2454 } should {
            have(size == 1)
            have("'q'" in single().message)
        }
    }

    /** Round 426b: a >2000-chain of NON-assert calls that merely MENTION the walked
     *  reference must NOT consume depth — tsc resolves the callee's effects signature
     *  (none) and follows the call in the while(true) loop. The pre-426b gate
     *  (path-containment only) recursed per call and tripped TS2563 on tsc's own
     *  diagnosticInformationMap.generated.ts (~2,100 `diag(…, DiagnosticCategory.Error,
     *  …)` statements). The union-receiver property access at the end forces a
     *  narrowing walk back through the whole chain. */
    @Test
    fun `a non-assert call chain does not consume depth`() {
        val source = """
            // @strict: true
            declare function noop(v: unknown): void;
            interface A { p: number }
            interface B { q: number }
            declare const ab: A | B;
            let x: A | B = ab;
            ${List(2500) { "noop(x);" }.joinToString("\n            ")}
            x.p;
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "nonassert.ts")
        result.diagnostics should {
            // tsc getEffectsSignature is undefined for 'noop' -> the while loop iterates.
            have(none { it.code == 2563 })
            // Control: the un-narrowed union access x.p must still report TS2339.
            have(any { it.code == 2339 })
        }
    }

    /** Positive control for the round-426b gate: an ASSERTS-annotated callee DOES
     *  consume a depth level per call (tsc getTypeAtFlowCall recurses through an
     *  effects signature), so the same-length chain trips exactly one TS2563 —
     *  guards against a too-lax gate that iterates past real assertion narrowing
     *  (the round-413 landmine). */
    @Test
    fun `an assert call chain consumes depth`() {
        val source = """
            // @strict: true
            declare function check(v: unknown): asserts v;
            interface A { p: number }
            interface B { q: number }
            declare const ab: A | B;
            let x: A | B = ab;
            ${List(2500) { "check(x);" }.joinToString("\n            ")}
            x.p;
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "assertchain.ts")
        assert(result.diagnostics.count { it.code == 2563 } == 1)
    }

    /** The corpus-motivated shape (largeControlFlowGraph, whose generated test is
     *  JS-emit-only and so pins NO diagnostic): an AUTO-typed array (`const data = []`,
     *  no annotation) mutated by thousands of top-level `data[0] = 0` writes. tsc types
     *  each write's receiver via getFlowTypeOfReference and every mutation RELEVANT to
     *  the evolving array recurses (getTypeAtFlowAssignment/getTypeAtFlowArrayMutation),
     *  so the >2000-write chain trips TS2563 — ours via the dedicated
     *  `evolvingArrayWalkTrips` init walk. Reported once, at the first statement. */
    @Test
    fun `an evolving-array write chain trips`() {
        val source = """
            // @strict: true
            const data = [];
            ${List(3000) { "data[0] = 0;" }.joinToString("\n            ")}
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "evolving.ts")
        assert(result.diagnostics.count { it.code == 2563 } == 1)
        assert(result.diagnostics.first { it.code == 2563 }.line == 1)
    }

    /** Control: 100 evolving-array writes stay under the 2000-depth trip — no TS2563. */
    @Test
    fun `a small evolving-array chain does not trip`() {
        val source = """
            // @strict: true
            const data = [];
            ${List(100) { "data[0] = 0;" }.joinToString("\n            ")}
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "evolvingsmall.ts")
        result.diagnostics should {
            have(none { it.code == 2563 })
        }
    }

    /** Negative control: a straight-line chain of the SAME length does NOT trip —
     *  linear pass-through antecedents are followed iteratively without consuming
     *  depth (tsc getTypeAtFlowNode's while(true) loop) — and the TS2454 fires. */
    @Test
    fun `a straight-line chain does not trip`() {
        val source = """
            // @strict: true
            declare const b: boolean;
            let p: number;
            let v: number;
            ${List(3000) { "p = 1;" }.joinToString("\n            ")}
            if (b) { const w: number = v; }
        """.trimIndent()
        val result = TypeScriptCompiler().compile(source, "straight.ts")
        result.diagnostics should {
            // Fast-forward consumes no depth, so nothing trips and the TS2454 still fires.
            have(none { it.code == 2563 })
            have(any { it.code == 2454 })
        }
    }
}
