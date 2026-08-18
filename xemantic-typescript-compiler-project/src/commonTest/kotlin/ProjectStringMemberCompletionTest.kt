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
import kotlin.test.Test

/**
 * (API.12) [Project.completionsAt] with the caret INSIDE the string of an
 * `o["…"]` — the last query in this module that did not answer an element access.
 *
 * Every behaviour asserted here was READ OUT of tsc 7.0.2's own language server
 * (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, `scripts/lsp_completion.py`) before it
 * was written: that the items carry the member's spelling UNQUOTED, that the span
 * accepting one replaces is the literal's TEXT with the quotes excluded, that a
 * member whose spelling is not an identifier is offered, that an index signature
 * contributes nothing, and that a caret past the closing quote is a FREE NAME
 * position again. Where this API diverges — a template, a caret at the opening
 * quote, an indexed-access type — the divergence is pinned as such rather than
 * discovered later.
 *
 * The discriminating device is round 917's, inverted once more: the receiver's
 * members are spelled exactly like unrelated top-level bindings, so a free-name or
 * scope-derived answer is a SUPERSET rather than an empty list or a crash, and only
 * an exact comparison separates the two.
 *
 * Offsets are derived from the fixture by `indexOf`; a hardcoded offset pins this
 * file's own arithmetic and would pass for an implementation that ignored its
 * argument.
 */
class ProjectStringMemberCompletionTest {

    /**
     * An ES `module` kind and TWO program files, for the reason
     * `ProjectCompletionTest` states: below two files the unresolved-module region
     * returns early and every import-related assertion goes vacuous.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * `alpha` and `beta` are BOTH top-level bindings and members of `Bag`, which is
     * what makes the exact-list assertions below discriminate a type-derived answer
     * from a scope-derived one. `"has space"` and `"1abc"` are members no dot can
     * reach, which is the whole reason element access exists.
     */
    private val main = """
        import { Imported } from "./b";
        export const alpha: string = "an unrelated top-level binding";
        export const beta: string = "another unrelated top-level binding";
        export interface Bag {
            alpha: string;
            beta: number;
            "has space": boolean;
            "1abc": string;
        }
        declare const bag: Bag;
        export const readBracket = bag["alpha"];
        export const readSingle = bag['alpha'];
        export const readTemplate = bag[`alpha`];
        export const readPlain: string = "alpha";
        enum Colour { Red = 1, Green = 2 }
        export const readColour = Colour["Red"];
        declare const anything: any;
        export const readAny = anything["alpha"];
        interface Indexed { [key: string]: number; known: number; }
        declare const indexed: Indexed;
        export const readIndexed = indexed["known"];
        class Guard {
            private secret: number = 1;
            pub: number = 2;
            read(): number { return this["secret"]; }
        }
        declare const imported: Imported;
        export const readImported = imported["width"];
    """.trimIndent() + "\n"

    private val other = """
        export interface Imported { width: number; height: number; }
    """.trimIndent() + "\n"

