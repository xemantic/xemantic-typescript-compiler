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
 * (CHK.42) A function body nested in a `return` EXPRESSION is checked at all.
 *
 * It was the one expression position that did not reach `walkFunctionBodiesInExpr`:
 * a file-level var-decl initializer reached it, a var-decl initializer inside a
 * function body reached it, a CALL ARGUMENT reached it, an object-literal property
 * value reached it — and `return (node) => { … }` / `return { m(node) { … } }` /
 * `return ( … )` did not. So NO diagnostic could fire inside such a body, in either
 * direction, which is why (CHK.40)'s return-contextual-typing work had to be pinned
 * as TS7006 suppression plus a hover rather than as a type error.
 *
 * Both `ReturnStatement` arms carry the walk, deliberately: the spine anchor runs
 * `recordOnly` for a statement nested in a function body and truncates every
 * diagnostic it records, so the LEGACY arm in `checkTypeAssignabilityInStatements`
 * is the one that emits — while a `return` at a file's top level is anchored by the
 * spine. The pins below drive both (a top-level `return` cannot exist, so `nested`
 * exercises the legacy arm and every fixture here is inside a function).
 *
 * Every row was read out of `tools/tsgo-7.0.2/lib/tsc` and matches it at exact
 * line:column and message.
 */
class ReturnPositionFunctionBodyTest {

    private val prelude = """
        interface N { kind: number }
        interface V { m(node: N): void }
        declare function take(f: (a: N) => void): void;
    """.trimIndent() + "\n"

    private fun diags(source: String) = diagnose(prelude + source.trimIndent())

    private fun codes(source: String): List<Int> = diags(source).map { it.code }.sorted()

    /** THE DEFECT: an arrow body nested in a `return`. */
    @Test
    fun `an arrow body nested in a return is checked`() {
        assert(
            codes("export function w1(): ((a: N) => void) { return (node) => { const q: number = \"s\"; void q; void node; }; }") ==
                listOf(2322),
        )
    }

    /** The object-literal-method form. */
    @Test
    fun `an object-literal method body nested in a return is checked`() {
        assert(
            codes("export function w2(): V { return { m(node) { const q: number = \"s\"; void q; void node; } }; }") ==
                listOf(2322),
        )
    }

    /** The parenthesised form — a third spelling of the same position. */
    @Test
    fun `a parenthesised function body nested in a return is checked`() {
        assert(
            codes("export function w3(): ((a: N) => void) { return ((node) => { const q: number = \"s\"; void q; void node; }); }") ==
                listOf(2322),
        )
    }

    /**
     * CONTROL: the CALL-ARGUMENT position has always been walked, so this row is
     * green on both binaries and is here to keep the comparison honest — a change
     * that broke the walker generally would lose it too.
     */
    @Test
    fun `a call-argument function body is checked - control`() {
        assert(
            codes("export function w4(): void { take((node) => { const q: number = \"s\"; void q; void node; }); }") ==
                listOf(2322),
        )
    }

    /** NEGATIVE CONTROL: a CORRECT body nested in a return stays silent. */
    @Test
    fun `a correct body nested in a return reports nothing`() {
        assert(
            codes("export function d1(): ((a: N) => void) { return (node) => { const q: number = node.kind; void q; }; }")
                .isEmpty(),
        )
    }

    /**
     * THE PARAMETER'S TYPE, not just the arity. The `return` ANNOTATION is what
     * contextually types the returned function's un-annotated parameter — the walk
     * alone would leave `node` implicitly `any` and this row silent. `tsgo` reports
     * it at 3:70 of the same fixture; the message is `Type 'number' is not
     * assignable to type 'string'`, i.e. `node.kind` really did resolve to `number`.
     */
    @Test
    fun `the return annotation types a returned function expression's parameter`() {
        val d = diags("export function c1(): ((a: N) => void) { return function (a) { const q: string = a.kind; void q; }; }")
        assert(d.map { it.code } == listOf(2322))
        assert(d.map { it.message } == listOf("Type 'number' is not assignable to type 'string'."))
    }

    /** The arrow spelling of the same. */
    @Test
    fun `the return annotation types a returned arrow's parameter`() {
        val d = diags("export function c2(): ((a: N) => void) { return (a) => { const q: string = a.kind; void q; }; }")
        assert(d.map { it.code } == listOf(2322))
        assert(d.map { it.message } == listOf("Type 'number' is not assignable to type 'string'."))
    }

    /** The object-literal member spelling, which reads its own member signature. */
    @Test
    fun `a returned object-literal method's parameter carries its member type`() {
        val d = diags("export function d2(): V { return { m(node) { const q: string = node.kind; void q; } }; }")
        assert(d.map { it.code } == listOf(2322))
        assert(d.map { it.message } == listOf("Type 'number' is not assignable to type 'string'."))
    }

    /**
     * The REST-parameter shape, and it is the row that keeps B183's annotation
     * contextualizer alive in this arm: `applyPulledContextualParamTypes` skips a
     * `...rest` parameter by construction, so without
     * `contextualizeFnExprFromAnnotation(returnTypeNode, …)` beside the walk this
     * body's `y` is implicitly `any` and the row disappears. Every other row here
     * survives that ablation, which is exactly why this one had to be written.
     */
    @Test
    fun `a returned function's REST parameter is typed by the return annotation`() {
        val d = diags(
            "export function e1(): ((...y: string[]) => void) { " +
                "return function (...y) { const q: number = y[0]; void q; }; }",
        )
        assert(d.map { it.code } == listOf(2322))
        assert(d.map { it.message } == listOf("Type 'string' is not assignable to type 'number'."))
    }

    /** TWO levels of `return` nesting — the walk is recursive, not one-deep. */
    @Test
    fun `a body two return levels down is checked`() {
        assert(
            codes(
                "export function d4(): (() => ((a: N) => void)) { " +
                    "return () => { return (node) => { const q: string = node.kind; void q; }; }; }",
            ) == listOf(2322),
        )
    }
}
