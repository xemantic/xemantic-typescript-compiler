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

import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NodeKind
import com.xemantic.typescript.compiler.ParserFlags
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.forEachChild

/**
 * (GATE.2) The properties that must hold of [SourceIndex] on **any** input, checked
 * over real TypeScript source rather than over hand-written fixtures.
 *
 * ## Why this exists
 *
 * `(BUG.2)` (round 919) was live for nine rounds: one missing contextual re-scan
 * de-synchronised the token stream at the first `${…}` in a file and every
 * position-directed query answered about a huge enclosing node from there to end of
 * file. Every one of those nine rounds was gated by a green suite, because **a
 * hand-written fixture for a lexical API does not contain what real source
 * contains** — nobody writes a substituting template into a go-to-definition pin.
 *
 * So the instrument this file is has to have two properties the pins it sits beside
 * do not. It asserts INVARIANTS rather than answers — things true of any correct
 * implementation, so they keep working as the code changes and need no baseline to
 * drift — and it runs over INPUT NOBODY WROTE FOR IT. `TokenIndexGateTest` supplies
 * both corpora; `RealSourceTokenGateMain` is the same checker pointed at a local
 * tree.
 *
 * ## The oracle
 *
 * Several rules below are stated against the PARSE rather than against a second
 * hand-written expectation, and that is the load-bearing choice. `Parser` is the
 * context-sensitive lexer this index approximates: it knows that a `/` starts a
 * regular expression, that a `}` closes a substitution and that text between JSX
 * tags is text. So "every identifier the parser found is a token here, at exactly
 * its own span" ([Rule.IDENTIFIER_IS_A_TOKEN]) is a comparison against the one thing
 * in the process that cannot be wrong about lexing by construction — and it is
 * precisely what a MERGE destroys, because a token that swallowed an identifier
 * leaves no token starting where the identifier does.
 *
 * ## Reading a violation
 *
 * Every violation names its [Rule] and carries the offset it was found at plus a
 * short excerpt, because on a 3 MB input a rule name alone is not actionable. The
 * checker collects rather than throws, and each rule is capped, so one broken file
 * reports its whole shape instead of its first symptom.
 */
internal object TokenIndexInvariants {

    /**
     * The rules, one per property. Named rather than numbered so a violation reads
     * as a sentence and a regression names the property it broke.
     */
    enum class Rule {
        /** Token spans are ascending, non-overlapping and inside the text. */
        TOKENS_PARTITION,

        /** The scan reached end of file rather than the non-advance fail-safe. */
        SCAN_REACHES_EOF,

        /** Everything between two tokens is whitespace or a comment. */
        GAPS_ARE_TRIVIA,

        /** A string literal token never crosses a line break. */
        STRING_STAYS_ON_ITS_LINE,

        /** Only a literal kind may be long; an operator or a name may not. */
        NON_LITERAL_TOKEN_IS_SHORT,

        /** Every identifier the parser found starts a token of exactly its length. */
        IDENTIFIER_IS_A_TOKEN,

        /** [SourceIndex.realEndOf] of an identifier is that identifier's own text. */
        IDENTIFIER_SPAN_IS_EXACT,

        /** A descent to an identifier's own position reaches that identifier. */
        IDENTIFIER_IS_REACHABLE,

        /** A path strictly nests and every node on it contains the offset. */
        PATH_NESTS,

        /** `offsetAt(positionAt(o)) == o`, and the coordinates match the rule. */
        POSITION_ROUND_TRIPS,
    }

    /** One failure: which rule, where, and enough text to act on. */
    class Violation(
        val rule: Rule,
        val fileName: String,
        val offset: Int,
        val detail: String,
    ) {
        override fun toString(): String = "$fileName:$offset $rule — $detail"
    }

    /** What one file measured, reported whether or not anything failed. */
    class Report(
        val fileName: String,
        val characters: Int,
        val tokens: Int,
        val identifiers: Int,
        val longestToken: Int,
        val violations: List<Violation>,
    )

    /**
     * The most violations one rule may report per file.
     *
     * A de-synchronised stream fails one rule at every later position, so an
     * uncapped run over a 3 MB file builds a list of hundreds of thousands of
     * strings and the process dies before it prints anything — the same shape as the
     * whole-program sweep's 1.9 GB peak. Capping PER RULE rather than overall keeps
     * a loud rule from hiding a quiet one.
     */
    private const val PER_RULE_CAP = 12

    /**
     * A token that is not a literal may not be longer than this.
     *
     * A template literal, a string, a regular expression and JSX text are all
     * legitimately long, so the bound applies to everything else — where the longest
     * honest token is an identifier, and no TypeScript in existence has a 512-character
     * one. A merge into an operator or a name is what this catches.
     */
    private const val NON_LITERAL_TOKEN_LIMIT = 512

