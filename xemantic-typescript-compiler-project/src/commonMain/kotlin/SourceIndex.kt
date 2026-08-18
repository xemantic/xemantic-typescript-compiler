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
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.QualifiedName
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
    /**
     * (API.4a) Every token's START offset, parallel to [tokenEnds].
     *
     * `realEndOf` needs only the ends; a COMPLETION ANCHOR needs the whole token —
     * which token the caret is inside, where the word it is inside begins, and what
     * the token before that word is. Recorded in the same scan, so it costs nothing
     * a position query was not already paying.
     *
     * A token's start is `Scanner.getTokenPos()`, which is assigned AFTER
     * `scanLeadingTrivia()` — so trivia is in the GAPS between consecutive tokens
     * and never inside one, which is what makes [completionAnchorAt]'s comment
     * detection a scan of one gap rather than a second lexer.
     */
    private val tokenStarts: IntArray,
    /** (API.4a) Every token's kind, parallel to [tokenEnds]. */
    private val tokenKinds: Array<SyntaxKind>,
    /** (API.4a) The text this index describes — the anchor reports a PREFIX. */
    private val text: String,
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
            val tokens = scanTokens(text)
            return SourceIndex(
                sourceFile = sourceFile,
                textLength = text.length,
                tokenEnds = tokens.ends,
                tokenStarts = tokens.starts,
                tokenKinds = tokens.kinds,
                text = text,
            )
        }

        /** (API.4a) [scanTokens]' three parallel arrays. */
        private class Tokens(
            val starts: IntArray,
            val ends: IntArray,
            val kinds: Array<SyntaxKind>,
        )

        /**
         * Every token in [text], in scan order — so [Tokens.ends] is ascending.
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
        private fun scanTokens(text: String): Tokens {
            val scanner = Scanner(text)
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val kinds = ArrayList<SyntaxKind>()
            var previousEnd = -1
            while (true) {
                val token = scanner.scan()
                val end = scanner.getPos()
                starts.add(scanner.getTokenPos())
                ends.add(end)
                kinds.add(token)
                if (token == SyntaxKind.EndOfFile || end <= previousEnd) break
                previousEnd = end
            }
            return Tokens(starts.toIntArray(), ends.toIntArray(), kinds.toTypedArray())
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
     * (API.3c) Every [SyntaxKind.Identifier] node in the file, in ascending
     * position order — the candidate set a whole-file semantic sweep asks about.
     *
     * ## Why identifiers, and nothing else
     *
     * The rule has to be cheap to state, because the alternative is a taste-driven
     * list that drifts. An identifier is exactly the thing that HAS a semantic
     * answer worth asking for: it names something, so it has a type and — when it
     * is a free name — a declaration. Punctuation and keywords name nothing;
     * literals have a type but a trivially syntactic one; larger expressions have
     * types a hover could show, but a caller wanting the type of `f(x)` can ask
     * [pathAt] for the caret it actually has, and enumerating every expression node
     * would multiply the capture set for answers nobody sweeps for.
     *
     * MEMBER names are included (the `p` of `o.p`, a property signature's name):
     * they are typed, which is the sweep's primary product. Their go-to-definition
     * answer is deliberately empty — see `CapturedDefinition` — so an entry for one
     * carries a type and no locations, which is a truthful answer rather than a gap.
     *
     * ITERATIVE, exactly as [pathAt] is and for the same reason: a recursive
     * full-tree walk is bounded by the tree's depth and this repo's corpus carries
     * chains deep enough to crash one.
     */
    fun identifiers(): List<Node> {
        val found = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if ((node as NodeBase).kindId == NodeKind.IDENTIFIER) found.add(node)
            forEachChild(node) { child -> stack.add(child) }
        }
        // The walk order is a property of `forEachChild` and of the stack, i.e. of
        // this implementation; the ORDER A CALLER SEES must not be. Sorted on the raw
        // pair, which is a total order over distinct nodes.
        found.sortWith(compareBy({ it.pos }, { it.end }))
        return found
    }

    // --- (API.4a) the completion anchor ------------------------------------------

    /**
     * What a caret at [offset] may complete, and what it is attached to.
     *
     * ## The decision, in order
     *
     * 1. An [offset] outside `0 .. textLength` answers [CompletionKind.NONE].
     *    Unlike [pathAt] the file's own END is legitimate here: a caret after the
     *    last character is where a user types, and there is no node there to want.
     * 2. A caret STRICTLY INSIDE a string, template, regular-expression or numeric
     *    literal token answers NONE. Strictly: at such a token's first offset the
     *    caret is before its opening quote and at its last it is after the closing
     *    one, and neither is inside the literal.
     * 3. A caret inside a COMMENT answers NONE. Comments are not tokens here — the
     *    scanner consumes them as leading trivia before recording a token's start —
     *    so they live entirely in the GAPS between consecutive tokens, and the gap
     *    the caret falls in is re-read for `//` and comment-open markers. That is
     *    exact rather than heuristic precisely because a string literal IS a token
     *    and can never appear in a gap.
     * 4. Otherwise the WORD under the caret is found: the token W with
     *    `start < offset <= end` whose first character can begin an identifier.
     *    Strictly greater than `start`, so a caret immediately BEFORE a word is not
     *    inside it and completes as an insertion; less-or-equal `end`, so a caret
     *    immediately after `o.fo|` is inside `fo`. Keywords qualify — `new` and
     *    `class` are legal property names, and a half-typed identifier is often a
     *    keyword prefix.
     * 5. The token immediately before the word (or before the caret, when there is
     *    no word) decides the kind: a `.` or `?.` makes it
     *    [CompletionKind.MEMBER], anything else [CompletionKind.FREE_NAME]. Only
     *    NON-EMPTY tokens are considered, which is what stops the zero-width
     *    end-of-file token from displacing a real one at the end of a buffer.
     *
     * Whitespace and newlines need no rule of their own and get none: they are gaps,
     * so `o.` then a newline then the caret still finds `.` as the token before it.
     *
     * ## The receiver, and the recovery rule for an incomplete `o.`
     *
     * THE RECEIVER IS TAKEN FROM THE PARSE, NOT FROM THE TOKEN STREAM. That is
     * affordable because this parser ALWAYS builds a `PropertyAccessExpression` for
     * a `.`: when no identifier follows it reports TS1003 and synthesizes a
     * zero-width `Identifier("")` (`Parser.kt`, the `Dot ->` arm of the
     * member-access loop). So `o.` at end of file, `o.` before a `}` and `o.`
     * before a newline all produce a real node whose `expression` is the receiver,
     * and no bracket-balanced backwards scan over raw text is needed.
     *
     * The node is found by descending to the character IMMEDIATELY BEFORE the dot —
     * which is inside the receiver's own last token — and walking back OUT along
     * that path to the first `PropertyAccessExpression` or `QualifiedName` whose own
     * dot is this one. Descending to the dot itself would not work: the dot is the
     * last token of `o.`, so the access node's real end is snapped back below it and
     * the descent would stop at the source file.
     *
     * "Whose own dot is this one" is exact and needs no guessing: a member access's
     * dot lies between its receiver's real end and its name's `pos`, so the test is
     * `realEnd(expression) <= dotStart < name.pos`. In `a.b.c` that rejects `a.b`
     * (whose name starts before the second dot) and accepts `a.b.c`, giving `a.b` as
     * the receiver — and since two dots in one path are at different offsets, at
     * most one node in the path can match.
     *
     * A dot the parse did NOT turn into a member access — a dot inside a numeric
     * literal never becomes a `.` token at all, and a type-position dot our parser
     * recovers differently would be one — yields a MEMBER anchor with a null
     * [CompletionAnchor.receiver], i.e. an empty list. Refusing is deliberate: the
     * alternative is to guess a receiver from bracket-balanced text, and a
     * confidently wrong receiver produces a confidently wrong list.
     */
    fun completionAnchorAt(offset: Int): CompletionAnchor {
        if (offset < 0 || offset > textLength) return CompletionAnchor.none(offset)
        val containing = tokenContaining(offset)
        if (containing >= 0 && isUncompletableLiteral(tokenKinds[containing])) {
            return CompletionAnchor.none(offset)
        }
        if (containing < 0 && offsetIsInComment(offset)) return CompletionAnchor.none(offset)
        val word = wordTokenAt(offset)
        val anchorStart = if (word >= 0) tokenStarts[word] else offset
        val prefix = if (word >= 0) text.substring(tokenStarts[word], offset) else ""
        val replacementEnd = if (word >= 0) tokenEnds[word] else offset
        val before = tokenEndingAtOrBefore(anchorStart)
        val beforeKind = if (before >= 0) tokenKinds[before] else null
        if (beforeKind != SyntaxKind.Dot && beforeKind != SyntaxKind.QuestionDot) {
            return CompletionAnchor(
                CompletionKind.FREE_NAME,
                prefix,
                anchorStart,
                replacementEnd,
                receiver = null,
                scopeAnchor = scopeAnchorAt(offset),
            )
        }
        return CompletionAnchor(
            CompletionKind.MEMBER,
            prefix,
            anchorStart,
            replacementEnd,
            receiverOfDotAt(tokenStarts[before]),
        )
    }

    /**
     * (API.4b) The node whose SCOPE is the scope in force at [offset] — the anchor a
     * free-name completion is captured at.
     *
     * The innermost node ENCLOSING the caret, which is [pathAt]'s own answer, with
     * the source file standing in wherever [pathAt] answers nothing: a caret past
     * the last character of the file, and a caret in the trailing trivia of the last
     * statement, are both positions a user types at and both sit in the file's own
     * scope.
     *
     * ## Why enclosing is the right relation, and why nothing narrower exists
     *
     * A completion caret is BETWEEN nodes by construction. In
     * `function f() { const b = 1;\n  co| }` the caret sits at the real end of the
     * `co` expression statement, so the statement does not contain it (spans are
     * half-open) and the innermost enclosing node is the function's BODY BLOCK —
     * whose scope, since a function-like's immediate body shares its function's
     * scope in the binder, is exactly the scope holding `f`'s parameters and locals.
     * That is the answer wanted, and it is reached with no special case: the same
     * rule gives the enclosing block for a caret on a blank line, the class for a
     * caret in a class body, and the source file for a caret between top-level
     * statements.
     *
     * The node is only ever used to NAME a span the checker matches on, so a coarser
     * answer degrades to a coarser scope and never to a wrong one.
     */
    private fun scopeAnchorAt(offset: Int): Node = pathAt(offset).lastOrNull() ?: sourceFile

    /**
     * The receiver of the member access whose dot starts at [dotStart], or null when
     * the parse built no member access there — see [completionAnchorAt] for the rule
     * and for why refusing beats guessing.
     */
    private fun receiverOfDotAt(dotStart: Int): Node? {
        if (dotStart <= 0) return null
        val path = pathAt(dotStart - 1)
        for (index in path.indices.reversed()) {
            val node = path[index]
            val receiver: Node
            val namePos: Int
            when (node) {
                is PropertyAccessExpression -> {
                    receiver = node.expression
                    namePos = node.name.pos
                }
                is QualifiedName -> {
                    receiver = node.left
                    namePos = node.right.pos
                }
                else -> continue
            }
            if (realEndOf(receiver) <= dotStart && dotStart < namePos) return receiver
        }
        return null
    }

    /** The token whose span strictly contains [offset], or -1. */
    private fun tokenContaining(offset: Int): Int {
        var lo = 0
        var hi = tokenStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                offset < tokenStarts[mid] -> hi = mid - 1
                offset >= tokenEnds[mid] -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * The identifier-like token the caret is INSIDE or at the end of, or -1.
     *
     * "Identifier-like" is decided from the token's first CHARACTER rather than from
     * its kind, so every keyword qualifies without a keyword list to keep in sync —
     * which is what a member completion needs, since `new`, `class` and `default`
     * are all legal property names.
     */
    private fun wordTokenAt(offset: Int): Int {
        var lo = 0
        var hi = tokenStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                offset <= tokenStarts[mid] -> hi = mid - 1
                offset > tokenEnds[mid] -> lo = mid + 1
                else -> {
                    val start = tokenStarts[mid]
                    if (start >= textLength) return -1
                    val first = text[start]
                    return if (first.isLetter() || first == '_' || first == '$') mid else -1
                }
            }
        }
        return -1
    }

    /** The last NON-EMPTY token ending at or before [offset], or -1. */
    private fun tokenEndingAtOrBefore(offset: Int): Int {
        var lo = 0
        var hi = tokenEnds.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tokenEnds[mid] <= offset) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        // The end-of-file token is zero-width and shares its end with the file's
        // last real token, so it must not be allowed to answer here.
        while (best >= 0 && tokenStarts[best] >= tokenEnds[best]) best--
        return best
    }

    /** True for the literal kinds a caret inside offers no completion in. */
    private fun isUncompletableLiteral(kind: SyntaxKind): Boolean = when (kind) {
        SyntaxKind.StringLiteral,
        SyntaxKind.NumericLiteral,
        SyntaxKind.BigIntLiteral,
        SyntaxKind.RegularExpressionLiteral,
        SyntaxKind.NoSubstitutionTemplateLiteral,
        SyntaxKind.TemplateHead,
        SyntaxKind.TemplateMiddle,
        SyntaxKind.TemplateTail,
        -> true
        else -> false
    }

    /**
     * True when [offset] is inside a comment.
     *
     * Only the trivia GAP around [offset] is examined, which is sound because the
     * scanner records a token's start after skipping leading trivia — so a comment
     * is never inside a token, and a `//` inside a string literal is never inside a
     * gap.
     */
    private fun offsetIsInComment(offset: Int): Boolean {
        val previous = tokenEndingAtOrBefore(offset)
        var at = if (previous < 0) 0 else tokenEnds[previous]
        // A comment containing the caret must START at or before it, so scanning the
        // gap only as far as the caret decides containment.
        while (at <= offset && at < textLength) {
            if (text[at] == '/' && at + 1 < textLength && text[at + 1] == '/') {
                var end = at + 2
                while (end < textLength && text[end] != '\n' && text[end] != '\r') end++
                if (offset in at until end) return true
                at = end
            } else if (text[at] == '/' && at + 1 < textLength && text[at + 1] == '*') {
                var end = at + 2
                while (end + 1 < textLength && !(text[end] == '*' && text[end + 1] == '/')) end++
                val close = minOf(end + 2, textLength)
                if (offset in at until close) return true
                at = close
            } else {
                at++
            }
        }
        return false
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
