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
 * (KIR) The pin for [CheckedNodeSink]: the seam is OFF unless a sink is handed in,
 * and when one is, its answers are the WALK's answers.
 *
 * The fixture is [TypeCaptureMeasurementTest]'s, deliberately and verbatim in its
 * interesting part, because that class already MEASURED the three positions where
 * asking the same checker afterwards is not merely coarser but wrong — a body local
 * answered about a same-named global, a narrowed reference and a parameter both
 * answered `any`. A sink that reproduces the capture's answers at those three
 * positions is a sink that ran inside the walk; nothing else reproduces them.
 *
 * Every assertion that would otherwise put an AST node inside a power-assert
 * expression computes a `Boolean`/`Int` local first: the diagram renders every
 * captured subexpression, and an AST data class renders (or stack-overflows
 * rendering) its whole subtree.
 */
class CheckedNodeSinkTest {

    /**
     * [TypeCaptureMeasurementTest]'s source, plus a declared function and a call to
     * it for the signature leg.
     *
     * `collide` is declared TWICE — once as a global `string`, once as a
     * function-body local `number` — which is what turns "the sink loses narrowing"
     * into "the sink answers about a different declaration".
     */
    private val source = """
        const topConst: string = "t";
        declare const collide: string;
        declare function twice(n: number): number;
        interface Shape { p: string; }
        function f(u: string | number, p: number) {
            const collide: number = 1;
            const useLocal = collide;
            if (typeof u === "string") {
                const useNarrow = u;
            }
            const useParam = p;
        }
        const called = twice(1);
    """.trimIndent()

    private val fileName = "/proj/m.ts"

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [source]. */
    private fun offsetOf(needle: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = source.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /**
     * Everything the pins need, recorded from inside the callback.
     *
     * The lens is asked HERE and nowhere else: [CheckedLens] is valid only for the
     * duration of the call that received it, because the ambient it reads is
     * installed around the callback and restored afterwards.
     */
    private class Recorder : CheckedNodeSink {

        val expressions = ArrayList<Expression>()
        val declarations = ArrayList<Node>()

        /** The rendered type of every [Identifier], keyed by its raw `pos`. */
        val identifierTypes = HashMap<Int, String>()

        /** The rendered type of each parameter's SYMBOL, keyed by its name. */
        val parameterTypes = HashMap<String, String>()

        /** The rendered DECLARED type of each interface symbol, keyed by name. */
        val interfaceDeclaredTypes = HashMap<String, String>()

        /** How many call signatures each named callee offered. */
        val calleeSignatureCounts = HashMap<String, Int>()

        /** Which declaration the overload selection picked, per named callee. */
        val selectedOverloadDeclarations = HashMap<String, Node?>()

        /** The binder's node→symbol table, for the [CheckedLens.declaredTypeOfSymbol] leg. */
        var nodeToSymbol: Map<Long, Symbol> = emptyMap()

        override fun expression(node: Expression, lens: CheckedLens) {
            expressions.add(node)
            if (node is Identifier) {
                identifierTypes[node.pos] = lens.render(lens.typeOf(node))
            }
            if (node is CallExpression) {
                val callee = node.expression
                if (callee is Identifier) {
                    val signatures = lens.callSignatures(callee)
                    calleeSignatureCounts[callee.text] = signatures.size
                    selectedOverloadDeclarations[callee.text] =
                        lens.selectOverload(signatures, node.arguments)?.declaration
                }
            }
        }

        override fun declaration(node: Node, lens: CheckedLens) {
            declarations.add(node)
            if (node is Parameter) {
                val name = node.name
                if (name is Identifier) {
                    // A parameter is deliberately absent from the binder's
                    // `nodeToSymbol` — `Binder.declareLexical` records into the
                    // SEPARATE scope-symbol space and never calls
                    // `recordNodeSymbol` — so the position's own lexical chain is
                    // what names it, which is exactly what a lens is for.
                    val symbol = lens.resolveName(name.text)
                    if (symbol != null) {
                        parameterTypes[name.text] = lens.render(lens.typeOfSymbol(symbol))
                    }
                }
            }
            if (node is InterfaceDeclaration) {
                val symbol = nodeToSymbol[nodeKey(node)]
                if (symbol != null) {
                    interfaceDeclaredTypes[node.name.text] =
                        lens.render(lens.declaredTypeOfSymbol(symbol))
                }
            }
        }
    }

    /** How many DISTINCT node objects (by identity, not by data-class equality). */
    private fun identityCount(nodes: List<Node>): Int {
        val seen = ArrayList<Node>()
        for (node in nodes) if (seen.none { it === node }) seen.add(node)
        return seen.size
    }

    /** Runs [source] through a checker driven by a fresh [Recorder]. */
    private fun run(): Recorder {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val binderResult = Binder(options).bind(sourceFile)
        val recorder = Recorder()
        recorder.nodeToSymbol = binderResult.nodeToSymbol
        Checker(
            options, listOf(binderResult), isMultiFileSource = true, checkedSink = recorder,
        )
        return recorder
    }

    @Test
    fun `off is off - a checker with no sink calls nothing and one with a sink does`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val neverPassed = Recorder()
        // The sink is simply not handed in — the default is null, so
        // `captureHookCurrentFile` stays null and the per-node hook never fires.
        Checker(options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true)
        assert(neverPassed.expressions.size == 0)
        assert(neverPassed.declarations.size == 0)
        // The control that makes the zero above a measurement rather than a
        // tautology: the same program, the same fixture, a sink that IS passed.
        val passed = run()
        assert(passed.expressions.size > 0)
        assert(passed.declarations.size > 0)
    }

