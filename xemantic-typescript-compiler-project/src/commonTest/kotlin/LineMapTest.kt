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
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The arithmetic contract of [LineMap], pinned independently of any compile.
 *
 * Every assertion here derives its expected offsets with `indexOf` rather than
 * hardcoding them, so a fixture edit cannot silently make a pin describe the wrong
 * character. The agreement with the compiler's own private line arithmetic — the
 * only property that makes this reimplementation trustworthy — is pinned one file
 * over, against a real [com.xemantic.typescript.compiler.Diagnostic], because no
 * restatement of the convention inside this file could establish it.
 */
class LineMapTest {

    /** Three lines and a trailing newline, i.e. four lines with an empty last one. */
    private val text = "const alpha = 1;\nconst beta = 2;\nconst gamma = 3;\n"

    private val map = LineMap.of(text)

    @Test
    fun `a trailing newline opens a final empty line`() {
        // Every editor shows it, and a diagnostic at end-of-file lands on it.
        assert(map.lineCount == 4)
        assert(map.textLength == text.length)
        assert(map.positionAt(text.length) == TextPosition(4, 1))
    }

    @Test
    fun `the first character of the file is line 1 character 1`() {
        assert(map.positionAt(0) == TextPosition(1, 1))
        assert(map.offsetAt(1, 1) == 0)
    }

    @Test
    fun `the first character of a line is character 1`() {
        val start = text.indexOf("const beta")
        assert(map.positionAt(start) == TextPosition(2, 1))
        assert(map.offsetAt(2, 1) == start)
    }

    @Test
    fun `the last character of a line keeps its own line`() {
        // The `;` of line 2 — the character immediately before the terminator, which
        // is where an off-by-one in the line search would spill onto line 3.
        val semicolon = text.indexOf("const beta").let { text.indexOf(';', it) }
        val position = map.positionAt(semicolon)
        assert(position.line == 2)
        assert(position.character == "const beta = 2;".length)
        assert(map.offsetAt(position.line, position.character) == semicolon)
    }

    @Test
    fun `the caret at the end of a line is addressable`() {
        // One past the last character: the terminator's own offset. An editor puts
        // the caret there whenever the user presses End.
        val terminator = text.indexOf('\n', text.indexOf("const beta"))
        val position = map.positionAt(terminator)
        assert(position == TextPosition(2, "const beta = 2;".length + 1))
        assert(map.offsetAt(position.line, position.character) == terminator)
    }

    @Test
    fun `every offset round-trips through a position`() {
        // The whole domain, inclusive of the end-of-text caret. The fixture has no
        // CRLF, so it has no non-addressable offsets — the CRLF hole is pinned below.
        for (offset in 0..text.length) {
            val position = map.positionAt(offset)
            assert(map.offsetAt(position.line, position.character) == offset)
        }
    }

    // --- line terminators ------------------------------------------------------

    @Test
    fun `CRLF text yields the same coordinates as LF text`() {
        val crlf = text.replace("\n", "\r\n")
        val crlfMap = LineMap.of(crlf)
        assert(crlfMap.lineCount == map.lineCount)
        // Compare the SAME character in both texts, located by content rather than
        // by offset — the offsets necessarily differ, the coordinates must not.
        for (needle in listOf("const alpha", "beta = 2", "gamma", ";")) {
            val lf = map.positionAt(text.indexOf(needle))
            val cr = crlfMap.positionAt(crlf.indexOf(needle))
            assert(cr == lf)
        }
        // And the last character of a CRLF line is still on that line: the `\r` must
        // not be counted as content, or every line's width would read one too wide.
        val semicolon = crlf.indexOf(';', crlf.indexOf("const beta"))
        assert(crlfMap.positionAt(semicolon) == TextPosition(2, "const beta = 2;".length))
    }

