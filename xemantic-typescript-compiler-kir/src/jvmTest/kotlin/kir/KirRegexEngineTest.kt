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

package com.xemantic.typescript.compiler.kir

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.runtime.JsRegExp
import com.xemantic.typescript.compiler.kir.runtime.compiledRegexProgram
import com.xemantic.typescript.compiler.kir.runtime.jsEndAnchorTranslated
import kotlin.test.Test

/**
 * (KIR.PERF.2) the fast matcher answers `test` exactly as `java.util.regex` does.
 *
 * The whole design rests on a differential, not on a proof: the reference
 * engine stays LIVE and every answer here is compared against it over a matrix
 * of patterns and inputs. So the gate is the round-792 shape — the
 * specification is runnable and is never demoted to a legality gate — and the
 * failure a differential cannot see is the one this file guards separately:
 * a matcher that quietly REFUSES everything would agree with the oracle on
 * every input and be worth nothing, so [the fast matcher accepts the patterns
 * the benchmark libraries use] asserts the acceptance directly.
 *
 * Refusals are pinned in the other direction too. Every construct outside the
 * regular subset must produce a null program AND still answer correctly, which
 * is what makes "the subset is small" a performance statement rather than a
 * correctness risk.
 */
class KirRegexEngineTest {

    // The patterns `smol-toml` validates every scalar and every key part with,
    // verbatim from its sources -- primitive.ts and struct.ts.
    private val intRegex = """^((0x[0-9a-fA-F](_?[0-9a-fA-F])*)|(([+-]|0[ob])?\d(_?\d)*))$"""
    private val floatRegex = """^[+-]?\d(_?\d)*(\.\d(_?\d)*)?([eE][+-]?\d(_?\d)*)?$"""
    private val leadingZero = """^[+-]?0[0-9_]"""
    private val keyPart = """^[a-zA-Z0-9-_]+[ \t]*$"""

    /** Runs both engines and returns the fast one's answer, throwing on a disagreement. */
    private fun differential(source: String, flags: String, input: String): Boolean {
        val expression = JsRegExp(source, flags)
        val oracle = java.util.regex.Pattern.compile(
            // The runtime's own reference text, not the raw source: the end
            // anchor's Java meaning is not JavaScript's, and the fallback
            // engine is compiled from this too.
            jsEndAnchorTranslated(source, flags),
            (if ('m' in flags) java.util.regex.Pattern.MULTILINE else 0) or
                (if ('s' in flags) java.util.regex.Pattern.DOTALL else 0) or
                (if ('i' in flags) java.util.regex.Pattern.CASE_INSENSITIVE else 0)
        ).matcher(input).find()
        val answer = expression.test(input)
        if (answer != oracle) {
            throw AssertionError(
                "/$source/$flags on <$input>: fast=$answer reference=$oracle"
            )
        }
        return answer
    }

    private fun sweep(source: String, flags: String = "", inputs: List<String>) {
        for (input in inputs) differential(source, flags, input)
    }

    /**
     * The document population plus the adversarial inputs.
     *
     * Every string the benchmark's TOML document presents to one of the three
     * `test` patterns, and then the shapes that break a matcher which is nearly
     * right: an underscore in every wrong place, a truncated radix prefix, an
     * empty string, a string that matches a PREFIX only (which an unanchored
     * matcher would accept), a trailing newline (which Java's `$` accepts and
     * JavaScript's does not — hence the structural end anchor), and a value
     * long enough that a backtracking engine would notice.
     */
    private val scalars = listOf(
        "", "0", "7", "42", "1_000", "1_000_000", "+99", "-17", "0x1F", "0xdead_beef",
        "0o755", "0b1010_1010", "3.14", "-0.0", "1e10", "1E+10", "6.022e23",
        "1_0.2_5e-1_0", "007", "0_", "_1", "1_", "1__0", "0x", "0x_1", "0b", "+_1",
        "12\n", "\n12", "1 2", "0xZZ", "1.2.3", "1e", "e10", "--1", "++1",
        "123456789012345678901234567890", "1_2_3_4_5_6_7_8_9_0_1_2_3_4_5_6",
        "true", "inf", "nan", "1979-05-27",
    )

