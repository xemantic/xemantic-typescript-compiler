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

import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.JsxText
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.NoSubstitutionTemplateLiteralNode
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NodeKind
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.ParserFlags
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.QualifiedName
import com.xemantic.typescript.compiler.RegularExpressionLiteralNode
import com.xemantic.typescript.compiler.Scanner
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.StringLiteralNode
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
 * The scan reproduces the parser's context-sensitive lexing in two ways and
 * deliberately not in a third. The template re-scan is reproduced by TRACKING the
 * substitution nesting exactly as `Parser` does (see [scanTokens]); the regular
 * expression and the JSX text are reproduced by ASKING THE PARSE (see
 * [contextualLexemes]), because the parser turns each of them into a node carrying
 * its own raw text and therefore its own exact span; and `reScanGreaterToken` is not
 * reproduced at all.
 *
 * The asymmetry is not taste, it is round 919's law. A context-free scan that SPLITS
 * a contextual token into several (a `>>` scanning as one token where the parser
 * wants two) only ADDS ends and leaves every real token boundary present, so
 * `realEnd` is unchanged and the worst case is a bound that is never too HIGH — a
 * node the descent declines to enter, reported as its parent. A scan that MERGES
 * across a real boundary is a different animal: one unhandled `${` de-synchronises
 * the token stream for the WHOLE REST OF THE FILE, which is a wrong answer at every
 * later position rather than a coarse one.
 *
 * **A regular expression is a splitting case that turns into a merging one whenever
 * its body holds a quote or a backtick**, which is what (GATE.2) measured: tsc's own
 * `utilities.ts` contains ``/\r\n|[\\`…]/g``, whose backtick opened a
 * `NoSubstitutionTemplateLiteral` running to the next backtick anywhere in the file
 * — a 25,761-character token that swallowed the twelve identifiers after it. JSX
 * text is the same shape one construct over (`<p>it's fine</p>`). So both are taken
 * from the parse rather than guessed, which is also why nothing here has to decide
 * the undecidable question of whether a `/` divides or quotes: whatever the parser
 * decided, this index reproduces, and the two can therefore never disagree.
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
    internal val tokenEnds: IntArray,
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
    internal val tokenStarts: IntArray,
    /** (API.4a) Every token's kind, parallel to [tokenEnds]. */
    internal val tokenKinds: Array<SyntaxKind>,
    /** (API.4a) The text this index describes — the anchor reports a PREFIX. */
    internal val text: String,
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
        fun of(
            text: String,
            fileName: String,
            flags: ParserFlags,
            /**
             * (GATE.2) False reproduces the PRE-(GATE.2) scan, which took no
             * contextual lexeme from the parse.
             *
             * It exists so the invariant gate has a positive control, and for no
             * other reason: a checker that cannot see a broken index reads exactly
             * like one whose subject is correct (round 849), and every rule in
             * `TokenIndexInvariants` would otherwise be green whether it worked or
             * not. This is the in-binary OFF arm the equivalence pin needs — the
             * same shape `--spineMaskOff` has in the compiler. Nothing in `Project`
             * passes it.
             */
            useParseAsLexerOracle: Boolean = true,
        ): SourceIndex = around(
            text,
            Parser(
                text,
                fileName,
                forceJsx = flags.forceJsx,
                topLevelAwait = flags.topLevelAwait,
                needsJsxFlag = flags.needsJsxFlag,
                noImplicitAny = flags.noImplicitAny,
            ).parse(),
            useParseAsLexerOracle,
        )

        /**
         * (INC.36) Indexes [text]'s tokens around a tree SOMEONE ELSE parsed.
         *
         * The whole of [of] except the parse, and the reason it is split out is a
         * measurement: `Project` was parsing the program a second time beside the
         * compiler's own crawl, which retained 103 MB of duplicate trees on tsc's
         * own 78 sources (`docs/perf/language-service-retention.md`).
         *
         * CONTRACT, and it is the caller's to keep: [sourceFile] MUST be the parse
         * of [text] under the flags [of] would have used. Nothing here can check
         * that — the tree is consulted for the contextual lexemes a scanner cannot
         * decide alone (a regex literal, a run of JSX text), so a tree belonging to
         * DIFFERENT text would de-synchronise the token stream rather than fail.
         * `parsedSourceOrNull` is the one supported source, and its own key is
         * `(fileName, content, flags)`, i.e. exactly that contract expressed as a
         * lookup.
         */
        fun around(
            text: String,
            sourceFile: SourceFile,
            useParseAsLexerOracle: Boolean = true,
        ): SourceIndex {
            val tokens = scanTokens(
                text,
                if (useParseAsLexerOracle) contextualLexemes(sourceFile) else emptyList(),
            )
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
         * (GATE.2) One lexeme whose extent only the PARSER could know, taken from the
         * node the parser built for it.
         */
        private class ContextualLexeme(
            val start: Int,
            val end: Int,
            val kind: SyntaxKind,
        )

        /**
         * (GATE.2) Every regular-expression literal and every run of JSX text in
         * [root], in ascending position order.
         *
         * ## Why these two, and why from the tree
         *
         * They are the two lexemes a context-free `Scanner.scan()` loop cannot
         * approximate SAFELY. Both may legally contain a quote or a backtick — the
         * measured cases are tsc's own ``/\r\n|[\\`…]/g`` and any JSX text with an
         * apostrophe in it — and a quote reached by the context-free loop opens a
         * string or, far worse, a template literal that runs to the next backtick
         * ANYWHERE IN THE FILE. That is a merge, and by round 919's law a merge
         * de-synchronises the stream to end of file rather than degrading one answer.
         *
         * The parser already decided both questions, and — this is the part that
         * makes the mechanism exact rather than another heuristic — it recorded its
         * decision as a node holding the lexeme's RAW text. A
         * `RegularExpressionLiteralNode`'s `text` is `Scanner.getTokenText()`, i.e.
         * the characters from the opening `/` to the last flag, and a `JsxText`'s is
         * what `Scanner.scanJsxText()` returned; in both cases `pos + text.length` is
         * the exact end, with none of `Node.end`'s one-token overshoot. So there is
         * nothing left to guess: [scanTokens] emits the lexeme verbatim and resumes
         * the scanner past it.
         *
         * The consequence worth stating is that this index cannot disagree with the
         * tree it describes. A `/` the parser read as division has no node here, so
         * the scan reads it as division too; a `/` the parser read as a regex has
         * one, so the scan reads it as a regex. The undecidable question is never
         * asked.
         *
         * ITERATIVE, like every other full-tree walk in this class: real TypeScript
         * carries expression chains deep enough to crash a recursive one.
         */
        private fun contextualLexemes(root: SourceFile): List<ContextualLexeme> {
            val found = ArrayList<ContextualLexeme>()
            val stack = ArrayList<Node>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                when (node) {
                    is RegularExpressionLiteralNode ->
                        if (node.text.isNotEmpty()) {
                            found.add(
                                ContextualLexeme(
                                    node.pos,
                                    node.pos + node.text.length,
                                    SyntaxKind.RegularExpressionLiteral,
                                ),
                            )
                        }
                    is JsxText ->
                        if (node.text.isNotEmpty()) {
                            found.add(
                                ContextualLexeme(node.pos, node.pos + node.text.length, SyntaxKind.JsxText),
                            )
                        }
                    else -> Unit
                }
                forEachChild(node) { child -> stack.add(child) }
            }
            found.sortBy { it.start }
            return found
        }

        /**
         * Every token in [text], in scan order — so [Tokens.ends] is ascending.
         *
         * ## The one contextual re-scan this loop MUST perform, and why
         *
         * `Scanner.scan()` is context-free and the parser is not: at three places it
         * re-scans the current token under a context only it knows
         * (`reScanSlashToken`, `reScanGreaterToken`, `reScanTemplateToken`). Two of
         * those are harmless here — they only ever SPLIT one token into several, which
         * ADDS ends and leaves every real boundary present, so [realEndOf] is
         * unchanged. **The template one is not**, and (API.5) measured what it costs:
         * on a `` `${a}|${b}` `` the context-free loop reads the `}` of a substitution
         * as a CloseBrace, then the `|` as an operator, and then the closing backtick
         * OPENS a fresh `NoSubstitutionTemplateLiteral` that runs to the next backtick
         * anywhere in the file. That is a MERGE, and unlike a split it does not stay
         * local: the token stream is de-synchronised **for the rest of the file**, so
         * every node after the first substituting template literal gets a `realEnd`
         * snapped back to some earlier token, [pathAt] cannot descend into it, and
         * every position-directed query silently answers about a huge enclosing node
         * instead. Measured on this repo's own compiler profile before the fix:
         * `checker.ts` scanned as **50,684 tokens for 3,151,772 characters**, the
         * longest of them **62,089 characters**, and a caret on a top-level function
         * name resolved to the whole file's `Block`.
         *
         * So the substitution nesting is tracked exactly as `Parser` tracks it: a
         * `TemplateHead` opens a substitution, braces inside it are counted, and the
         * `}` that closes it is re-scanned into a `TemplateMiddle` (the substitution
         * stays open) or a `TemplateTail` (it closes). `reScanTemplateToken` rewinds
         * to the current token's own start, so the recorded start is unaffected and
         * only the end and the kind change. Nested templates work by construction —
         * the inner `TemplateHead` pushes its own counter.
         *
         * The loop terminates on end-of-file, and ALSO on a token that did not
         * advance the scanner. That second exit is a fail-safe rather than a
         * reachable case: this class is meant to run inside a long-lived host, and
         * the failure mode of a scanner that stops advancing on some input is not a
         * wrong answer but a wedged process — the shape that has already cost this
         * repo a daemon (CLAUDE.md, round 873: a request that never returns holds
         * the compile thread for good). Truncating the index degrades every later
         * node in the file to a coarser answer, which is the same graceful direction
         * as the class KDoc's split case.
         */
        private fun scanTokens(text: String, lexemes: List<ContextualLexeme>): Tokens {
            val scanner = Scanner(text)
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val kinds = ArrayList<SyntaxKind>()
            // One entry per OPEN template substitution, holding how many plain `{`
            // are still unclosed inside it. Empty means "not in a substitution", and
            // then a `}` is just a `}`.
            val substitutions = ArrayList<Int>()
            var previousEnd = -1
            // The next lexeme the parse says the context-free scan cannot see.
            var lexeme = 0
            while (true) {
                var token = scanner.scan()
                // (GATE.2) A lexeme the PARSER recognised and this loop cannot: emit
                // it verbatim and resume past it. Checked before the template
                // bookkeeping below, which a regex and a JSX run are both outside of.
                val tokenStart = scanner.getTokenPos()
                while (lexeme < lexemes.size &&
                    (lexemes[lexeme].end <= tokenStart || lexemes[lexeme].end <= previousEnd)
                ) {
                    lexeme++
                }
                if (lexeme < lexemes.size &&
                    scanner.getPos() > lexemes[lexeme].start &&
                    lexemes[lexeme].start >= previousEnd
                ) {
                    val contextual = lexemes[lexeme]
                    // The scanned token began BEFORE the lexeme (a `/` before its own
                    // regex body). Keeping its prefix is a SPLIT, which is always safe.
                    if (tokenStart < contextual.start) {
                        starts.add(tokenStart)
                        ends.add(contextual.start)
                        kinds.add(token)
                    }
                    starts.add(contextual.start)
                    ends.add(contextual.end)
                    kinds.add(contextual.kind)
                    previousEnd = contextual.end
                    lexeme++
                    scanner.resetToPosition(contextual.end)
                    continue
                }
                when {
                    token == SyntaxKind.TemplateHead -> substitutions.add(0)
                    substitutions.isEmpty() -> Unit
                    token == SyntaxKind.OpenBrace ->
                        substitutions[substitutions.size - 1] = substitutions.last() + 1
                    token == SyntaxKind.CloseBrace ->
                        if (substitutions.last() > 0) {
                            substitutions[substitutions.size - 1] = substitutions.last() - 1
                        } else {
                            token = scanner.reScanTemplateToken()
                            // A middle keeps the substitution open at depth 0; a tail
                            // closes it. Any other answer means the rewind found no
                            // template after all, and leaving the entry would swallow
                            // every later `}` — so it closes too.
                            if (token != SyntaxKind.TemplateMiddle) {
                                substitutions.removeAt(substitutions.size - 1)
                            }
                        }
                }
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
     * (API.18) The file-final REAL token's end, or -1 when the file has none
     * (an empty file scans only the zero-width end-of-file token). Computed
     * with [finalTokenOwnerByKey] on first ask; -2 means not yet computed.
     */
    private var finalTokenEnd = -2

    /**
     * (API.18) The nodes that OWN the file-final real token, keyed by their
     * packed `(pos shl 32) or end` for an O(1) probe and confirmed by IDENTITY
     * (a parent and its first child can share both coordinates, so several
     * owners may share one key — and an ABUTTING node can share a key with an
     * owner, which is exactly the ambiguity the identity list resolves).
     *
     * ## Why ownership needs a descent, not span arithmetic
     *
     * Only the zero-width EOF lookahead produces an EXACT raw `end`, so the
     * file-final token is the one place `realEndOf`'s strictly-below snap is
     * wrong — and the (API.18) analysis measured that no span test can repair
     * it: a true container of the final token and a node merely abutting it
     * are indistinguishable by `(pos, rawEnd)` alone (`ex` and its enclosing
     * property access in `…ex.` share BOTH). What discriminates is structure,
     * read by a descent over RAW ends: at each level the LAST child whose
     * `[pos, end]` covers the whole token wins (an earlier sibling whose raw
     * end merely overshoots ONTO the token — `foo` in an ASI-separated
     * `foo⏎bar` — loses to the later true owner), and the chain counts only
     * when its leaf STARTS at the token's start. An identifier, a literal or
     * a keyword leaf starts there; a closing bracket never does, and neither
     * does an abutter — so a punctuation-final file computes an EMPTY chain
     * and keeps today's conservative answers.
     */
    private var finalTokenOwnerByKey: HashMap<Long, ArrayList<Node>>? = null

    private fun finalTokenOwners(): HashMap<Long, ArrayList<Node>> {
        finalTokenOwnerByKey?.let { return it }
        val byKey = HashMap<Long, ArrayList<Node>>()
        var index = tokenKinds.size - 1
        while (index >= 0 && tokenKinds[index] == SyntaxKind.EndOfFile) index--
        val ts = if (index >= 0) tokenStarts[index] else -1
        val te = if (index >= 0) tokenEnds[index] else -1
        if (te > ts) {
            val path = ArrayList<Node>()
            var current: Node = sourceFile
            while (true) {
                var next: Node? = null
                forEachChild(current) { child ->
                    if (child.pos <= ts && te <= child.end && child.pos < child.end) {
                        next = child
                    }
                }
                val chosen = next ?: break
                path.add(chosen)
                current = chosen
            }
            if (path.lastOrNull()?.pos == ts) {
                for (owner in path) {
                    val key = (owner.pos.toLong() shl 32) or (owner.end.toLong() and 0xFFFFFFFFL)
                    byKey.getOrPut(key) { ArrayList() }.add(owner)
                }
            }
        }
        finalTokenEnd = te
        finalTokenOwnerByKey = byKey
        return byKey
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
     * (API.18) A node that OWNS the file-final real token also has an exact
     * `end` — the EOF lookahead is zero-width for the whole chain above the
     * final token when the file carries no trailing trivia — and snapping it
     * strictly below loses the token from every ancestor's span at once, which
     * is what made a file-final identifier unreachable by every position
     * lookup. Ownership is decided by [finalTokenOwnerByKey]'s descent, never
     * by the span itself; a node whose raw end merely equals the final token's
     * end without owning it (`ex` in `…ex.`, `return ex` before a closing
     * brace) keeps the snap.
     *
     * Clamped to at least `node.pos`, so a node the search cannot place (a
     * zero-width recovery node, or one inside a region the context-free scan
     * merged) gets an EMPTY span and is skipped by the descent, rather than a
     * span running backwards.
     */
    fun realEndOf(node: Node): Int {
        if ((node as NodeBase).kindId == NodeKind.SOURCE_FILE) return textLength
        val bound = node.end
        val owners = finalTokenOwners()
        if (bound == finalTokenEnd) {
            val key = (node.pos.toLong() shl 32) or (bound.toLong() and 0xFFFFFFFFL)
            val sharers = owners[key]
            if (sharers != null && sharers.any { it === node }) return bound
        }
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
     * they are typed, which is the sweep's primary product, and since (API.3d) a
     * member USE also carries a definition — resolved through its receiver. A
     * member's own DECLARATION name still resolves to nothing (`CapturedDefinition`
     * says why), so an entry for one carries a type and no locations, which is a
     * truthful answer rather than a gap.
     *
     * (API.5) This is also the population a reference sweep asks about, per file for
     * `Project.documentHighlightsAt` and over every program file for
     * `Project.referencesAt` — so the boundary drawn here is the boundary of what
     * can be FOUND, which is why an element access (`o["p"]`, whose member is a
     * string literal) is neither searchable nor findable.
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

    /**
     * (API.9) The population a REFERENCE sweep asks about: every [identifiers] node,
     * plus every LITERAL that names the member of an element access — a string, and
     * since (API.16) a no-substitution TEMPLATE.
     *
     * The second half is the boundary round 925 measured this API to be short at, and
     * it is a boundary of the POPULATION rather than of the resolution: `o["p"]` is an
     * ordinary member access whose name happens not to be an identifier, and tsc 7.0.2
     * puts it in the group (measured — thirteen spans for an interface member, one of
     * them the literal). Nothing else about a string literal is asked: a literal in any
     * other position is not swept, so `const unrelated = "p"` is not a reference and a
     * spelling scan's answer and this one differ by exactly that.
     *
     * (API.17) …and every OTHER literal in a member-NAME position, which is now the
     * same enumeration: `{ "p": v }`, `{ ["p"]: v }`, ``{ [`p`]: v }`` and a computed
     * member declaration. [SyntaxRoles.memberNameLiterals] states the one predicate they
     * all pass, and an element access is one of its cases rather than a case of its own.
     * A computed key was the LAST silent miss in this API — with the contextual member
     * OPTIONAL, stranding it costs no diagnostic, so the applied rename compiled clean
     * with the old name still spelled in the literal.
     *
     * [identifiers] stays as it is because [Project.fileSemantics] enumerates through
     * it and its contract — "every `Identifier`, and nothing else" — is a documented
     * one; the two populations are deliberately different questions.
     */
    fun occurrenceNodes(): List<Node> {
        val found = ArrayList<Node>(identifiers())
        found.addAll(SyntaxRoles.memberNameLiterals(sourceFile))
        found.sortWith(compareBy({ it.pos }, { it.end }))
        return found
    }

    /**
     * (API.9) [node]'s NAME span — what an editor highlights and what a rename must
     * replace — as `start` in the first slot and `end` in the second.
     *
     * For an identifier that is [realEndOf] and the node's own `pos`. For the string
     * literal of an `o["p"]` it is the TEXT BETWEEN THE QUOTES, which is both what tsc
     * edits (measured: `[77,78)` for a literal occupying `[76,79)`) and the only span a
     * rename may write into — replacing the quotes too would write `o[newName]`.
     *
     * (API.16) A no-substitution TEMPLATE is the same rule with the same reason and the
     * same measurement — tsc edits `[110,111)` for a ``o[`p`]`` whose literal occupies
     * `[109,112)` — so the delimiter is stripped for it too. Writing over the backticks
     * would produce `o[newName]`, which compiles and means something else, which is the
     * failure round 926 measured for the quote form.
     *
     * A literal the token scan cannot place, or one left UNTERMINATED by a partial
     * edit, falls back to its whole span rather than to arithmetic that would eat a
     * real character.
     */
    fun occurrenceSpanOf(node: Node): IntArray {
        val end = realEndOf(node)
        val delimited = when (node) {
            is StringLiteralNode -> !node.isUnterminated
            is NoSubstitutionTemplateLiteralNode -> !node.isUnterminated
            else -> false
        }
        if (delimited && end - node.pos >= 2) {
            return intArrayOf(node.pos + 1, end - 1)
        }
        return intArrayOf(node.pos, end)
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
     * 2. A caret inside the STRING of an `o["…"]` answers [CompletionKind.MEMBER]
     *    with the element access's receiver — see the section below.
     * 3. A caret otherwise inside a string, template, regular-expression or numeric
     *    literal token answers NONE. Strictly inside: at such a token's first offset
     *    the caret is before its opening quote and at its last it is after the
     *    closing one, and neither is inside the literal.
     * 4. A caret inside a COMMENT answers NONE. Comments are not tokens here — the
     *    scanner consumes them as leading trivia before recording a token's start —
     *    so they live entirely in the GAPS between consecutive tokens, and the gap
     *    the caret falls in is re-read for `//` and comment-open markers. That is
     *    exact rather than heuristic precisely because a string literal IS a token
     *    and can never appear in a gap.
     * 5. Otherwise the WORD under the caret is found: the token W with
     *    `start < offset <= end` whose first character can begin an identifier.
     *    Strictly greater than `start`, so a caret immediately BEFORE a word is not
     *    inside it and completes as an insertion; less-or-equal `end`, so a caret
     *    immediately after `o.fo|` is inside `fo`. Keywords qualify — `new` and
     *    `class` are legal property names, and a half-typed identifier is often a
     *    keyword prefix.
     * 6. The token immediately before the word (or before the caret, when there is
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
     *
     * ## (API.12) The caret inside `o["`
     *
     * A string literal is a member NAME when it is the argument of an element
     * access, and nowhere else — the rule (API.9) gave the occurrence sweep, applied
     * to a caret instead of to a node, and read out of the SAME enumeration
     * ([SyntaxRoles.stringElementAccessAt]) so the two cannot drift. So a caret in
     * such a literal is a MEMBER anchor whose receiver is the expression before the
     * `[`, and the member enumeration behind it is unchanged: the same union rule,
     * the same accessibility filter, the same `this` and export-table legs. Only the
     * anchor is new, which is the whole of this feature.
     *
     * **The span is the TEXT, quotes EXCLUDED**, the same span a member rename
     * writes into (`occurrenceSpanOf`) — so an accepted item produces `o["alpha"]`
     * with exactly one pair of quotes, and the item's `name` is the member's own
     * spelling, unquoted. tsc 7.0.2 answers identically, measured: for a literal at
     * `[460,467)` its edit range is `[461,466)` and its `newText` carries no quotes.
     * A member whose spelling is NOT an identifier — `"has space"`, `"1abc"` — is
     * offered HERE and only here, which is the reason element access exists.
     *
     * **An UNTERMINATED literal is the common editing state and is answered.** The
     * user who has typed `o["` has no closing quote yet, so the caret is at the
     * token's END rather than inside it, and the text span runs to that end. That is
     * the one place termination changes an answer, and it is read from the parser's
     * own `isUnterminated` rather than from a character test.
     *
     * **What is deliberately NOT answered here**, each measured against tsc:
     *
     * - a caret in a `` o[`p`] `` TEMPLATE, which tsc completes. Refused for the
     *   reason the shared enumeration exists: (API.9)'s occurrence population is
     *   string literals only, so a member written through a template is one a later
     *   rename cannot find — offering it would invite text this API cannot maintain.
     *   Same conservative direction as (API.11)'s object-literal method.
     * - a caret BEFORE the opening quote (`o[|"p"]`), where tsc offers free names.
     *   It is left NONE, which is what every literal's first offset already answers.
     * - an indexed-access TYPE (`type T = Bag["|"]`). tsc offers FREE NAMES there,
     *   not member names, so refusing diverges from tsc by exactly as much as
     *   offering members would.
     * - a string in an argument whose parameter is a literal union or a `keyof`
     *   (`w("|")` where `w` takes `keyof Bag`), which tsc completes from the
     *   CONTEXTUAL type. That is a different resolution, not a different anchor.
     */
    fun completionAnchorAt(offset: Int): CompletionAnchor {
        if (offset < 0 || offset > textLength) return CompletionAnchor.none(offset)
        val containing = tokenContaining(offset)
        stringMemberAnchorAt(offset, containing)?.let { return it }
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
                // (API.7) The grammar position is read at the WORD's own start, not at
                // the caret: `co|` sits at the real end of the `co` statement, so the
                // caret is enclosed by the BLOCK while the word is enclosed by the
                // identifier the parser built — and it is that identifier's parent that
                // says which production the word is part of.
                keywords = SyntaxRoles.keywordsFor(pathAt(if (word >= 0) anchorStart else offset)),
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
     * (API.12) The MEMBER anchor for a caret inside the string of an `o["…"]`, or
     * null when the caret is not in one.
     *
     * ## Where the caret can be, which is two places and not one
     *
     * A closed literal CONTAINS the caret, and [containing] has already found the
     * token. An UNTERMINATED one does not: the user who has typed `o["` has a
     * one-character token and types at the offset just PAST it, which no token
     * contains — and that is the state a completion request is normally made in, so
     * it is answered rather than treated as an edge case. The two are told apart by
     * the parser's own [StringLiteralNode.isUnterminated], never by looking for a
     * closing quote in the text: past a CLOSED literal the caret is outside the
     * string and completes free names, which is what tsc answers there too.
     *
     * A caret AT the opening quote is not inside the string and is refused, which is
     * simply what every literal's first offset already answered.
     *
     * ## The span
     *
     * `[literalStart + 1, textEnd)` — the TEXT, with the quotes excluded, where
     * `textEnd` is the token's end for an unterminated literal and one before it for
     * a closed one. Accepting an item therefore replaces the whole member name the
     * caret is in and nothing else, leaving exactly one pair of quotes. It is the
     * same span `occurrenceSpanOf` gives a rename, computed here from the TOKEN
     * because the token stream is what has an exact end for a literal the parse
     * left unterminated.
     */
    private fun stringMemberAnchorAt(offset: Int, containing: Int): CompletionAnchor? {
        val token: Int
        val caretIsInsideToken: Boolean
        if (containing >= 0) {
            if (!isMemberNameLiteralToken(tokenKinds[containing])) return null
            token = containing
            caretIsInsideToken = true
        } else {
            val before = tokenEndingAtOrBefore(offset)
            if (before < 0 || tokenEnds[before] != offset) return null
            if (!isMemberNameLiteralToken(tokenKinds[before])) return null
            token = before
            caretIsInsideToken = false
        }
        val literalStart = tokenStarts[token]
        // At or before the opening quote the caret is not in the string at all.
        if (offset <= literalStart) return null
        val access = SyntaxRoles.stringElementAccessAt(sourceFile, literalStart) ?: return null
        val literal = access.argumentExpression
        // A ONE-CHARACTER token is the lone opening quote and cannot be closed —
        // which the parser's own flag gets WRONG, because it decides termination by
        // comparing the raw text's last character to its first and a lone `"` is
        // both. That degenerate literal is exactly the state `o["` is in, i.e. the
        // one this feature exists for, so the arithmetic is checked as well as the
        // flag. It is arithmetic and not a character test: a closed literal has at
        // least an opening and a closing quote.
        val parsedUnterminated = when (literal) {
            is StringLiteralNode -> literal.isUnterminated
            is NoSubstitutionTemplateLiteralNode -> literal.isUnterminated
            else -> return null
        }
        val unterminated = parsedUnterminated || tokenEnds[token] - literalStart < 2
        if (!caretIsInsideToken && !unterminated) return null
        val textStart = literalStart + 1
        val textEnd = if (unterminated) tokenEnds[token] else tokenEnds[token] - 1
        if (offset < textStart || offset > textEnd) return null
        return CompletionAnchor(
            CompletionKind.MEMBER,
            text.substring(textStart, offset),
            textStart,
            textEnd,
            access.expression,
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

    /**
     * (API.16) True for the token kinds that can carry a member NAME in an element
     * access — a string and a no-substitution template, and nothing else.
     *
     * A `TemplateHead` is deliberately absent: it is the first token of a template WITH
     * substitutions, which spells no fixed name, and tsc refuses the position outright.
     */
    private fun isMemberNameLiteralToken(kind: SyntaxKind): Boolean =
        kind == SyntaxKind.StringLiteral || kind == SyntaxKind.NoSubstitutionTemplateLiteral

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
        // (GATE.2) JSX text is a literal here for the same reason a string is: what
        // the user is typing between two tags is prose, not a name, and the token
        // level cannot tell a tag completion from prose anyway — which is exactly the
        // grammar-position mechanism § 10a refuses keywords for.
        SyntaxKind.JsxText,
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

    // --- (API.6) the signature-help anchor -----------------------------------------

    /**
     * (API.6) The innermost CALL whose argument list contains [offset], and which
     * argument slot the caret is in — or null when the caret is in no argument list.
     *
     * ## Why this is not [pathAt]'s answer
     *
     * Signature help asks a question about a REGION the parse does not carry a node
     * for. Three of its cases defeat a containment test outright:
     *
     * - `f(a, b|)` — the caret is at the real END of `b`, so the half-open spans put
     *   it outside `b` and inside the call, and yet the answer is argument 1, not 2;
     * - `f(a, |)` — the second argument does not exist in the tree at all;
     * - `f(a, ` before a `}`, or `f(` at end of file — the call node's own real end is
     *   snapped back to the last token it managed to consume, which is BEFORE the
     *   caret, so no descent reaches the call. (The node itself exists: this parser
     *   builds a `CallExpression` the moment it sees a `(`, reporting the missing `)`
     *   — `parseArgumentListWorker` breaks on end-of-file and on a `}` and then runs
     *   `parseExpected(CloseParen)`.)
     *
     * So the region is computed from the TOKEN STREAM by bracket matching, anchored
     * at the call's own open paren, and the ARGUMENT INDEX is a count of commas —
     * which is what an argument index physically is.
     *
     * ## The region
     *
     * From one past the `(` to the token that closes it. Matching walks a small
     * bracket stack over `()`, `[]` and `{}`, and stops EARLY at a closer that does
     * not match the top of the stack: an unmatched `}` means the enclosing block is
     * closing while our paren is still open, i.e. the user has not typed the `)` yet,
     * and the argument list ends there. Template substitutions need no rule of their
     * own — [scanTokens] re-scans the `}` that closes one into a template middle or
     * tail, so it is not a `}` here at all.
     *
     * A caret AT the closing paren is inside (it is still in the last argument's
     * slot); one past it is not. A caret on the callee, or on the `(` itself, is not
     * in the argument list either.
     *
     * ## The argument index
     *
     * The number of commas of THIS argument list before the caret. A comma inside one
     * of the arguments is not one of them, and the arguments the parse produced are
     * exactly what says which those are — so a comma inside `g(x, y)`, inside
     * `{ a: 1, b: 2 }` and inside `Map<string, number>` is excluded by the same test,
     * with no per-construct rule and no need to lex type arguments.
     *
     * ## Innermost
     *
     * Every call in the file is a candidate and the one with the LATEST open paren
     * wins. Nesting makes that exact rather than a heuristic: an inner call's `(` is
     * inside the outer call's region, so `f(g(|))` answers `g`, while `f(g(x), |)`
     * answers `f` because the caret is past `g`'s `)` and outside its region.
     *
     * A TAGGED TEMPLATE is not a candidate — it has no parenthesized argument list —
     * and neither is a `new C` written without parentheses, whose `arguments` is null.
     *
     * ## Cost
     *
     * ONE walk of this file's own tree per query, plus a bounded token scan for the
     * winning call. The walk is the price of the incomplete case rather than
     * laziness: a candidate set taken from [pathAt] would be a handful of nodes and
     * would not contain the call at all whenever the user has not typed the `)`,
     * which is precisely when signature help is wanted. It is the same order as
     * [identifiers], and it is nothing beside the compile the answer needs.
     */
    fun signatureAnchorAt(offset: Int): SignatureAnchor? {
        if (offset < 0 || offset > textLength) return null
        var best: SignatureAnchor? = null
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            forEachChild(node) { child -> stack.add(child) }
            val arguments: List<Node> = when (node) {
                is CallExpression -> node.arguments
                is NewExpression -> node.arguments ?: continue
                else -> continue
            }
            val openParen = openParenTokenOf(node) ?: continue
            val start = tokenEnds[openParen]
            if (offset < start) continue
            if (best != null && start <= best.argumentListStart) continue
            val end = argumentListEndFrom(openParen)
            if (offset > end) continue
            best = SignatureAnchor(
                call = node,
                activeArgument = argumentIndexIn(arguments, openParen, end, offset),
                argumentListStart = start,
                argumentListEnd = end,
            )
        }
        return best
    }

    /**
     * The index of [call]'s own open-paren token, or null when the parse has none —
     * which happens for a `new C` with no parentheses and for a call whose `(` the
     * recovery never reached.
     *
     * Searched forward from past the CALLEE and past any TYPE ARGUMENT list, so a
     * type argument that itself contains parentheses (`f<(x: A) => B>(y)`) does not
     * supply a false answer, and bounded by the call's own raw `end` so a
     * malformed node cannot adopt a paren belonging to something else.
     */
    private fun openParenTokenOf(call: Node): Int? {
        val callee: Node
        val typeArguments: List<Node>?
        when (call) {
            is CallExpression -> {
                callee = call.expression
                typeArguments = call.typeArguments
            }
            is NewExpression -> {
                callee = call.expression
                typeArguments = call.typeArguments
            }
            else -> return null
        }
        var from = realEndOf(callee)
        typeArguments?.lastOrNull()?.let { from = maxOf(from, realEndOf(it)) }
        var index = firstTokenStartingAtOrAfter(from)
        while (index < tokenKinds.size && tokenStarts[index] < call.end) {
            if (tokenKinds[index] == SyntaxKind.OpenParen) return index
            index++
        }
        return null
    }

    /**
     * Where the argument list opened by the token at [openParen] ends — the offset of
     * the token that closes it, or the end of the file.
     *
     * See [signatureAnchorAt] for the rule; the early stop on a MISMATCHED closer is
     * what makes an unterminated call answer a region rather than nothing.
     */
    private fun argumentListEndFrom(openParen: Int): Int {
        val expected = ArrayList<SyntaxKind>()
        expected.add(SyntaxKind.CloseParen)
        var index = openParen + 1
        while (index < tokenKinds.size) {
            when (val kind = tokenKinds[index]) {
                SyntaxKind.OpenParen -> expected.add(SyntaxKind.CloseParen)
                SyntaxKind.OpenBracket -> expected.add(SyntaxKind.CloseBracket)
                SyntaxKind.OpenBrace -> expected.add(SyntaxKind.CloseBrace)
                SyntaxKind.CloseParen, SyntaxKind.CloseBracket, SyntaxKind.CloseBrace -> {
                    if (kind != expected.last()) return tokenStarts[index]
                    expected.removeAt(expected.size - 1)
                    if (expected.isEmpty()) return tokenStarts[index]
                }
                SyntaxKind.EndOfFile -> return textLength
                else -> Unit
            }
            index++
        }
        return textLength
    }

    /**
     * How many of this argument list's own commas lie before [offset] — the argument
     * slot the caret is in.
     *
     * A comma that falls inside one of [arguments] belongs to that argument's own
     * syntax (a nested call, an object literal, a type argument list) and is not one
     * of this list's separators.
     */
    private fun argumentIndexIn(
        arguments: List<Node>,
        openParen: Int,
        regionEnd: Int,
        offset: Int,
    ): Int {
        var count = 0
        var index = openParen + 1
        while (index < tokenKinds.size &&
            tokenStarts[index] < offset &&
            tokenStarts[index] < regionEnd
        ) {
            if (tokenKinds[index] == SyntaxKind.Comma) {
                val at = tokenStarts[index]
                if (arguments.none { at >= it.pos && at < realEndOf(it) }) count++
            }
            index++
        }
        return count
    }

    /** The first token starting at or after [offset], or [tokenStarts]`.size`. */
    private fun firstTokenStartingAtOrAfter(offset: Int): Int {
        var lo = 0
        var hi = tokenStarts.size - 1
        var best = tokenStarts.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tokenStarts[mid] >= offset) {
                best = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return best
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
