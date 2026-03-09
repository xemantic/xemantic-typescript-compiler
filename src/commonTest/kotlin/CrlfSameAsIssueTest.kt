/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.sameAs
import kotlin.test.Test

/**
 * Demonstrates a display issue in [com.xemantic.kotlin.test.sameAs] when compared strings
 * contain CRLF (`\r\n`) line endings.
 *
 * ## Background
 *
 * TypeScript compiler baseline files use mixed line endings:
 * - Section headers (`//// [filename.ts]`) use CRLF
 * - Echoed source code preserves original LF endings
 * - Emitted JavaScript output uses CRLF
 *
 * When [sameAs] computes and renders a unified diff of two such strings,
 * `splitLinesForDiff` splits on `\n` only, so each "line" retains a trailing `\r`.
 * When the diff lines are printed to a terminal, the `\r` moves the cursor back to
 * column 0 — causing subsequent characters to overwrite the line prefix (`-`, `+`, ` `).
 * The resulting diff output is visually garbled and impossible to read.
 *
 * ## Reproduction
 *
 * The test [crlfDiffOutputIsGarbledInTerminal] intentionally fails to expose the symptom.
 * Run it and observe the printed diff message: the `-`/`+` prefixes are invisible because
 * the `\r` at the end of each content line overwrites them.
 *
 * The test [lfDiffOutputIsReadableForComparison] performs the same logical comparison
 * with LF-only strings and intentionally fails too — its diff IS readable, showing what
 * the CRLF version should ideally look like.
 *
 * ## Expected fix
 *
 * [sameAs] should either:
 * 1. Escape `\r` as `\` + `r` (or `␍`) when rendering diff lines, or
 * 2. Strip trailing `\r` from lines before rendering (treating CRLF and LF as equal
 *    line separators), reporting a separate "line endings differ" note if needed.
 */
class CrlfSameAsIssueTest {

    /**
     * A single-file TypeScript baseline as produced by [formatBaseline].
     *
     * Structure: source echo section (LF endings) followed by JS output section (CRLF endings).
     * This mirrors the real files in `typescript-repo/tests/baselines/reference/`.
     */
    private fun makeBaseline(jsLine: String): String = buildString {
        // Source echo: LF endings (preserved from the original .ts file)
        append("//// [example.ts]\n")
        append("export const x = 1;\n")
        append("\n")
        // JS output: CRLF endings (as produced by toCRLF() in BaselineFormatter.kt)
        append("//// [example.js]\r\n")
        append("$jsLine\r\n")
    }

    /**
     * Intentionally failing test — **run this and look at the diff output**.
     *
     * Expected symptom: the diff lines that contain content end with `\r`, causing
     * the terminal cursor to jump to column 0.  The `+`/`-` prefix on the *next*
     * rendered character then overwrites the beginning of the same visual line, so
     * you cannot tell which lines were added and which were removed.
     *
     * Typical garbled output (what you actually see in a terminal):
     * ```
     * +export const x = 1;   ← was "-export const x = 1;\r", \r ate the "-"
     * +export const x = 2;   ← actually the added line, but looks the same
     * ```
     *
     * Note: this test is intentionally @Ignore-d so it does not break the CI suite.
     * Remove the @Ignore annotation locally to reproduce the issue.
     */
    @kotlin.test.Ignore
    @Test
    fun crlfDiffOutputIsGarbledInTerminal() {
        val expected = makeBaseline("export const x = 1;")
        val actual   = makeBaseline("export const x = 2;")   // one character differs
        // The diff message will be unreadable: \r in content lines overwrites the -/+ prefix
        actual sameAs expected
    }

    /**
     * Same logical difference as [crlfDiffOutputIsGarbledInTerminal], but with LF-only
     * strings — no `\r` anywhere.  The diff IS readable and correctly highlights the
     * changed line.
     *
     * Compare the output of this test with [crlfDiffOutputIsGarbledInTerminal] to
     * confirm that CRLF is the sole cause of the display problem.
     *
     * Note: intentionally @Ignore-d; remove @Ignore locally to see the readable diff.
     */
    @kotlin.test.Ignore
    @Test
    fun lfDiffOutputIsReadableForComparison() {
        val expected = makeBaseline("export const x = 1;").replace("\r\n", "\n")
        val actual   = makeBaseline("export const x = 2;").replace("\r\n", "\n")
        // The diff message is clean: "-export const x = 1;" / "+export const x = 2;"
        actual sameAs expected
    }

    /**
     * A minimal, self-contained reproduction that does not depend on [makeBaseline].
     *
     * Two two-line strings: identical first line, differing second line, both CRLF.
     * Stripped down to the bare minimum for inclusion in a library issue report.
     *
     * Note: intentionally @Ignore-d; remove @Ignore locally to reproduce.
     */
    @kotlin.test.Ignore
    @Test
    fun minimalCrlfReproduction() {
        val expected = "unchanged line\r\nexpected content\r\n"
        val actual   = "unchanged line\r\nactual content\r\n"
        // In a terminal the diff shows only "actual content" with no -/+ sign visible,
        // because "\r" at line end overwrites the sign.
        actual sameAs expected
    }
}