    /** Kinds whose token may legitimately be long, and may contain anything. */
    private fun isLiteralKind(kind: SyntaxKind): Boolean = when (kind) {
        SyntaxKind.StringLiteral,
        SyntaxKind.RegularExpressionLiteral,
        SyntaxKind.NoSubstitutionTemplateLiteral,
        SyntaxKind.TemplateHead,
        SyntaxKind.TemplateMiddle,
        SyntaxKind.TemplateTail,
        SyntaxKind.JsxText,
        -> true
        else -> false
    }

    /**
     * Every property above, checked over one file.
     *
     * Parses and indexes [text] exactly as `Project` does — through
     * [SourceIndex.of], with the caller supplying the same [ParserFlags] the
     * compiler would compute — so a violation here is a violation a host would see.
     */
    fun check(
        text: String,
        fileName: String,
        flags: ParserFlags,
        /** False builds the PRE-(GATE.2) index — the gate's positive control. */
        useParseAsLexerOracle: Boolean = true,
    ): Report {
        val index = SourceIndex.of(text, fileName, flags, useParseAsLexerOracle)
        val violations = ArrayList<Violation>()
        val counts = HashMap<Rule, Int>()
        fun report(rule: Rule, offset: Int, detail: String) {
            val seen = counts.getOrElse(rule) { 0 }
            if (seen >= PER_RULE_CAP) return
            counts[rule] = seen + 1
            violations.add(Violation(rule, fileName, offset, detail))
        }

        checkTokenStream(index, text, ::report)
        val identifiers = checkAgainstTheParse(index, text, ::report)
        checkPaths(index, text, identifiers, ::report)
        checkPositions(text, ::report)

        var longest = 0
        for (i in index.tokenStarts.indices) {
            val length = index.tokenEnds[i] - index.tokenStarts[i]
            if (length > longest) longest = length
        }
        return Report(
            fileName = fileName,
            characters = text.length,
            tokens = index.tokenStarts.size,
            identifiers = identifiers.size,
            longestToken = longest,
            violations = violations,
        )
    }

    /**
     * The rules that are about the token arrays alone: that they partition the text,
     * that the scan finished, that the gaps between them hold nothing but trivia,
     * and that no token is a shape its kind cannot be.
     *
     * [Rule.GAPS_ARE_TRIVIA] is the one worth explaining. The scanner records a
     * token's start AFTER skipping leading trivia, so comments and whitespace live
     * entirely in the gaps — which means re-reading a gap as trivia is not a second
     * lexer with its own opinion, it is a check that the gap contains nothing the
     * scanner should have turned into a token. A merge that swallowed a real token
     * usually shows here as well as at [Rule.IDENTIFIER_IS_A_TOKEN].
     */
    private fun checkTokenStream(
        index: SourceIndex,
        text: String,
        report: (Rule, Int, String) -> Unit,
    ) {
        val starts = index.tokenStarts
        val ends = index.tokenEnds
        val kinds = index.tokenKinds
        if (starts.isEmpty()) {
            report(Rule.SCAN_REACHES_EOF, 0, "the scan produced no tokens at all")
            return
        }
        var previousEnd = 0
        for (i in starts.indices) {
            val start = starts[i]
            val end = ends[i]
            when {
                start < previousEnd ->
                    report(Rule.TOKENS_PARTITION, start, "token $i starts at $start, inside the token ending at $previousEnd")
                end < start ->
                    report(Rule.TOKENS_PARTITION, start, "token $i runs backwards: [$start, $end)")
                end > text.length ->
                    report(Rule.TOKENS_PARTITION, start, "token $i ends at $end, past the file's ${text.length}")
                else -> {
                    if (start > previousEnd) checkGap(text, previousEnd, start, report)
                    if (!isLiteralKind(kinds[i]) && end - start > NON_LITERAL_TOKEN_LIMIT) {
                        report(
                            Rule.NON_LITERAL_TOKEN_IS_SHORT,
                            start,
                            "${kinds[i]} spans ${end - start} characters: ${excerpt(text, start)}",
                        )
                    }
                    if (kinds[i] == SyntaxKind.StringLiteral && hasLineBreak(text, start, end)) {
                        report(
                            Rule.STRING_STAYS_ON_ITS_LINE,
                            start,
                            "a string literal spans ${end - start} characters across a line break: ${excerpt(text, start)}",
                        )
                    }
                }
            }
            previousEnd = maxOf(previousEnd, end)
        }
        val lastKind = kinds[kinds.size - 1]
        if (lastKind != SyntaxKind.EndOfFile) {
            report(
                Rule.SCAN_REACHES_EOF,
                previousEnd,
                "the scan stopped at $previousEnd of ${text.length} on a $lastKind — the non-advance fail-safe fired",
            )
        }
        if (previousEnd < text.length) {
            report(
                Rule.SCAN_REACHES_EOF,
                previousEnd,
                "the last token ends at $previousEnd, ${text.length - previousEnd} characters short of the file",
            )
        }
    }

