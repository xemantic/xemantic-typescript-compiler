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
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.forEachChild
import kotlin.test.Test

/**
 * MEASURED semantics of `Node.pos` / `Node.end` in THIS compiler, recorded because
 * a position-to-node lookup is arithmetic over those two numbers and the
 * convention is not the one tsc's own is.
 *
 * ## Finding 1 — `pos` excludes leading trivia
 *
 * **`Node.pos` is tsc's `getStart()`, not tsc's `pos`.** In tsc a node's `pos`
 * is its FULL start — leading trivia included — and `getStart()` is the separate
 * call that skips forward over the comments; every editor-feature routine there is
 * written against that split. Here `Parser.getPos()` is `Scanner.getTokenPos()`,
 * and `Scanner.scan` assigns `tokenPos = pos` AFTER `scanLeadingTrivia()`, so a
 * node's `pos` is already the first character of its first TOKEN. Leading comments
 * are not inside the span at all: they are carried beside it, in
 * `Node.leadingComments`.
 *
 * Consequences for anything built on top, which is why this is a pin and not a
 * comment: a "narrowest node containing this offset" walk needs NO `getStart()`
 * skip (adding one, as a reader of tsc would, moves the answer forward over the
 * node's own first token), and an offset inside a comment is contained by NO node
 * except the enclosing container, so trivia positions must be answered by a
 * deliberate rule rather than falling out of the arithmetic.
 *
 * ## Finding 2 — `end` is NOT the end of the node, and this is the blocking one
 *
 * **`Node.end` is the end of the token FOLLOWING the node.** `Parser.getEnd()` is
 * `Scanner.getPos()`, and a node finishes parsing only after the parser has taken
 * its one-token lookahead, so the scanner has already moved past the next token.
 * Measured over a preorder walk of `const abc = 1;\nlet d = abc;\n`, EVERY node but
 * the source file overshoots by exactly one token: the identifier `abc` reads
 * `[6, 11)` where its text is `[6, 9)` — its `end` is past the `=` — and the first
 * statement reads `[0, 18)` where its text ends at 14, its `end` being past `let`.
 *
 * So sibling spans OVERLAP, and "the narrowest node whose `[pos, end)` contains
 * this offset" is not a well-formed question against these numbers: a caret on the
 * `=` is inside the preceding identifier's span, and a caret on `let` is inside the
 * preceding statement's. A position-to-node lookup must bound a node's real end by
 * the token stream or by the next sibling's `pos`; it cannot use `end`. That is a
 * design constraint the (API.2) queue entry did not anticipate, and it is the
 * reason the node-lookup half of that item is not in this module yet.
 *
 * ## Why parsing directly is legitimate HERE and not in production code
 *
 * INV.1(e) requires every option-derived parse argument to flow through
 * `computeParserFlags`, which is `internal` to the core module and therefore
 * unreachable from here (see the round-910 session note). This fixture is
 * deliberately chosen so the question does not arise: it is a `.ts` file with no
 * `import`/`export`, no `await` and no JSX, for which `forceJsx`, `needsJsxFlag`
 * and `topLevelAwait` are false under EVERY option set, and `noImplicitAny` — the
 * only remaining flag — changes diagnostics rather than the tree. So the defaults
 * used below provably are the flags a compile would compute for this text, and
 * nothing here is a template for a production parse site.
 */
class NodeSpanSemanticsTest {

    /** A statement, a comment line, then a statement — the minimal trivia shape. */
    private val text = "const x = 1;\n// a comment\nlet y = 2;\n"

    private fun parse() = Parser(text, "/proj/src/a.ts").parse()

    @Test
    fun `a node's pos is its first token and excludes its leading trivia`() {
        val file = parse()
        assert(file.statements.size == 2)
        val second = file.statements[1]
        // THE measurement: the comment starts at 13 and `let` at 26. If `pos` were
        // tsc's full start it would be 13.
        assert(second.pos == text.indexOf("let y"))
        assert(second.pos != text.indexOf("// a comment"))
    }

    @Test
    fun `leading trivia is carried beside the node rather than inside its span`() {
        val file = parse()
        val comments = file.statements[1].leadingComments
        // `assert` carries a contract, so `comments` smart-casts below.
        assert(comments != null)
        assert(comments.size == 1)
        assert(comments[0].pos == text.indexOf("// a comment"))
        // Below the node's own start, i.e. outside the span — which is exactly why a
        // lookup cannot reach a comment by containment.
        assert(comments[0].pos < file.statements[1].pos)
    }

    @Test
    fun `the first statement starts at offset zero`() {
        val file = parse()
        assert(file.statements[0].pos == 0)
    }

    // --- `end`, which is the half that does NOT mean what it says ---------------

    /** Two statements, so the second one's first token follows the first one's `;`. */
    private val pair = "const abc = 1;\nlet d = abc;\n"

    /** Every node of [pair] in preorder. */
    private fun preorder(): List<Node> {
        val nodes = ArrayList<Node>()
        fun walk(node: Node) {
            nodes.add(node)
            forEachChild(node) { walk(it) }
        }
        walk(Parser(pair, "/proj/src/b.ts").parse())
        return nodes
    }

    @Test
    fun `a node's end runs past the node - to the end of the FOLLOWING token`() {
        // MEASURED, and the single most consequential fact about these two numbers:
        // `Parser.getEnd()` is `Scanner.getPos()`, read AFTER the parser has taken
        // its one-token lookahead — so a node's `end` is the end of the token AFTER
        // it, not its own. Both halves below are derived from the fixture text rather
        // than hardcoded, so the pin says what it means.
        val nodes = preorder()
        val statements = Parser(pair, "/proj/src/b.ts").parse().statements
        // `const abc = 1;` ends at 14; its `end` is past `let`.
        assert(statements[0].end == pair.indexOf("let") + "let".length)
        // The identifier `abc` is three characters; its `end` is past the `=`.
        val abc = nodes.first { it.kind == SyntaxKind.Identifier }
        assert(abc.pos == pair.indexOf("abc"))
        assert(abc.end == pair.indexOf('=') + 1)
    }

    @Test
    fun `sibling spans therefore OVERLAP and cannot decide containment`() {
        // The consequence for anything built on top, which is why the fact above is
        // pinned rather than noted: "the narrowest node whose [pos, end) contains
        // this offset" is not a well-formed question against these spans. The offset
        // of `let` is inside the PREVIOUS statement's span, and the offset of the `=`
        // is inside the preceding identifier's — so a containment walk answers "you
        // are on `abc`" for a caret on the `=`.
        val statements = Parser(pair, "/proj/src/b.ts").parse().statements
        assert(statements[0].end > statements[1].pos)
        val letOffset = pair.indexOf("let")
        assert(letOffset >= statements[0].pos && letOffset < statements[0].end)
        // A position lookup must therefore bound a node's real end by the token
        // stream (or by the next sibling's `pos`), NOT by `end`.
    }

    @Test
    fun `the source file's span is the whole text`() {
        // The one node whose `end` is exact, and only because the token following it
        // is the zero-width end-of-file.
        val file = Parser(pair, "/proj/src/b.ts").parse()
        assert(file.pos == 0)
        assert(file.end == pair.length)
    }
}
