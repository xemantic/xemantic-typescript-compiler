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
 * (INC.78) One compiled `include`/`exclude` glob, matched against absolute paths.
 *
 * The [regex] is the DEFINITION and stays live as the differential oracle (round
 * 792's shape — never a legality gate): every answer this class gives must be the
 * answer `regex.matches(path)` gives, and `GlobMatcherTest` asserts exactly that
 * over an adversarial pattern/path grid. What the class adds is a way to reach that
 * answer without running the regex, for the pattern shapes real tsconfigs use.
 *
 * **WHY IT EXISTS.** The root-file glob asks every candidate against every include
 * and every exclude, and on an application-shaped project that is the whole file
 * count times the pattern count on EVERY BUILD — i.e. on every keystroke of a
 * language-service host. Measured on the 2,401-file `many-small-2400-dom` fixture
 * with the shipped one-include/one-exclude configuration, `FrontEnd.CFG_MATCH` was
 * **4.7-8.1 ms, 1.9-3.4 us per candidate**, which is 5-8% of a ~90-105 ms
 * incremental floor. The cost is the regex: `src / ** / *` compiles to
 * `^…/src/(?:[^/]+/)*[^/]*(?:\.ts|…)$`, whose `(?:[^/]+/)*` is a backtracking loop
 * over every directory segment of every path — and it runs to a MATCH for every
 * file in the project, so no cheap refusal can help it.
 *
 * **THE TWO MECHANISMS, AND WHICH ONE CARRIES THE WIN.**
 *  - [literalPrefix] is a NECESSARY condition (a path not starting with the
 *    pattern's constant leading text can never match), so it may only REFUSE. It is
 *    what makes an `exclude` naming a directory the walk mostly does not visit cost
 *    a `startsWith` instead of a regex.
 *  - [fastSuffixes] is an EXACT decision and is where the win is. When the whole
 *    pattern is `<literal> / ** / *<literal-tail>` — no other wildcard, no
 *    `?`, no second `**` — then `(?:[^/]+/)*[^/]*` matches ANY remainder, with or
 *    without further directories, so the pattern accepts exactly the paths that
 *    start with the literal head and end with the literal tail. That shape covers
 *    `src`, `src / ** / *`, `src / ** / *.ts`, `dist`, `** / *.spec.ts` — which
 *    is what tsconfigs contain.
 *
 * Anything else (a `*` inside a segment, a `?`, a bare `**`, two `**` separated by
 * literal text) keeps the regex, with the prefix filter still in front of it.
 *
 * **THE HAZARD IS SILENCE.** (CFG.1): a wrongly-included or wrongly-dropped root
 * file changes WHICH PROGRAM is compiled, and this repo has no diagnostic channel
 * that notices — the corpus harness materialises no directory at all and all eight
 * dashboard profiles scope `include` to a subtree that no wildcard shape stresses.
 * So the gate is the differential against [regex], not a green suite.
 */
class GlobMatcher private constructor(
    /**
     * The pattern as a regular expression — the DEFINITION of what this matcher
     * accepts, kept live so a test can compare every fast answer against it.
     */
    val regex: Regex,
    /**
     * The pattern's constant leading text, up to its first wildcard. A necessary
     * condition for a match, never a sufficient one.
     */
    val literalPrefix: String,
    /**
     * When non-null, the pattern is `[literalPrefix]` followed by `** / *<tail>`, and these
     * are the tails it accepts (one per supported extension when the pattern named
     * none). A path is then accepted exactly when it starts with [literalPrefix] and
     * ends with one of these, with no overlap between the two.
     */
    val fastSuffixes: List<String>?,
) {

    /** True when [path] is accepted by this pattern. Equivalent to `regex.matches(path)`. */
    fun matches(path: String): Boolean {
        if (!path.startsWith(literalPrefix)) return false
        val suffixes = fastSuffixes ?: return regex.matches(path)
        val remaining = path.length - literalPrefix.length
        for (i in suffixes.indices) {
            val suffix = suffixes[i]
            // The length guard keeps head and tail DISJOINT: without it a path shorter
            // than the two together could satisfy both by overlapping in the middle,
            // which the regex — where the head and the tail are consecutive — never
            // accepts.
            if (suffix.length <= remaining && path.endsWith(suffix)) return true
        }
        return false
    }

    companion object {

        /** The characters `globToRegex` gives a meaning other than themselves. */
        private const val WILDCARDS = "*?"

        /**
         * Compiles [pattern] (already absolute) exactly as the regex form always did,
         * then derives the two shortcuts above from the SAME normalized text — so the
         * fast path can never be describing a different pattern than the oracle.
         */
        fun compile(pattern: String, supportedExt: List<String>): GlobMatcher {
            var p = PathUtil.normalize(pattern)
            val lastSeg = PathUtil.basename(p)
            val extlessDir = !lastSeg.contains('.') && !lastSeg.contains('*') && !lastSeg.contains('?')
            if (extlessDir) p = "$p/**/*"
            val sb = StringBuilder()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when (c) {
                    '*' -> if (i + 1 < p.length && p[i + 1] == '*') {
                        // `**/` => any number of dirs; bare `**` => anything
                        if (i + 2 < p.length && p[i + 2] == '/') { sb.append("(?:[^/]+/)*"); i += 2 } else sb.append(".*")
                        i++
                    } else { sb.append("[^/]*"); i++ }
                    '?' -> { sb.append("[^/]"); i++ }
                    '.', '(', ')', '+', '{', '}', '[', ']', '$', '^', '|', '\\' -> { sb.append('\\').append(c); i++ }
                    else -> { sb.append(c); i++ }
                }
            }
            // No extension in the pattern? accept any supported extension on the matched leaf.
            val appendsExtensions = !lastSeg.contains('.')
            if (appendsExtensions) {
                val alt = supportedExt.joinToString("|") { Regex.escape(it) }
                sb.append("(?:$alt)")
            }
            val regex = Regex("^$sb$")

            val firstWildcard = p.indexOfFirst { it in WILDCARDS }
            val prefix = if (firstWildcard < 0) p else p.substring(0, firstWildcard)
            val tail = fastTailOf(p, firstWildcard)
            val suffixes = when {
                tail == null -> null
                appendsExtensions -> supportedExt.map { tail + it }
                else -> listOf(tail)
            }
            return GlobMatcher(regex, prefix, suffixes)
        }

        /**
         * The literal tail of a `<literal> / ** / *<tail>` pattern, or null when
         * [p] is not of that shape.
         *
         * The shape is required EXACTLY — `**` immediately followed by a slash and a
         * bare `*`, and then
         * nothing but literal text with no `/` in it — because that is the one case
         * where `(?:[^/]+/)*[^/]*` provably matches every remainder: any run of
         * directories, then any leaf. A second `**`, a `?`, or a `*` inside a segment
         * all constrain the middle, and none of them may take this path.
         */
        private fun fastTailOf(p: String, firstWildcard: Int): String? {
            if (firstWildcard < 0) return null
            // The head must end at a directory boundary: `(?:[^/]+/)*` starts a segment.
            if (firstWildcard == 0 || p[firstWildcard - 1] != '/') return null
            if (!p.startsWith("**/*", firstWildcard)) return null
            val tail = p.substring(firstWildcard + 4)
            if (tail.any { it in WILDCARDS || it == '/' }) return null
            return tail
        }
    }
}
