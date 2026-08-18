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
 * (API.4b) THE MEASUREMENT free-name completion rests on, built exactly like
 * [DefinitionCaptureMeasurementTest]'s: for the same position, in the SAME checker
 * instance, what does ENUMERATING the scope chain during the walk answer and what
 * does enumerating it afterwards answer?
 *
 * The answer is sharper here than for either of the two queries before it, because
 * a definition query at least falls through to a global lookup and a type query at
 * least degrades to `any`. An enumeration has nothing to fall through TO: the
 * spine's `spineScopeClear` nulls the chain per file, so a post-hoc sweep starts at
 * a null scope, walks no levels at all, and answers with the merged globals leg
 * alone — every parameter, every local, every block binding and every type
 * parameter simply absent, and — worse than absent — an outer or imported binding
 * of the same spelling standing where the shadowing local should be.
 *
 * Measured (round 918) on the fixture below:
 *
 * | question                                | captured                 | post-hoc      |
 * |-----------------------------------------|--------------------------|---------------|
 * | is the parameter `param` offered        | yes                      | NO            |
 * | is the body local `collide` offered     | yes                      | yes           |
 * | ... and WHOSE declaration is it          | the body `const`         | the FILE one  |
 * | is the type parameter `TParam` offered  | yes                      | NO            |
 * | is a sibling function's local offered   | NO (the control)         | NO            |
 *
 * The third row is the dangerous one and is the same shape (API.3a) and (API.3b)
 * each measured for their own question: not a shorter list, a list whose item means
 * something else.
 */
class ScopeCaptureMeasurementTest {

    /**
     * One file. `collide` is declared at file level and again as a function-body
     * local, and `siblingOnly` lives in a DIFFERENT function's body — so the
     * fixture separates "innermost wins" from "everything in the file wins", which
     * a fixture with one function could not.
     */
    private val source = """
        const collide: string = "g";
        const topLevel: string = "t";
        function sibling(): void { const siblingOnly: number = 0; }
        function f<TParam>(param: number): void {
            const collide: number = 1;
            const use = collide;
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
     * The RAW `(pos, end)` span of the innermost node the caret inside `f`'s body
     * sits in — found the way the `-project` module's index finds it, but from the
     * tree alone, since this module has no token index.
     *
     * The anchor chosen is the `use` VariableDeclaration's own initializer
     * identifier: an ordinary node inside the body, which is all a scope
     * enumeration needs — the scope in force there is the function's.
     */
    private fun anchorSpan(sourceFile: SourceFile): TypeCaptureSpan {
        val offset = offsetOf("use = collide") + "use = ".length
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
        val captured: List<CapturedName>,
        val postHoc: List<CapturedName>,
    )

    /** Builds ONE checker with the anchor requested, then re-asks it post-hoc. */
    private fun measure(): Measured {
        val options = CompilerOptions()
        val sourceFile = Parser(source, fileName).parse()
        val span = anchorSpan(sourceFile)
        val checker = Checker(
            options, listOf(Binder(options).bind(sourceFile)), isMultiFileSource = true,
            typeCapture = TypeCaptureRequest(spans = emptyList(), scopeSpans = listOf(span)),
        )
        val captured = checker.capturedScopes.firstOrNull {
            it.fileName == span.fileName && it.start == span.start && it.end == span.end
        }
        assert(captured != null)
        return Measured(captured.names, checker.postHocScopeNamesForTesting(fileName))
    }

    @Test
    fun `a parameter and a type parameter are offered while walking and are GONE afterwards`() {
        val m = measure()
        assert(m.captured.any { it.name == "param" && it.kind == "Parameter" })
        assert(m.captured.any { it.name == "TParam" && it.kind == "TypeParameter" })
        // Nothing durable binds either by name once the chain is torn down — the
        // same finding (API.3b) made for a parameter's definition.
        assert(m.postHoc.none { it.name == "param" })
        assert(m.postHoc.none { it.name == "TParam" })
    }

    @Test
    fun `a shadowing body local is offered ONCE and post-hoc the OUTER one takes its place`() {
        val m = measure()
        val capturedCollide = m.captured.filter { it.name == "collide" }
        // Once: a name bound at two levels is one item, not two.
        assert(capturedCollide.size == 1)
        // The BODY declaration. Both are `VariableDeclaration`s here, so the kind
        // cannot separate them and the count is what the assertion rests on —
        // together with the post-hoc row below, where the item is still present.
        assert(capturedCollide[0].kind == "VariableDeclaration")
        val postHocCollide = m.postHoc.filter { it.name == "collide" }
        // Present in BOTH, which is exactly why this is dangerous rather than
        // obviously broken: post-hoc the item is the FILE-level binding, reached
        // through the globals leg, and nothing about the item says so.
        assert(postHocCollide.size == 1)
    }

    @Test
    fun `the control - a SIBLING function's local is offered by NEITHER`() {
        val m = measure()
        // The scope chain ascends; it never descends into a closed sibling scope. An
        // enumeration that walked the wrong structure — the file's nodes, say —
        // passes every positive assertion above and fails this one.
        assert(m.captured.none { it.name == "siblingOnly" })
        assert(m.postHoc.none { it.name == "siblingOnly" })
    }

    @Test
    fun `the control - a file-level const is offered by BOTH`() {
        val m = measure()
        assert(m.captured.any { it.name == "topLevel" })
        // Post-hoc is not wrong about everything, which is what makes the shadowing
        // row above dangerous rather than visibly empty.
        assert(m.postHoc.any { it.name == "topLevel" })
    }
}