    @Test
    fun `a function-body local reports its own type and not the same-named global's`() {
        val recorder = run()
        // `collide` at its USE. Post-hoc this reads `string` — the global — which
        // TypeCaptureMeasurementTest measures.
        assert(recorder.identifierTypes[offsetOf("collide", 2)] == "number")
    }

    @Test
    fun `a guard-narrowed reference reports the narrowed type`() {
        val recorder = run()
        val at = offsetOf("useNarrow = u") + "useNarrow = ".length
        // Post-hoc this reads `any`: nothing durable binds `u` at all.
        assert(recorder.identifierTypes[at] == "string")
    }

    @Test
    fun `a parameter reports its declared type and not any`() {
        val recorder = run()
        val at = offsetOf("useParam = p") + "useParam = ".length
        assert(recorder.identifierTypes[at] == "number")
    }

    @Test
    fun `a top-level annotated const reports its annotation`() {
        val recorder = run()
        // The control, and the one row post-hoc also gets right: a file-level
        // declaration is reachable from durable tables.
        assert(recorder.identifierTypes[offsetOf("topConst")] == "string")
    }

    /**
     * Node identity, not span.
     *
     * PINNED: the sink is not deduplicated by `(pos, end)`. The shape is round
     * 917's — a dangling `.` at end of buffer makes the parser synthesize a
     * zero-width `Identifier("")` and read the `PropertyAccessExpression`'s `end`
     * after a lookahead that sees only EOF, so the access and its RECEIVER carry
     * the SAME raw pair. A span-keyed capture arbitrates between them and DISCARDS
     * one; a backend that must lower every node cannot tolerate that, so the sink
     * hands over both.
     *
     * The assertion is stated as "strictly more distinct nodes than distinct span
     * keys" rather than as an equality on the colliding pair, because that is the
     * property a consumer depends on and it stays true however the parser spells
     * the recovery.
     */
    @Test
    fun `the sink is keyed by node identity and not by span`() {
        val options = CompilerOptions()
        // No trailing newline: the buffer must end IMMEDIATELY after the dot.
        val dangling = "const o = { p: 1 };\nconst r = o."
        val sourceFile = Parser(dangling, "/proj/d.ts").parse()
        val recorder = Recorder()
        Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            checkedSink = recorder,
        )
        val distinctNodes = identityCount(recorder.expressions)
        val distinctSpans = recorder.expressions.map { nodeKey(it) }.toSet().size
        assert(distinctNodes > distinctSpans)
    }

    @Test
    fun `the declarations leg sees a function declaration and a parameter`() {
        val recorder = run()
        val sawFunction = recorder.declarations.any { it is FunctionDeclaration }
        val sawParameter = recorder.declarations.any { it is Parameter }
        assert(sawFunction)
        assert(sawParameter)
        // …and the lens answers about the parameter's own symbol, resolved through
        // the position's lexical chain.
        assert(recorder.parameterTypes["p"] == "number")
        assert(recorder.parameterTypes["u"] == "string | number")
    }

    @Test
    fun `the lens answers the declared type of a symbol taken from the binder tables`() {
        val recorder = run()
        // `declaredTypeOfSymbol` is the right query for a TYPE-meaning symbol, and
        // an interface — unlike a parameter — IS in the binder's `nodeToSymbol`.
        assert(recorder.interfaceDeclaredTypes["Shape"] == "Shape")
    }

    @Test
    fun `the lens resolves a call to the signature of the declared function`() {
        val recorder = run()
        assert(recorder.calleeSignatureCounts["twice"] == 1)
        // The expected declaration is taken from the recorder's OWN declarations —
        // i.e. from the tree the checker walked — so this is node IDENTITY and not
        // a data-class structural compare over a re-parse.
        val expected = recorder.declarations.filterIsInstance<FunctionDeclaration>()
            .first { it.name?.text == "twice" }
        val selectedIsTwice = recorder.selectedOverloadDeclarations["twice"] === expected
        assert(selectedIsTwice)
    }
}
