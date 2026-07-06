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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    private fun branchChainSource(ifs: Int): String = buildString {
        append("// @strict: true\n")
        append("declare const b: boolean;\n")
        append("let p: number;\n")
        append("let v: number;\n")
        repeat(ifs) { append("if (b) { p = 1; }\n") }
        append("if (b) { const w: number = v; }\n")
    }

    /** Control: small CFG — no TS2563, and the TS2454 emitter DOES fire. */
    @Test fun smallCfgKeepsDefiniteAssignmentAnalysis() {
        val result = TypeScriptCompiler().compile(branchChainSource(ifs = 100), "small.ts")
        assertTrue(
            result.diagnostics.none { it.code == 2563 },
            "control broken: 100 branch joins must stay under the 2000-recursion trip"
        )
        assertTrue(
            result.diagnostics.any { it.code == 2454 },
            "control broken: conditional-assign-then-read must be TS2454 on a small CFG, got: " +
                result.diagnostics.joinToString { "TS${it.code}" }
        )
    }

    /** A 3000-join walk trips the depth limit: exactly ONE TS2563 (one-shot per
     *  container), and every TS2454 in the tripped container is suppressed
     *  (tsc's flow analysis returns errorType there). */
    @Test fun deepBranchChainTripsDepthLimit() {
        val result = TypeScriptCompiler().compile(branchChainSource(ifs = 3000), "big.ts")
        assertEquals(
            1, result.diagnostics.count { it.code == 2563 },
            "expected exactly ONE TS2563 for the tripped module container, got: " +
                result.diagnostics.filter { it.code == 2563 }.joinToString { "${it.line}:${it.character}" }
        )
        assertTrue(
            result.diagnostics.none { it.code == 2454 },
            "TS2454 must be suppressed in the tripped container (tsc emits TS2563 OR TS2454, never both)"
        )
        // tsc reports at the containing block's first token — module container ⇒ the
        // first statement (line 1; line 0 is the directive comment).
        assertEquals(1, result.diagnostics.first { it.code == 2563 }.line, "TS2563 must anchor at the file's first statement")
    }

    /** The disable is PER-CONTAINER: a trip inside `big()` reports TS2563 at ITS
     *  body's first statement and suppresses only ITS TS2454 — the sibling
     *  function's TS2454 still fires (the old per-file proxy suppressed it). */
    @Test fun tripIsPerContainerNotPerFile() {
        val source = buildString {
            append("// @strict: true\n")
            append("declare const b: boolean;\n")
            append("function big() {\n")
            append("  let p: number;\n")
            append("  let v: number;\n")
            repeat(3000) { append("  if (b) { p = 1; }\n") }
            append("  if (b) { const w: number = v; }\n")
            append("}\n")
            append("function small() {\n")
            append("  let q: number;\n")
            append("  if (b) { const r: number = q; }\n")
            append("}\n")
        }
        val result = TypeScriptCompiler().compile(source, "percontainer.ts")
        assertEquals(1, result.diagnostics.count { it.code == 2563 }, "one trip, one TS2563")
        val ts2563 = result.diagnostics.first { it.code == 2563 }
        assertEquals(3, ts2563.line, "TS2563 anchors at big()'s first statement (let p), not the file top")
        val ts2454s = result.diagnostics.filter { it.code == 2454 }
        assertEquals(
            1, ts2454s.size,
            "the sibling function's TS2454 must SURVIVE the trip in big(), got: " +
                result.diagnostics.joinToString { "TS${it.code}@${it.line}" }
        )
        assertTrue(
            ts2454s.single().message.contains("'q'"),
            "the surviving TS2454 must be small()'s 'q', got: ${ts2454s.single().message}"
        )
    }

    /** Round 426b: a >2000-chain of NON-assert calls that merely MENTION the walked
     *  reference must NOT consume depth — tsc resolves the callee's effects signature
     *  (none) and follows the call in the while(true) loop. The pre-426b gate
     *  (path-containment only) recursed per call and tripped TS2563 on tsc's own
     *  diagnosticInformationMap.generated.ts (~2,100 `diag(…, DiagnosticCategory.Error,
     *  …)` statements). The union-receiver property access at the end forces a
     *  narrowing walk back through the whole chain. */
    @Test fun nonAssertCallChainDoesNotConsumeDepth() {
        val source = buildString {
            append("// @strict: true\n")
            append("declare function noop(v: unknown): void;\n")
            append("interface A { p: number }\n")
            append("interface B { q: number }\n")
            append("declare const ab: A | B;\n")
            append("let x: A | B = ab;\n")
            repeat(2500) { append("noop(x);\n") }
            append("x.p;\n")
        }
        val result = TypeScriptCompiler().compile(source, "nonassert.ts")
        assertTrue(
            result.diagnostics.none { it.code == 2563 },
            "a non-assert call chain must not consume walk depth (tsc getEffectsSignature " +
                "is undefined for 'noop' -> the while loop iterates), got: " +
                result.diagnostics.filter { it.code == 2563 }.joinToString { "${it.line}:${it.character}" }
        )
        assertTrue(
            result.diagnostics.any { it.code == 2339 },
            "control broken: the un-narrowed union access x.p must still report TS2339"
        )
    }

    /** Positive control for the round-426b gate: an ASSERTS-annotated callee DOES
     *  consume a depth level per call (tsc getTypeAtFlowCall recurses through an
     *  effects signature), so the same-length chain trips exactly one TS2563 —
     *  guards against a too-lax gate that iterates past real assertion narrowing
     *  (the round-413 landmine). */
    @Test fun assertCallChainConsumesDepth() {
        val source = buildString {
            append("// @strict: true\n")
            append("declare function check(v: unknown): asserts v;\n")
            append("interface A { p: number }\n")
            append("interface B { q: number }\n")
            append("declare const ab: A | B;\n")
            append("let x: A | B = ab;\n")
            repeat(2500) { append("check(x);\n") }
            append("x.p;\n")
        }
        val result = TypeScriptCompiler().compile(source, "assertchain.ts")
        assertEquals(
            1, result.diagnostics.count { it.code == 2563 },
            "an asserts-callee chain must trip the depth limit exactly once, got: " +
                result.diagnostics.map { "TS${it.code}" }.distinct().joinToString()
        )
    }

    /** The corpus-motivated shape (largeControlFlowGraph, whose generated test is
     *  JS-emit-only and so pins NO diagnostic): an AUTO-typed array (`const data = []`,
     *  no annotation) mutated by thousands of top-level `data[0] = 0` writes. tsc types
     *  each write's receiver via getFlowTypeOfReference and every mutation RELEVANT to
     *  the evolving array recurses (getTypeAtFlowAssignment/getTypeAtFlowArrayMutation),
     *  so the >2000-write chain trips TS2563 — ours via the dedicated
     *  `evolvingArrayWalkTrips` init walk. Reported once, at the first statement. */
    @Test fun evolvingArrayWriteChainTrips() {
        val source = buildString {
            append("// @strict: true\n")
            append("const data = [];\n")
            repeat(3000) { append("data[0] = 0;\n") }
        }
        val result = TypeScriptCompiler().compile(source, "evolving.ts")
        assertEquals(
            1, result.diagnostics.count { it.code == 2563 },
            "a 3000-write evolving-array chain must trip exactly one TS2563, got: " +
                result.diagnostics.map { "TS${it.code}" }.distinct().joinToString()
        )
        assertEquals(
            1, result.diagnostics.first { it.code == 2563 }.line,
            "TS2563 must anchor at the module's first statement (const data, line 1 after the directive)"
        )
    }

    /** Control: a small evolving-array chain stays under the trip — no TS2563. */
    @Test fun smallEvolvingArrayDoesNotTrip() {
        val source = buildString {
            append("// @strict: true\n")
            append("const data = [];\n")
            repeat(100) { append("data[0] = 0;\n") }
        }
        val result = TypeScriptCompiler().compile(source, "evolvingsmall.ts")
        assertTrue(
            result.diagnostics.none { it.code == 2563 },
            "100 evolving-array writes must stay under the 2000-depth trip"
        )
    }

    /** Negative control: a straight-line chain of the SAME length does NOT trip —
     *  linear pass-through antecedents are followed iteratively without consuming
     *  depth (tsc getTypeAtFlowNode's while(true) loop) — and the TS2454 fires. */
    @Test fun straightLineChainDoesNotTrip() {
        val source = buildString {
            append("// @strict: true\n")
            append("declare const b: boolean;\n")
            append("let p: number;\n")
            append("let v: number;\n")
            repeat(3000) { append("p = 1;\n") }
            append("if (b) { const w: number = v; }\n")
        }
        val result = TypeScriptCompiler().compile(source, "straight.ts")
        assertTrue(
            result.diagnostics.none { it.code == 2563 },
            "a straight-line chain must NOT trip the depth limit (fast-forward consumes no depth)"
        )
        assertTrue(
            result.diagnostics.any { it.code == 2454 },
            "the TS2454 must still fire when nothing tripped"
        )
    }
}
