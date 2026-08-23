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
import com.xemantic.typescript.compiler.Diagnostic
import kotlin.test.Test

/**
 * (INC.14) ONE `Checker` answers many queries — `Project.prepare`, and the
 * partition-keyed `diagnosticsOf` memo beside it.
 *
 * Two independent claims are pinned here, each with a COUNT receipt and each with
 * the staleness obligation that makes the count safe:
 *
 * * **`prepare(files)` makes every later SEMANTIC query about any of them free.**
 *   Hover, go-to-definition, semantics and document highlights in `N` prepared
 *   buffers are ONE build between them instead of `N`.
 * * **`diagnosticsOf` is served from a WIDER partition already checked.** `N`
 *   per-file error queries after one `N`-file query are `N` filters and no build,
 *   which is the error-reporting case an editor's annotator drives.
 *
 * ## The receipt is a COUNT, and the answers are asserted beside it
 *
 * A build is not observable from its result — two builds of one state return equal
 * values — so "no build happened" is asserted as reads of `tsconfig.json`, of which
 * `ProjectCompiler.build` performs exactly one and which nothing caches across
 * builds (`ProjectCaptureMemoTest` carries the argument, CLAUDE.md round 914). A
 * count-only pin would be satisfied by a memo serving an EMPTY answer, so every
 * count here sits beside an assertion about what was answered — and where the risk
 * is that a wide check answers DIFFERENTLY from a narrow one, the assertion is a
 * differential against a second, unprepared [Project] rather than a transcribed
 * expectation.
 *
 * ## Why the staleness pin edits a file it does not query
 *
 * (INC.12)'s gotcha: a staleness pin that edits the file it then queries is vacuous,
 * because the edit moves the request key and the memo misses for the wrong reason.
 * So the edit here lands on the file the queried one IMPORTS FROM, where a stale
 * prepared answer survives every key comparison and is simply WRONG.
 */
class ProjectPreparedCheckTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val a = "/proj/src/a.ts"
    private val b = "/proj/src/b.ts"
    private val c = "/proj/src/c.ts"
    private val t = "/proj/src/t.ts"

    /** The type both buffers import — the file an edit lands on. */
    private val types = "export type Shared = string;\n"

    /**
     * `o["p"]` is LOAD-BEARING for the same reason `ProjectCaptureMemoTest` says it
     * is: without it a file's identifiers and its occurrence nodes are the same set,
     * and a pin about the file-wide question passes whichever population is asked for.
     */
    private fun buffer(name: String) = """
        import type { Shared } from "./t";
        interface Shape$name { readonly p: Shared; }
        declare const o$name: Shape$name;
        const first$name = o$name.p;
        const second$name = o$name.p;
        const third$name = o$name["p"];
    """.trimIndent()

    private val sourceA = buffer("A")
    private val sourceB = buffer("B")
    private val sourceC = buffer("C")

    private fun projectWith(
        files: Map<String, String> = mapOf(a to sourceA, b to sourceB, c to sourceC, t to types),
    ): Pair<Project, CountingVfs> {
        val counting = CountingVfs(
            InMemoryVfs(mapOf("/proj/tsconfig.json" to config) + files),
        )
        val project = Project.open("/proj", counting)
        // The first query in a project's life also builds a `SourceIndex`, which needs
        // the compiler options and therefore a build of its own — warmed here so every
        // count below counts CAPTURE builds only (`ProjectCaptureMemoTest`'s reason).
        project.nodeInfoAt(files.keys.first(), 0)
        return project to counting
    }

    /** How many builds [block] performs — one `tsconfig.json` read each. */
    private fun buildsIn(counting: CountingVfs, block: () -> Unit): Int {
        val before = counting.readsOf("/proj/tsconfig.json")
        block()
        return counting.readsOf("/proj/tsconfig.json") - before
    }

    private fun caretOn(text: String, needle: String, occurrence: Int = 0, plus: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at + plus
    }

    @Test
    fun `three prepared buffers are ONE build between them, not three`() {
        val (project, counting) = projectWith()
        val builds = buildsIn(counting) { project.prepare(listOf(a, b, c)) }
        assert(builds == 1)
        var hoverA: QuickInfo? = null
        var hoverB: QuickInfo? = null
        var hoverC: QuickInfo? = null
        val after = buildsIn(counting) {
            hoverA = project.quickInfoAt(a, caretOn(sourceA, "oA.p", plus = 3))
            hoverB = project.quickInfoAt(b, caretOn(sourceB, "oB.p", plus = 3))
            hoverC = project.quickInfoAt(c, caretOn(sourceC, "oC.p", plus = 3))
        }
        assert(after == 0)
        // Counts alone would be satisfied by a memo serving nothing.
        assert(hoverA?.displayString == "string")
        assert(hoverB?.displayString == "string")
        assert(hoverC?.displayString == "string")
    }

    @Test
    fun `a prepared check serves go-to-definition, semantics and highlights too`() {
        val (project, counting) = projectWith()
        project.prepare(listOf(a, b))
        var definitions: List<DefinitionLocation> = emptyList()
        var semantics: List<SemanticInfo> = emptyList()
        var highlights: List<ReferenceLocation> = emptyList()
        val builds = buildsIn(counting) {
            definitions = project.definitionsAt(b, caretOn(sourceB, "oB.p", plus = 3))
            semantics = project.fileSemantics(a)
            highlights = project.documentHighlightsAt(a, caretOn(sourceA, "oA.p", plus = 3))
        }
        assert(builds == 0)
        assert(definitions.size == 1)
        assert(definitions[0].fileName == b)
        assert(semantics.isNotEmpty())
        // `p` declared, read twice by `.p` and once by `["p"]` — four occurrences.
        assert(highlights.size == 4)
    }

    /**
     * The differential the counts cannot express: a prepared (wide) check must answer
     * what an unprepared (one-file) one answers. The second project is the control,
     * and it is a real one — remove the `prepare` call and the two sides are the same
     * code path, which is why the pin is written against a SEPARATE instance.
     */
    @Test
    fun `a prepared answer equals the unprepared one, span for span`() {
        val (prepared, _) = projectWith()
        val (plain, _) = projectWith()
        prepared.prepare(listOf(a, b, c))
        for ((file, text) in listOf(a to sourceA, b to sourceB, c to sourceC)) {
            assert(prepared.fileSemantics(file) == plain.fileSemantics(file))
            val caret = caretOn(text, ".p", plus = 1)
            assert(prepared.documentHighlightsAt(file, caret) == plain.documentHighlightsAt(file, caret))
            assert(prepared.quickInfoAt(file, caret) == plain.quickInfoAt(file, caret))
        }
    }

    /**
     * The staleness pin, and the one that matters most: the edit lands on `t.ts`,
     * which `a.ts` never mentions by caret and which no request key names — so a
     * prepared answer that outlives it is wrong while looking perfectly valid.
     */
    @Test
    fun `an edit to another file changes what a prepared buffer answers`() {
        val (project, _) = projectWith()
        project.prepare(listOf(a, b))
        val caret = caretOn(sourceA, "oA.p", plus = 3)
        assert(project.quickInfoAt(a, caret)?.displayString == "string")
        project.updateFile(t, "export type Shared = number;\n")
        assert(project.quickInfoAt(a, caret)?.displayString == "number")
    }

    /** …and the same obligation for the diagnostics memo. */
    @Test
    fun `an edit to another file changes what diagnosticsOf answers`() {
        val (project, _) = projectWith(
            mapOf(
                t to types,
                a to "import type { Shared } from \"./t\";\nexport const av: Shared = \"x\";\n",
            ),
        )
        assert(project.diagnosticsOf(listOf(a)).isEmpty())
        project.updateFile(t, "export type Shared = number;\n")
        val after = project.diagnosticsOf(listOf(a))
        assert(after.size == 1)
        assert(after[0].code == 2322)
    }

    @Test
    fun `a file NOT prepared still builds - the non-vacuity control`() {
        val (project, counting) = projectWith()
        project.prepare(listOf(a, b))
        var hover: QuickInfo? = null
        val builds = buildsIn(counting) {
            hover = project.quickInfoAt(c, caretOn(sourceC, "oC.p", plus = 3))
        }
        assert(builds == 1)
        assert(hover?.displayString == "string")
    }

    @Test
    fun `an ordinary caret query does not evict the prepared check`() {
        val (project, counting) = projectWith()
        project.prepare(listOf(a, b))
        // Two unprepared buffers in a row would flush a two-entry LRU; the prepared
        // slot is separate precisely so they cannot.
        project.quickInfoAt(c, caretOn(sourceC, "oC.p", plus = 3))
        project.quickInfoAt(c, caretOn(sourceC, "oC.p", occurrence = 1, plus = 3))
        var hover: QuickInfo? = null
        val builds = buildsIn(counting) {
            hover = project.quickInfoAt(a, caretOn(sourceA, "oA.p", plus = 3))
        }
        assert(builds == 0)
        assert(hover?.displayString == "string")
    }

    @Test
    fun `preparing a subset of what is already prepared does not build`() {
        val (project, counting) = projectWith()
        project.prepare(listOf(a, b, c))
        assert(buildsIn(counting) { project.prepare(listOf(a)) } == 0)
        assert(buildsIn(counting) { project.prepare(listOf(b, c)) } == 0)
        // …and a file OUTSIDE it does build, so the containment test is a test.
        assert(buildsIn(counting) { project.prepare(listOf(a, t)) } == 1)
    }

    /**
     * The containment test is a TEST: a caret can land on a node the file-wide
     * request does not carry — a call expression, a literal, an arithmetic
     * expression — and a prepared check does not carry it either. Weakening the
     * lookup to "is this file prepared" would serve a result holding no answer for
     * that span, and the hover would render NOTHING with no error anywhere, which is
     * this whole layer's one silent failure.
     */
    @Test
    fun `a caret on a NON-occurrence in a prepared file is still answered`() {
        val arithmetic = """
            const sum = 41 + 1;
            const twice = sum + sum;
        """.trimIndent()
        val (project, counting) = projectWith(mapOf(a to arithmetic, t to types))
        project.prepare(listOf(a))
        var hover: QuickInfo? = null
        val builds = buildsIn(counting) {
            hover = project.quickInfoAt(a, caretOn(arithmetic, "41 + 1", plus = 3))
        }
        // It BUILDS, because the answer was never prepared — and it ANSWERS, which a
        // file-membership lookup would not.
        assert(builds == 1)
        assert(hover?.displayString == "number")
    }

    @Test
    fun `preparing nothing builds nothing`() {
        val (project, counting) = projectWith()
        assert(buildsIn(counting) { project.prepare(emptyList()) } == 0)
    }

    @Test
    fun `an edit drops the prepared check - the query after it builds again`() {
        val (project, counting) = projectWith()
        project.prepare(listOf(a, b))
        project.updateFile(t, "export type Shared = number;\n")
        assert(buildsIn(counting) { project.quickInfoAt(a, caretOn(sourceA, "oA.p", plus = 3)) } == 1)
    }

    @Test
    fun `N per-file diagnostics queries after ONE N-file query are ZERO builds`() {
        val (project, counting) = projectWith()
        // Dirty project: the whole-program short-circuit in `diagnosticsOf` must not
        // be what answers these, or the pin measures that instead.
        project.updateFile(a, sourceA + "\nexport const extra = firstA;\n")
        val wide = buildsIn(counting) { project.diagnosticsOf(listOf(a, b, c)) }
        assert(wide == 1)
        var one: List<Diagnostic> = emptyList()
        var two: List<Diagnostic> = emptyList()
        val narrow = buildsIn(counting) {
            one = project.diagnosticsOf(listOf(a))
            two = project.diagnosticsOf(listOf(b, c))
        }
        assert(narrow == 0)
        assert(one.isEmpty())
        assert(two.isEmpty())
    }

    /**
     * The subset answer must be the SUBSET, not the superset's whole list — the one
     * way a containment memo goes wrong without changing any count.
     */
    @Test
    fun `a subset query answers only its own files' rows`() {
        val broken = "export const bad: string = 1;\n"
        val (project, counting) = projectWith(mapOf(a to sourceA, b to broken, t to types))
        project.updateFile(b, broken)
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(a, b)) } == 1)
        var forA: List<Diagnostic> = emptyList()
        var forB: List<Diagnostic> = emptyList()
        assert(
            buildsIn(counting) {
                forA = project.diagnosticsOf(listOf(a))
                forB = project.diagnosticsOf(listOf(b))
            } == 0,
        )
        assert(forA.isEmpty())
        assert(forB.size == 1)
        assert(forB[0].code == 2322)
    }
}
