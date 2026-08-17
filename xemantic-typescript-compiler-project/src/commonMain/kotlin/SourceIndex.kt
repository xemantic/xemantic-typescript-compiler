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

import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NodeKind
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.ParserFlags
import com.xemantic.typescript.compiler.Scanner
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.forEachChild

/**
 * One file's parse, plus the one extra thing a position lookup needs and the tree
 * does not carry: where each TOKEN ends.
 *
 * ## The problem this class exists to solve
 *
 * "The narrowest node containing this offset" is not answerable from `Node.pos` and
 * `Node.end` in this compiler, because **`Node.end` is not the end of the node**.
 * `Parser.getEnd()` is `Scanner.getPos()`, read after the parser has taken its
 * one-token lookahead, so a node's `end` is the end of the token FOLLOWING it.
 * Sibling spans therefore OVERLAP: in `const abc = 1;` the identifier `abc` reads
 * `[6, 11)` — past the `=` — and in `const abc = 1;\nlet d = abc;` the first
 * statement reads `[0, 18)`, past `let`. `NodeSpanSemanticsTest` measures both.
 * (`pos` is fine: it is already the first character of the node's first token,
 * tsc's `getStart()` rather than tsc's `pos`, with leading comments carried beside
 * the node in `Node.leadingComments` rather than inside its span.)
 *
 * ## The two candidate mechanisms, and why this is the token one
 *
 * **(a) Bound a node's end by the NEXT SIBLING's `pos`** (and by the parent's bound
 * for a last child) is cheaper — pure AST arithmetic, no re-scan — and it is
 * REFUTED by the very case it has to get right. It fixes the statement case (`let`
 * starts at 15, so statement 0 is bounded to `[0, 15)`) and it does NOT fix the
 * operator case: inside `const abc = 1;` the identifier `abc` ends at 11 and its
 * next sibling — the initializer `1` — starts at 12, so the bound is `min(11, 12)`
 * = 11 and a caret on the `=` at offset 10 is STILL reported as being on `abc`.
 * The `=` belongs to no child, and no arithmetic over child positions can see a
 * token that no child covers. Trimming trailing whitespace off the bound does not
 * help either: the overshoot ends in `=`, not in a space.
 *
 * **(b) Re-scan and snap each node's end back to the token stream** — what this
 * class does. Stated as an invariant rather than a heuristic: if a node spans
 * tokens `t[i]..t[j]` then `node.pos == t[i].start` and `node.end == t[j+1].end`,
 * so the node's real end is `t[j].end`, i.e.
 *
 *     realEnd(node) = the greatest token end STRICTLY BELOW node.end
 *
 * which needs no sibling, no parent and no knowledge of which tokens a node owns —
 * only the sorted set of token ends. On the two measured cases it gives 9 for `abc`
 * (`=` ends at 11, the token before it ends at 9) and 14 for the first statement
 * (`let` ends at 18, the `;` before it ends at 14), which is exactly the text.
 *
 * ## What it costs, and where it degrades
 *
 * One extra linear scan of the file, once per parse, cached with it — the same
 * order as the parse it rides beside, and paid only by a host that asks a position
 * question. Nothing in the compile path is touched.
 *
 * The scan is a plain `Scanner.scan()` loop, so it does NOT reproduce the parser's
 * context-sensitive re-scans (`reScanSlashToken`, `reScanTemplateToken`,
 * `scanJsxText`). That is sound in one direction and not the other, and the
 * direction it is sound in is the one that matters: a context-free scan can only
 * SPLIT a contextual token into several (a regex literal `/ab/` scans as `/`, `ab`,
 * `/`), which ADDS ends and leaves every real token boundary present, so
 * `realEnd` is unchanged. It could only go wrong by MERGING across a real
 * boundary — a template head `` `a${ `` scanned as one whole template token —
 * where the greatest-end-below search then answers too low and the node's span
 * comes out short or empty. A short span is not a wrong answer, it is a COARSER
 * one: the descent simply does not enter that node and reports its parent. No
 * position can ever be attributed to the wrong file, the wrong statement or a
 * sibling, because a bound derived this way is never too HIGH.
 *
 * ## The boundary convention
 *
 * `start until end` — half-open, `offset == start` is inside, `offset == end` is
 * not. An editor caret sits BETWEEN characters, so some tie has to be broken, and
 * this one is broken the way the rest of the compiler already breaks it
 * (`Diagnostic.start`/`length`, `Node.pos`/`end` and `LineMap`'s offset domain are
 * all half-open) and the way tsc's own `getTokenAtPosition` does. The consequence
 * worth stating: a caret immediately AFTER an identifier (`abc|`) is not on that
 * identifier, it is on the enclosing node. `tsserver` gives that caret quick-info
 * anyway, and it does so in a layer ABOVE this one (`getTouchingToken`'s
 * `includePrecedingTokenAtEndPosition`); a host wanting the same asks here twice,
 * at `offset` and at `offset - 1`. Building that preference in here would make the
 * primitive ambiguous — two adjacent nodes would both contain the boundary — and
 * an ambiguous primitive cannot be layered on.
 */
