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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (BUG.1) Every offset-to-line conversion in this compiler implements ONE
 * line-terminator convention, so a SYNTAX diagnostic and a SEMANTIC one can never
 * number the same file's lines differently.
 *
 * Until round 915 they could. `Parser.computeLineStarts` broke a line at `\n`, at
 * `\r\n` and at a lone `\r` (tsc's rule); `Checker.lineStartsFor` counted `\n`
 * only, and so did the tsconfig reader, the reference-directive scanner and the
 * JSX dev-runtime coordinate emitter. On classic-Mac text — the ONLY shape where
 * the two readings differ, since `\r\n` ends in a `\n` and is therefore identical
 * under both — the parser numbered the lines and the checker reported everything as
 * line 1.
 *
 * That is exactly why no corpus baseline could ever have caught it, and why this
 * file is the gate. Its sharp assertion is the SELF-CONSISTENCY one: not that a
 * particular line number is produced, but that the two halves of one compile agree
 * with each other AND with what the three line shapes make of the same program.
 *
 * Note these fixtures deliberately do NOT go through `diagnose`, and the reason is
 * the second half of why the corpus is blind here: `parseMultiFileSource` — the
 * `// @directive` splitter behind the whole generated suite — begins by replacing
 * every `\r\n` and `\r` with `\n`, so NO string-entry-point compile can put a `\r`
 * in front of the Parser. Only the project/`Vfs` path can, and [diagnoseVerbatim]
 * reproduces it by handing the pipeline a `ParsedSource` directly.
 */
class LineTerminatorConsistencyTest {

    /**
     * The same program in three line shapes.
     *
     * Line 3 carries a SEMANTIC error (TS2322, emitted by the Checker) and line 5 a
     * SYNTAX one (TS1128, emitted by the Parser); line 1 carries neither, so an
     * arithmetic that collapses the file onto line 1 cannot pass by accident.
     */
    private fun program(lineBreak: String): String = listOf(
        "const first = 1;",
        "",
        "const wrong: string = 2;",
        "",
        "}",
    ).joinToString(lineBreak)

    /** Independent restatement of the rule, so the pin is not the code under test. */
    private fun lineOf(text: String, offset: Int): Int {
        var line = 1
        var i = 0
        while (i < offset && i < text.length) {
            when {
                text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    if (i + 2 > offset) break
                    line++
                    i += 2
                }
                text[i] == '\r' || text[i] == '\n' -> {
                    line++
                    i++
                }
                else -> i++
            }
        }
        return line
    }

    @Test
    fun `a lone CR file's syntax and semantic diagnostics number its lines the same way`() {
        val source = program("\r")
        val diagnostics = diagnoseVerbatim(source)
            .filter { it.start != null && it.line != null }
        // Vacuity guards: both emitters must actually have fired, or "they agree" is
        // a statement about an empty set. Parser codes are 1xxx, checker codes 2xxx.
        assert(diagnostics.any { it.code == 2322 })
        assert(diagnostics.any { it.code < 2000 })
        // The semantic diagnostic is on line 3 and the syntax one on line 5 — the
        // whole defect was the semantic half answering 1 while the syntax half
        // answered 5, so pinning BOTH values is what separates "agreeing" from
        // "agreeing on nothing".
        assert(diagnostics.any { it.code == 2322 && it.line == 3 })
        assert(diagnostics.any { it.code < 2000 && it.line == 5 })
        // ... and no diagnostic of either half disagrees with the convention.
        for (diagnostic in diagnostics) {
            assert(diagnostic.line == lineOf(source, diagnostic.start!!))
        }
    }

    @Test
    fun `LF and CRLF and lone CR yield the same line numbers for the same program`() {
        // The control that catches the other way of getting this wrong: counting
        // `\r` and `\n` separately breaks a CRLF line in two, which would make the
        // CRLF column of this table read 5 and 9 instead of 3 and 5.
        val shapes = listOf("\n", "\r\n", "\r").map { lineBreak ->
            diagnoseVerbatim(program(lineBreak))
                .filter { it.line != null }
                .map { it.code to it.line }
                .sortedBy { "${it.first}:${it.second}" }
        }
        assert(shapes[0].isNotEmpty())
        assert(shapes[1] == shapes[0])
        assert(shapes[2] == shapes[0])
    }

    @Test
    fun `consecutive and trailing lone CRs are each an ordinary line break`() {
        // Two breaks in a row open an empty line rather than being coalesced, and a
        // trailing one opens an empty final line; both are where an off-by-one in
        // the scan lives.
        val source = "const a = 1;\r\rconst wrong: string = 2;\r"
        val semantic = diagnoseVerbatim(source).filter { it.code == 2322 }
        assert(semantic.isNotEmpty())
        assert(semantic.all { it.line == 3 })
    }

    @Test
    fun `a CR between two statements does not swallow the statement after it`() {
        // The character, not only the line: a conversion that consumed a lone `\r`
        // as if it were half of a `\r\n` would start line 2 one character late and
        // report every column on it one too large.
        val source = "const a = 1;\rconst wrong: string = 2;"
        val semantic = diagnoseVerbatim(source).first { it.code == 2322 }
        val start = semantic.start!!
        // Located from the fixture, not from the arithmetic under test.
        val secondLineStart = source.indexOf('\r') + 1
        assert(start >= secondLineStart)
        assert(semantic.line == 2)
        assert(semantic.character == start - secondLineStart + 1)
    }

    // --- the shared arithmetic's own edges ---------------------------------------
    //
    // These do not discriminate the defect (the Parser's index was always right);
    // they guard the off-by-ones that a single shared implementation could newly
    // introduce for the whole compiler at once.

    @Test
    fun `an empty text is one line and its only offset is the origin`() {
        assert(computeLineStarts("").toList() == listOf(0))
        assert(lineAndCharacterAt("", 0) == 1 to 1)
    }

    @Test
    fun `a text that is a single CR is two lines`() {
        assert(computeLineStarts("\r").toList() == listOf(0, 1))
        assert(lineAndCharacterAt("\r", 0) == 1 to 1)
        assert(lineAndCharacterAt("\r", 1) == 2 to 1)
    }

    @Test
    fun `a trailing break opens an empty final line under every shape`() {
        assert(computeLineStarts("a\n").toList() == listOf(0, 2))
        assert(computeLineStarts("a\r").toList() == listOf(0, 2))
        assert(computeLineStarts("a\r\n").toList() == listOf(0, 3))
    }

    @Test
    fun `CRLF is one break of two characters and not two breaks`() {
        assert(computeLineStarts("a\r\nb").toList() == listOf(0, 3))
        assert(computeLineStarts("a\rb").toList() == listOf(0, 2))
        assert(computeLineStarts("a\nb").toList() == listOf(0, 2))
    }

    @Test
    fun `the LF inside a CRLF pair stays on the line the CR ends`() {
        // It is the start of no line, so it has no coordinate of its own; the
        // scanning form and the memoized-index form must make the same of it or the
        // two would disagree exactly where nobody looks.
        val text = "a\r\nb"
        assert(lineAndCharacterAt(text, 2) == 1 to 3)
        assert(lineAndCharacterAt(text, 3) == 2 to 1)
    }

    @Test
    fun `the scanning form and the line index answer identically everywhere`() {
        // The two shapes exist for different call sites (one memoizes, one does
        // not); nothing but this pin forces them to stay the same function.
        for (text in listOf("", "\r", "\n", "\r\n", "a\rb\r\nc\nd\r", "\r\r\n\n\r")) {
            val starts = computeLineStarts(text)
            for (offset in 0..text.length) {
                var line = 0
                for (index in starts.indices) if (starts[index] <= offset) line = index
                val scanned = lineAndCharacterAt(text, offset)
                assert(scanned.first == line + 1)
                assert(scanned.second == offset - starts[line] + 1)
            }
        }
    }
}
