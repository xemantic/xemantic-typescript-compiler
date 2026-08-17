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
 * (API.3) THE MEASUREMENT the capture design rests on: for the same six
 * positions, in the same checker instance, what does capturing DURING the walk
 * answer, and what does asking the SAME checker afterwards answer?
 *
 * Measured (round 911), captured vs post-hoc:
 *
 * | position                                    | captured  | post-hoc |
 * |---------------------------------------------|-----------|----------|
 * | a top-level annotated `const`               | `string`  | `string` |
 * | a body local shadowing a global of another type | `number` | `string` |
 * | a `typeof`-narrowed parameter reference     | `string`  | `any`    |
 * | a parameter, at its use                     | `number`  | `any`    |
 * | an arrow body's parameter                   | `string`  | `any`    |
 * | a class method body's parameter             | `number`  | `any`    |
 *
 * Five of six differ, and the body-local row differs by DECLARATION rather than by
 * width — post-hoc answers about the global.
 *
 * "Hand the checker back and call `getTypeOfExpression`" looks free — `Checker`
 * does all its work in `init`, so the instance still holds its tables. The claim
 * this class measures rather than asserts is that it is silently wrong for the
 * cases a hover user actually points at, because `getTypeOfIdentifier` consults
 * `currentLocalTypes` (populated during the checking walk) before it consults
 * anything durable, and at rest that map is empty.
 *
 * Both answers come from ONE `Checker`: the capture is passed in, the post-hoc
 * query is [Checker.postHocTypeAtSpanForTesting] on the very same instance. So the
 * only variable is WHEN the question was asked.
 */
class TypeCaptureMeasurementTest {

