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
 * (INV.1) Stage 1 of `docs/INVERSION-DESIGN.md`: the per-file [NodeAnswerStore],
 * pinned on the SAME fixture and the SAME six positions `TypeCaptureMeasurementTest`
 * measures — so that "what the store recorded" can be held against both the
 * capture (which must agree with it: same rule, same hook, same ambient) and the
 * post-hoc query (which must DISAGREE with it on the walk-scoped rows: that
 * disagreement is the whole reason the store exists).
 *
 * Three pins the design named, each written as the design's own words:
 *
 *  1. THE POSITIVE CONTROL (round 911's shape) — a body local's recorded type
 *     differs from the post-hoc `getTypeOfExpression` answer, by DECLARATION and
 *     not by width: `number` recorded, `string` (the same-named global) post-hoc.
 *  2. THE PRODUCTION-MODE COUNTER AT ZERO (round 900's law) — with the flag off,
 *     [Checker.nodeAnswerComputations] is exactly 0: not "the store is empty",
 *     which a guard around the STORE would give while its ARGUMENT was still
 *     computed on every production compile, but "the guarded function never
 *     ran". The count is taken inside it.
 *  3. THE SHIPPED DEFAULT — [NodeAnswers.enabled] is false with nothing installed
 *     ((INC.16)'s law: a mode every pin sets is a default pinned by nothing).
 *
 * And two the design did not name but the store's KDoc promises: every
 * [Expression] of the file holds an answer (the population is unconditional),
 * and the computation count equals the recorded count (first-wins is checked
 * BEFORE the resolution, so a refused write costs nothing).
 */
class NodeAnswerStoreTest {

    /** `TypeCaptureMeasurementTest`'s fixture, verbatim — same six positions. */
    private val source = """
        const topConst: string = "t";
        declare const collide: string;
        function f(u: string | number, p: number) {
            const collide: number = 1;
            const useLocal = collide;
            if (typeof u === "string") {
                const useNarrow = u;
            }
            const useParam = p;
        }
        const arrow = (q: string) => {
            const useArrow = q;
            return useArrow;
        };
        class C {
            m(r: number) {
                const useMethod = r;
                return useMethod;
            }
        }
    """.trimIndent()

    private val fileName = "/proj/m.ts"

    private fun offsetOf(needle: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = source.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /** Iterative, like every full-tree walk in this repo. */
    private fun identifierAt(sourceFile: SourceFile, offset: Int): Identifier {
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        var found: Identifier? = null
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is Identifier && node.pos == offset) found = node
            forEachChild(node) { child -> stack.add(child) }
        }
        val identifier = found
        assert(identifier != null)
        return identifier
    }

    private fun positions(): Map<String, Int> = mapOf(
        "topConst" to offsetOf("topConst"),
        "bodyLocal" to offsetOf("collide", 2),
        "narrowed" to offsetOf("useNarrow = u") + "useNarrow = ".length,
        "parameter" to offsetOf("useParam = p") + "useParam = ".length,
        "arrowParam" to offsetOf("useArrow = q") + "useArrow = ".length,
        "methodParam" to offsetOf("useMethod = r") + "useMethod = ".length,
    )

    /** The walk's answer at each position, which the store must reproduce. */
    private val expected = mapOf(
        "topConst" to "string",
        "bodyLocal" to "number",
        "narrowed" to "string",
        "parameter" to "number",
        "arrowParam" to "string",
        "methodParam" to "number",
    )

    private class Built(
        val checker: Checker,
        val sourceFile: SourceFile,
        val spans: Map<String, TypeCaptureSpan>,
    )

    private fun build(record: Boolean, capture: Boolean = false): Built {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val spans = positions().mapValues { (_, offset) ->
            val id = identifierAt(sourceFile, offset)
            TypeCaptureSpan(fileName, id.pos, id.end)
        }
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = if (capture) TypeCaptureRequest(spans.values.toList()) else null,
            recordNodeAnswers = record,
        )
        return Built(checker, sourceFile, spans)
    }

    private fun Built.stored(name: String): String? {
        val span = spans.getValue(name)
        return checker.nodeAnswerTextAtSpanForTesting(span.fileName, span.start, span.end)
    }

    private fun Built.postHoc(name: String): String? {
        val span = spans.getValue(name)
        return checker.postHocTypeAtSpanForTesting(span.fileName, span.start, span.end)
    }

    @Test
    fun `the store holds the walk's answer at all six measured positions`() {
        val b = build(record = true)
        for ((name, type) in expected) {
            val stored = b.stored(name)
            assert(stored == type)
        }
    }

    @Test
    fun `positive control - a body local's recorded type differs from the post-hoc answer by declaration`() {
        val b = build(record = true)
        // Recorded under the walk's ambient: the function-body `collide`.
        assert(b.stored("bodyLocal") == "number")
        // Post-hoc, on the SAME instance: `currentLocalTypes` is at rest, so the
        // query falls through to the same-named global — a different declaration.
        assert(b.postHoc("bodyLocal") == "string")
        assert(b.stored("bodyLocal") != b.postHoc("bodyLocal"))
    }

    @Test
    fun `the store keeps what post-hoc loses - a narrow and three parameters`() {
        val b = build(record = true)
        assert(b.stored("narrowed") == "string")
        assert(b.postHoc("narrowed") == "any")
        assert(b.stored("parameter") == "number")
        assert(b.postHoc("parameter") == "any")
        assert(b.stored("arrowParam") == "string")
        assert(b.postHoc("arrowParam") == "any")
        assert(b.stored("methodParam") == "number")
        assert(b.postHoc("methodParam") == "any")
    }

    @Test
    fun `control - the file-level declaration is answered the same by the store and post-hoc`() {
        val b = build(record = true)
        assert(b.stored("topConst") == "string")
        assert(b.postHoc("topConst") == "string")
    }

    @Test
    fun `the store agrees with a capture at every requested span`() {
        // Same rule, same hook, same ambient — so the two answers must be one.
        val b = build(record = true, capture = true)
        val captured = b.checker.capturedTypes.associate {
            TypeCaptureSpan(it.fileName, it.start, it.end) to it.typeText
        }
        assert(captured.size == expected.size)
        for (name in expected.keys) {
            val stored = b.stored(name)
            val fromCapture = captured[b.spans.getValue(name)]
            assert(stored == fromCapture)
        }
    }

    @Test
    fun `every expression of the file holds an answer and no computation was wasted`() {
        val b = build(record = true)
        val store = b.checker.nodeAnswers[fileName]
        assert(store != null)
        // The population is every Expression the tree holds — unconditional.
        val stack = ArrayList<Node>()
        stack.add(b.sourceFile)
        var expressions = 0
        var unanswered = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is Expression) {
                expressions++
                if (store.typeAt(node) == null) unanswered++
            }
            forEachChild(node) { child -> stack.add(child) }
        }
        assert(expressions > 20)
        assert(unanswered == 0)
        assert(store.recorded == expressions)
        assert(store.capacity == b.sourceFile.nodeCount)
        // First-wins is decided BEFORE the resolution, so every computation landed.
        assert(b.checker.nodeAnswerComputations == store.recorded)
    }

    @Test
    fun `production mode - no store is allocated and the guarded function never runs`() {
        val b = build(record = false)
        assert(b.checker.nodeAnswers.isEmpty())
        // Round 900's law, as a pin: not "the store is empty" but "nothing was
        // computed for it" — the count lives inside the guarded function.
        assert(b.checker.nodeAnswerComputations == 0)
        assert(b.stored("bodyLocal") == null)
        // And the check itself is unaffected: the walk-scoped answer is still
        // reachable through the post-hoc seam exactly as before.
        assert(b.postHoc("topConst") == "string")
    }

    @Test
    fun `the shipped default is off`() {
        // (INC.16): a mode every pin installs explicitly is a default pinned by
        // nothing. Nothing here installs it; the constructor's default reads it.
        assert(!NodeAnswers.enabled)
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val checker = Checker(options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true)
        assert(checker.nodeAnswers.isEmpty())
        assert(checker.nodeAnswerComputations == 0)
    }

    @Test
    fun `negative control - a span in a file the program does not contain answers nothing`() {
        val b = build(record = true)
        val span = b.spans.getValue("bodyLocal")
        assert(b.checker.nodeAnswerTextAtSpanForTesting("/proj/other.ts", span.start, span.end) == null)
    }

    @Test
    fun `unit - the store is first-wins and refuses an unindexed node`() {
        val sourceFile = Parser(source, fileName).parse()
        val node = identifierAt(sourceFile, offsetOf("collide", 2))
        val store = NodeAnswerStore(sourceFile)
        assert(store.capacity == sourceFile.nodeCount)
        assert(!store.has(node))
        assert(store.record(node, numberType))
        assert(store.has(node))
        // A second write is REFUSED and the first answer survives.
        assert(!store.record(node, stringType))
        assert(store.typeAt(node) === numberType)
        assert(store.recorded == 1)
        // A `copy()`-ed node is unindexed (INV.2(a): nodeId −1) — no slot to own.
        val copied = node.copy()
        assert(copied.nodeId == -1)
        assert(!store.record(copied, stringType))
        assert(store.typeAt(copied) == null)
        assert(store.recorded == 1)
    }
}