internal class SourceIndex private constructor(
    /** The parse this index describes. */
    val sourceFile: SourceFile,
    /** The length of the text that was parsed; also the source file's real end. */
    val textLength: Int,
    /**
     * Every token's end offset, in scan order and therefore ascending.
     *
     * The whole mechanism: see the class KDoc. Ends rather than starts because the
     * quantity a node carries is an END that overshoots by one token, and mapping
     * it back needs the PREVIOUS end.
     */
    private val tokenEnds: IntArray,
) {

    companion object {

        /**
         * Parses [text] as [fileName] with [flags] and indexes its tokens.
         *
         * [flags] is not optional and not defaulted on purpose: INV.1(e) makes the
         * option-derived parser flags part of what a parse IS, and a lookup that
         * described a differently-flagged tree than the compile checks would be
         * wrong in a way no assertion about positions could see. The caller obtains
         * them from `computeParserFlags`, which is the compiler's own single source
         * for them.
         */
        fun of(text: String, fileName: String, flags: ParserFlags): SourceIndex {
            val sourceFile = Parser(
                text,
                fileName,
                forceJsx = flags.forceJsx,
                topLevelAwait = flags.topLevelAwait,
                needsJsxFlag = flags.needsJsxFlag,
                noImplicitAny = flags.noImplicitAny,
            ).parse()
            return SourceIndex(sourceFile, text.length, scanTokenEnds(text))
        }

        /**
         * Every token end in [text], ascending.
         *
         * The loop terminates on end-of-file, and ALSO on a token that did not
         * advance the scanner. That second exit is a fail-safe rather than a
         * reachable case: this class is meant to run inside a long-lived host, and
         * the failure mode of a scanner that stops advancing on some input is not a
         * wrong answer but a wedged process — the shape that has already cost this
         * repo a daemon (CLAUDE.md, round 873: a request that never returns holds
         * the compile thread for good). Truncating the index degrades every later
         * node in the file to a coarser answer, which is the same graceful direction
         * as the class KDoc's merge case.
         */
        private fun scanTokenEnds(text: String): IntArray {
            val scanner = Scanner(text)
            val ends = ArrayList<Int>()
            var previousEnd = -1
            while (true) {
                val token = scanner.scan()
                val end = scanner.getPos()
                ends.add(end)
                if (token == SyntaxKind.EndOfFile || end <= previousEnd) break
                previousEnd = end
            }
            return ends.toIntArray()
        }
    }

    /**
     * The offset one past [node]'s last character — the number `Node.end` is
     * commonly mistaken for.
     *
     * The source file is the one node whose `end` is already exact, because the
     * token following it is the zero-width end-of-file; it is answered from the
     * text length directly rather than through the search, which would otherwise
     * snap it back to the last real token and lose the file's trailing trivia.
     *
     * Clamped to at least `node.pos`, so a node the search cannot place (a
     * zero-width recovery node, or one inside a region the context-free scan
     * merged) gets an EMPTY span and is skipped by the descent, rather than a
     * span running backwards.
     */
    fun realEndOf(node: Node): Int {
        if ((node as NodeBase).kindId == NodeKind.SOURCE_FILE) return textLength
        val bound = node.end
        // The greatest token end strictly below `bound`.
        var lo = 0
        var hi = tokenEnds.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tokenEnds[mid] < bound) {
                best = tokenEnds[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (best > node.pos) best else node.pos
    }

    /**
     * The chain of nodes containing [offset], source file FIRST and the narrowest
     * node last, or empty when [offset] is in no node at all.
     *
     * Empty for a negative [offset] and for anything at or past [textLength]: the
     * source file's own span is half-open too, so the caret after the last
     * character of a file is outside it. An out-of-range offset is answered rather
     * than rejected — unlike `LineMap`, which throws — because "is there a node
     * here" has a truthful negative answer for any number, where "what coordinate
     * is this offset" does not.
     *
     * ITERATIVE, deliberately: a recursive descent would be bounded by the tree's
     * depth, and this repo's own corpus carries expression chains thousands of
     * levels deep whose only symptom is a hard crash (CLAUDE.md's deep-recursion
     * rule).
     */
    fun pathAt(offset: Int): List<Node> {
        if (offset < 0 || offset >= textLength) return emptyList()
        val path = ArrayList<Node>()
        var current: Node = sourceFile
        while (true) {
            path.add(current)
            // First match wins, and there can be only one: the bounded spans of two
            // siblings cannot overlap, because one's last token ends before the
            // other's first token starts.
            var found: Node? = null
            forEachChild(current) { child ->
                if (found == null && offset >= child.pos && offset < realEndOf(child)) {
                    found = child
                }
            }
            current = found ?: break
        }
        return path
    }
}