    @Test
    fun `the LF inside a CRLF pair is not addressable and resolves to the line end`() {
        // A position in the MIDDLE of a line break is neither the end of the line
        // before nor the start of the line after, so it has no coordinate. It must
        // resolve to the caret at the end of the preceding line rather than to the
        // next line's character 1 — which is the direction that would move a lookup
        // into the following statement.
        val crlf = "a\r\nb"
        val crlfMap = LineMap.of(crlf)
        assert(crlfMap.positionAt(1) == TextPosition(1, 2))
        assert(crlfMap.positionAt(2) == TextPosition(1, 2))
        assert(crlfMap.offsetAt(1, 2) == 1)
        assert(crlfMap.positionAt(3) == TextPosition(2, 1))
    }

    @Test
    fun `a lone CR ends a line`() {
        // The LSP rule and the rule of our own Parser. `Checker.lineStartsFor`
        // disagrees — see the next test, which pins the divergence rather than
        // pretending it away.
        val cr = "a\rbb\rc"
        val crMap = LineMap.of(cr)
        assert(crMap.lineCount == 3)
        assert(crMap.positionAt(cr.indexOf("bb")) == TextPosition(2, 1))
        assert(crMap.positionAt(cr.indexOf('c')) == TextPosition(3, 1))
    }

    @Test
    fun `a lone CR does not reproduce the checker's own line numbering`() {
        // DOCUMENTED DIVERGENCE, not a defect of this class: on lone-CR text the
        // compiler disagrees with itself — `Parser.computeLineStarts` breaks the
        // line, `Checker.lineStartsFor` counts `\n` only and reports everything as
        // line 1. This class follows the Parser/LSP rule, so a SEMANTIC diagnostic
        // on such a file carries a line number this map does not produce. Pinned so
        // that a future agent reading a mismatched coordinate finds the reason here
        // instead of rediscovering it against a lone-CR file nobody writes on
        // purpose.
        val cr = "a\rb"
        val checkerWouldSay = 1
        assert(LineMap.of(cr).positionAt(cr.indexOf('b')).line != checkerWouldSay)
    }

    @Test
    fun `a unicode line separator is not a line terminator`() {
        // tsc breaks lines at U+2028 / U+2029; neither our Parser nor our Checker
        // does, and this map follows OUR compiler — a coordinate no diagnostic of
        // ours can carry is worse than none.
        assert(LineMap.of("a\u2028b").lineCount == 1)
        assert(LineMap.of("a\u2029b").lineCount == 1)
    }

    // --- degenerate texts ------------------------------------------------------

    @Test
    fun `empty text is one empty line`() {
        val empty = LineMap.of("")
        assert(empty.lineCount == 1)
        assert(empty.positionAt(0) == TextPosition(1, 1))
        assert(empty.offsetAt(1, 1) == 0)
    }

    @Test
    fun `lineStart reports where each line begins`() {
        assert(map.lineStart(1) == 0)
        assert(map.lineStart(2) == text.indexOf("const beta"))
        assert(map.lineStart(3) == text.indexOf("const gamma"))
        assert(map.lineStart(4) == text.length)
    }

    // --- out-of-domain arguments -----------------------------------------------

    @Test
    fun `an offset outside the text is refused`() {
        // Not clamped: the caller of an editor feature is a program, so failing
        // where the wrong offset was computed beats answering a coordinate for a
        // character that does not exist.
        assertFailsWith<IllegalArgumentException> { map.positionAt(-1) }
        assertFailsWith<IllegalArgumentException> { map.positionAt(text.length + 1) }
    }

    @Test
    fun `a line outside the file is refused while a character past a line is clamped`() {
        assertFailsWith<IllegalArgumentException> { map.offsetAt(0, 1) }
        assertFailsWith<IllegalArgumentException> { map.offsetAt(map.lineCount + 1, 1) }
        assertFailsWith<IllegalArgumentException> { map.offsetAt(1, 0) }
        // A caret past the end of line 1 stays on line 1 — it must never return an
        // offset the next line owns.
        val clamped = map.offsetAt(1, 9999)
        assert(clamped == text.indexOf('\n'))
        assert(map.positionAt(clamped).line == 1)
    }
}
