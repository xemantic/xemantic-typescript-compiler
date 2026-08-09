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
 * The B333 jsxRuntime pragma pattern — a block comment holding `@jsxRuntime`
 * followed by `classic` or `automatic`, which overrides the `@jsx` option's
 * runtime choice for one file (the LAST pragma in the file wins, as tsc
 * collects them in order).
 *
 * ## (WARM.10): why this is hand-written
 *
 * `Transformer.transform` ran this matcher over the FULL TEXT of every file it
 * transformed, under **no gate whatsoever** — no option test, no substring
 * pre-filter — and its leading literal is a slash-star, TWO characters.
 * `java.util.regex`'s `BnM.optimize` refuses any prefix shorter than four, so
 * the pattern got no Boyer-Moore prefix search and was attempted at **every one
 * of the 9,977,097 characters** of tsc's own sources, finding **0** pragmas.
 * Measured warm on the compiler profile: **44.1 ms = 0.55% of an emit
 * rebuild**, 5.2% of `Transformer.transform`.
 *
 * It is the third member of a defect class this arc found three times in four
 * rounds — a whole-program regex with no extractable literal prefix, matching
 * nothing (round 860's UMD scan, round 862's `declare … require` probe) — and
 * the FIRST one on the EMIT path, which is why nothing had ever seen it: round
 * 738's `skipEmitOutputs` gate means `--noEmit` never enters the transformer,
 * so `BenchMain`, `cost_gate.py` and the `--noEmit --listAll` 8-profile grid are
 * all structurally blind to it at once.
 *
 * The replacement is an EXACT equivalent, not a gate: round 860's law is that a
 * gate is a claim about where a construct may legally appear and round 792's is
 * that the dashboard profiles cannot falsify such a claim. It is anchored on the
 * literal [JSX_RUNTIME_TAG], found with [String.indexOf] — tsc's sources carry
 * **0** of those in 9,977,097 characters, so the whole scan is one linear sweep.
 * It also removes a regex-DIALECT hazard from `commonMain`: `kotlin.text.Regex`
 * is `java.util.regex` on the JVM and a different engine on Native.
 *
 * This regex stays LIVE as the SPECIFICATION that
 * `JsxRuntimePragmaScanTest` differentially compares [scanJsxRuntimePragmas]
 * against, over a family of shapes including the ones that must NOT match. Do
 * not delete or edit it without editing the scanner to match.
 */
internal val jsxRuntimePragmaRegex =
    Regex("""/\*\s*@jsxRuntime\s+(classic|automatic)\s*\*/""")

/**
 * The keyword of every jsxRuntime pragma in [text], in source order — exactly
 * `jsxRuntimePragmaRegex.findAll(text).map { it.groupValues[1] }`.
 *
 * The equivalence, term by term:
 *
 *  - `\s` in `java.util.regex` **without** `UNICODE_CHARACTER_CLASS` is the
 *    six-character set space / tab / LF / VT / FF / CR — hence [isJsxPragmaSpace]. It is
 *    deliberately NOT `Char.isWhitespace()`, which on the JVM also accepts
 *    `Character.isSpaceChar` (NBSP, U+2028, …) and would accept text the
 *    pattern rejects.
 *  - every `\s*` / `\s+` in the pattern is followed by a NON-whitespace
 *    obligation (`@`, `c`/`a`, `*`), so greedy consumption never backtracks and
 *    a maximal-munch scan is exact.
 *  - the alternation `(classic|automatic)` cannot match both at one position,
 *    so trying either order is the same answer.
 *  - `@jsxRuntime` must be followed by `\s+`, so `@jsxRuntimeFoo` is not a
 *    pragma — the forward whitespace run is what rejects it.
 *  - **matches may not overlap**, and here that is not vacuous: when a pragma's
 *    closing star-slash is immediately followed by another star, the second
 *    candidate's opening slash-star REUSES the first match's closing slash, so
 *    `findAll` — which resumes at the previous match's END — never sees it.
 *    The `lastEnd` cursor reproduces that. Anchoring on the tag is what makes the two enumerations comparable at
 *    all: every match contains exactly one [JSX_RUNTIME_TAG], and a match's
 *    start is a function of that occurrence, so the starts are monotone in
 *    occurrence order and leftmost-non-overlapping selection IS this loop.
 */
internal fun scanJsxRuntimePragmas(text: String): List<String> {
    var out: MutableList<String>? = null
    var lastEnd = 0
    var from = 0
    while (true) {
        val tag = text.indexOf(JSX_RUNTIME_TAG, from)
        if (tag < 0) break
        from = tag + JSX_RUNTIME_TAG.length
        // Backwards from `@jsxRuntime`: `\s*` then a literal slash-star.
        var i = tag
        while (i > 0 && isJsxPragmaSpace(text[i - 1])) i--
        if (i < 2 || text[i - 1] != '*' || text[i - 2] != '/') continue
        val start = i - 2
        if (start < lastEnd) continue                     // findAll resumes at the previous end
        // Forwards: `\s+` then `classic` or `automatic`.
        var j = from
        while (j < text.length && isJsxPragmaSpace(text[j])) j++
        if (j == from) continue                           // `\s+` needs at least one
        val keyword = when {
            text.startsWith(JSX_RUNTIME_CLASSIC, j) -> JSX_RUNTIME_CLASSIC
            text.startsWith(JSX_RUNTIME_AUTOMATIC, j) -> JSX_RUNTIME_AUTOMATIC
            else -> continue
        }
        j += keyword.length
        // Then `\s*` and the closing star-slash.
        while (j < text.length && isJsxPragmaSpace(text[j])) j++
        if (j + 1 >= text.length || text[j] != '*' || text[j + 1] != '/') continue
        val list = out ?: mutableListOf<String>().also { out = it }
        list.add(keyword)
        lastEnd = j + 2
        from = lastEnd
    }
    return out ?: emptyList()
}

private const val JSX_RUNTIME_TAG = "@jsxRuntime"
private const val JSX_RUNTIME_CLASSIC = "classic"
private const val JSX_RUNTIME_AUTOMATIC = "automatic"

/**
 * The `\s` class of `java.util.regex` — NOT [Char.isWhitespace], which is wider.
 */
private fun isJsxPragmaSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
