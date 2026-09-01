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
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.RealLibFiles
import com.xemantic.typescript.compiler.computeParserFlags
import kotlin.test.Test

/**
 * (GATE.2) The permanent, hermetic half of the real-source invariant gate.
 *
 * ## What this is for
 *
 * `(BUG.2)` shipped for nine rounds behind a green suite, and the reason is not that
 * anyone was careless: **a hand-written fixture for a lexical API does not contain
 * what real source contains.** Nobody writes a substituting template into a
 * go-to-definition pin, so nobody's pin saw the token stream de-synchronise there.
 * The instrument that finds that class of defect has to assert INVARIANTS — see
 * [TokenIndexInvariants] — and it has to run over INPUT NOBODY WROTE FOR IT.
 *
 * ## The two corpora, and why both
 *
 * **(1) A deliberately adversarial shape corpus**, written here. It is cheap, it
 * names each shape, and a violation points straight at the construct. Its weakness
 * is exactly the one that let `(BUG.2)` live: it can only contain shapes somebody
 * thought of.
 *
 * **(2) The real TypeScript `lib.*.d.ts` sources**, `RealLibFiles.files` — 3.7 MB of
 * TypeScript written by the TypeScript team for their own purposes, embedded in this
 * repo since the real-lib migration and therefore present wherever this build is,
 * with no new files, no fetch and no third-party tree to vendor. That is the whole
 * argument for choosing it over checking a copy of tsc's own `src/compiler` in:
 * hermeticity without a licensing question. Its weakness is the mirror of the shape
 * corpus's — a declaration file has no regular expressions and no JSX — which is why
 * neither corpus is enough alone.
 *
 * **(3) The corpus this gate was actually developed against is neither**, because it
 * is a local artifact: `build/bench/tsc-project-*`, tsc's own 78 sources, is where
 * `(BUG.2)` showed and where this round's three defects showed. A test reading it
 * would pass quietly on a fresh checkout and in CI, which is the failure mode this
 * repo has paid for twice, so it lives in `RealSourceTokenGateMain` behind
 * `scripts/round920-token-gate.sh` — which REFUSES rather than skips. Measured
 * there, over all eight profiles: **1,327 files, 101,287,620 characters, 11,299,274
 * tokens, 3,936,158 identifiers, zero violations.**
 */
class TokenIndexGateTest {

    /**
     * One named source, checked, reported as a list of human-readable violations.
     *
     * A list rather than a boolean because the power-assert diagram renders it: a
     * failure prints which rule broke, at which offset, and the text there, which is
     * the difference between a pin that says "something is wrong" and one that says
     * what.
     */
    private fun violationsOf(fileName: String, source: String): List<String> {
        val flags = computeParserFlags(fileName, source, CompilerOptions())
        return TokenIndexInvariants.check(source, fileName, flags).violations.map { it.toString() }
    }

    // --- corpus 1: the shapes a context-free scan can get wrong --------------------

    /**
     * Every lexeme whose extent depends on grammar context, and every neighbour of
     * one, in a single file — so a de-synchronisation anywhere in it is visible at
     * every identifier after it.
     *
     * The three that have actually cost this repo a defect are first: a substituting
     * template (`(BUG.2)`, round 919), a regular expression carrying a backtick
     * (`(GATE.2)`, this round — the shape is tsc's own `escapedCharsMap` regexes),
     * and a regular expression carrying quotes. The rest are the neighbours the same
     * reasoning reaches: division that is not a regex, `>>` that is two closing angle
     * brackets, a unicode escape inside a name, comments and strings holding
     * characters that open something.
     */
    private val hardShapes = """
        const substituted = `${'$'}{first}|${'$'}{second}`;
        const nested = `outer ${'$'}{`inner ${'$'}{first}`} done`;
        const tagged = String.raw`a${'$'}{first}b`;
        const backtickInRegExp = /\r\n|[\\`\u0000-\u0009\u2028\u2029]/g;
        const quotesInRegExp = /["'`]/g;
        const slashInClass = /[/]|\/{2,}/gu;
        const commentLike = /\/\*.*?\*\//gs;
        const dividedNotRegExp = first / second / third;
        const dividedThenRegExp = (first / second) / third;
        const afterReturn = () => { return /abc/.test("x"); };
        const nestedTypeArgs: Array<Array<Map<string, number>>> = [];
        const shifted = first >> second >>> third;
        const escapedName = 'ok';
        const stringWithQuote = "he said \"hi\" and `that`";
        const stringWithBackslash = 'a\\b';
        const bigints = 1n + 0x1fn;
        const separated = 1_000_000;
        const optional = first?.second?.[third]?.(fourth);
        const nullish = first ?? second;
        // a comment with a ` backtick and a ' quote and a "double" and /a regex/
        /* a block comment with ` and ' and " and /regex/ and ${'$'}{substitution} */
        const arrowParam = value => value + 1;
        const asyncArrow = async value => value;
        function indexed(map: { [key: string]: number }) { return map; }
        function caught() { try { indexed({}); } catch (err) { return err; } }
        declare global { interface Window { xtsc: string } }
        const satisfied = { a: 1 } satisfies Record<string, number>;
        const asserted = first as const;
        enum Colour { Red = 1, Green = 2 }
        namespace Outer { export const inner = 1; }
        const afterEverything = arrowParam;
    """.trimIndent() + "\n"

