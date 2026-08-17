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
 * (API.3b) THE MEASUREMENT go-to-definition rests on, built exactly like
 * [TypeCaptureMeasurementTest]'s: for the same positions, in the SAME checker
 * instance, what does resolving DURING the walk answer and what does resolving
 * afterwards answer?
 *
 * The mechanism is one indirection along from (API.3a)'s and it is NOT the same
 * one, which is this class's real product. A type's walk-scoped input is
 * `currentLocalTypes`, which the spine's statement anchors install and restore per
 * dispatch. A definition's is `spineCurrentScope`, the INV.2(c) lexical chain,
 * which the walk maintains per NODE and pushes before a node's own handlers — so
 * it is already correct at an arbitrary node and needs no reconstruction. What the
 * two share is that both are gone once the walk is over: `spineScopeClear` nulls
 * the chain per file, so a post-hoc query has no scope to ascend and falls through
 * to the node-keyed global lookup.
 *
 * Measured (round 913), captured vs post-hoc, on a body local that shadows a
 * same-named global declared in the same file:
 *
 * | position                          | captured                | post-hoc               |
 * |-----------------------------------|-------------------------|------------------------|
 * | a body local's use                | the body `const`        | the file-level `const` |
 * | a parameter's use                 | the parameter           | nothing at all         |
 * | a top-level `const`'s use         | the file-level `const`  | the same (the control) |
 *
 * The body-local row is the sharp one and it is the same shape (API.3a) measured
 * for types: not a coarser answer, a DIFFERENT DECLARATION — an editor would
 * navigate the user to the wrong line and look like it worked.
 */
class DefinitionCaptureMeasurementTest {

