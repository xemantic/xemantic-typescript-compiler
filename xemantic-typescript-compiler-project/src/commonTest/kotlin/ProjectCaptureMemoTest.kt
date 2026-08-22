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

/**
 * (INC.12) The capture memo — `Project.captures`.
 *
 * Every caret-scoped query is one capture build and until this existed there was no
 * reuse of any kind between them. What is pinned here is one property and its
 * staleness obligation:
 *
 * * an identical request against an UNCHANGED project is answered without building,
 *   which makes hover-then-navigate one build and document highlights free at every
 *   caret after the first;
 * * an EDIT drops it — including an edit to a file the request never names, and
 *   including an edit that adds a file to the program, which is the direction where
 *   a stale hit is a MISSING FILE rather than a wrong type.
 *
 * ## The receipt is a COUNT
 *
 * A build is not observable from its result — two builds of one state return equal
 * values — so "the memo was used" is asserted as reads of `tsconfig.json`, of which
 * `ProjectCompiler.build` performs exactly one and which nothing caches across builds
 * (CLAUDE.md round 914: a count of ALL Vfs reads is NOT a count of builds, because
 * some compiler cache warms across builds inside one JVM and takes a source read with
 * it). A timed assertion over a compile would be a coin flip.
 */
class ProjectCaptureMemoTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val a = "/proj/src/a.ts"
    private val b = "/proj/src/b.ts"

    private val source = """
        interface Shape { readonly p: string; }
        const o: Shape = { p: "x" };
        const first = o.p;
        const second = o.p;
    """.trimIndent()

    private fun projectWith(files: Map<String, String>): Pair<Project, CountingVfs> {
        val counting = CountingVfs(
            InMemoryVfs(mapOf("/proj/tsconfig.json" to config) + files),
        )
        val project = Project.open("/proj", counting)
        // The FIRST query in a project's life also builds this file's `SourceIndex`,
        // which needs the compiler options and therefore a build of its own. Warmed
        // here so every count below is a count of CAPTURE builds — otherwise the first
        // row of every test reads 2 and the memo's effect is buried in an artefact of
        // where the index came from.
        project.nodeInfoAt(files.keys.first(), 0)
        return project to counting
    }

    /** How many builds [block] performs — one `tsconfig.json` read each. */
    private fun buildsIn(counting: CountingVfs, block: () -> Unit): Int {
        val before = counting.readsOf("/proj/tsconfig.json")
        block()
        return counting.readsOf("/proj/tsconfig.json") - before
    }

    private fun offsetOf(text: String, needle: String, occurrence: Int = 0, plus: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at + plus
    }

    @Test
    fun `hover and go-to-definition at ONE caret are ONE build`() {
        val (project, counting) = projectWith(mapOf(a to source))
        val caret = offsetOf(source, "o.p", plus = 2)
        // Both answers are asserted, not just the counts: a memo that served an empty
        // result would satisfy a count-only pin while breaking both features.
        var hover: QuickInfo? = null
        var definitions: List<DefinitionLocation> = emptyList()
        assert(buildsIn(counting) { hover = project.quickInfoAt(a, caret) } == 1)
        assert(buildsIn(counting) { definitions = project.definitionsAt(a, caret) } == 0)
        assert(hover?.displayString == "string")
        assert(definitions.size == 1)
        assert(definitions[0].fileName == a)
    }

    @Test
    fun `document highlights at a SECOND caret in an unchanged file build nothing`() {
        val (project, counting) = projectWith(mapOf(a to source))
        // The highlight request is derived from the FILE's occurrence nodes and never
        // from the caret — the caret only picks the seed afterwards — so two different
        // carets ask the compiler the same question.
        val firstCaret = offsetOf(source, "o.p", plus = 2)
        val secondCaret = offsetOf(source, "o.p", occurrence = 1, plus = 2)
        var one: List<ReferenceLocation> = emptyList()
        var two: List<ReferenceLocation> = emptyList()
        assert(buildsIn(counting) { one = project.documentHighlightsAt(a, firstCaret) } == 1)
        assert(buildsIn(counting) { two = project.documentHighlightsAt(a, secondCaret) } == 0)
        // Same group, reached from either end — so the second answer is a real answer
        // and not an empty list the count could not tell from one.
        assert(one.isNotEmpty())
        assert(two.map { it.start } == one.map { it.start })
    }

    @Test
    fun `a DIFFERENT caret's hover still builds`() {
        val (project, counting) = projectWith(mapOf(a to source))
        assert(buildsIn(counting) { project.quickInfoAt(a, offsetOf(source, "o.p", plus = 2)) } == 1)
        // The negative control the two pins above need: the memo answers the question
        // it was asked and no other one. `first` is a different node, so a different
        // request, so a build.
        assert(buildsIn(counting) { project.quickInfoAt(a, offsetOf(source, "first")) } == 1)
    }

    @Test
    fun `an edit to the queried file drops the memo`() {
        val (project, counting) = projectWith(mapOf(a to source))
        val caret = offsetOf(source, "o.p", plus = 2)
        assert(buildsIn(counting) { project.quickInfoAt(a, caret) } == 1)
        assert(buildsIn(counting) { project.quickInfoAt(a, caret) } == 0)
        project.updateFile(a, source)
        assert(buildsIn(counting) { project.quickInfoAt(a, caret) } == 1)
    }

    @Test
    fun `an edit to ANOTHER file changes the answer rather than being served stale`() {
        // The caret's own file is never touched, so the REQUEST is byte-identical
        // across the edit — which is the only shape in which a memo can serve a stale
        // answer at all. What changes is the program the answer is about.
        val importer = """
            import { shared } from "./b";
            const x = shared;
            const y = x;
        """.trimIndent()
        val (project, counting) = projectWith(
            mapOf(a to importer, b to "export const shared = 1;\n"),
        )
        val caret = offsetOf(importer, "x", occurrence = 1)
        var before: QuickInfo? = null
        var after: QuickInfo? = null
        assert(buildsIn(counting) { before = project.quickInfoAt(a, caret) } == 1)
        project.updateFile(b, "export const shared = \"s\";\n")
        assert(buildsIn(counting) { after = project.quickInfoAt(a, caret) } == 1)
        // A `const` keeps its literal type, so these are the literals and not their
        // widened primitives — which makes the pin sharper, not weaker.
        assert(before?.displayString == "1")
        assert(after?.displayString == "\"s\"")
    }

    @Test
    fun `an edit that ADDS A FILE to the program is not answered from the memo`() {
        // The direction CLAUDE.md names as the dangerous one: a mis-keyed hit at the
        // CRAWL is a MISSING FILE, not a wrong type, and only a pin whose edit changes
        // what the program CONTAINS can see it. `./b` does not exist at the first
        // query, so the import resolves to nothing and the caret is not `number`.
        val importer = """
            import { shared } from "./b";
            const x = shared;
            const y = x;
        """.trimIndent()
        val (project, counting) = projectWith(mapOf(a to importer))
        val caret = offsetOf(importer, "x", occurrence = 1)
        var before: QuickInfo? = null
        var after: QuickInfo? = null
        assert(buildsIn(counting) { before = project.quickInfoAt(a, caret) } == 1)
        project.updateFile(b, "export const shared = 1;\n")
        assert(buildsIn(counting) { after = project.quickInfoAt(a, caret) } == 1)
        assert(before?.displayString != "1")
        assert(after?.displayString == "1")
    }

    @Test
    fun `the memo is BOUNDED, so a long-lived project cannot grow it`() {
        val (project, counting) = projectWith(mapOf(a to source))
        val caret = offsetOf(source, "o.p", plus = 2)
        val other = offsetOf(source, "first")
        val third = offsetOf(source, "second")
        assert(buildsIn(counting) { project.quickInfoAt(a, caret) } == 1)
        assert(buildsIn(counting) { project.quickInfoAt(a, other) } == 1)
        // Two entries is the bound. Asking a third distinct question evicts the oldest,
        // so the first question builds again — which is what makes this a BOUND rather
        // than a claim about a map that happens to be small today.
        assert(buildsIn(counting) { project.quickInfoAt(a, third) } == 1)
        assert(buildsIn(counting) { project.quickInfoAt(a, caret) } == 1)
    }
}
