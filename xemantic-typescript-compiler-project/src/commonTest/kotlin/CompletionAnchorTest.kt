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
import com.xemantic.typescript.compiler.ParserFlags
import kotlin.test.Test

/**
 * (API.4a) [SourceIndex.completionAnchorAt] — what a caret is ASKING for, decided
 * from the token stream and the parse, with no checker anywhere near it.
 *
 * These are the cheapest and sharpest pins in the completion feature and they are
 * deliberately first: the anchor is the part a future agent will otherwise break,
 * because nothing downstream can tell a wrong anchor from a receiver that genuinely
 * has no members. Each assertion names the KIND, the PREFIX and the replacement
 * span — the three quantities a host consumes — so a change that gets the kind right
 * and the prefix wrong still reddens.
 *
 * Every offset is derived by `indexOf` plus an explicit caret rule, never hardcoded:
 * a hardcoded offset pins this file's arithmetic and would pass for an
 * implementation that ignored its argument.
 */
class CompletionAnchorTest {

    /** U+2038 CARET — the caret marker; see [anchorAt] for why not `|`. */
    private val CARET = '\u2038'

    /**
     * The flags are the ones a plain `.ts` file gets. They are passed explicitly
     * because `SourceIndex.of` refuses to default them (INV.1(e)) — a differently
     * flagged parse is a different tree, and no assertion about a position can see
     * that.
     */
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
     * The anchor at the caret marked `\u2038` in [marked], which is stripped first.
     *
     * The marker is U+2038 CARET rather than `|` because `|` is a union type's
     * separator and TypeScript's bitwise-or: marking with it silently placed the
     * caret inside `{ a: number } | undefined` the first time this file was run, and
     * the test then measured the wrong offset while looking entirely correct.
     */
    private fun anchorAt(marked: String): CompletionAnchor {
        val caret = marked.indexOf(CARET)
        assert(caret >= 0)
        assert(marked.indexOf(CARET, caret + 1) < 0)
        val text = marked.substring(0, caret) + marked.substring(caret + 1)
        return indexOf(text).completionAnchorAt(caret)
    }

    // --- MEMBER: the caret follows a dot ------------------------------------------

