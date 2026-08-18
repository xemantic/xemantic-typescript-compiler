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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.ParserFlags
import kotlin.test.Test

/**
 * (API.7) `SyntaxRoles` — the parent-chain classifier — asserted with NO checker and
 * no build anywhere near it, which is what makes these the cheapest pins in the arc
 * and the ones a future agent should run first.
 *
 * ## What every read/write pin here is built to fail against
 *
 * THE NAIVE RULE round 919 named and refused: "an identifier is a write when it is
 * the left of an `=` or the operand of `++`". That rule passes every easy assertion
 * in this class and fails exactly the three destructuring/for-head shapes, which are
 * therefore written FIRST and marked as the discriminators. A second shortcut — "any
 * identifier under an assignment's left-hand side is a write" — passes those three
 * and fails `the receiver of a written member is a READ`.
 *
 * ## What every keyword pin is built to fail against
 *
 * AN UNCONDITIONAL LIST. Offering the same keywords everywhere passes "a statement
 * position offers `interface`" and fails `an expression position does not offer a
 * declaration keyword` and `await is offered only inside an async function` — which
 * is round 918's whole argument, restated as two tests.
 *
 * Offsets are derived with `indexOf`; a hardcoded offset pins this file's own
 * arithmetic and would pass for an implementation that ignored its argument.
 */
class SyntaxRoleTest {

    /** U+2038 CARET, for the keyword pins — see `CompletionAnchorTest` for why not `|`. */
    private val CARET = '‸'

    private fun indexOf(text: String): SourceIndex = SourceIndex.of(
        text,
        "/t.ts",
        ParserFlags(
            forceJsx = false,
            topLevelAwait = false,
            needsJsxFlag = false,
            noImplicitAny = false,
        ),
    )

    /**
     * The use of the identifier the `‸` in [marked] sits immediately BEFORE.
     *
     * Marked rather than counted: an `indexOf` of the spelling silently selects the
     * wrong occurrence the moment a fixture gains a word, and the assertion then
     * measures a different node while reading correctly. The marker is stripped and
     * its offset is the identifier's own first character, which
     * `TokenIndexInvariants` independently pins as reaching that identifier.
     */
    private fun useOf(marked: String): ReferenceUse {
        val caret = marked.indexOf(CARET)
        assert(caret >= 0)
        assert(marked.indexOf(CARET, caret + 1) < 0)
        val text = marked.substring(0, caret) + marked.substring(caret + 1)
        val node = indexOf(text).pathAt(caret).lastOrNull()
        assert(node is Identifier)
        return SyntaxRoles.referenceUse(node)
    }

    /** The keywords offered at the caret marked `‸` in [marked]. */
    private fun keywordsAt(marked: String): List<String> {
        val caret = marked.indexOf(CARET)
        assert(caret >= 0)
        val text = marked.substring(0, caret) + marked.substring(caret + 1)
        return indexOf(text).completionAnchorAt(caret).keywords
    }

    // --- THE READ/WRITE DISCRIMINATORS, round 919's own three shapes ---------------

    @Test
    fun `an array destructuring target is a WRITE - the discriminator`() {
        assert(useOf("let x = 0;\nconst pair = [1, 2];\n[‸x] = pair;\n") == ReferenceUse.WRITE)
    }

    @Test
    fun `an object destructuring target is a WRITE - the discriminator`() {
        assert(useOf("let x = 0;\nconst o = { x: 1 };\n({ ‸x } = o);\n") == ReferenceUse.WRITE)
    }

    @Test
    fun `a for-of head is a WRITE - the discriminator`() {
        assert(useOf("let x = 0;\nconst xs = [1];\nfor (‸x of xs) { }\n") == ReferenceUse.WRITE)
    }

    @Test
    fun `a for-in head is a WRITE`() {
        assert(useOf("let k = \"\";\nconst o = { a: 1 };\nfor (‸k in o) { }\n") == ReferenceUse.WRITE)
    }

    // --- the destructuring set, to its stated depth --------------------------------

    @Test
    fun `a renamed object destructuring target is a WRITE and its source key is not`() {
        assert(
            useOf("let target = 0;\nconst o = { source: 1 };\n({ source: ‸target } = o);\n") ==
                ReferenceUse.WRITE,
        )
        assert(
            useOf("let target = 0;\nconst o = { source: 1 };\n({ ‸source: target } = o);\n") ==
                ReferenceUse.UNCLASSIFIED,
        )
    }

    @Test
    fun `a destructuring target with a default is a WRITE`() {
        assert(
            useOf("let x = 0;\nconst pair: number[] = [];\n[‸x = 9] = pair;\n") ==
                ReferenceUse.WRITE,
        )
    }

