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
import com.xemantic.typescript.compiler.Vfs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [Project.nodeInfoAt]: that it finds the narrowest node genuinely containing an
 * offset, that it reads the OVERLAY, and that it is not fooled by the two ways
 * this compiler's spans lie.
 *
 * ## What most of these tests are really about
 *
 * `Node.end` in this compiler is the end of the token FOLLOWING the node
 * (`NodeSpanSemanticsTest` measures it), so sibling spans overlap and a lookup
 * written as "the narrowest node whose `[pos, end)` contains the offset" answers
 * the PREVIOUS node for a caret on an operator or on the next statement's first
 * keyword. Two tests below stand exactly on those positions — `a caret on the
 * equals sign …` and `a caret on a following statement's first keyword …` — and
 * they are the pins that discriminate a correct implementation from the naive one.
 * Both are written against offsets DERIVED from the fixture text, so they say what
 * they mean rather than pinning arithmetic.
 *
 * ## Offsets, not hardcoded numbers
 *
 * Every offset here comes from an `indexOf` over the same string the project is
 * built from. A hardcoded offset in a position test pins the test's own arithmetic
 * and passes for a lookup that ignores its argument.
 */
class ProjectNodeAtTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    /** Two statements, so the second one's first token follows the first one's `;`. */
    private val pair = "const abc = 1;\nlet d = abc;\n"

    private fun projectWith(
        source: String = pair,
        fileName: String = "/proj/src/a.ts",
        configSource: String = config,
    ): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to configSource,
                fileName to source,
            ),
        ),
    )

    private fun open(vfs: Vfs): Project = Project.open("/proj", vfs)

    // --- the ordinary case -----------------------------------------------------

    @Test
    fun `a caret inside an identifier resolves to that identifier`() {
        val project = projectWith()
        val abc = pair.indexOf("abc")
        val info = project.nodeInfoAt("/proj/src/a.ts", abc + 1)
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(info.start == abc)
        // THE end pin: the identifier is three characters. Its `Node.end` is 11 — past
        // the `=` — so this is the number a raw-span lookup cannot produce.
        assert(info.end == abc + 3)
        // Out to the file, parent first.
        assert(info.ancestorKinds.last() == "SourceFile")
        assert(info.ancestorKinds.first() == "VariableDeclaration")
    }

    @Test
    fun `the caret at the very first character of a node is inside it`() {
        val project = projectWith()
        val abc = pair.indexOf("abc")
        val info = project.nodeInfoAt("/proj/src/a.ts", abc)
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(info.start == abc)
    }

    @Test
    fun `the caret immediately after a node is outside it - the spans are half-open`() {
        // The documented convention, pinned because it is the one a host has to know
        // to build touch semantics on top: `abc|` is not on `abc`. A caller wanting
        // tsserver's behaviour asks again at `offset - 1`, which the second half of
        // this test shows still works.
        val project = projectWith()
        val abc = pair.indexOf("abc")
        val after = project.nodeInfoAt("/proj/src/a.ts", abc + 3)
        assert(after != null)
        assert(after.kind != "Identifier")
        val stepBack = project.nodeInfoAt("/proj/src/a.ts", abc + 3 - 1)
        assert(stepBack != null)
        assert(stepBack.kind == "Identifier")
    }

    @Test
    fun `the internal node lookup and the descriptor describe the same node`() {
        val project = projectWith()
        val abc = pair.indexOf("abc")
        val info = project.nodeInfoAt("/proj/src/a.ts", abc + 1)
        val node = project.nodeAt("/proj/src/a.ts", abc + 1)
        assert(info != null)
        assert(node != null)
        assert(node.pos == info.start)
        assert(node.kind.name == info.kind)
    }

    // --- the two spans-lie traps ------------------------------------------------

    @Test
    fun `a caret on the equals sign between a name and its initializer is not on the name`() {
        // TRAP 1. `abc` reads `[6, 11)` because its `end` is the end of the `=` that
        // follows it, so a raw-span lookup answers `Identifier` here. The `=` belongs
        // to no child of the declaration, which is also why bounding a node's end by
        // its NEXT SIBLING's `pos` does not fix this case: the initializer `1` starts
        // at 12, past the `=` at 10.
        val project = projectWith()
        val equals = pair.indexOf('=')
        val info = project.nodeInfoAt("/proj/src/a.ts", equals)
        assert(info != null)
        assert(info.kind == "VariableDeclaration")
        assert(info.kind != "Identifier")
        // ... and the declaration really does start at the name and stop at the `1`,
        // rather than running past its own `;`.
        assert(info.start == pair.indexOf("abc"))
        assert(info.end == pair.indexOf('1') + 1)
    }

    @Test
    fun `a caret on a following statement's first keyword is not on the statement before it`() {
        // TRAP 2. The first statement's `end` is past `let`, so a raw-span lookup
        // descends into statement ONE for a caret on statement TWO and answers a node
        // starting at offset 0.
        val project = projectWith()
        val letOffset = pair.indexOf("let")
        val info = project.nodeInfoAt("/proj/src/a.ts", letOffset)
        assert(info != null)
        assert(info.start == letOffset)
        assert(info.ancestorKinds.last() == "SourceFile")
    }

    @Test
    fun `a statement's own semicolon is inside the statement and outside its declaration list`() {
        // The other side of the same arithmetic: the `;` is the statement's last
        // token, so the statement's real end is past it while the declaration list's
        // is not.
        val project = projectWith()
        val semicolon = pair.indexOf(';')
        val info = project.nodeInfoAt("/proj/src/a.ts", semicolon)
        assert(info != null)
        assert(info.kind == "VariableStatement")
        assert(info.start == 0)
        assert(info.end == semicolon + 1)
    }

    // --- trivia -----------------------------------------------------------------

    /** A blank line and a comment between two statements — every flavour of trivia. */
    private val spaced = "const x = 1;\n\n// a comment\nlet y = 2;\n"

    @Test
    fun `a caret in the blank line between two statements is in the enclosing file`() {
        // Documented behaviour: a node's span stops at its own last token, so trivia
        // is inside no statement and the innermost node that ENCLOSES it answers.
        val project = projectWith(spaced)
        val blank = spaced.indexOf("\n\n") + 1
        val info = project.nodeInfoAt("/proj/src/a.ts", blank)
        assert(info != null)
        assert(info.kind == "SourceFile")
        assert(info.ancestorKinds.isEmpty())
    }

    @Test
    fun `a caret inside a comment is in the enclosing file rather than in a statement`() {
        // Leading comments are carried BESIDE the following node, below its `pos`
        // (`NodeSpanSemanticsTest` finding 1), so no containment walk can reach them.
        val project = projectWith(spaced)
        val comment = spaced.indexOf("// a comment") + 3
        val info = project.nodeInfoAt("/proj/src/a.ts", comment)
        assert(info != null)
        assert(info.kind == "SourceFile")
    }

    // --- the negative answers ---------------------------------------------------

    @Test
    fun `an offset past the end of the file has no node`() {
        val project = projectWith()
        assert(project.nodeInfoAt("/proj/src/a.ts", pair.length + 5) == null)
    }

    @Test
    fun `the caret after the last character of a file has no node`() {
        // Not an oversight: the source file's span is half-open like every other, so
        // the end-of-file caret is outside it. Stated so a host knows to expect null
        // for a legitimate cursor position rather than reading it as a failure.
        val project = projectWith()
        assert(project.nodeInfoAt("/proj/src/a.ts", pair.length) == null)
    }

    @Test
    fun `a negative offset has no node and does not throw`() {
        val project = projectWith()
        assert(project.nodeInfoAt("/proj/src/a.ts", -1) == null)
    }

    @Test
    fun `an unknown file has no node`() {
        val project = projectWith()
        assert(project.nodeInfoAt("/proj/src/nowhere.ts", 0) == null)
    }

    @Test
    fun `a file whose parse yields no node at the offset answers null`() {
        // An empty file parses to a source file spanning nothing, so every offset is
        // outside every span.
        val project = projectWith(source = "")
        assert(project.nodeInfoAt("/proj/src/a.ts", 0) == null)
    }

    // --- the overlay, and the cache that must not outlive it --------------------

    @Test
    fun `an edit is what the query sees and the pre-edit query gives the other answer`() {
        // The pin that would otherwise fail silently, in BOTH of its halves: a lookup
        // reading the backing store answers about the wrong text, and a lookup reading
        // a cached parse of the previous keystroke answers about the wrong text in a
        // way only a query-edit-query sequence can see. The pre-edit query is the
        // negative control AND the thing that fills the cache.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                "/proj/src/a.ts" to "const a = 1;\n",
            ),
        )
        val project = open(vfs)
        val initializer = "const a = 1;\n".indexOf('1')
        val before = project.nodeInfoAt("/proj/src/a.ts", initializer)
        assert(before != null)
        assert(before.kind == "NumericLiteral")

        // Same length, so the offset still names the initializer.
        project.updateFile("/proj/src/a.ts", "const a = \"\";\n")
        val after = project.nodeInfoAt("/proj/src/a.ts", initializer)
        assert(after != null)
        assert(after.kind == "StringLiteral")
        // The disk is untouched, which is what makes the answer above the buffer's.
        assert(vfs.readText("/proj/src/a.ts") == "const a = 1;\n")
    }

    @Test
    fun `a deleted file has no node even though it is still on disk`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                "/proj/src/a.ts" to "const a = 1;\n",
            ),
        )
        val project = open(vfs)
        assert(project.nodeInfoAt("/proj/src/a.ts", 6) != null)
        project.deleteFile("/proj/src/a.ts")
        assert(project.nodeInfoAt("/proj/src/a.ts", 6) == null)
    }

    @Test
    fun `a closed project refuses a node query`() {
        val project = projectWith()
        project.close()
        assertFailsWith<IllegalStateException> {
            project.nodeInfoAt("/proj/src/a.ts", 0)
        }
    }

    // --- the parser flags -------------------------------------------------------

    /**
     * A `.tsx` file really does parse as JSX.
     *
     * NOT a pin on `computeParserFlags` being consulted, and deliberately labelled
     * so: `Parser.isJsxFile` is `forceJsx || fileName.endsWith(".tsx") ||
     * fileName.endsWith(".jsx")`, i.e. a `.tsx` file parses JSX from its EXTENSION
     * whatever flags it is given. The flag `computeParserFlags` computes for a
     * `.tsx` file, `needsJsxFlag`, drives a diagnostic rather than the grammar. So
     * this pin says the extension reaches the parser; the pin that the OPTIONS reach
     * it is the `await` one below.
     */
    @Test
    fun `a tsx file parses as JSX`() {
        val source = "const e = <div>hi</div>;\n"
        val project = projectWith(source, fileName = "/proj/src/c.tsx")
        val info = project.nodeInfoAt("/proj/src/c.tsx", source.indexOf("div") + 1)
        assert(info != null)
        assert(info.ancestorKinds.any { it.startsWith("Jsx") })
    }

    /**
     * The one shape whose GRAMMAR an option decides: JSX inside a plain `.js` file.
     *
     * `computeParserFlags` sets `forceJsx` for a `.js`/`.cjs`/`.mjs` file exactly
     * when `jsx` is configured or `allowJs` is on, and `Parser.isJsxFile` is
     * `forceJsx || <the .tsx/.jsx extensions>` — so with `allowJs` this text is a
     * JSX element and without it the `<` is an operator and the tree is something
     * else entirely. Nothing about the file's NAME or CONTENT can decide it, which
     * is what makes the pair below a pin on the project's options reaching the
     * parser rather than on anything incidental.
     *
     * (`topLevelAwait`, the other option-derived flag, was tried first and does NOT
     * discriminate: our parser produces an `AwaitExpression` for a top-level `await`
     * with the flag off too.)
     */
    private val jsxInJs = "const e = <div>hi</div>;\n"

    private fun configWithAllowJs(allowJs: Boolean) =
        """{ "compilerOptions": { "target": "es2020", "allowJs": $allowJs } }"""

    @Test
    fun `the project's own compiler options decide how a file is parsed`() {
        val project = projectWith(
            jsxInJs,
            fileName = "/proj/src/e.js",
            configSource = configWithAllowJs(true),
        )
        val info = project.nodeInfoAt("/proj/src/e.js", jsxInJs.indexOf("div") + 1)
        assert(info != null)
        assert(info.ancestorKinds.any { it.startsWith("Jsx") })
    }

    @Test
    fun `negative control - without allowJs the same js file is not parsed as JSX`() {
        // The other arm. Without it, "there is a Jsx ancestor" could hold for a
        // parser built on defaults just as well.
        val project = projectWith(
            jsxInJs,
            fileName = "/proj/src/e.js",
            configSource = configWithAllowJs(false),
        )
        val info = project.nodeInfoAt("/proj/src/e.js", jsxInJs.indexOf("div") + 1)
        assert(info != null)
        assert(info.ancestorKinds.none { it.startsWith("Jsx") })
        assert(!info.kind.startsWith("Jsx"))
    }

    @Test
    fun `editing the tsconfig changes how a file is parsed`() {
        // The invalidation a per-path drop cannot do: a parse is option-dependent, so
        // a config edit has to drop EVERY cached parse, not the config's own — and the
        // pre-edit query is what fills the cache that would otherwise answer stale.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to configWithAllowJs(false),
                "/proj/src/e.js" to jsxInJs,
            ),
        )
        val project = open(vfs)
        val offset = jsxInJs.indexOf("div") + 1
        val before = project.nodeInfoAt("/proj/src/e.js", offset)
        assert(before != null)
        assert(before.ancestorKinds.none { it.startsWith("Jsx") })

        project.updateFile("/proj/tsconfig.json", configWithAllowJs(true))
        val after = project.nodeInfoAt("/proj/src/e.js", offset)
        assert(after != null)
        assert(after.ancestorKinds.any { it.startsWith("Jsx") })
    }
}