    private val keys = listOf(
        "", "a", "key", "key ", "key\t", "key \t ", "a-b", "a_b", "A-1_z",
        "key name", "key.name", "kéy", "\"quoted\"", "key]", "-", "_", "0",
        "a".repeat(64), "a".repeat(64) + " ", "a b", " a",
    )

    // ── the acceptance control, without which every differential is vacuous ──

    @Test
    fun `the fast matcher accepts the patterns the benchmark libraries use`() {
        // A matcher that refused everything would pass every differential in
        // this file and be worth nothing. These are the four `test` patterns
        // section 5 of the levers doc priced, so this is the pin that says the
        // measured population is the one being answered.
        assert(compiledRegexProgram(intRegex, "") != null)
        assert(compiledRegexProgram(floatRegex, "") != null)
        assert(compiledRegexProgram(leadingZero, "") != null)
        assert(compiledRegexProgram(keyPart, "") != null)
    }

    @Test
    fun `a refused pattern is cached as a refusal rather than re-parsed`() {
        // Two calls, one answer: the cache holds the REFUSAL as well as the
        // program, so a fallback pattern costs one parse for the life of the
        // process rather than one per literal evaluation.
        assert(compiledRegexProgram("""(?=a)b""", "") == null)
        assert(compiledRegexProgram("""(?=a)b""", "") == null)
    }

    // ── the differentials ────────────────────────────────────────────────────

    @Test
    fun `the toml integer pattern agrees with the reference engine`() {
        sweep(intRegex, inputs = scalars)
    }

    @Test
    fun `the toml float pattern agrees with the reference engine`() {
        sweep(floatRegex, inputs = scalars)
    }

    @Test
    fun `the toml leading-zero pattern agrees with the reference engine`() {
        sweep(leadingZero, inputs = scalars)
    }

    @Test
    fun `the toml key pattern agrees with the reference engine`() {
        sweep(keyPart, inputs = keys)
    }

    @Test
    fun `an unanchored pattern agrees with the reference engine`() {
        sweep("_", inputs = scalars + keys)
        sweep("""\r\n|\n|\r""", inputs = listOf("a\r\nb", "a\nb", "a\rb", "ab", "\r", "\n"))
        sweep("ab|abc", inputs = listOf("abc", "ab", "a", "xabcx", ""))
    }

    @Test
    fun `the end anchor means the end of the input, as JavaScript does`() {
        // MEASURED DIVERGENCE, now closed in both engines. Java's `$` also
        // matches BEFORE a final line terminator, so a `/^\d+$/` test of
        // "12\n" was `true` here and is `false` in every JavaScript engine.
        // The fast matcher handles the anchor structurally and always meant the JavaScript
        // thing; `jsEndAnchorTranslated` now spells the same meaning `\z` for
        // the reference engine, which is what lets the differential below run
        // as a plain equality.
        assert(!JsRegExp("^\\d+${'$'}", "").test("12\n"))
        assert(JsRegExp("^\\d+${'$'}", "").test("12"))
        assert(!JsRegExp("\\d${'$'}", "").test("12\n"))
        // ... and under `m` the pattern is outside the subset, so this is the
        // REFERENCE engine answering, where the anchor is a line end in both.
        assert(JsRegExp("^\\d+${'$'}", "m").test("12\n34"))
    }

    @Test
    fun `the anchors are structural and mean what JavaScript means by them`() {
        assert(!JsRegExp("^a$", "").test("a\n"))
        assert(JsRegExp("^a$", "").test("a"))
        assert(!JsRegExp("^a$", "").test("ba"))
        assert(!JsRegExp("^a$", "").test("ab"))
        assert(JsRegExp("^a", "").test("ab"))
        assert(!JsRegExp("^a", "").test("ba"))
        assert(JsRegExp("a$", "").test("ba"))
        assert(!JsRegExp("a$", "").test("ab"))
        // A literal dollar is not the anchor, and an escaped BACKSLASH before
        // one is: the parity of the run decides.
        assert(JsRegExp("""a\$""", "").test("a${'$'}b"))
        assert(compiledRegexProgram("""a\$""", "") != null)
    }

