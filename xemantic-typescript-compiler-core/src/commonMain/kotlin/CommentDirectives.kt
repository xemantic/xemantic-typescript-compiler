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

package com.xemantic.typescript.compiler

/**
 * (INV.0) (P18.270) — the COMMENT-DIRECTIVE family: tsc's `// @ts-ignore` / `// @ts-expect-error`
 * directives as its scanner records them ([TsCommentDirective], the per-file memo
 * `tsCommentDirectiveCache` / `tsCommentDirectivesOf`, the hand-scanned directive regexes
 * `scanTsCommentDirectives` / `classifyTsCommentDirectiveAt` / `insideOpenBlockComment` /
 * `isTsDirectiveCommentPrefix` / `commentOpenOnLineBefore`), program.ts's
 * `markPrecedingCommentDirectiveLine` (`markPrecedingTsCommentDirective`), and the funnel filter
 * [applyTsCommentDirectives] (suppression + TS2578), which `Checker.getDiagnostics` calls.
 * Extracted VERBATIM from `Checker.kt` (two spans: 8580-8588, 11344-11614); every Checker member
 * it reads is reached through [checker]. Ambient reads: `docs/inversion-ambient-ledger.md` row 19.
 */
internal class CommentDirectives(
    private val checker: Checker,
) {

    /**
     * (CHK.31) Per-FILE map `line -> directive` for tsc's `// @ts-ignore` /
     * `// @ts-expect-error` comment directives, keyed by file name and built
     * once per file (see [tsCommentDirectivesOf]). Declared before `init` per
     * the init-order trap, and empty for every file whose source does not
     * mention a directive at all — which the round-895 n-gram filter answers
     * without scanning.
     */
    private val tsCommentDirectiveCache: MutableMap<String, Map<Int, TsCommentDirective>> = HashMap()

    /**
     * (CHK.31) One `// @ts-ignore` / `// @ts-expect-error` comment directive, as
     * tsc's scanner records it (`appendIfCommentDirective`, scanner.ts).
     *
     * [pos]..[end] is the COMMENT's own span — and for a block comment only its
     * LAST LINE, because that is the only line tsc offers the directive regex
     * (`text.slice(lastLineStart ?? tokenStart, pos)`). A directive written on
     * an INNER line of a block comment is therefore not a directive at all,
     * which `tools/tsgo-7.0.2/lib/tsc` confirms: a three-line block comment whose
     * MIDDLE line spells the directive leaves the next line's error reported.
     *
     * The map this lands in is keyed by the line the comment ENDS on, which is
     * the line [markPrecedingTsCommentDirective] walks up to.
     */
    private class TsCommentDirective(
        val expectError: Boolean,
        val pos: Int,
        val end: Int,
    )

    /**
     * (CHK.31) The directives of one file, built once and memoized.
     *
     * The whole-source scans go through [srcHas]/[srcIndexOf] deliberately: the
     * round-895 n-gram filter answers "this file mentions no directive" without
     * touching the text, which is every file on all eight dashboard profiles
     * bar the three that merely SPELL the words in a string literal or a prose
     * comment.
     */
    private fun tsCommentDirectivesOf(fileName: String, source: String): Map<Int, TsCommentDirective> =
        tsCommentDirectiveCache.getOrPut(fileName) { scanTsCommentDirectives(source) }

    private fun scanTsCommentDirectives(source: String): Map<Int, TsCommentDirective> {
        val hasIgnore = checker.srcHas(source, "@ts-ignore")
        val hasExpect = checker.srcHas(source, "@ts-expect-error")
        if (!hasIgnore && !hasExpect) return emptyMap()
        // Insertion-ordered (ascending position) so a later directive on the
        // same line wins, exactly as tsc's `directivesByLine` Map build does.
        val out = mutableMapOf<Int, TsCommentDirective>()
        var from = 0
        while (from < source.length) {
            val a = if (hasIgnore) checker.srcIndexOf(source, "@ts-ignore", from) else -1
            val b = if (hasExpect) checker.srcIndexOf(source, "@ts-expect-error", from) else -1
            val at = when {
                a < 0 -> b
                b < 0 -> a
                else -> if (a < b) a else b
            }
            if (at < 0) break
            val directive = classifyTsCommentDirectiveAt(source, at, expectError = at == b)
            if (directive != null) {
                out[checker.getLineAndCharacterOfPosition(source, directive.end).first] = directive
            }
            from = at + 1
        }
        return out
    }

    /**
     * (CHK.31) tsc's two directive regexes, hand-scanned:
     * `^\/\/\/?\s*@(ts-expect-error|ts-ignore)` for a `//` comment and
     * `^(?:\/|\*)*\s*@(ts-expect-error|ts-ignore)` for the last line of a block
     * comment (both applied to the comment text `trimStart()`ed). NEITHER has a
     * trailing word boundary, so `@ts-ignoreXYZ` IS a directive — measured on
     * tsgo 7.0.2, whose scanner is a plain `strings.HasPrefix`.
     *
     * Both regexes are anchored at the COMMENT's OWN START, which is why the
     * opener is located by a string-aware forward scan
     * ([commentOpenOnLineBefore]) and never by a backward `lastIndexOf("//")`:
     * `disableJsDiagnostics.ts` (the services profile) writes the prose comment
     * `// Only need to add ` + backtick + `// @ts-ignore` + backtick + ` for a line once.`
     * and a backward search lands on the INNER slashes, reading a sentence about
     * a quick fix as a live directive that silences the next line. Nothing but a
     * real codebase contains that shape — CLAUDE.md's (GATE.2) lesson.
     */
    private fun classifyTsCommentDirectiveAt(source: String, at: Int, expectError: Boolean): TsCommentDirective? {
        val lineStart = if (at == 0) 0 else source.lastIndexOf('\n', at - 1) + 1
        val opener = commentOpenOnLineBefore(source, lineStart, at)
        // A block comment, whether it opened on this line or an earlier one. tsc
        // offers the regex `text.slice(lastLineStart ?? tokenStart, pos)`, so the
        // directive only counts on the comment's LAST line.
        val blockStart = when {
            opener >= 0 && source[opener + 1] == '*' -> opener
            opener < 0 && source.lastIndexOf("/*", at) > source.lastIndexOf("*/", at) -> lineStart
            // (CHK.205) A `//` on a line INSIDE a block comment (a JSDoc code-fence
            // example) opens nothing — it is block-comment text, so the directive
            // counts only on the block's last line, like any other block line.
            opener >= 0 && insideOpenBlockComment(source, lineStart, opener) -> lineStart
            else -> -1
        }
        if (blockStart >= 0) {
            if (!isTsDirectiveCommentPrefix(source, blockStart, at)) return null
            val close = source.indexOf("*/", at)
            if (close >= 0) {
                var j = at
                var sameLine = true
                while (j < close) { if (source[j] == '\n') { sameLine = false; break }; j++ }
                if (sameLine) return TsCommentDirective(expectError, blockStart, close + 2)
            }
            return null
        }
        if (opener < 0 || source[opener + 1] != '/') return null
        var count = 0
        var k = opener
        while (k < at && source[k] == '/') { count++; k++ }
        if (count != 2 && count != 3) return null
        while (k < at && (source[k] == ' ' || source[k] == '\t')) k++
        if (k != at) return null
        var end = at
        while (end < source.length && source[end] != '\n' && source[end] != '\r') end++
        return TsCommentDirective(expectError, opener, end)
    }

    /**
     * (CHK.205) Whether [lineStart]'s line begins inside a block comment that is
     * still open at [opener] on that line. The block opener found by a backward
     * search is confirmed to be a real comment opener by [commentOpenOnLineBefore]
     * on ITS own line, so a glob inside a string literal does not read as an open
     * comment.
     */
    private fun insideOpenBlockComment(source: String, lineStart: Int, opener: Int): Boolean {
        if (lineStart == 0) return false
        val open = source.lastIndexOf("/*", lineStart - 1)
        if (open < 0) return false
        val closeBefore = source.indexOf("*/", open + 2)
        if (closeBefore in 0 until opener) return false
        val openLineStart = if (open == 0) 0 else source.lastIndexOf('\n', open - 1) + 1
        return commentOpenOnLineBefore(source, openLineStart, open + 2) == open
    }

    /** (CHK.31) `^\s*(?:\/|\*)*\s*$` — the text a block comment's last line may
     *  carry before the `@`, per tsc's multi-line directive regex applied to the
     *  `trimStart()`ed comment text. */
    private fun isTsDirectiveCommentPrefix(source: String, from: Int, to: Int): Boolean {
        var k = from
        while (k < to && (source[k] == ' ' || source[k] == '\t')) k++
        while (k < to && (source[k] == '/' || source[k] == '*')) k++
        while (k < to && (source[k] == ' ' || source[k] == '\t')) k++
        return k == to
    }

    /**
     * (CHK.31) The offset at which a comment OPENS on [at]'s own line before
     * [at], or -1 — string-aware, because a `//` inside a string or a template
     * is not a comment opener and a directive regex anchored anywhere but the
     * real comment start is a suppression the author never wrote.
     */
    private fun commentOpenOnLineBefore(source: String, lineStart: Int, at: Int): Int {
        var k = lineStart
        while (k < at) {
            val ch = source[k]
            if (ch == '"' || ch == '\'' || ch == '`') {
                k++
                while (k < at) {
                    if (source[k] == '\\') { k += 2; continue }
                    if (source[k] == ch) { k++; break }
                    k++
                }
            } else if (ch == '/' && k + 1 < source.length && source[k + 1] == '/') {
                return k
            } else if (ch == '/' && k + 1 < source.length && source[k + 1] == '*') {
                // A block that CLOSES before `at` is trivia the directive sits
                // after, not the comment carrying it: `/* a block */ // @ts-ignore`.
                val close = source.indexOf("*/", k + 2)
                if (close < 0 || close + 2 > at) return k
                k = close + 2
            } else {
                k++
            }
        }
        return -1
    }

    /**
     * (CHK.31) tsc's `markPrecedingCommentDirectiveLine` (program.ts), 1-based:
     * from the line ABOVE the diagnostic, walk UP — a line carrying a directive
     * suppresses (and is returned, so the caller can mark it USED); a blank line
     * or a `//` line continues the walk; anything else stops it, INCLUDING the
     * body or tail line of a block comment.
     *
     * Returns the suppressing line, or -1.
     */
    private fun markPrecedingTsCommentDirective(
        source: String,
        diagnosticLine: Int,
        directives: Map<Int, TsCommentDirective>,
    ): Int {
        val starts = checker.lineStartsFor(source)
        var line = diagnosticLine - 1
        while (line >= 1) {
            if (directives.containsKey(line)) return line
            if (line > starts.size) return -1
            val s = starts[line - 1]
            val e = if (line < starts.size) starts[line] else source.length
            val text = source.substring(s, e).trim()
            if (text.isNotEmpty() && !text.startsWith("//")) return -1
            line--
        }
        return -1
    }

    /**
     * (CHK.31) The general comment-directive filter, applied at the one funnel
     * every consumer passes through ([getDiagnostics]).
     *
     * Two halves, in tsc's order: every diagnostic preceded by a directive is
     * DROPPED and marks that directive used, then every `@ts-expect-error` that
     * marked nothing is reported as TS2578.
     *
     * THE PARTITION RULE: both halves are scoped to [checkedResults], i.e. to
     * the files this checker actually WALKED — never to the whole program. A
     * file the partition did not check produces no diagnostics for its
     * `@ts-expect-error` to suppress, so a program-wide TS2578 pass would
     * manufacture one false positive per directive on every narrowed build,
     * which is exactly what an editor asks for. (INC.20)'s per-file rule.
     *
     * Pure in [diagnostics]: [getDiagnostics] is called more than once per
     * compile, so nothing here mutates the list it reads.
     */
    fun applyTsCommentDirectives(visible: List<Diagnostic>): List<Diagnostic> {
        var perFile: MutableMap<String, Map<Int, TsCommentDirective>>? = null
        var sources: MutableMap<String, String>? = null
        // Deliberately the FIELD, not the (INC.17) `checkedResults` getter: that
        // getter's census classifies `init` PASSES as partition-invariant or
        // partition-dependent, and this is not a pass — it is the public API,
        // called after every pass has run and more than once per compile. Routing
        // it through the getter attributes a read to no pass at all, which is
        // exactly what `PartitionCensusHookTest` refuses (and rightly: the
        // per-pass sums must partition the reads, not sample them).
        for (result in checker.checkedResultsAll) {
            val fileName = result.sourceFile.fileName
            val text = result.sourceFile.text
            val directives = tsCommentDirectivesOf(fileName, text)
            if (directives.isEmpty()) continue
            // (CHK.202) tsgo returns a plain-JS or a skipped file's rows before it
            // looks at a directive: none is honoured and none is reported unused.
            if (checker.uncheckedJsModeOf(fileName) != UncheckedJsFiles.Mode.CHECKED) continue
            if (perFile == null) { perFile = mutableMapOf(); sources = mutableMapOf() }
            perFile[fileName] = directives
            sources!![fileName] = text
        }
        val files = perFile ?: return visible
        val texts = sources!!
        val used = HashMap<String, MutableSet<Int>>()
        val kept = ArrayList<Diagnostic>(visible.size)
        for (d in visible) {
            val fileName = d.fileName
            val directives = if (fileName == null) null else files[fileName]
            if (directives == null || fileName == null) { kept.add(d); continue }
            val source = texts[fileName]!!
            val line = d.line ?: d.start?.let { checker.getLineAndCharacterOfPosition(source, it).first }
            if (line == null) { kept.add(d); continue }
            val marked = markPrecedingTsCommentDirective(source, line, directives)
            if (marked >= 0) used.getOrPut(fileName) { HashSet() }.add(marked) else kept.add(d)
        }
        for ((fileName, directives) in files) {
            val source = texts[fileName]!!
            val usedHere = used[fileName]
            for ((line, directive) in directives) {
                if (!directive.expectError) continue
                if (usedHere != null && line in usedHere) continue
                val (l, c) = checker.getLineAndCharacterOfPosition(source, directive.pos)
                kept.add(Diagnostic(
                    message = "Unused '@ts-expect-error' directive.",
                    category = DiagnosticCategory.Error, code = 2578, fileName = fileName,
                    line = l, character = c, start = directive.pos, length = directive.end - directive.pos,
                ))
            }
        }
        return kept
    }
}
