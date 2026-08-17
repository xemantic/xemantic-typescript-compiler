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

import com.xemantic.typescript.compiler.Diagnostic

/**
 * A 1-based line and character, the coordinate every editor speaks.
 *
 * The convention is [Diagnostic]'s, exactly: both components count from ONE, so
 * the very first character of a file is `TextPosition(1, 1)`. That choice is not
 * taste — a host correlates these against [Diagnostic.line] / [Diagnostic.character],
 * and a map that disagreed with the diagnostics of the same compiler would be worse
 * than no map at all. `LineMapTest` pins the agreement against a real diagnostic
 * rather than against a restatement of this sentence.
 *
 * Note that LSP counts both components from ZERO. A host bridging to it subtracts
 * one from each; this API deliberately does not, because its neighbour in the same
 * module is [Diagnostic].
 */
public data class TextPosition(
    /** 1-based line. */
    public val line: Int,
    /** 1-based character within [line]. */
    public val character: Int,
)

/**
 * The line index of one file's text: [TextPosition] to offset and back.
 *
 * Editors address text by (line, character) and this compiler addresses it by a
 * 0-based character offset ([Diagnostic.start], `Node.pos`/`Node.end`), so every
 * editor feature needs this translation in BOTH directions — and the compiler
 * itself only ever needed one. `computeLineStarts` (`Parser.kt`) is `private`, its
 * consumer `getLineAndCharacterOfPosition` likewise, and both are offset-to-line
 * only. This class is therefore a REIMPLEMENTATION rather than a wrapper, which
 * makes the one thing that matters about it whether it agrees with the original.
 *
 * ## Line terminators
 *
 * `\n`, `\r\n` and a lone `\r` all end a line here — the LSP rule, the rule of
 * tsc's own `computeLineStarts`, and the rule the whole compiler now implements
 * through the single `lineBreakWidthAt` in its `LineStarts.kt`.
 *
 * That agreement is younger than this class. Until (BUG.1) landed in round 915 the
 * compiler disagreed with ITSELF on lone-`\r` text — the only shape where the
 * readings differ, since `\r\n` ends in a `\n` and is therefore identical under
 * both: the Parser broke the line and the Checker counted `\n` only, so a SEMANTIC
 * diagnostic reported every position as line 1 while a SYNTAX one from the same
 * compile numbered them. `ProjectPositionTest` pins the agreement on such a file
 * against real diagnostics, which is the only form of the claim this class can
 * make honestly.
 *
 * Unicode `U+2028` / `U+2029` are NOT terminators here. tsc treats them as such;
 * neither our Parser nor our Checker does, and this class matches OUR compiler
 * rather than tsc — a map that split a line the compiler did not split would
 * report a coordinate no diagnostic of ours can carry.
 *
 * ## The offset domain
 *
 * Offsets run `0 .. textLength` inclusive: the upper bound is the caret position
 * after the last character, which is a real position an editor can put a cursor
 * at and which the compiler emits (a diagnostic at end-of-file has
 * `start == textLength`).
 *
 * ## Round-tripping
 *
 * `offsetAt(positionAt(o)) == o` for every offset in that domain EXCEPT the `\n`
 * of a `\r\n` pair: a position in the MIDDLE of a line break is not expressible
 * as a (line, character), because it is neither the end of the line before nor
 * the start of the line after. LSP has the same hole. Such an offset resolves to
 * the end of the preceding line, which is where an editor puts the caret.
 *
 * Immutable and safe to share; it is a pure index over one string and retains no
 * reference to it.
 */
public class LineMap private constructor(
    /** 0-based offset where each line begins. `lineStarts[0]` is always 0. */
    private val lineStarts: IntArray,
    /**
     * 0-based offset where each line's CONTENT ends — the index of its terminator,
     * or [textLength] for the last line.
     *
     * Kept as a second array rather than derived from the next line's start,
     * because the terminator is one character or two and the difference is exactly
     * what decides whether a character index is on this line.
     */
    private val lineContentEnds: IntArray,
    /** The length of the text this map indexes. */
    public val textLength: Int,
) {

    public companion object {

        /** Builds the index of [text] in one pass. */
        public fun of(text: String): LineMap {
            val n = text.length
            // One entry per line; a file of n characters has at most n + 1 lines.
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            starts.add(0)
            var i = 0
            while (i < n) {
                when (text[i]) {
                    '\r' -> {
                        ends.add(i)
                        // `\r\n` is ONE terminator, so the next line starts after both.
                        i += if (i + 1 < n && text[i + 1] == '\n') 2 else 1
                        starts.add(i)
                    }
                    '\n' -> {
                        ends.add(i)
                        i += 1
                        starts.add(i)
                    }
                    else -> i += 1
                }
            }
            // The last line is terminated by end-of-text rather than by a break.
            ends.add(n)
            return LineMap(starts.toIntArray(), ends.toIntArray(), n)
        }
    }

    /**
     * How many lines the text has.
     *
     * A text ending in a line break has a final EMPTY line, as every editor shows
     * it: `"a\n"` is two lines, and offset 2 is `(2, 1)`.
     */
    public val lineCount: Int get() = lineStarts.size

    /**
     * The 1-based coordinate of [offset].
     *
     * @throws IllegalArgumentException if [offset] is outside `0 .. textLength`.
     *   A silent clamp would answer a coordinate for an offset that does not
     *   exist, and the caller of an editor feature is a program, not a person —
     *   the useful behaviour is to fail where the wrong offset was computed.
     */
    public fun positionAt(offset: Int): TextPosition {
        require(offset in 0..textLength) {
            "offset $offset outside 0..$textLength"
        }
        // The greatest line whose start is at or before `offset`.
        var lo = 0
        var hi = lineStarts.size - 1
        var line = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineStarts[mid] <= offset) {
                line = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        // An offset inside a `\r\n` lands on the line BEFORE it (its start is not
        // past the offset), and clamping to that line's content end is what makes
        // it the caret position at the end of that line — see the class KDoc.
        val clamped = if (offset > lineContentEnds[line]) lineContentEnds[line] else offset
        return TextPosition(line + 1, clamped - lineStarts[line] + 1)
    }

    /**
     * The 0-based offset of the 1-based ([line], [character]) coordinate.
     *
     * [character] is CLAMPED into the line: an editor legitimately reports a caret
     * past the end of a line (a column-selection, a virtual-whitespace caret, or
     * simply a stale coordinate typed a keystroke earlier), and the answer a
     * feature wants for it is the end of that line rather than an exception. The
     * clamp stops at the line's terminator, so a coordinate on line 1 can never
     * return an offset on line 2 — which is the property that would otherwise let
     * one wrong column silently move a lookup into the next statement.
     *
     * @throws IllegalArgumentException if [line] is outside `1 .. lineCount`, or
     *   [character] is below 1. The line is NOT clamped: a line number out of
     *   range is a caller bug about the file's identity, not about a caret.
     */
    public fun offsetAt(line: Int, character: Int): Int {
        require(line in 1..lineCount) { "line $line outside 1..$lineCount" }
        require(character >= 1) { "character $character below 1" }
        val start = lineStarts[line - 1]
        val end = lineContentEnds[line - 1]
        val offset = start + (character - 1)
        return if (offset > end) end else offset
    }

    /** The 0-based offset of the first character of the 1-based [line]. */
    public fun lineStart(line: Int): Int {
        require(line in 1..lineCount) { "line $line outside 1..$lineCount" }
        return lineStarts[line - 1]
    }
}
