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
 * The `declare const/var/let/function require` probe of
 * [TypeScriptCompiler.cpcRequireOnlyOrphans] — the specification, kept LIVE as
 * the oracle [DeclareRequireScanTest] differentially compares
 * [containsDeclareRequire] against.
 *
 * It must not be deleted or edited without editing [containsDeclareRequire] to
 * match: the pin is a differential over a corpus of shapes, so an edit to one
 * side alone shows up as a pin failure and nowhere else.
 *
 * ## (WARM.8)(c): why this pattern is the most expensive thing after the checker
 *
 * Round 861 measured `cpcRequireOnlyOrphans` at **1.72% of a warm rebuild and
 * 97.6% of the whole post-checker region**, and recorded that it "was not
 * sub-partitioned, so nothing here says which of its scans costs the 130 ms".
 * Round 862 sub-partitioned it and the answer is this line: **87.4% of the
 * block, 121 ms of 139 ms**, against 12.1% for the `import("…")` scan and
 * 0.27% for the statement walk.
 *
 * The mechanism is the leading `\b`. `java.util.regex` compiles a pattern that
 * begins with a literal slice into a Boyer-Moore search over the input
 * (`BnM.optimize`), which is why the sibling `import\s*\(…` pattern beside it
 * costs a seventh as much; a pattern that begins with a zero-width assertion
 * gets no such treatment and is attempted at **every one of 9,977,097
 * positions**. On the compiler profile it accepts **0 files** — a whole-program
 * 10 MB sweep that matches nothing, on every multi-file compile.
 *
 * Round 860's law applies unchanged: this is not a *gate* (a claim about where
 * the construct may legally appear, which round 792 says the dashboard profiles
 * cannot falsify) but an EXACT rewrite, anchored on the literal `require` —
 * 571 occurrences in those 9,977,097 characters, so one linear sweep plus 571
 * constant-time rejections. It also removes a regex-DIALECT hazard from
 * `commonMain`: the engine behind `kotlin.text.Regex` is `java.util.regex` on
 * the JVM and a different one on Native, while this scan is the same code
 * everywhere.
 */
internal val declareRequireRegex =
    Regex("""\bdeclare\s+(?:const|var|let|function)\s+require\b""")

/**
 * True iff [declareRequireRegex] has a match in [text] — the exact same answer,
 * computed by anchoring on the literal `require` and verifying the rest
 * backwards.
 *
 * The equivalence, term by term:
 *
 *  - the pattern's only literal that is REQUIRED and reasonably rare is
 *    `require`, so it is the anchor; every occurrence is checked, which is what
 *    makes "the first match `findAll` would have found" irrelevant to a
 *    boolean answer.
 *  - `require\b` — `e` is a word character, so the boundary holds iff the next
 *    position is end-of-input or a non-word character. `\w` here is ASCII
 *    `[a-zA-Z0-9_]`: the pattern carries no `UNICODE_CHARACTER_CLASS` flag, and
 *    `$` is deliberately NOT a word character (see [isDeclareRequireWordChar]).
 *  - each `\s+` is a run of at least one of space, tab, LF, U+000B, U+000C or
 *    CR — the non-`UNICODE_CHARACTER_CLASS` `\s` class exactly. Consuming the MAXIMAL
 *    run backwards is exact rather than merely greedy: backtracking to a
 *    shorter run would require the neighbouring token to start (or end) with a
 *    whitespace character, and neither `declare` nor any of the four keywords
 *    does.
 *  - `(?:const|var|let|function)` is an alternation, so all four are tried and
 *    the answer is "any of them completes the match". At most one can be a
 *    suffix of the same text, but trying all of them costs nothing and removes
 *    the need to prove that.
 *  - `\bdeclare` — `d` is a word character, so the boundary holds iff `declare`
 *    starts at offset 0 or is preceded by a non-word character.
 */
internal fun containsDeclareRequire(text: String): Boolean {
    var from = 0
    while (true) {
        val r = text.indexOf(DECLARE_REQUIRE_ANCHOR, from)
        if (r < 0) return false
        from = r + DECLARE_REQUIRE_ANCHOR.length
        // `require\b`
        if (from < text.length && isDeclareRequireWordChar(text[from])) continue
        // Backwards: `\s+` then one of the four keywords.
        var i = r
        while (i > 0 && isDeclareRequireSpace(text[i - 1])) i--
        if (i == r) continue                       // `\s+` needs at least one
        for (kw in DECLARE_REQUIRE_KEYWORDS) {
            if (i < kw.length || !text.regionMatches(i - kw.length, kw, 0, kw.length)) continue
            // Backwards: `\s+` then `declare`, then the leading `\b`.
            var j = i - kw.length
            val beforeKeyword = j
            while (j > 0 && isDeclareRequireSpace(text[j - 1])) j--
            if (j == beforeKeyword) continue       // `\s+` needs at least one
            if (j < DECLARE_REQUIRE_DECLARE.length ||
                !text.regionMatches(
                    j - DECLARE_REQUIRE_DECLARE.length,
                    DECLARE_REQUIRE_DECLARE, 0, DECLARE_REQUIRE_DECLARE.length,
                )
            ) continue
            val d = j - DECLARE_REQUIRE_DECLARE.length
            if (d != 0 && isDeclareRequireWordChar(text[d - 1])) continue
            return true
        }
    }
}

private const val DECLARE_REQUIRE_ANCHOR = "require"
private const val DECLARE_REQUIRE_DECLARE = "declare"

private val DECLARE_REQUIRE_KEYWORDS = arrayOf("const", "var", "let", "function")

/** `java.util.regex`'s `\s` without `UNICODE_CHARACTER_CLASS`. */
private fun isDeclareRequireSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

/** `java.util.regex`'s `\w` without `UNICODE_CHARACTER_CLASS` — no `$`. */
private fun isDeclareRequireWordChar(c: Char): Boolean =
    (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '_'
