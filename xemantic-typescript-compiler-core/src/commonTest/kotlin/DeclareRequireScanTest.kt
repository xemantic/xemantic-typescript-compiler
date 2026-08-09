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
 * (WARM.8)(c) round 862 — [containsDeclareRequire] must answer exactly what
 * [declareRequireRegex] answers.
 *
 * Round 862 sub-partitioned `cpcRequireOnlyOrphans` and found that pattern is
 * **87.4% of it** — 121 ms of 139 ms on a warm rebuild of the compiler profile,
 * because a `java.util.regex` pattern beginning with `\b` gets no Boyer-Moore
 * prefix search and is attempted at every one of 9,977,097 positions, where it
 * accepts **0 files**. The scanner anchors on the literal `require` instead.
 *
 * Two independent pins, and they fail against different mistakes:
 *
 *  - the DIFFERENTIAL over a generated cross-product catches an edit to either
 *    side alone, which is why the pattern stays live as the specification;
 *  - the hand-written EXPECTATION table catches a matched edit to both, which
 *    the differential cannot see by construction.
 *
 * The shapes that discriminate are the boundary ones, and they are not
 * guessable: `$` is NOT a word character to `java.util.regex`'s `\b` (no
 * `UNICODE_CHARACTER_CLASS` flag), and neither is a non-ASCII letter — so
 * `$declare const require` and `édeclare const require` both MATCH, while
 * `xdeclare const require` does not.
 */
class DeclareRequireScanTest {

    /** Every shape below, with the answer stated by hand rather than by the oracle. */
    private val expectations: List<Pair<String, Boolean>> = listOf(
        // --- accepted -------------------------------------------------------
        "declare const require: any" to true,
        "declare var require;" to true,
        "declare let require: NodeRequire" to true,
        "declare function require(s: string): any;" to true,
        // the whole file is the match, so `require` ends at end-of-input
        "declare const require" to true,
        // every member of the `\s` class, in both runs
        "declare\n\tconst\u000Brequire " to true,
        "declare  \r\n  var \t require\n" to true,
        // `\b` before `declare`: `$` and a non-ASCII letter are NOT word chars
        "\$declare const require;" to true,
        "édeclare const require;" to true,
        ";declare const require;" to true,
        "}\ndeclare function require(): void;" to true,
        // the first `require` occurrence fails, a later one matches — the anchor
        // loop must not stop at the first rejection
        "const requireX = 1;\ndeclare const require: any;" to true,
        "import x = require('./a');\ndeclare var require;" to true,
        // --- rejected -------------------------------------------------------
        "" to false,
        "declare const requires: any" to false,      // `require\b` fails
        "declare const require_2: any" to false,     // `_` IS a word char
        "declare const require9" to false,
        "xdeclare const require" to false,           // `\bdeclare` fails
        "_declare const require" to false,
        "9declare const require" to false,
        "declare constrequire" to false,             // the second `\s+` needs one
        "declareconst require" to false,             // the first `\s+` needs one
        "declare require" to false,                  // no keyword
        "const require: any" to false,               // no `declare`
        "declare const let require" to false,        // keyword must abut `declare`
        "declare const xrequire" to false,           // `\s+` before `require`
        "require('./x')" to false,
        "declare const q = require('./x')" to false,
        // a `declare` and a `require` in the same file but not in one phrase
        "declare const x: any;\nconst y = require('./z');" to false,
    )

    @Test
    fun `the scanner answers exactly what the pattern answers on every hand-written shape`() {
        for ((text, _) in expectations) {
            val byPattern = declareRequireRegex.containsMatchIn(text)
            val byScanner = containsDeclareRequire(text)
            assert(byScanner == byPattern)
        }
        // Not vacuous: the corpus must contain both answers.
        assert(expectations.any { it.second })
        assert(expectations.any { !it.second })
    }

    @Test
    fun `the hand-written expectations hold - a matched edit to both sides is caught`() {
        for ((text, expected) in expectations) {
            val byScanner = containsDeclareRequire(text)
            assert(byScanner == expected)
        }
    }

    /**
     * The DIFFERENTIAL — a cross-product of the pattern's four variable
     * positions, so no single term can be dropped or widened without a
     * disagreement: 6 prefixes x 6 separators x 6 keywords x 6 separators x
     * 6 suffixes = 7,776 shapes, each answered twice.
     */
    @Test
    fun `the scanner and the pattern agree on the whole generated cross-product`() {
        val prefixes = listOf("", "x", "_", ";", "\$", "\n")
        val separators = listOf("", " ", "  ", "\t", "\n", "\u000B")
        val keywords = listOf("const", "var", "let", "function", "constx", "")
        val suffixes = listOf("", ";", ":any", "s", "_", "9")
        var agreed = 0
        var accepted = 0
        for (p in prefixes) for (s1 in separators) for (k in keywords) for (s2 in separators) {
            for (suf in suffixes) {
                val text = p + "declare" + s1 + k + s2 + "require" + suf
                val byPattern = declareRequireRegex.containsMatchIn(text)
                val byScanner = containsDeclareRequire(text)
                assert(byScanner == byPattern)
                agreed++
                if (byPattern) accepted++
            }
        }
        // Not vacuous in either direction: the generator must produce both
        // answers, or "they agree" would be a statement about one constant.
        assert(agreed == prefixes.size * separators.size * keywords.size * separators.size * suffixes.size)
        assert(accepted > 0)
        assert(accepted < agreed)
    }

    /**
     * …and the anchor must be found anywhere in a long text, not only near its
     * start — the scanner sweeps with `indexOf` and a mistake in its `from`
     * advance would loop or stop early, which the short shapes above cannot see.
     */
    @Test
    fun `the scanner finds a match buried in a large text and terminates on one that has none`() {
        val filler = "const requires = 1; // require('./x') requireX require_\n".repeat(2000)
        assert(!containsDeclareRequire(filler))
        assert(!declareRequireRegex.containsMatchIn(filler))
        val hit = filler + "declare const require: any;\n" + filler
        assert(containsDeclareRequire(hit))
        assert(declareRequireRegex.containsMatchIn(hit))
    }
}