    @Test
    fun `a rest target is a WRITE in both bracket forms`() {
        assert(
            useOf("let xs: number[] = [];\nconst src = [1];\n[...‸xs] = src;\n") ==
                ReferenceUse.WRITE,
        )
        assert(
            useOf("let rest = {};\nconst src = { a: 1 };\n({ ...‸rest } = src);\n") ==
                ReferenceUse.WRITE,
        )
    }

    @Test
    fun `a target nested two patterns deep is a WRITE`() {
        assert(
            useOf("let deep = 0;\nconst src = { a: [1] };\n({ a: [‸deep] } = src);\n") ==
                ReferenceUse.WRITE,
        )
    }

    /**
     * The mirror of the discriminators, and the pin that fails the OTHER shortcut:
     * the same brackets in a value position are reads, so a rule keyed on "is under an
     * array or object literal" is wrong in both directions.
     */
    @Test
    fun `the same brackets in a value position are READS`() {
        assert(useOf("const x = 1;\nconst a = [‸x];\n") == ReferenceUse.READ)
        assert(useOf("const y = 1;\nconst o = { ‸y };\n") == ReferenceUse.READ)
    }

    // --- assignments, updates, members ---------------------------------------------

    @Test
    fun `a simple assignment target is a WRITE and a compound one is a READ WRITE`() {
        assert(useOf("let x = 0;\n‸x = 1;\n") == ReferenceUse.WRITE)
        assert(useOf("let y = 0;\n‸y += 1;\n") == ReferenceUse.READ_WRITE)
    }

    @Test
    fun `an update operator is a READ WRITE in both positions`() {
        assert(useOf("let x = 0;\n‸x++;\n") == ReferenceUse.READ_WRITE)
        assert(useOf("let y = 0;\n--‸y;\n") == ReferenceUse.READ_WRITE)
    }

    @Test
    fun `a written MEMBER is a WRITE and its receiver is a READ`() {
        assert(useOf("declare const o: { p: number };\no.‸p = 1;\n") == ReferenceUse.WRITE)
        assert(useOf("declare const o: { p: number };\n‸o.p = 1;\n") == ReferenceUse.READ)
    }

    @Test
    fun `only the LAST segment of a written member chain is a WRITE`() {
        val fixture = "declare const a: { b: { c: number } };\n"
        assert(useOf(fixture + "a.b.‸c = 1;\n") == ReferenceUse.WRITE)
        assert(useOf(fixture + "a.‸b.c = 1;\n") == ReferenceUse.READ)
        assert(useOf(fixture + "‸a.b.c = 1;\n") == ReferenceUse.READ)
    }

    @Test
    fun `an assignment target reached through parentheses and non-null is still a WRITE`() {
        assert(useOf("let x = 0;\n(‸x) = 1;\n") == ReferenceUse.WRITE)
        assert(useOf("let y: number | null = 0;\n‸y! = 1;\n") == ReferenceUse.WRITE)
    }

    // --- the binding names -----------------------------------------------------------

    @Test
    fun `a parameter name and a variable declaration name are WRITES`() {
        assert(
            useOf("function f(‸param: number): number { return param; }\n") ==
                ReferenceUse.WRITE,
        )
        assert(useOf("const ‸declared = 1;\n") == ReferenceUse.WRITE)
        assert(
            useOf("const src = { a: 1 };\nconst { a: ‸bound } = src;\n") == ReferenceUse.WRITE,
        )
    }

    // --- what is UNCLASSIFIED, which is the state the refusal bought ------------------

    @Test
    fun `a type-position name is UNCLASSIFIED`() {
        assert(
            useOf("interface Shape { w: number }\ndeclare const s: ‸Shape;\n") ==
                ReferenceUse.UNCLASSIFIED,
        )
    }

    @Test
    fun `a typeof operand inside a type is a READ, not a type name`() {
        assert(
            useOf("const value = 1;\ndeclare const t: typeof ‸value;\n") == ReferenceUse.READ,
        )
    }

    @Test
    fun `a declaration name that binds no storage is UNCLASSIFIED`() {
        assert(useOf("function ‸fn(): void { }\n") == ReferenceUse.UNCLASSIFIED)
        assert(useOf("class ‸Cls { }\n") == ReferenceUse.UNCLASSIFIED)
        assert(useOf("interface ‸Iface { }\n") == ReferenceUse.UNCLASSIFIED)
        assert(useOf("type ‸Alias = number;\n") == ReferenceUse.UNCLASSIFIED)
    }