    /**
     * That `[from, to)` holds only whitespace and comments.
     *
     * Comment scanning is deliberately the minimal one: a `//` runs to the next line
     * break and a comment-open runs to its close, because those are the only two
     * comment forms the scanner skips. Anything else in a gap is a character the
     * scanner declined to tokenise, which is the shape of a merge one token earlier.
     */
    private fun checkGap(
        text: String,
        from: Int,
        to: Int,
        report: (Rule, Int, String) -> Unit,
    ) {
        var at = from
        while (at < to) {
            val c = text[at]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
                    c == '\u000b' || c == '\u000c' || c == '\uFEFF' || c == '\u00a0' ||
                    c == '\u2028' || c == '\u2029' -> at++
                c == '/' && at + 1 < to && text[at + 1] == '/' -> {
                    while (at < to && text[at] != '\n' && text[at] != '\r') at++
                }
                c == '/' && at + 1 < to && text[at + 1] == '*' -> {
                    var end = at + 2
                    while (end + 1 < text.length && !(text[end] == '*' && text[end + 1] == '/')) end++
                    at = minOf(end + 2, to)
                }
                // A shebang is trivia the scanner consumes and nothing else may be
                // at offset 0, so it is admitted exactly there and nowhere else.
                at == 0 && c == '#' && text.length > 1 && text[1] == '!' -> {
                    while (at < to && text[at] != '\n' && text[at] != '\r') at++
                }
                else -> {
                    report(
                        Rule.GAPS_ARE_TRIVIA,
                        at,
                        "no token covers offset $at and it is not trivia: ${excerpt(text, at)}",
                    )
                    return
                }
            }
        }
    }

    /**
     * The rules stated against the PARSE — the class KDoc's oracle.
     *
     * Returns the identifiers it examined, so the path rules can reuse them without
     * a second walk. A synthesized zero-width identifier (the `Identifier("")` our
     * parser puts after a dangling `o.`) is excluded from every rule here: it names
     * no text, so no token can start at it and no descent can reach it, and that is
     * by design rather than a defect.
     *
     * The length comparison is skipped for a raw span containing a backslash,
     * because a unicode escape (`\u0061bc`) makes the identifier's decoded name
     * shorter than the characters it occupies — the one legitimate way the two
     * disagree.
     */
    private fun checkAgainstTheParse(
        index: SourceIndex,
        text: String,
        report: (Rule, Int, String) -> Unit,
    ): List<Node> {
        val identifiers = index.identifiers().filter { (it as? Identifier)?.text?.isNotEmpty() == true }
        for (node in identifiers) {
            val name = (node as Identifier).text
            val token = tokenStartingAt(index, node.pos)
            if (token < 0) {
                report(
                    Rule.IDENTIFIER_IS_A_TOKEN,
                    node.pos,
                    "the parser found '$name' at ${node.pos} and no token starts there: ${excerpt(text, node.pos)}",
                )
                continue
            }
            val tokenEnd = index.tokenEnds[token]
            val raw = text.substring(node.pos, minOf(tokenEnd, text.length))
            if (!raw.contains('\\') && raw != name) {
                report(
                    Rule.IDENTIFIER_IS_A_TOKEN,
                    node.pos,
                    "the parser found '$name' at ${node.pos}; the token there is '${raw.take(40)}'",
                )
                continue
            }
            val realEnd = index.realEndOf(node)
            if (realEnd != tokenEnd) {
                report(
                    Rule.IDENTIFIER_SPAN_IS_EXACT,
                    node.pos,
                    "'$name' occupies [${node.pos}, $tokenEnd) and realEndOf answers $realEnd",
                )
            }
        }
        return identifiers
    }

    /**
     * That a descent reaches every identifier, and that the path it descends through
     * is a strictly nesting chain of nodes that all contain the offset.
     *
     * Reachability is the end-to-end property every position-directed query is built
     * on, and it is the one `(BUG.2)` destroyed: a node whose real end snapped back
     * below its own start has an empty span, so [SourceIndex.pathAt] declines to
     * enter it and answers some huge ancestor instead. Asserting containment alone
     * would not see that — the source file contains every offset in the file, so a
     * containment assertion is satisfied by a completely broken index.
     */
    private fun checkPaths(
        index: SourceIndex,
        text: String,
        identifiers: List<Node>,
        report: (Rule, Int, String) -> Unit,
    ) {
        for (node in identifiers) {
            val path = index.pathAt(node.pos)
            if (path.lastOrNull() !== node) {
                val last = path.lastOrNull()
                val what = if (last == null) "nothing" else
                    "${kindNameOf(last)}[${last.pos}, ${index.realEndOf(last)})"
                report(
                    Rule.IDENTIFIER_IS_REACHABLE,
                    node.pos,
                    "a descent to '${(node as Identifier).text}' reaches $what: ${excerpt(text, node.pos)}",
                )
                continue
            }
            var previousStart = -1
            var previousEnd = text.length + 1
            for (step in path) {
                val start = step.pos
                val end = index.realEndOf(step)
                if (node.pos < start || node.pos >= end) {
                    report(
                        Rule.PATH_NESTS,
                        node.pos,
                        "${kindNameOf(step)}[$start, $end) is on the path to ${node.pos} and does not contain it",
                    )
                    break
                }
                if (start < previousStart || end > previousEnd) {
                    report(
                        Rule.PATH_NESTS,
                        node.pos,
                        "${kindNameOf(step)}[$start, $end) is not inside its parent [$previousStart, $previousEnd)",
                    )
                    break
                }
                previousStart = start
                previousEnd = end
            }
        }
    }

    /**
     * That the offset/coordinate conversion is a bijection, against an INDEPENDENT
     * restatement of the terminator rule rather than against itself.
     *
     * The independent statement is the loop below: `\r\n` is one break, a lone `\r`
     * is a break, `\n` is a break, and U+2028/U+2029 are not (round 915's
     * convention — tsc splits there and this compiler deliberately does not, because
     * a coordinate no diagnostic of ours can carry is worse than none). Comparing
     * [LineMap] against a second copy of its own arithmetic would prove nothing;
     * comparing it against the rule as prose is what makes this a check.
     */
    private fun checkPositions(
        text: String,
        report: (Rule, Int, String) -> Unit,
    ) {
        val map = LineMap.of(text)
        var line = 1
        var character = 1
        var offset = 0
        while (offset < text.length) {
            val position = map.positionAt(offset)
            if (position.line != line || position.character != character) {
                report(
                    Rule.POSITION_ROUND_TRIPS,
                    offset,
                    "offset $offset is line ${position.line} column ${position.character}, the rule says $line:$character",
                )
                return
            }
            val back = map.offsetAt(position.line, position.character)
            if (back != offset) {
                report(
                    Rule.POSITION_ROUND_TRIPS,
                    offset,
                    "offset $offset converts to $line:$character and back to $back",
                )
                return
            }
            val c = text[offset]
            if (c == '\r' || c == '\n') {
                // `\r\n` is ONE break, so the `\n` is consumed here rather than
                // being seen as a second one on the next iteration.
                if (c == '\r' && offset + 1 < text.length && text[offset + 1] == '\n') offset++
                line++
                character = 1
            } else {
                character++
            }
            offset++
        }
    }

    /** The token starting exactly at [offset], or -1. Binary, since the starts ascend. */
    private fun tokenStartingAt(index: SourceIndex, offset: Int): Int {
        val starts = index.tokenStarts
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                starts[mid] < offset -> lo = mid + 1
                starts[mid] > offset -> hi = mid - 1
                // The zero-width end-of-file token shares its start with nothing
                // real, but a zero-width recovery token can share one with a real
                // token, so the non-empty one wins.
                else -> {
                    var at = mid
                    while (at > 0 && starts[at - 1] == offset) at--
                    while (at < starts.size && starts[at] == offset) {
                        if (index.tokenEnds[at] > offset) return at
                        at++
                    }
                    return -1
                }
            }
        }
        return -1
    }

    private fun hasLineBreak(text: String, from: Int, to: Int): Boolean {
        for (at in from until minOf(to, text.length)) {
            if (text[at] == '\n' || text[at] == '\r') return true
        }
        return false
    }

    /** [count] characters from [offset], with line breaks made visible. */
    private fun excerpt(text: String, offset: Int, count: Int = 48): String {
        val from = maxOf(0, offset)
        val to = minOf(text.length, from + count)
        if (from >= to) return "<end of file>"
        return "'" + text.substring(from, to).replace("\n", "\\n").replace("\r", "\\r") + "'"
    }

    /** The node's kind name, without reaching for the reflection `NodeInfo` uses. */
    private fun kindNameOf(node: Node): String =
        if ((node as NodeBase).kindId == NodeKind.SOURCE_FILE) "SourceFile" else node.kind.toString()

    /**
     * Every identifier in one file, walked ITERATIVELY — a recursive full-tree walk
     * is bounded by the tree's depth, and real TypeScript carries chains deep enough
     * to crash one (CLAUDE.md's deep-recursion rule).
     *
     * Unused by [check], which goes through [SourceIndex.identifiers]; kept out of
     * that path deliberately so the gate does not become a test of its own helper.
     */
    fun identifierCount(root: Node): Int {
        var found = 0
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if ((node as NodeBase).kindId == NodeKind.IDENTIFIER) found++
            forEachChild(node) { child -> stack.add(child) }
        }
        return found
    }
}