    private fun projectWith(mainText: String = main): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to mainText,
                otherFile to other,
            ),
        ),
    )

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [text]. */
    private fun offsetOf(needle: String, occurrence: Int = 0, text: String = main): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /**
     * The caret immediately after the opening quote of the FIRST `["` (or `['`) in
     * [access] — where a user who has just opened a member string is sitting.
     */
    private fun insideBracketOf(access: String, text: String = main): Int {
        val at = offsetOf(access, 0, text)
        val bracket = access.indexOf('[')
        assert(bracket >= 0)
        return at + bracket + 2
    }

    // --- THE DISCRIMINATOR --------------------------------------------------------

    /**
     * A receiver whose members are spelled exactly like two unrelated top-level
     * bindings. The wrong answer is not empty and not a crash — it is the file's
     * whole scope, which CONTAINS the right names, so only an exact comparison
     * separates a resolution through the receiver from one through the scope chain.
     */
    @Test
    fun `a caret inside a member string offers the RECEIVER's members and nothing the scope binds`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, insideBracketOf("""bag["alpha"]"""))
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.refusal == null)
        // Sorted by name, which is why `1abc` leads: the order is imposed by the API.
        assert(completions.items.map { it.name } == listOf("1abc", "alpha", "beta", "has space"))
        assert(completions.items.none { it.name == "bag" })
        assert(completions.items.none { it.name == "readBracket" })
        assert(completions.items.none { it.name == "Bag" })
    }

    /**
     * The reason element access exists, and the one thing this position offers that
     * no dot position can use: a member whose spelling is not an identifier.
     */
    @Test
    fun `a member whose spelling is not an identifier is offered here`() {
        val project = projectWith()
        val items = project.completionsAt(mainFile, insideBracketOf("""bag["alpha"]""")).items
        assert(items.any { it.name == "has space" })
        assert(items.any { it.name == "1abc" })
        // The name is the member's own spelling — UNQUOTED, as tsc's `newText` is.
        // A host inserting it into the span below produces exactly one pair of quotes.
        assert(items.first { it.name == "has space" }.typeText == "boolean")
    }

    // --- the span, and what accepting an item WRITES ------------------------------

    /**
     * The span is the literal's TEXT, quotes EXCLUDED — the same span a member
     * rename writes into (round 926), and measured to be tsc's own edit range.
     */
    @Test
    fun `the replacement span is the TEXT between the quotes`() {
        val project = projectWith()
        val caret = insideBracketOf("""bag["alpha"]""")
        val completions = project.completionsAt(mainFile, caret)
        assert(completions.prefix == "")
        assert(completions.replacementStart == caret)
        assert(completions.replacementEnd == caret + "alpha".length)
        // ...i.e. the quotes are OUTSIDE it, in both directions.
        assert(main[completions.replacementStart - 1] == '"')
        assert(main[completions.replacementEnd] == '"')
    }

    /**
     * The claim the span is FOR, checked by making the edit and compiling the
     * program again (round 925's shape): applying an item produces code that
     * compiles, with exactly one pair of quotes around the member name.
     */
    @Test
    fun `applying an item produces compiling code with exactly one pair of quotes`() {
        val project = projectWith()
        val caret = insideBracketOf("""bag["alpha"]""")
        val completions = project.completionsAt(mainFile, caret)
        val item = completions.items.first { it.name == "has space" }
        val edited = main.substring(0, completions.replacementStart) + item.name +
            main.substring(completions.replacementEnd)
        assert("""bag["has space"]""" in edited)
        val before = project.diagnostics().map { it.code }
        project.updateFile(mainFile, edited)
        // The member exists and its type is `boolean`, so the edited program means
        // what it did before — asserted by RECOMPILING it (round 925's shape) rather
        // than by reading the span arithmetic back. A span that ate a quote produces
        // `bag[has space]`, which does not parse.
        assert(project.diagnostics().map { it.code } == before)
    }

    @Test
    fun `a caret inside a partially typed member string reports the typed prefix`() {
        val project = projectWith()
        val start = insideBracketOf("""bag["alpha"]""")
        val completions = project.completionsAt(mainFile, start + 2)
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.prefix == "al")
        // The list is NOT cut by the prefix — filtering and ranking are host policy.
        assert(completions.items.map { it.name } == listOf("1abc", "alpha", "beta", "has space"))
        // The replacement still covers the whole name, so accepting mid-name leaves
        // no `pha` behind.
        assert(completions.replacementStart == start)
        assert(completions.replacementEnd == start + "alpha".length)
    }

    // --- which quotes, and which literals ------------------------------------------

    @Test
    fun `a SINGLE-quoted member string answers exactly as a double-quoted one does`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, insideBracketOf("""bag['alpha']"""))
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.items.map { it.name } == listOf("1abc", "alpha", "beta", "has space"))
    }

    /**
     * (API.16), round 931 — WAS A REFUSAL. Round 929 refused this position for ONE
     * reason: (API.9)'s occurrence population was string literals only, so a member
     * written through a template was one a later rename could not find, and this API
     * does not offer text it cannot maintain. Round 931 put the template IN that
     * population, which retires the reason rather than weakening it — the same
     * enumeration serves both, so completion and rename still cannot drift about what a
     * member name is. tsc completes it identically (measured: two items, the edit range
     * inside the backticks), and so now does this.
     */
    @Test
    fun `a caret in a TEMPLATE element access completes exactly as a quoted one does`() {
        val project = projectWith()
        val template = project.completionsAt(mainFile, insideBracketOf("bag[`alpha`]"))
        val quoted = project.completionsAt(mainFile, insideBracketOf("""bag["alpha"]"""))
        assert(template.kind == CompletionKind.MEMBER)
        assert(template.refusal == null)
        assert(template.items.map { it.name } == quoted.items.map { it.name })
    }

    /**
     * The boundary the whole classifier is about: a string literal is a member name
     * in an element-access position and NOWHERE ELSE. This literal is spelled
     * `"alpha"` exactly like the one above it, so a spelling- or token-driven answer
     * cannot tell them apart.
     */
    @Test
    fun `negative control - a plain string literal that is no member name is refused`() {
        val project = projectWith()
        val declaration = "readPlain: string = "
        // One character past the opening quote, i.e. inside a literal spelled exactly
        // like the member string above it.
        val caret = offsetOf(declaration) + declaration.length + 1
        val completions = project.completionsAt(mainFile, caret)
        assert(completions.kind == CompletionKind.NONE)
        assert(completions.refusal == CompletionRefusal.NO_COMPLETION_CONTEXT)
        assert(completions.items.isEmpty())
    }

    /**
     * tsc answers FREE NAMES past the closing quote — the caret is out of the string
     * — and so does this, which is the pre-(API.12) behaviour and must stay.
     */
    @Test
    fun `a caret PAST the closing quote is a free-name position again`() {
        val project = projectWith()
        val caret = insideBracketOf("""bag["alpha"]""") + "alpha\"".length
        val completions = project.completionsAt(mainFile, caret)
        assert(completions.kind == CompletionKind.FREE_NAME)
        assert(completions.items.any { it.name == "bag" })
    }

    // --- what the receiver's type says --------------------------------------------

    /**
     * The whole member enumeration is round 917's, unchanged — which is what makes
     * this round one classifier. These pin that it really is the same one.
     */
    @Test
    fun `an ENUM answers from its own export table`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, insideBracketOf("""Colour["Red"]"""))
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.items.map { it.name } == listOf("Green", "Red"))
    }

    @Test
    fun `an INDEX SIGNATURE contributes no item, only the named member does`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, insideBracketOf("""indexed["known"]"""))
        assert(completions.kind == CompletionKind.MEMBER)
        // tsc offers exactly this: an index signature is not a candidate a user can
        // accept, and its symbol-table spelling is not a name.
        assert(completions.items.map { it.name } == listOf("known"))
    }

    @Test
    fun `an any receiver answers an EMPTY list and is not a refusal`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, insideBracketOf("""anything["alpha"]"""))
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.refusal == null)
        assert(completions.items.isEmpty())
    }

    @Test
    fun `a this receiver inside a method offers the class's private member`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, insideBracketOf("""this["secret"]"""))
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.items.map { it.name } == listOf("pub", "read", "secret"))
        assert(completions.items.first { it.name == "secret" }.accessibility == "private")
    }

    @Test
    fun `a receiver whose interface is IMPORTED offers that interface's members`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, insideBracketOf("""imported["width"]"""))
        assert(completions.items.map { it.name } == listOf("height", "width"))
    }

    // --- the common editing state: nothing is closed yet ---------------------------

    /**
     * THE STATE A COMPLETION REQUEST IS NORMALLY MADE IN. The user has typed `bag["`
     * and there is no closing quote, no `]` and no `;` — so the caret is one past a
     * one-character token, contained by nothing, and the literal is UNTERMINATED.
     * Answering it is the difference between the feature working in an editor and
     * working only in already-written code.
     */
    @Test
    fun `an UNTERMINATED member string at the end of the file still answers`() {
        val text = main + "export const typing = bag[\""
        val project = projectWith(text)
        val completions = project.completionsAt(mainFile, text.length)
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.items.map { it.name } == listOf("1abc", "alpha", "beta", "has space"))
        // A pure insertion, just inside the opening quote.
        assert(completions.replacementStart == text.length)
        assert(completions.replacementEnd == text.length)
    }

    @Test
    fun `an UNTERMINATED member string with a partial name reports its prefix`() {
        val text = main + "export const typing = bag[\"al\n"
        val project = projectWith(text)
        val caret = text.length - 1
        val completions = project.completionsAt(mainFile, caret)
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.prefix == "al")
        assert(completions.replacementStart == caret - 2)
        assert(completions.replacementEnd == caret)
        assert(completions.items.map { it.name } == listOf("1abc", "alpha", "beta", "has space"))
    }
}