    @Test
    fun `an object literal key being declared and a label are UNCLASSIFIED`() {
        assert(useOf("const o = { ‸key: 1 };\n") == ReferenceUse.UNCLASSIFIED)
        assert(useOf("‸outer: for (;;) { break outer; }\n") == ReferenceUse.UNCLASSIFIED)
        assert(useOf("outer: for (;;) { break ‸outer; }\n") == ReferenceUse.UNCLASSIFIED)
    }

    @Test
    fun `a class member declaration name is UNCLASSIFIED and its use through a receiver is a READ`() {
        val fixture = "class C { field: number = 1; }\ndeclare const c: C;\n"
        assert(useOf("class C { ‸field: number = 1; }\n") == ReferenceUse.UNCLASSIFIED)
        assert(useOf(fixture + "const read = c.‸field;\n") == ReferenceUse.READ)
    }

    // --- KEYWORDS: the two pins that fail an unconditional list ----------------------

    @Test
    fun `an expression position does not offer a declaration keyword - the discriminator`() {
        val keywords = keywordsAt("declare function f(x: unknown): void;\nf(inter‸);\n")
        assert("interface" !in keywords)
        assert("const" !in keywords)
        // …but it IS an expression position, so the expression starters are there.
        assert("new" in keywords)
        assert("typeof" in keywords)
    }

    @Test
    fun `await is offered only inside an async function - the discriminator`() {
        assert("await" !in keywordsAt("function plain(): void {\n  aw‸\n}\n"))
        assert("await" in keywordsAt("async function fresh(): Promise<void> {\n  aw‸\n}\n"))
    }

    @Test
    fun `yield is offered only inside a generator`() {
        assert("yield" !in keywordsAt("function plain(): void {\n  yi‸\n}\n"))
        assert("yield" in keywordsAt("function* gen(): Iterator<number> {\n  yi‸\n}\n"))
    }

    @Test
    fun `a statement position offers the declaration keywords`() {
        val keywords = keywordsAt("const before = 1;\ninter‸\n")
        assert("interface" in keywords)
        assert("const" in keywords)
        assert("function" in keywords)
        // A statement position admits an expression statement, so both lists are there.
        assert("typeof" in keywords)
    }

    @Test
    fun `a module-level declaration keyword is not offered inside a function body`() {
        val inside = keywordsAt("function f(): void {\n  imp‸\n}\n")
        assert("import" !in inside)
        assert("interface" !in inside)
        // The always-legal statement starters are still there.
        assert("const" in inside)
        assert("if" in inside)
        assert("import" in keywordsAt("const before = 1;\nimp‸\n"))
    }

    @Test
    fun `return break and continue are offered only where they bind`() {
        assert("return" !in keywordsAt("const top = 1;\nre‸\n"))
        assert("return" in keywordsAt("function f(): void {\n  re‸\n}\n"))
        assert("break" !in keywordsAt("function f(): void {\n  br‸\n}\n"))
        assert("break" in keywordsAt("function f(): void {\n  while (true) { br‸ }\n}\n"))
        assert("continue" !in keywordsAt("function f(): void {\n  switch (1) { default: co‸ }\n}\n"))
        assert("break" in keywordsAt("function f(): void {\n  switch (1) { default: br‸ }\n}\n"))
    }

    /**
     * A loop does not reach into a nested function, so the ascent stops at the first
     * function-like — the shape a rule that merely scans for any enclosing loop gets
     * wrong.
     */
    @Test
    fun `a loop does not offer break inside a function nested in it`() {
        assert(
            "break" !in
                keywordsAt("while (true) {\n  const f = (): void => { br‸ };\n}\n"),
        )
    }

    @Test
    fun `super is offered only inside a class body`() {
        assert("super" !in keywordsAt("function f(): void {\n  su‸\n}\n"))
        assert("super" in keywordsAt("class C {\n  m(): void { su‸ }\n}\n"))
    }

    @Test
    fun `a type position offers the type keywords and nothing else`() {
        val keywords = keywordsAt("declare const x: str‸;\n")
        assert("string" in keywords)
        assert("unknown" in keywords)
        assert("keyof" in keywords)
        assert("const" !in keywords)
        assert("interface" !in keywords)
        assert("new" !in keywords)
    }

    @Test
    fun `a class body offers no keywords at all - a position this declines to name`() {
        assert(keywordsAt("class C {\n  ‸\n}\n").isEmpty())
    }

    @Test
    fun `a member caret offers no keywords`() {
        val anchor = indexOf("const o = { a: 1 };\no.\n").completionAnchorAt(
            "const o = { a: 1 };\no.".length,
        )
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.keywords.isEmpty())
    }

    @Test
    fun `a caret inside a string offers no keywords`() {
        assert(keywordsAt("const s = \"ab‸cd\";\n").isEmpty())
    }
}