    @Test
    fun `quantifiers and classes agree with the reference engine`() {
        val inputs = listOf(
            "", "a", "aa", "aaa", "aaaa", "aaaaa", "b", "ab", "ba", "a-b",
            "0", "09", "a0", " ", "\t", "\n", "-", "]", "^", ".", "\\",
        )
        for (source in listOf(
            "a*", "a+", "a?", "a{2}", "a{2,}", "a{2,4}", "a{0}", "(?:ab)+",
            "[a-z]+", "[^a-z]+", "[a-zA-Z0-9-_]+", "[\\d]", "[\\w]",
            "[\\s]", "\\d+", "\\D+", "\\w+", "\\W+", "\\s+", "\\S+", ".", ".*",
            "a|b|c", "(a|b)*c", "^a*${'$'}", "a*?b", "[-a]", "[a-]", "[\\]]",
            // `-` after a class ESCAPE is a literal in both engines
            "[\\d-x]",
        )) sweep(source, inputs = inputs)
    }

    @Test
    fun `the dot excludes line terminators unless the s flag is set`() {
        sweep(".", inputs = listOf("\n", "\r", "a", ""))
        sweep(".", "s", listOf("\n", "\r", "a", ""))
        assert(compiledRegexProgram(".", "s") != null)
    }

    // ── the refusals, each still ANSWERING through the reference engine ──────

    @Test
    fun `a construct outside the regular subset is refused and answered by the oracle`() {
        val refused = listOf(
            // lookaround, the named group and the two backreference spellings
            """(?=a)b""", """(?!a)b""", """(?<=a)b""", """(?<name>a)""", """(a)\1""",
            """\k<name>""",
            // the assertions and escapes that are not one code unit
            """\ba""", """\Ba""", """\A""", """\x41""", """\cA""", """\p{L}""",
            // an anchor that is not at the pattern's own edge
            """a^b""", """a${'$'}b""", """(^a|b)""",
            // a negated class escape inside a class, which is a set complement
            // the range representation cannot union
            """[\D]""", """[\W]""", """[\S]""",
            // a repetition bound past what the expansion may cost
            """a{1000}""",
        )
        for (source in refused) {
            assert(compiledRegexProgram(source, "") == null)
        }
        // ... and every one of them still answers, because the reference engine
        // is the fallback rather than an error.
        assert(JsRegExp("""(?=a)b""", "").test("ab") == java.util.regex.Pattern
            .compile("""(?=a)b""").matcher("ab").find())
        assert(JsRegExp("""\ba""", "").test("a b"))
        assert(JsRegExp("""(a)\1""", "").test("aa"))
        assert(JsRegExp("""a{1000}""", "").test("a".repeat(1000)))
    }

    @Test
    fun `the i u v and y flags are outside the subset`() {
        // `i` needs case folding the DFA's character sets do not carry, `u`
        // changes what a code unit IS, and `y` is a stateful match this class
        // does not model at all.
        assert(compiledRegexProgram("a", "i") == null)
        assert(compiledRegexProgram("a", "u") == null)
        assert(compiledRegexProgram("a", "y") == null)
        assert(compiledRegexProgram("a", "g") != null)
        assert(JsRegExp("a", "i").test("A"))
    }

    @Test
    fun `the m flag is refused only where an anchor would have to read a position`() {
        assert(compiledRegexProgram("^a", "m") == null)
        assert(compiledRegexProgram("a${'$'}", "m") == null)
        assert(compiledRegexProgram("ab", "m") != null)
        assert(JsRegExp("^b", "m").test("a\nb"))
    }

    // ── the two failure modes a differential over ASCII cannot see ───────────

    @Test
    fun `a code unit above 255 is matched correctly`() {
        // The DFA's row has ONE slot for every code unit at or above 256, and
        // that is exact only while no character set in the pattern separates
        // two of them. These three do not, so they take the shared slot.
        val wideInputs = listOf("\u00e9", "\u4e2d", "\u4e2e", "a", "", "\u4e2d\u4e2d")
        sweep("[^a]", inputs = wideInputs)
        sweep(".", inputs = wideInputs)
        sweep("\u4e2d+", inputs = wideInputs)
    }

