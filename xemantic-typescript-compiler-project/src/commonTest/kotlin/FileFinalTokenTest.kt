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
import com.xemantic.typescript.compiler.NodeKind
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.ParserFlags
import kotlin.test.Test

/**
 * (API.18) The file-final token is reachable when — and ONLY when — a node
 * owns it.
 *
 * The defect these pin: a file whose last character is its last token's (no
 * trailing newline) records EXACT raw ends up the whole containing chain (the
 * EOF lookahead is zero-width), `realEndOf`'s strictly-below snap truncated
 * every one of those spans short of the token, and every position lookup
 * answered null anywhere inside a file-final identifier — silently. Two
 * span-arithmetic fixes were built and reverted before this one; the analysis
 * (in the (API.18) queue item) is that a container and an abutter are
 * indistinguishable by `(pos, rawEnd)` at EOF, so ownership is decided by a
 * DESCENT whose leaf must START at the token — which an identifier, literal
 * or keyword does and a closing bracket or an abutter never can.
 */
class FileFinalTokenTest {

    private fun index(text: String): SourceIndex = SourceIndex.of(
        text,
        "/proj/src/t.ts",
        ParserFlags(
            forceJsx = false,
            topLevelAwait = false,
            needsJsxFlag = false,
            noImplicitAny = true,
        ),
    )

    @Test
    fun `a file-final identifier with no trailing newline is reachable at every offset`() {
        val text = "const abc = 1;\nconst tail = abc"
        val idx = index(text)
        val start = text.length - 3
        for (offset in start until text.length) {
            val leaf = idx.pathAt(offset).lastOrNull()
            val leafIsTheIdentifier = (leaf as? Identifier)?.text == "abc" && leaf.pos == start
            assert(leafIsTheIdentifier)
        }
    }

    @Test
    fun `the healed final identifier answers an exact real end`() {
        val text = "const abc = 1;\nconst tail = abc"
        val idx = index(text)
        val leaf = idx.pathAt(text.length - 1).lastOrNull()
        val realEnd = leaf?.let { idx.realEndOf(it) }
        assert(realEnd == text.length)
    }

    @Test
    fun `a trailing newline and its absence answer the same final-identifier path`() {
        // The heal must be invisible on a healthy file: the differential is the
        // node-kind chain at the same caret, with and without the newline.
        val bare = "const abc = 1;\nconst tail = abc"
        val caret = bare.length - 2
        fun chainOf(text: String): List<Pair<Int, Int>> =
            index(text).pathAt(caret).map { (it as NodeBase).kindId to it.pos }
        val bareChain = chainOf(bare)
        val healthyChain = chainOf(bare + "\n")
        val same = bareChain == healthyChain
        val nonEmpty = bareChain.isNotEmpty()
        assert(nonEmpty)
        assert(same)
    }

    @Test
    fun `a punctuation-final file keeps the conservative answer`() {
        // No node STARTS at a closing brace, so the owner chain is empty and
        // the caret on it answers what it answered before the heal — the
        // honest record that this population stays unhealed.
        val text = "function f() { return abc }"
        val idx = index(text)
        val path = idx.pathAt(text.length - 1)
        val onlySourceFile = path.size == 1 &&
            (path[0] as NodeBase).kindId == NodeKind.SOURCE_FILE
        assert(onlySourceFile)
    }

    @Test
    fun `a return statement before a file-final brace is not widened onto it`() {
        // `return abc` ABUTS the `}` — its raw end IS the final token's end —
        // and must keep its snapped span: a caret inside `abc` still answers
        // the identifier, never a span that swallowed the brace.
        val text = "function f() { return abc }"
        val idx = index(text)
        val caret = text.indexOf("abc") + 1
        val leaf = idx.pathAt(caret).lastOrNull()
        val leafName = (leaf as? Identifier)?.text
        val leafEnd = leaf?.let { idx.realEndOf(it) }
        assert(leafName == "abc")
        assert(leafEnd == text.indexOf("abc") + 3)
    }

    @Test
    fun `a dangling dot at end of file keeps its recovery behavior`() {
        // The final token is the `.` — nothing starts at it (the parser's
        // zero-width recovery identifier sits AFTER it), so the owner chain is
        // empty and the dangling-dot completion population is untouched.
        val text = "const holder = { p: 1 };\nholder."
        val idx = index(text)
        val dotOffset = text.length - 1
        val path = idx.pathAt(dotOffset)
        val onlySourceFile = path.size == 1 &&
            (path[0] as NodeBase).kindId == NodeKind.SOURCE_FILE
        assert(onlySourceFile)
    }

    @Test
    fun `an asi sibling whose raw end overshoots onto the final token loses to the owner`() {
        // `foo` and `bar` are two ASI-separated expression statements; the
        // first one's raw end IS the final token's end (its following token is
        // `bar`), and the last-match descent is what keeps a caret inside
        // `bar` out of it.
        val text = "foo\nbar"
        val idx = index(text)
        val caret = text.length - 2
        val leaf = idx.pathAt(caret).lastOrNull()
        val leafName = (leaf as? Identifier)?.text
        val leafPos = leaf?.pos
        assert(leafName == "bar")
        assert(leafPos == 4)
    }

    @Test
    fun `a file-final numeric literal is reachable too`() {
        val text = "const x = 12345"
        val idx = index(text)
        val caret = text.length - 3
        val leaf = idx.pathAt(caret).lastOrNull()
        val leafIsLiteral = leaf != null &&
            (leaf as NodeBase).kindId == NodeKind.NUMERIC_LITERAL_NODE
        assert(leafIsLiteral)
    }

    @Test
    fun `an empty file and a trivia-only file stay empty answers`() {
        val emptyPath = index("").pathAt(0)
        val triviaPath = index("// just a comment\n").pathAt(3)
        assert(emptyPath.isEmpty())
        val triviaAnswersShallow = triviaPath.size <= 1
        assert(triviaAnswersShallow)
    }

}