    /**
     * The JSX half, which needs its own file because the extension is what puts the
     * parser into JSX mode.
     *
     * JSX text is the second lexeme this round taught the index to take from the
     * parse, and for the same reason as a regular expression: it may hold an
     * apostrophe or a backtick, which a context-free scan turns into a string or a
     * template literal running past the end of the element.
     */
    private val jsxShapes = """
        declare const React: unknown;
        const withApostrophe = <p>it's fine and it`s fine and "quoted"</p>;
        const nestedElements = <div className="a"><span>text {withApostrophe} more</span></div>;
        const selfClosing = <br />;
        const fragment = <>a ' b ` c</>;
        const attributeStrings = <p title='single' data-x="double">body</p>;
        const afterJsx = 1;
        const readsAfterJsx = afterJsx;
    """.trimIndent() + "\n"

    /** Line terminators, which only the position rules see. */
    private val terminators =
        "const crlf = 1;\r\nconst lf = 2;\nconst cr = 3;\rconst afterAll = crlf + lf + cr;\n"

    @Test
    fun `a file ending in its final identifier with no trailing newline passes every rule`() {
        // (API.18)'s population, admitted to the gate the day it was healed:
        // before the owner-chain fix this shape violated
        // IDENTIFIER_SPAN_IS_EXACT, IDENTIFIER_IS_REACHABLE and PATH_NESTS at
        // once - the whole containing chain's raw ends are EXACT (the EOF
        // lookahead is zero-width), and the strictly-below snap lost the final
        // token from every span.
        val text = "const abc = 1;\nconst tail = abc"
        assert(violationsOf("/proj/src/eof.ts", text).isEmpty())
    }

    @Test
    fun `the index holds on every lexical shape a context-free scan can get wrong`() {
        assert(violationsOf("/proj/src/shapes.ts", hardShapes).isEmpty())
    }

    @Test
    fun `the index holds on JSX`() {
        assert(violationsOf("/proj/src/shapes.tsx", jsxShapes).isEmpty())
    }

    @Test
    fun `the index holds on every line terminator`() {
        assert(violationsOf("/proj/src/terminators.ts", terminators).isEmpty())
    }

    // --- corpus 2: real TypeScript nobody wrote for this test ----------------------

    /**
     * Every `lib.*.d.ts` this compiler embeds — 3.7 MB of real TypeScript, the
     * largest single file 2.3 MB.
     *
     * Asserted as one list over all of them rather than as a test per file, because
     * the population is generated and a per-file enumeration would have to be
     * regenerated with it. The count is asserted first: a corpus that silently became
     * empty would make every rule below vacuous, which is round 849's lesson and the
     * one failure mode a sweep cannot see from the inside.
     */
    @Test
    fun `the index holds on the real TypeScript lib sources`() {
        val libs = RealLibFiles.files
        assert(libs.size > 40)
        val violations = ArrayList<String>()
        var characters = 0
        for ((key, source) in libs) {
            characters += source.length
            violations.addAll(violationsOf("lib.$key.d.ts", source))
            // One broken file reports its whole shape; a hundred broken files would
            // build a list nothing can print.
            if (violations.size > 40) break
        }
        assert(characters > 2_000_000)
        assert(violations.isEmpty())
    }

    // --- the three defects this round found, pinned one at a time ------------------

    /**
     * `(GATE.2)` defect 1, measured in tsc's own `utilities.ts`: a backtick inside a
     * regular expression opened a `NoSubstitutionTemplateLiteral` that ran to the
     * next backtick anywhere in the file — a 25,761-character token that swallowed
     * the twelve identifiers after it.
     *
     * Pinned on what follows the regex rather than on the regex itself, because the
     * failure is not local: the token stream is wrong from there to end of file, and
     * an assertion standing on the regex passes on the broken scan.
     */
    @Test
    fun `a backtick inside a regular expression does not swallow the rest of the file`() {
        val source = """
            const escape = /[\\`]/g;
            const template = `a template later in the file`;
            const afterRegExp = 1;
            const readsAfter = afterRegExp;
        """.trimIndent() + "\n"
        val project = Project.open(
            "/proj",
            InMemoryVfs(mapOf("/proj/tsconfig.json" to CONFIG, "/proj/src/a.ts" to source)),
        )
        val at = source.lastIndexOf("afterRegExp")
        val info = project.nodeInfoAt("/proj/src/a.ts", at)
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(source.substring(info.start, info.end) == "afterRegExp")
        val definitions = project.definitionsAt("/proj/src/a.ts", at)
        assert(definitions.size == 1)
        assert(definitions[0].start == source.indexOf("afterRegExp"))
    }