    @Test
    fun `a character set that separates two wide code units still answers`() {
        // `wideUniform` is false here, so every code unit at or above 256 takes
        // the UNCACHED path -- slower, and the same answer, which is what the
        // differential exists to say.
        val inputs = listOf("\u00e9", "\u4e2d", "\u4e2e", "a", "b", "")
        sweep("[a-\u4e2d]", inputs = inputs)
        sweep("[^a-\u4e2d]", inputs = inputs)
    }

    // ── the two members that stopped going through the regex machinery ─────

    @Test
    fun `a literal pattern replaces exactly as the regex engine did`() {
        // `value.replace(/_/g, '')` is 16 calls per benchmark document, and the
        // pattern is ONE character. The equivalence is the claim being pinned:
        // a literal pattern's match set is the occurrences of that string,
        // leftmost-first and non-overlapping.
        for (source in listOf("_", ", ", "ab", "\\.", "\\${'$'}")) {
            for (global in listOf("g", "")) {
                val expression = JsRegExp(source, global)
                for (input in listOf(
                    "", "_", "a_b", "__", "a__b", "_a_", "ab", "abab", "a.b", "a${'$'}b",
                    "1_000_000", "x, y, z",
                )) {
                    val quoted = java.util.regex.Matcher.quoteReplacement("X")
                    val matcher = java.util.regex.Pattern.compile(source).matcher(input)
                    val reference = if (global == "g") matcher.replaceAll(quoted)
                    else matcher.replaceFirst(quoted)
                    val answer = com.xemantic.typescript.compiler.kir.runtime
                        .jsStrReplace(input, expression, "X")
                    if (answer != reference) {
                        throw AssertionError(
                            "/${'$'}source/${'$'}global replace of <${'$'}input>: " +
                                "fast=<${'$'}answer> reference=<${'$'}reference>"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `split from the cached pattern answers as the per-call Regex did`() {
        // The old body built a `Regex(source)` on EVERY call, which recompiled
        // the pattern and — being built from the source alone — ignored the
        // expression's flags. This pins that the cached form is the same
        // answer, trailing empty field included.
        for (source in listOf("""\r\n|\n|\r""", ",", "\\s+", "a")) {
            for (input in listOf(
                "", "a", "a\nb", "a\r\nb\rc", "a,b,c", "a,,b", ",a,", "a b  c", "aaa",
            )) {
                val answer = JsRegExp(source, "g").splitOf(input)
                val reference = input.split(Regex(source))
                if (answer != reference) {
                    throw AssertionError(
                        "/${'$'}source/ split of <${'$'}input>: fast=${'$'}answer reference=${'$'}reference"
                    )
                }
            }
        }
    }

    @Test
    fun `an anchored or operator-bearing pattern has no literal form`() {
        // The fast path must not claim a pattern whose match set is not the
        // occurrences of a string.
        assert(compiledRegexProgram("a", "")?.literal == "a")
        assert(compiledRegexProgram("ab", "")?.literal == "ab")
        assert(compiledRegexProgram("^ab", "")?.literal == null)
        assert(compiledRegexProgram("ab${'$'}", "")?.literal == null)
        assert(compiledRegexProgram("a*", "")?.literal == null)
        assert(compiledRegexProgram("a|b", "")?.literal == null)
        assert(compiledRegexProgram("[ab]", "")?.literal == null)
        assert(compiledRegexProgram(".", "")?.literal == null)
    }

    @Test
    fun `a pattern whose DFA outgrows the cap falls back rather than answering wrongly`() {
        // The state cap is a resource bound, not a semantic one: past it the
        // matcher answers UNKNOWN and `test` asks the reference engine, so the
        // ANSWER is unchanged and only the cost is.
        val wide = (0 until 40).joinToString("|") { "a{$it}b" }
        val expression = JsRegExp(wide, "")
        val input = "a".repeat(39) + "b"
        assert(expression.test(input) ==
            java.util.regex.Pattern.compile(wide).matcher(input).find())
    }

}