    /**
     * One file, six positions, chosen so that each isolates one ambient read.
     *
     * `collide` is declared TWICE — once as a global, once as a function-body local
     * of a different type — which is what turns "post-hoc loses narrowing" into
     * "post-hoc answers about a different declaration". The names are unique
     * substrings so the offsets can be located by [String.indexOf] rather than
     * counted by hand.
     */
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

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [source]. */
    private fun offsetOf(needle: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = source.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /**
     * The RAW `(pos, end)` span of the identifier starting at [offset].
     *
     * Iterative, like every full-tree walk in this repo. `Node.end` overshoots by a
     * token (round 910) and is used here purely as an IDENTITY: the capture matches
     * on the same raw pair.
     */
    private fun identifierSpanAt(sourceFile: SourceFile, offset: Int): TypeCaptureSpan {
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
        return TypeCaptureSpan(fileName, identifier.pos, identifier.end)
    }

    private class Measured(
        val spans: Map<String, TypeCaptureSpan>,
        val captured: Map<String, String?>,
        val postHoc: Map<String, String?>,
    )

    /**
     * Builds ONE checker with every span requested, then re-asks the same instance
     * for each span post-hoc.
     */
    private fun measure(): Measured {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val positions = mapOf(
            // (i) a top-level annotated const, at its own declaration.
            "topConst" to offsetOf("topConst"),
            // (ii) a function-body local, at its USE, shadowing a global of a
            // different type.
            "bodyLocal" to offsetOf("collide", 2),
            // (iii) a reference inside a `typeof` guard's then-branch.
            "narrowed" to offsetOf("useNarrow = u") + "useNarrow = ".length,
            // (iv) a parameter, at its use.
            "parameter" to offsetOf("useParam = p") + "useParam = ".length,
            // (v) an ARROW body's parameter — a body the spine reaches through a
            // different frame builder than a function declaration's.
            "arrowParam" to offsetOf("useArrow = q") + "useArrow = ".length,
            // (vi) a class METHOD body's parameter — likewise.
            "methodParam" to offsetOf("useMethod = r") + "useMethod = ".length,
        )
        val spans = positions.mapValues { (_, offset) -> identifierSpanAt(sourceFile, offset) }
        val request = TypeCaptureRequest(spans.values.toList())
        val binderResult = Binder(options).bind(sourceFile)
        val checker = Checker(
            options, listOf(binderResult), isMultiFileSource = true, typeCapture = request,
        )
        val capturedBySpan = checker.capturedTypes.associateBy {
            TypeCaptureSpan(it.fileName, it.start, it.end)
        }
        return Measured(
            spans = spans,
            captured = spans.mapValues { (_, span) -> capturedBySpan[span]?.typeText },
            postHoc = spans.mapValues { (_, span) ->
                checker.postHocTypeAtSpanForTesting(span.fileName, span.start, span.end)
            },
        )
    }

    @Test
    fun `a top-level annotated const is answered correctly by BOTH the capture and the post-hoc query`() {
        val m = measure()
        assert(m.captured["topConst"] == "string")
        // The control, and a refutation worth stating: post-hoc is not wrong about
        // everything. A file-level declaration is reachable from durable tables, so
        // the ambient the walk holds is not what answers it.
        assert(m.postHoc["topConst"] == "string")
    }

    @Test
    fun `a function-body local is answered by the capture and MISSED by the post-hoc query`() {
        val m = measure()
        assert(m.captured["bodyLocal"] == "number")
        // The sharp case: not merely "less narrow" — a DIFFERENT declaration. At rest
        // `currentLocalTypes` is empty, so the post-hoc query falls through to the
        // same-named global.
        assert(m.postHoc["bodyLocal"] == "string")
        assert(m.captured["bodyLocal"] != m.postHoc["bodyLocal"])
    }

    @Test
    fun `a guard-narrowed reference is answered by the capture and MISSED by the post-hoc query`() {
        val m = measure()
        assert(m.captured["narrowed"] == "string")
        // MEASURED, and sharper than "the narrow is lost": post-hoc does not know the
        // parameter at all — nothing durable binds `u` — so it degrades to `any`,
        // which is the one answer that is silent everywhere it is used.
        assert(m.postHoc["narrowed"] == "any")
        assert(m.captured["narrowed"] != m.postHoc["narrowed"])
    }

    @Test
    fun `a parameter is answered by the capture and MISSED by the post-hoc query`() {
        val m = measure()
        assert(m.captured["parameter"] == "number")
        assert(m.postHoc["parameter"] == "any")
    }

    @Test
    fun `an arrow body and a class method body are answered by the capture too`() {
        val m = measure()
        // Three different frame builders reach a body — a function declaration, an
        // arrow, a method — and the capture reads the frame rather than any one of
        // them, so all three answer.
        assert(m.captured["arrowParam"] == "string")
        assert(m.captured["methodParam"] == "number")
        assert(m.postHoc["arrowParam"] == "any")
        assert(m.postHoc["methodParam"] == "any")
    }

    @Test
    fun `every requested span is answered exactly once`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val span = identifierSpanAt(sourceFile, offsetOf("collide", 2))
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(listOf(span, span)),
        )
        assert(checker.capturedTypes.size == 1)
        assert(checker.capturedTypes[0].kind == "Identifier")
        assert(checker.capturedTypes[0].start == span.start)
        assert(checker.capturedTypes[0].end == span.end)
    }

    @Test
    fun `negative control - a span no node carries is answered by nothing`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(listOf(TypeCaptureSpan(fileName, 1, 2))),
        )
        assert(checker.capturedTypes.isEmpty())
    }

    @Test
    fun `negative control - a span in a file the program does not contain is answered by nothing`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val span = identifierSpanAt(sourceFile, offsetOf("collide", 2))
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(listOf(span.copy(fileName = "/proj/other.ts"))),
        )
        assert(checker.capturedTypes.isEmpty())
    }

    @Test
    fun `negative control - no request captures nothing and the results are empty`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val checker = Checker(options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true)
        assert(checker.capturedTypes.isEmpty())
    }
}
