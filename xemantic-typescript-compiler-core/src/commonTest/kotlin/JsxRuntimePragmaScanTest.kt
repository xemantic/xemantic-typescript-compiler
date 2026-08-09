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
 * (WARM.10) round 863 — the B333 jsxRuntime pragma scan.
 *
 * `Transformer.transform` ran `jsxRuntimePragmaRegex` over the full text of
 * every transformed file under NO gate; measured warm on the compiler profile
 * it is **44.1 ms = 0.55% of an emit rebuild**, finding 0 pragmas in 9,977,097
 * characters, because the pattern's leading literal is two characters and
 * `java.util.regex`'s `BnM.optimize` refuses anything under four.
 * [scanJsxRuntimePragmas] replaces the matcher with an exact equivalent.
 *
 * What this class pins, and why each pin can FAIL:
 *
 *  - the DIFFERENTIAL against the pattern that is still live as the
 *    specification, over a cross-product battery including every near-miss —
 *    a scanner that changed any verdict reddens it;
 *  - that the battery is not vacuous on either side (round 753: an ablation
 *    that counts nothing tested nothing);
 *  - the POSITIVE control end to end — a file that DOES carry the pragma must
 *    still emit the classic runtime — and its complement, which is what
 *    separates "the scan works" from "the scan always returns nothing".
 *
 * The emit pins matter more here than a diagnostic pin would: this value
 * reaches no diagnostic at all, only the shape of the emitted JS, so the
 * `--noEmit --listAll` 8-profile grid is structurally blind to a mistake in it.
 */
class JsxRuntimePragmaScanTest {

    // ---------------------------------------------------------------- scanner

    @Test
    fun `the scan reports each pragma keyword in source order`() {
        val text = "/* @jsxRuntime classic */\nconst a = 1;\n/* @jsxRuntime automatic */\n"
        assert(scanJsxRuntimePragmas(text) == listOf("classic", "automatic"))
    }

    @Test
    fun `runs of whitespace around the tag and the keyword are admitted`() {
        val text = "/*\t \n @jsxRuntime \t\n automatic \r\n */"
        assert(scanJsxRuntimePragmas(text) == listOf("automatic"))
    }

    @Test
    fun `negative control - shapes the pattern does not admit report nothing`() {
        // a doc comment opener leaves a star where the pattern needs whitespace
        assert(scanJsxRuntimePragmas("/** @jsxRuntime classic */").isEmpty())
        // no whitespace run after the tag
        assert(scanJsxRuntimePragmas("/*@jsxRuntimeclassic */").isEmpty())
        assert(scanJsxRuntimePragmas("/* @jsxRuntimeclassic */").isEmpty())
        // an unknown keyword
        assert(scanJsxRuntimePragmas("/* @jsxRuntime modern */").isEmpty())
        // a line comment is not a block comment
        assert(scanJsxRuntimePragmas("// @jsxRuntime classic\n").isEmpty())
        // never closed
        assert(scanJsxRuntimePragmas("/* @jsxRuntime classic ").isEmpty())
        // the tag alone
        assert(scanJsxRuntimePragmas("@jsxRuntime classic */").isEmpty())
    }

    /**
     * The one place the non-overlap rule is not vacuous: the second candidate's
     * opening slash-star reuses the first match's closing slash, so `findAll`
     * — which resumes at the previous match's END — never sees it, and the
     * scanner must not either. A scanner written without a `lastEnd` cursor
     * answers `[classic, automatic]` here, which would flip the file's runtime.
     */
    @Test
    fun `a candidate overlapping the previous match is skipped exactly as findAll skips it`() {
        val text = "/* @jsxRuntime classic */* @jsxRuntime automatic */"
        assert(referenceScan(text) == listOf("classic"))
        assert(scanJsxRuntimePragmas(text) == listOf("classic"))
    }

    // ------------------------------------------------------------ differential

    /** The oracle: what [jsxRuntimePragmaRegex] itself reports for [text]. */
    private fun referenceScan(text: String): List<String> =
        jsxRuntimePragmaRegex.findAll(text).map { it.groupValues[1] }.toList()

