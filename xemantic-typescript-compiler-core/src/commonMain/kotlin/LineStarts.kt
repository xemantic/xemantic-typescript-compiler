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
 * This compiler's line-terminator convention, stated ONCE.
 *
 * Returns how many characters the line break at [index] occupies, or `0` when
 * there is no break there: `\n` and a lone `\r` are one character, `\r\n` is one
 * break of two characters. That last clause is the whole reason this exists as a
 * function — counting `\r` and `\n` separately breaks a CRLF line in two, which is
 * the obvious way to get an offset-to-line conversion wrong, and it is invisible
 * on the LF-only text that everyone writes.
 *
 * Every offset-to-line conversion in this compiler goes through this rule, so that
 * a SYNTAX diagnostic and a SEMANTIC one on the same file can never number the
 * lines differently. Before round 915 they could: [computeLineStarts] (Parser)
 * broke a line at a lone `\r` and `Checker.lineStartsFor` counted `\n` only, so on
 * classic-Mac text the checker reported every position as line 1 — a
 * self-inconsistency no corpus baseline can see, because `\n` and `\r\n` are
 * identical under both readings. `LineTerminatorConsistencyTest` is the pin.
 *
 * Unicode `U+2028` / `U+2029` are NOT terminators here. tsc treats them as such;
 * our Scanner does not, so neither does this — a line number no token position of
 * ours agrees with would be worse than the divergence from tsc.
 */
internal fun lineBreakWidthAt(text: String, index: Int): Int =
    when (text[index]) {
        '\r' -> if (index + 1 < text.length && text[index + 1] == '\n') 2 else 1
        '\n' -> 1
        else -> 0
    }

/**
 * Compute an array of line start positions (0-based offsets where each line begins).
 *
 * The first entry is always `0` (the start of the first line), and a text that ends
 * in a line break gets a final entry at its length — the empty last line every
 * editor shows, and where an end-of-file diagnostic lands.
 *
 * Line breaks are [lineBreakWidthAt]'s.
 */
internal fun computeLineStarts(text: String): IntArray {
    // M0.3(viii): growable IntArray, no per-line Integer boxing (the old
    // mutableListOf(0) boxed every line start).
    var starts = IntArray(maxOf(16, text.length / 32))
    var count = 1 // starts[0] = 0 (start of the first line)
    var i = 0
    val n = text.length
    while (i < n) {
        val width = lineBreakWidthAt(text, i)
        if (width == 0) {
            i++
            continue
        }
        i += width
        if (count == starts.size) starts = starts.copyOf(starts.size * 2)
        starts[count++] = i
    }
    return if (count == starts.size) starts else starts.copyOf(count)
}

/**
 * The 1-based line and character of [position] in [text], by a bounded scan.
 *
 * Equivalent to a binary search over [computeLineStarts], and the shape to use
 * where there is nothing to memoize the array on: it stops at [position] instead of
 * indexing the whole text, and allocates nothing. Where a line index IS memoized —
 * the Parser's per-parse `lineStarts`, the Checker's per-source cache — search that
 * instead; both answer identically by construction, since both count breaks by
 * [lineBreakWidthAt].
 *
 * A [position] past the end of [text] keeps its (larger) character, exactly as the
 * binary-search form does, so a caller reporting a diagnostic one past the last
 * character gets the caret column rather than a clamped one.
 *
 * An offset INSIDE a `\r\n` pair — the `\n` — is not the start of the following
 * line, so it is reported as a character on the line the `\r` ends. It is not a
 * position any token of ours begins at; LSP has the same hole.
 */
internal fun lineAndCharacterAt(text: String, position: Int): Pair<Int, Int> {
    var line = 1
    var lineStart = 0
    var i = 0
    val cap = position.coerceAtMost(text.length)
    while (i < cap) {
        val width = lineBreakWidthAt(text, i)
        if (width == 0) {
            i++
            continue
        }
        // A break that does not END at or before `cap` has not opened a new line
        // that `position` could be on — this is the `\n` of a straddled `\r\n`.
        if (i + width > cap) break
        i += width
        line++
        lineStart = i
    }
    return line to (position - lineStart + 1)
}
