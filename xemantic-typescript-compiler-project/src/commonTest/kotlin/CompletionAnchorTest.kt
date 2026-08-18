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

    // --- (API.12) MEMBER: the caret is inside the string of an element access -----

    @Test
    fun `a caret just inside the opening quote of an element access is a MEMBER anchor`() {
        val marked = "declare const o: { alpha: 1 };\nconst r = o[\"\u2038\"];\n"
        val anchor = anchorAt(marked)
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "")
        // A pure insertion INSIDE the quotes: nothing has been typed yet.
        assert(anchor.replacementStart == anchor.replacementEnd)
        assert(anchor.receiver != null)
    }

    @Test
    fun `the element access span is the TEXT, with both quotes outside it`() {
        val marked = "declare const o: { alpha: 1 };\nconst r = o[\"al\u2038pha\"];\n"
        val anchor = anchorAt(marked)
        val text = marked.replace(CARET.toString(), "")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "al")
        // The SECOND `alpha` — the first is the type literal's member declaration.
        assert(anchor.replacementStart == text.indexOf("\"alpha\"") + 1)
        assert(anchor.replacementEnd == anchor.replacementStart + "alpha".length)
        // The quotes are on the OUTSIDE — the span accepting an item replaces is the
        // literal's text, exactly as `occurrenceSpanOf` gives a rename.
        assert(text[anchor.replacementStart - 1] == '"')
        assert(text[anchor.replacementEnd] == '"')
    }

    @Test
    fun `a SINGLE-quoted element access anchors exactly as a double-quoted one does`() {
        val anchor = anchorAt("declare const o: { alpha: 1 };\nconst r = o['al\u2038pha'];\n")
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "al")
        assert(anchor.receiver != null)
    }

    @Test
    fun `an UNTERMINATED element access string at end of file is a MEMBER anchor`() {
        // THE COMMON EDITING STATE: the caret is one past a ONE-CHARACTER token,
        // contained by no token at all, and only the parser's own `isUnterminated`
        // separates it from a caret past a CLOSED literal's quote.
        val text = "declare const o: { alpha: 1 };\nconst r = o[\""
        val anchor = indexOf(text).completionAnchorAt(text.length)
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "")
        assert(anchor.replacementStart == text.length)
        assert(anchor.replacementEnd == text.length)
        assert(anchor.receiver != null)
    }

    @Test
    fun `an UNTERMINATED element access string before a newline reports its prefix`() {
        val text = "declare const o: { alpha: 1 };\nconst r = o[\"al\n"
        val caret = text.length - 1
        val anchor = indexOf(text).completionAnchorAt(caret)
        assert(anchor.kind == CompletionKind.MEMBER)
        assert(anchor.prefix == "al")
        // The span runs to the token's end, which for an unterminated literal is
        // where the line breaks — there is no closing quote to step back over.
        assert(anchor.replacementStart == caret - 2)
        assert(anchor.replacementEnd == caret)
    }

    @Test
    fun `a caret AT the opening quote is not inside the string and admits no completion`() {
        // Measured divergence from tsc, which answers free names there. Stated in
        // `completionAnchorAt`: it is what every literal's first offset answers.
        val anchor = anchorAt("declare const o: { alpha: 1 };\nconst r = o[\u2038\"alpha\"];\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `a caret PAST the closing quote is a FREE_NAME anchor again`() {
        val anchor = anchorAt("declare const o: { alpha: 1 };\nconst r = o[\"alpha\"\u2038];\n")
        assert(anchor.kind == CompletionKind.FREE_NAME)
    }

    /**
     * The caret past a closing quote that NO TOKEN BEGINS AT — a space follows —
     * which is the only shape that reaches the "a closed literal's end is outside
     * it" rule: with `]` hard against the quote, the caret is inside the `]` token
     * and the token-kind test refuses first. Round 929's ablation found the rule
     * unreachable by every other pin in this file, which is a missing pin rather
     * than a redundant guard (CLAUDE.md, round 902).
     */
    @Test
    fun `a caret past a closing quote followed by a SPACE is a FREE_NAME anchor`() {
        val anchor = anchorAt("declare const o: { alpha: 1 };\nconst r = o[\"alpha\"\u2038 ];\n")
        assert(anchor.kind == CompletionKind.FREE_NAME)
    }

    @Test
    fun `negative control - a TEMPLATE element access admits no completion`() {
        // A stated divergence from tsc: (API.9)'s occurrence population is string
        // literals only, so a member written through a template is one a rename
        // cannot find, and this API does not offer text it cannot maintain.
        val anchor = anchorAt("declare const o: { alpha: 1 };\nconst r = o[`al\u2038pha`];\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    @Test
    fun `negative control - an INDEXED ACCESS TYPE admits no completion`() {
        // tsc offers FREE NAMES here rather than member names, so refusing diverges
        // from it by exactly as much as offering members would.
        val anchor = anchorAt("interface Bag { alpha: 1 }\ntype T = Bag[\"al\u2038pha\"];\n")
        assert(anchor.kind == CompletionKind.NONE)
    }

    // --- NONE: positions that admit no completion ---------------------------------

    /**
     * (API.12) Also the boundary pin for the element-access classifier: this literal
     * is in no element access, so a classifier keyed on the TOKEN rather than on the
     * position would answer members here.
     */
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