    /**
     * Every string the differential below compares, built as a cross-product so
     * the near-misses outnumber the hits — the interesting verdicts are the
     * refusals.
     */
    private fun battery(): List<String> {
        val gaps = listOf("", " ", "\t", " \t ", "\n", "\u000B", "\u000C", "\r\n", "\u00A0")
        val opens = listOf("/*", "/**", "//", "*", "x/*", " /*")
        val tags = listOf("@jsxRuntime", "@jsxruntime", "@jsxRuntimeX", "jsxRuntime")
        val keywords = listOf("classic", "automatic", "classical", "auto", "")
        val closes = listOf("*/", "*", "/", "**/", "")
        val bodies = ArrayList<String>()
        for (open in opens) for (g1 in gaps) for (tag in tags) for (g2 in gaps) {
            for (kw in keywords) for (g3 in listOf("", " ", "\n")) for (close in closes) {
                bodies.add("$open$g1$tag$g2$kw$g3$close")
            }
        }
        val contexts = listOf(
            { s: String -> s },
            { s: String -> "const q = 1;\n$s\nconst r = 2;\n" },
            { s: String -> "$s$s" },
            { s: String -> "/* @jsxRuntime classic */$s" },
        )
        return bodies.flatMap { b -> contexts.map { it(b) } }
    }

    /**
     * THE DISCRIMINATING PIN. The rewrite replaced the matcher, not the
     * semantics, so it must agree with the pattern on every shape — including
     * the ones an `indexOf` pre-filter or a `Char.isWhitespace()` predicate
     * would have decided differently.
     */
    @Test
    fun `the hand-written scan agrees with the reference pattern on the whole battery`() {
        val cases = battery()
        assert(cases.size > 10000)
        val mismatches = cases.mapNotNull { text ->
            val mine = scanJsxRuntimePragmas(text)
            val reference = referenceScan(text)
            if (mine == reference) null else "${text.replace("\n", "\\n")} -> $mine != $reference"
        }
        // take(5): the power-assert diagram renders every captured subexpression,
        // and a broken scanner mismatches on thousands of cases at once.
        val firstMismatches = mismatches.take(5)
        assert(firstMismatches.isEmpty())
    }

    /**
     * The battery is evidence only while it MATCHES sometimes — an all-empty
     * oracle would make the agreement pin vacuously green.
     */
    @Test
    fun `the battery exercises both verdicts - it is not vacuously empty on either side`() {
        val cases = battery()
        val matching = cases.count { referenceScan(it).isNotEmpty() }
        assert(matching > 100)
        assert(matching < cases.size)
    }

    /**
     * `Char.isWhitespace()` is WIDER than the regex `\s` class — it also accepts
     * `Character.isSpaceChar`, i.e. NBSP and friends. A scanner written with it
     * would accept text the specification rejects, and nothing else in this
     * class would necessarily notice, so it gets its own pin.
     */
    @Test
    fun `negative control - a non-breaking space is whitespace to Kotlin but not to the pattern`() {
        val nbsp = '\u00A0'
        val text = "/*" + nbsp + "@jsxRuntime" + nbsp + "classic" + nbsp + "*/"
        assert(nbsp.isWhitespace())
        assert(referenceScan(text).isEmpty())
        assert(scanJsxRuntimePragmas(text).isEmpty())
    }

    // ----------------------------------------------------------- end to end

    private fun emit(source: String, fileName: String = "a.tsx"): String =
        TypeScriptCompiler().compile(source.trimIndent(), fileName)
            .jsOutputs.joinToString("\n") { it.second }

    /**
     * POSITIVE CONTROL. Under `@jsx: react-jsx` the automatic runtime is the
     * default; the pragma overrides it to classic, which is visible in the
     * emitted call. A scanner that found nothing would leave this file on the
     * automatic runtime and no diagnostic anywhere would say so.
     */
    @Test
    fun `positive control - a classic pragma overrides the automatic runtime option`() {
        val js = emit(
            """
            // @jsx: react-jsx
            /* @jsxRuntime classic */
            const a = <div/>;
            """,
        )
        assert("React.createElement" in js)
    }

    /** The mirror: a `react` (classic) base flipped to automatic by the pragma. */
    @Test
    fun `positive control - an automatic pragma overrides the classic runtime option`() {
        val js = emit(
            """
            // @jsx: react
            /* @jsxRuntime automatic */
            const a = <div/>;
            """,
        )
        assert("jsx_runtime_1" in js)
        assert("React.createElement" !in js)
    }

    @Test
    fun `negative control - without the pragma the same file keeps the automatic runtime`() {
        val js = emit(
            """
            // @jsx: react-jsx
            const a = <div/>;
            """,
        )
        assert("React.createElement" !in js)
    }

    /** …and the LAST pragma in the file wins, as tsc collects them in order. */
    @Test
    fun `the last pragma in the file wins`() {
        val js = emit(
            """
            // @jsx: react
            /* @jsxRuntime automatic */
            /* @jsxRuntime classic */
            const a = <div/>;
            """,
        )
        assert("React.createElement" in js)
        assert("jsx_runtime_1" !in js)
    }
}