    /**
     * `(GATE.2)` defect 2: a parenthesis-less arrow's parameter, an index
     * signature's parameter and a `catch` clause's variable were all built with the
     * default `[0, 0)` span, so no descent could enter them and every caret on one
     * answered about the enclosing construct instead. **328 sites in tsc's 78
     * compiler sources**, i.e. the single most common wrong answer the API had.
     */
    @Test
    fun `a caret on a parenthesis-less arrow parameter descends to the parameter`() {
        val source = """
            const increment = value => value + 1;
            const result = increment(1);
        """.trimIndent() + "\n"
        val project = Project.open(
            "/proj",
            InMemoryVfs(mapOf("/proj/tsconfig.json" to CONFIG, "/proj/src/a.ts" to source)),
        )
        val declaration = source.indexOf("value")
        val info = project.nodeInfoAt("/proj/src/a.ts", declaration)
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(info.ancestorKinds.first() == "Parameter")
        assert(source.substring(info.start, info.end) == "value")
        // And the use resolves back to it, which is what a host actually asks.
        val use = source.indexOf("value", declaration + 1)
        val definitions = project.definitionsAt("/proj/src/a.ts", use)
        assert(definitions.size == 1)
        assert(definitions[0].start == declaration)
    }

    /** The same defect at its other two sites. */
    @Test
    fun `a caret on an index signature parameter and on a catch variable descends to it`() {
        val source = """
            interface Bag { [key: string]: number }
            function run(): unknown {
                try {
                    return 1;
                } catch (err) {
                    return err;
                }
            }
        """.trimIndent() + "\n"
        val project = Project.open(
            "/proj",
            InMemoryVfs(mapOf("/proj/tsconfig.json" to CONFIG, "/proj/src/a.ts" to source)),
        )
        val key = project.nodeInfoAt("/proj/src/a.ts", source.indexOf("key"))
        assert(key != null)
        assert(key.kind == "Identifier")
        assert(source.substring(key.start, key.end) == "key")
        val err = project.nodeInfoAt("/proj/src/a.ts", source.indexOf("err"))
        assert(err != null)
        assert(err.kind == "Identifier")
        assert(source.substring(err.start, err.end) == "err")
    }

    /**
     * `(GATE.2)` defect 3: the `global` of `declare global` was the one node in the
     * parser carrying an EXACT end where every other carries the end of the following
     * token, so snapping it back to the token stream produced an empty span.
     */
    @Test
    fun `a caret on the name of declare global descends to the identifier`() {
        val source = """
            declare global {
                interface Window { xtsc: string }
            }
            export const marker = 1;
        """.trimIndent() + "\n"
        val info = Project.open(
            "/proj",
            InMemoryVfs(mapOf("/proj/tsconfig.json" to CONFIG, "/proj/src/a.ts" to source)),
        ).nodeInfoAt("/proj/src/a.ts", source.indexOf("global"))
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(source.substring(info.start, info.end) == "global")
    }

    /**
     * The negative control the gate needs, and the reason it is here: a checker that
     * cannot see a broken index is indistinguishable from one whose subject is
     * correct (round 849). A file whose text was truncated mid-template is genuinely
     * de-synchronised, and every rule about it must fire.
     */
    @Test
    fun `the gate reports a violation when the index really is de-synchronised`() {
        val flags = computeParserFlags("/proj/src/a.ts", hardShapes, CompilerOptions())
        // The OFF arm: the same source, indexed the way it was indexed before this
        // round — with no contextual lexeme taken from the parse. The backtick in
        // `backtickInRegExp` then opens a template literal running to the next
        // backtick, and the identifiers it swallows are the ones the rules name.
        val broken = TokenIndexInvariants.check(hardShapes, "/proj/src/a.ts", flags, false)
        assert(broken.violations.isNotEmpty())
        assert(broken.violations.any { it.rule == TokenIndexInvariants.Rule.IDENTIFIER_IS_A_TOKEN })
        // Same input, same rules, the oracle back on.
        val fixed = TokenIndexInvariants.check(hardShapes, "/proj/src/a.ts", flags)
        assert(fixed.violations.isEmpty())
        assert(fixed.identifiers == broken.identifiers)
    }

    private companion object {
        const val CONFIG =
            """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
                """ "include": ["src/**/*.ts", "src/**/*.tsx"] }"""
    }
}