    @Test
    fun `a caret immediately after a dot is a MEMBER anchor with an empty prefix`() {
        val anchor = anchorAt("const o = { a: 1 };\no.\u2038\n")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "")
        // A pure insertion: there is no word to replace.
        assert(anchor.replacementStart == anchor.replacementEnd)
        // The receiver is a real node even though nothing follows the dot — the
        // parser synthesizes an empty name rather than abandoning the access.
        assert(anchor.receiver != null)
    }

    @Test
    fun `a caret at the very end of the file after a dot still anchors`() {
        // No trailing newline and no statement terminator: the dot is the LAST token
        // in the file, which is the case that defeats descending to the dot itself
        // (the access node's real end is snapped back below it).
        val anchor = anchorAt("const o = { a: 1 };\no.\u2038")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.receiver != null)
    }

    @Test
    fun `a caret inside a partially typed member name reports the typed prefix`() {
        val marked = "const o = { alpha: 1 };\no.al\u2038pha\n"
        val anchor = anchorAt(marked)
        assert(anchor.kind == CompletionKind.MEMBER)
        // The PREFIX is what was typed to the LEFT of the caret...
        assert(anchor.prefix == "al")
        val text = marked.replace(CARET.toString(), "")
        // ...while the REPLACEMENT covers the whole word, so accepting an item
        // mid-word leaves no `pha` behind.
        assert(anchor.replacementStart == text.indexOf("o.alpha") + 2)
        assert(anchor.replacementEnd == anchor.replacementStart + "alpha".length)
        assert(anchor.receiver != null)
    }

    @Test
    fun `a caret at the end of a member name is inside that word`() {
        val anchor = anchorAt("const o = { alpha: 1 };\no.alpha\u2038\n")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "alpha")
    }

    @Test
    fun `a caret in whitespace after a dot is still a MEMBER anchor`() {
        val anchor = anchorAt("const o = { a: 1 };\no.   \u2038\n")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "")
        assert(anchor.receiver != null)
    }

    @Test
    fun `a caret on a new line after a dot is still a MEMBER anchor`() {
        // Trivia is a GAP between tokens, so a newline needs no rule of its own —
        // and this is the shape that makes a naive "the node at the caret" lookup
        // answer with the enclosing statement.
        val anchor = anchorAt("const o = { a: 1 };\no.\n   \u2038\n")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.receiver != null)
    }

    @Test
    fun `a caret after an optional chaining dot is a MEMBER anchor`() {
        val anchor = anchorAt("declare const o: { a: number } | undefined;\no?.\u2038\n")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "")
        assert(anchor.receiver != null)
    }

    @Test
    fun `a caret after the SECOND dot of a chain anchors on the whole left expression`() {
        val marked = "const o = { a: { b: 1 } };\no.a.\u2038\n"
        val anchor = anchorAt(marked)
        assert(anchor.kind == CompletionKind.MEMBER)
        val text = marked.replace(CARET.toString(), "")
        val receiver = anchor.receiver
        assert(receiver != null)
        // `o.a`, not `a` and not `o` — the discriminating fact is the receiver's own
        // start, which only the outer property access has.
        assert(receiver.pos == text.indexOf("o.a.\n"))
        assert(receiver.kind.name == "PropertyAccessExpression")
    }

    @Test
    fun `a member anchor after a call expression receiver is the call`() {
        val marked = "declare function f(): { a: number };\nf().\u2038\n"
        val anchor = anchorAt(marked)
        assert(anchor.kind == CompletionKind.MEMBER)
        val receiver = anchor.receiver
        assert(receiver != null)
        assert(receiver.kind.name == "CallExpression")
    }

    // --- FREE_NAME: everything else that admits a completion ----------------------

    @Test
    fun `a caret inside a free identifier is a FREE_NAME anchor carrying its prefix`() {
        val marked = "const alpha = 1;\nconst b = al\u2038pha;\n"
        val anchor = anchorAt(marked)
        assert(anchor.kind == CompletionKind.FREE_NAME)
        assert(anchor.prefix == "al")
        assert(anchor.receiver == null)
        val text = marked.replace(CARET.toString(), "")
        assert(anchor.replacementStart == text.indexOf("= alpha") + 2)
        assert(anchor.replacementEnd == anchor.replacementStart + "alpha".length)
    }

    @Test
    fun `a caret at the start of a statement is a FREE_NAME anchor with no prefix`() {
        val anchor = anchorAt("const a = 1;\n\u2038\n")
        assert(anchor.kind == CompletionKind.FREE_NAME)
        assert(anchor.prefix == "")
        assert(anchor.replacementStart == anchor.replacementEnd)
    }

    @Test
    fun `a caret immediately BEFORE a word is not inside it`() {
        // The rule is `start < offset`, so the word to the right is untouched and an
        // accepted item is inserted. Stated as a pin because the opposite convention
        // is equally defensible and a silent change of it would move every host's
        // replacement span.
        val marked = "const alpha = 1;\nconst b = \u2038alpha;\n"
        val anchor = anchorAt(marked)
        assert(anchor.kind == CompletionKind.FREE_NAME)
        assert(anchor.prefix == "")
        assert(anchor.replacementStart == anchor.replacementEnd)
    }

    @Test
    fun `a caret after a name and a space is a FREE_NAME anchor, not a MEMBER one`() {
        // The negative control for the dot rule: the token before the caret is an
        // identifier, so nothing may be resolved through a receiver.
        val anchor = anchorAt("const alpha = 1;\nalpha \u2038\n")
        assert(anchor.kind == CompletionKind.FREE_NAME)
        assert(anchor.receiver == null)
    }

    // --- NONE: positions that admit no completion ---------------------------------

    @Test
    fun `a caret inside a string literal admits no completion`() {
        val anchor = anchorAt("const s = \"ab\u2038cd\";\n")
        assert(anchor.kind == CompletionKind.NONE)
        assert(anchor.receiver == null)
    }

    @Test
    fun `a caret inside a string that CONTAINS a dot admits no completion`() {
        // The discriminating case for the token rule: a naive backwards scan for a
        // `.` character would find one and offer members of nothing.
        val anchor = anchorAt("const o = { a: 1 };\nconst s = \"o.\u2038\";\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret inside a template literal admits no completion`() {
        val anchor = anchorAt("const s = `ab\u2038cd`;\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret inside a line comment admits no completion`() {
        val anchor = anchorAt("const a = 1;\n// a co\u2038mment\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret inside a line comment that contains a dot admits no completion`() {
        // A comment lives in a trivia GAP, so it is found by re-reading the gap —
        // and a `.` inside it must not turn into a member anchor.
        val anchor = anchorAt("const o = { a: 1 };\n// see o.\u2038foo\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret inside a block comment admits no completion`() {
        val anchor = anchorAt("const a = 1;\n/* a co\u2038mment */\nconst b = 2;\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret inside a numeric literal admits no completion`() {
        val anchor = anchorAt("const n = 12\u203834;\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret outside the file admits no completion`() {
        val index = indexOf("const a = 1;\n")
        assert(index.completionAnchorAt(-1).kind == CompletionKind.NONE)
        assert(index.completionAnchorAt(9999).kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret at the very end of the file is a FREE_NAME anchor, not out of range`() {
        // `pathAt` excludes the file's own end because its span is half-open; the
        // anchor must NOT, because that offset is exactly where a user types.
        val text = "const a = 1;\n"
        val anchor = indexOf(text).completionAnchorAt(text.length)
        assert(anchor.kind == CompletionKind.FREE_NAME)
    }
}
