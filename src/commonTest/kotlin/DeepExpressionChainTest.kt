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
import kotlin.test.assertNotNull

/**
 * Local corner-case tests for DEEP binary-expression chains.
 *
 * Regression origin (2026-07-02): after the inline catch(StackOverflowError) removal,
 * `spreadOverrideExpr`'s BinaryExpression case — which recursed into `e.left`, the deep
 * side of a left-associative chain — overflowed on `binderBinaryExpressionStress`
 * (a 6,452-term `a + b + c + …` chain). Historically the init boundary guard had been
 * absorbing that overflow as an invisible TS2589, so the corpus test stayed green.
 *
 * The generated TS-corpus suite pins only the corpus shape (one left-associative `+`
 * chain at one depth). These tests pin the INVARIANT — every full-tree walker must
 * survive deep chains in BOTH associativity directions and inside walker-recursion
 * contexts — at depths beyond the corpus, and they assert the sharp signal: a compile
 * that "succeeds" because the init boundary guard swallowed an overflow still carries
 * a TS2589 diagnostic, which every test here rejects explicitly.
 */
class DeepExpressionChainTest {

    private fun compileExpectingNoOverflow(source: String): CompilationResult {
        val result = TypeScriptCompiler().compile(source, "deepChain.ts")
        // TS2589 at position 0 is the init boundary guard's overflow report
        // (reportCheckerStackOverflow) — a masked StackOverflowError, not a pass.
        val overflow = result.diagnostics.firstOrNull { it.code == 2589 }
        have(overflow == null, "checker stack overflow swallowed by the init boundary guard")
        return result
    }

    /** The corpus shape at ~1.5× its depth: `((a + a) + a) + …` — deep down the LEFT spine. */
    @Test fun deepLeftAssociativePlusChain() {
        val terms = 10_000
        val source = "var a = 1;\nvar r = " + "a + ".repeat(terms - 1) + "a;\n"
        val result = compileExpectingNoOverflow(source)
        val js = assertNotNull(result.javascript, "no JS emitted for the left-associative chain")
        have(js.contains("var r = a + a"), "chain head missing from emitted JS")
        have(js.length > 3 * terms, "emitted JS suspiciously short — chain truncated?")
    }

    /** The mirror image the corpus does NOT cover: `a = (a = (a = 1))` — deep down the RIGHT spine. */
    @Test fun deepRightAssociativeAssignmentChain() {
        val assignments = 10_000
        val source = "var a;\n" + "a = ".repeat(assignments) + "1;\n"
        val result = compileExpectingNoOverflow(source)
        val js = assertNotNull(result.javascript, "no JS emitted for the right-associative chain")
        have(js.contains("a = a = "), "assignment chain missing from emitted JS")
        have(js.length > 3 * assignments, "emitted JS suspiciously short — chain truncated?")
    }

    /** Wrapper unwrapping stays iterative when parenthesized groups alternate with the spine. */
    @Test fun deepChainWithParenthesizedGroups() {
        val groups = 5_000
        val source = "var a = 1;\nvar r = " + "(a + a) + ".repeat(groups) + "a;\n"
        val result = compileExpectingNoOverflow(source)
        val js = assertNotNull(result.javascript, "no JS emitted for the parenthesized-group chain")
        have(js.contains("(a + a) + (a + a)"), "parenthesized groups missing from emitted JS")
    }

    /**
     * A deep chain nested where walkers RECURSE before iterating — an object-literal
     * property initializer passed as a call argument (`spreadOverrideExpr` re-enters
     * through PropertyAssignment.initializer and CallExpression arguments).
     */
    @Test fun deepChainInsideObjectLiteralCallArgument() {
        val terms = 10_000
        val source = """
            var a = 1;
            function f(x) { return x; }
            f({ p: ${"a + ".repeat(terms - 1)}a });
        """.trimIndent()
        val result = compileExpectingNoOverflow(source)
        val js = assertNotNull(result.javascript, "no JS emitted for the nested chain")
        have(js.contains("f({ p: a + a"), "nested chain missing from emitted JS")
    }

    /**
     * Chain operands that are themselves the walker's LEAF work — parenthesized
     * object literals with spreads (`checkOneObjectLiteralSpreadOverride` runs per
     * operand while the binary spine must stay on the iterative worklist).
     */
    @Test fun deepChainOfSpreadObjectLiteralOperands() {
        val terms = 2_000
        val source = "var o = { x: 1 };\nvar r = " +
            "({ ...o, y: 1 }) + ".repeat(terms - 1) + "({ ...o, y: 1 });\n"
        val result = compileExpectingNoOverflow(source)
        assertNotNull(result.javascript, "no JS emitted for the spread-operand chain")
    }
}