    /**
     * One file. `collide` is declared at file level and again as a function-body
     * local, so the two answers differ by OFFSET and the assertion cannot be
     * satisfied by accident.
     */
    private val source = """
        const collide: string = "g";
        const topLevel: string = "t";
        function f(param: number) {
            const collide: number = 1;
            const useLocal = collide;
            const useParam = param;
            const useTop = topLevel;
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

    /** The RAW `(pos, end)` span of the identifier starting at [offset]. */
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
        val captured: Map<String, CapturedDefinition?>,
        val postHoc: Map<String, CapturedDefinition?>,
    )

    /** Builds ONE checker with every span requested, then re-asks it post-hoc. */
    private fun measure(): Measured {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val positions = mapOf(
            // The third `collide`: file-level declaration, body declaration, this use.
            "bodyLocal" to offsetOf("collide", 2),
            "parameter" to offsetOf("useParam = param") + "useParam = ".length,
            "topLevel" to offsetOf("useTop = topLevel") + "useTop = ".length,
        )
        val spans = positions.mapValues { (_, offset) -> identifierSpanAt(sourceFile, offset) }
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(spans.values.toList()),
        )
        val capturedBySpan = checker.capturedDefinitions.associateBy {
            TypeCaptureSpan(it.fileName, it.start, it.end)
        }
        return Measured(
            captured = spans.mapValues { (_, span) -> capturedBySpan[span] },
            postHoc = spans.mapValues { (_, span) ->
                checker.postHocDefinitionAtSpanForTesting(span.fileName, span.start, span.end)
            },
        )
    }

    @Test
    fun `a body local resolves to ITSELF while walking and to the same-named file-level const afterwards`() {
        val m = measure()
        val captured = m.captured["bodyLocal"]
        assert(captured != null)
        assert(captured.locations.size == 1)
        // The BODY declaration — the second `collide` in the file.
        assert(captured.locations[0].start == offsetOf("collide", 1))
        assert(captured.locations[0].fileName == fileName)
        val postHoc = m.postHoc["bodyLocal"]
        assert(postHoc != null)
        // The sharp case: a DIFFERENT declaration, not a coarser one. The lexical
        // chain is gone, so the lookup falls through to the file-level binding.
        assert(postHoc.locations[0].start == offsetOf("collide"))
        assert(captured.locations[0].start != postHoc.locations[0].start)
    }

    @Test
    fun `a parameter resolves while walking and is MISSED entirely afterwards`() {
        val m = measure()
        val captured = m.captured["parameter"]
        assert(captured != null)
        assert(captured.locations[0].start == offsetOf("param"))
        assert(captured.locations[0].kind == "Identifier")
        // Nothing durable binds a parameter by name — the same finding (API.3a) made
        // for types, where the post-hoc answer degraded to `any`.
        assert(m.postHoc["parameter"] == null)
    }

    @Test
    fun `the control - a file-level const is resolved the same way by BOTH`() {
        val m = measure()
        val captured = m.captured["topLevel"]
        val postHoc = m.postHoc["topLevel"]
        assert(captured != null)
        assert(postHoc != null)
        // Post-hoc is not wrong about everything, which is exactly what makes the
        // body-local row dangerous rather than obviously broken.
        assert(captured.locations[0].start == postHoc.locations[0].start)
        assert(captured.locations[0].start == offsetOf("topLevel"))
    }

    @Test
    fun `a definition span is the NAME and its length is exact`() {
        val m = measure()
        val captured = m.captured["topLevel"]
        assert(captured != null)
        val location = captured.locations[0]
        // `topLevel` is 8 characters. The raw `Node.end` of that identifier reaches
        // past the following `:` (round 910), so a length taken as `end - pos` would
        // be longer than the name — this is the assertion that fails if the token
        // snap-back is dropped.
        assert(location.length == "topLevel".length)
        assert(source.substring(location.start, location.start + location.length) == "topLevel")
    }

    /**
     * (API.3d) THE DISCRIMINATOR of the member mechanism, and the reason it had to
     * be a second mechanism at all.
     *
     * `size` is a member of `o` AND a file-level `const`, so the two candidate
     * answers are different declarations in the same file and the assertion is which
     * one comes back. A scope lookup — what (API.3b) refused to do here, and what a
     * member path that quietly reused the free-name resolution would do — finds the
     * `const` and navigates the user to a line that has nothing to do with what they
     * clicked. Only a resolution that went through the RECEIVER'S TYPE can answer the
     * object literal's own `size`.
     *
     * Note what makes this sharp rather than merely green: the WRONG answer is not
     * an empty list or a crash, it is a plausible location in the right file. Only
     * the offset separates them.
     */
    @Test
    fun `a member name answers the MEMBER, not the same-named file-level binding`() {
        val options = CompilerOptions()
        val text = """
            const size: string = "s";
            const o = { size: 1 };
            const read = o.size;
        """.trimIndent()
        val sourceFile = Parser(text, fileName).parse()
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        var memberName: Identifier? = null
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is PropertyAccessExpression) memberName = node.name
            forEachChild(node) { child -> stack.add(child) }
        }
        assert(memberName != null)
        val span = TypeCaptureSpan(fileName, memberName.pos, memberName.end)
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(listOf(span)),
        )
        assert(checker.capturedDefinitions.size == 1)
        val locations = checker.capturedDefinitions[0].locations
        assert(locations.size == 1)
        // Derived by search, never hardcoded: the `size` of `{ size: 1 }` is the
        // second occurrence, the `const size` the first.
        val constDeclarationAt = text.indexOf("size")
        val memberDeclarationAt = text.indexOf("size", constDeclarationAt + 1)
        assert(locations[0].start == memberDeclarationAt)
        assert(locations[0].start != constDeclarationAt)
        assert(locations[0].length == "size".length)
        assert(locations[0].kind == "Identifier")
        // ... and the type at the same span is still captured, which proves the span
        // was reached rather than that the two facts happen to agree.
        assert(checker.capturedTypes.size == 1)
    }

    @Test
    fun `negative control - an unresolvable member answers NOTHING rather than guessing`() {
        val options = CompilerOptions()
        // `nope` is on no type and is also a file-level const, so the wrong answer
        // is available to a scope lookup and must not be taken.
        val text = """
            const nope: string = "s";
            const o = { size: 1 };
            const read = (o as any).nope;
        """.trimIndent()
        val sourceFile = Parser(text, fileName).parse()
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        var memberName: Identifier? = null
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is PropertyAccessExpression) memberName = node.name
            forEachChild(node) { child -> stack.add(child) }
        }
        assert(memberName != null)
        assert(memberName.text == "nope")
        val span = TypeCaptureSpan(fileName, memberName.pos, memberName.end)
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(listOf(span)),
        )
        assert(checker.capturedDefinitions.isEmpty())
        // ... and the type at the same span IS captured, which proves the span was
        // reached and that the emptiness above is a refusal rather than a miss.
        assert(checker.capturedTypes.size == 1)
    }

    @Test
    fun `negative control - no request captures no definitions`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val checker = Checker(options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true)
        assert(checker.capturedDefinitions.isEmpty())
    }

    @Test
    fun `negative control - a span no identifier carries is answered by nothing`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(listOf(TypeCaptureSpan(fileName, 1, 2))),
        )
        assert(checker.capturedDefinitions.isEmpty())
    }

    @Test
    fun `every requested span is answered at most once`() {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val span = identifierSpanAt(sourceFile, offsetOf("collide", 2))
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(listOf(span, span)),
        )
        assert(checker.capturedDefinitions.size == 1)
        assert(checker.capturedDefinitions[0].name == "collide")
    }
}
