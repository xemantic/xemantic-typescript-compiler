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
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [Project.diagnosticsOf] — the diagnostics of a FILE SET, computed by checking
 * only those files.
 *
 * Two families, and they pull against each other, which is the point. The first is
 * EQUIVALENCE: narrowing the check must not narrow the ANSWER, so the fixture's
 * error in `a.ts` is one that cannot be found without `b.ts` — an interface
 * declared in the other file. A partition that had lost the program would report
 * an unresolved import there, or nothing at all, rather than the assignability
 * error the whole-program build reports; a partition that had merely filtered a
 * whole-program build would agree trivially, which the second family is what rules
 * out.
 *
 * The second family is COUNT: how many BUILDS a query costs. A build is not
 * observable from its result — two builds of the same state return equal values —
 * so the count is taken at the one seam the implementation cannot fake, the reads
 * that reach the backing [Vfs], exactly as `ProjectSemanticsTest` does and with the
 * same control (`a plain build reads the config exactly once`) establishing the
 * unit rather than assuming it. Nothing here is timed: a timed pin over a compile
 * is a coin flip (CLAUDE.md, round 868).
 *
 * The sharpest pin of the second family is the NEGATIVE one — a whole-program query
 * after a narrow one still costs a build. It holds the invariant the whole design
 * rests on: a partition's diagnostics are a subset, so adopting one as this
 * project's build would make [Project.diagnostics] report that subset as the whole
 * program's errors, silently.
 *
 * **WHAT NOTHING BELOW PINS, STATED RATHER THAN LEFT TO BE DISCOVERED.** No pin in
 * this class fails if [Project.diagnosticsOf] stops passing `recheckOnly` and simply
 * builds the whole program, because the answer is FILTERED afterwards either way —
 * the two differ only in wall time, and a timed pin over a compile is a coin flip.
 * So the narrowing is held here at the SEAM instead (the last pin: the compiler's
 * own partition build reports the assigned file's rows and not another file's,
 * which fails uniquely if `recheckOnly` is ignored anywhere below this module), and
 * that it is REACHED is held by the measurement in `scripts/incremental-cost.sh`
 * plus the whole-project sweep in `scripts/partition-equivalence.sh`. An ablation
 * dropping the argument leaves every test in this class green; that is recorded
 * rather than papered over (CLAUDE.md, round 807 — a signal with no uniquely-its-own
 * failure is not a pin, and saying so is the honest outcome).
 */
class ProjectNarrowDiagnosticsTest {

    /**
     * `module` is an ES kind and the program has TWO files, for the reason
     * `ProjectDefinitionTest` states: below two program files, or without an ES
     * module kind, every import-related assertion is vacuous — and this class's
     * whole first family is an import-related assertion.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val aFile = "/proj/src/a.ts"
    private val bFile = "/proj/src/b.ts"

    /**
     * The error in `a.ts` is a CROSS-FILE one: `Shape` is declared in `b.ts`, so
     * only a build that crawled, resolved and bound the whole program can judge the
     * object literal against it. That is what makes the equivalence pins measure
     * narrowing rather than filtering.
     */
    private val aText = """
        import { Shape } from "./b";
        export const shape: Shape = { kind: 1 };
    """.trimIndent() + "\n"

    /**
     * `b.ts` carries an error of its OWN — independent of `a.ts`, and therefore the
     * thing a query about `a.ts` alone must not report.
     */
    private val bText = """
        export interface Shape {
            readonly kind: string;
        }
        export const wrong: number = "text";
    """.trimIndent() + "\n"

    private fun vfsWith(aSource: String = aText) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            aFile to aSource,
            bFile to bText,
        ),
    )

    private fun projectWith(aSource: String = aText): Project =
        Project.open("/proj", vfsWith(aSource))

    /**
     * How many builds [block] performed, counted at the backing [Vfs].
     *
     * The unit is reads of `tsconfig.json` rather than the sum of all reads,
     * because the sum is not stable across builds within one JVM — some compiler
     * cache warms and takes a source read with it, which makes a sum-based counter
     * order-dependent. Every `ProjectCompiler.build` loads the config exactly once
     * and nothing caches that across builds, which the control pin below
     * establishes rather than assumes.
     */
    private fun buildsIn(counting: CountingVfs, block: () -> Unit): Int {
        val before = counting.readsOf("/proj/tsconfig.json")
        block()
        return counting.readsOf("/proj/tsconfig.json") - before
    }

    /**
     * The diagnostics as comparable ROWS, sorted.
     *
     * Two builds of the same program agree row for row; their ORDER across files is
     * a property of which files each checked, which is exactly what differs between
     * a narrowed build and a whole-program one — so a cross-build comparison that
     * pinned order would pin the partition's file order and nothing else.
     */
    private fun rows(diagnostics: List<Diagnostic>): List<String> =
        diagnostics.map { "${it.fileName}|${it.start}|${it.code}|${it.message}" }.sorted()

    /** A project over a counting Vfs, with everything that is NOT a build warmed. */
    private fun countedProject(aSource: String = aText): Pair<Project, CountingVfs> {
        val counting = CountingVfs(vfsWith(aSource))
        val project = Project.open("/proj", counting)
        // Warms the project's own parse and resolved options — both read through this
        // Vfs and neither is a build.
        project.nodeInfoAt(aFile, 0)
        return project to counting
    }

    // --- EQUIVALENCE: narrowing the check does not narrow the answer -------------

    @Test
    fun `a narrow query finds an error only the whole program can see`() {
        // Deliberately the FIRST query on this project, so it is answered by a
        // narrowed build rather than by filtering a cached whole-program one.
        val project = projectWith()
        val narrow = project.diagnosticsOf(listOf(aFile))
        assert(narrow.any { it.code == 2322 })
        assert(narrow.all { it.fileName == aFile })
        // `Shape` resolved, so the program was crawled and bound in full: an
        // unresolved import would have been TS2307 and no assignability error at all.
        assert(narrow.none { it.code == 2307 })
        assert(narrow == project.diagnostics(aFile))
    }

    @Test
    fun `a narrow query reports nothing about a file it was not asked about`() {
        val project = projectWith()
        val narrow = project.diagnosticsOf(listOf(aFile))
        // The control: `b.ts` really does carry an error of its own, which the
        // whole-program query reports and this one must not.
        assert(project.diagnostics(bFile).any { it.code == 2322 })
        assert(narrow.none { it.fileName == bFile })
    }

    @Test
    fun `asking about two files answers both of them`() {
        val (project, counting) = countedProject()
        var both: List<Diagnostic> = emptyList()
        assert(buildsIn(counting) { both = project.diagnosticsOf(listOf(aFile, bFile)) } == 1)
        assert(both.any { it.code == 2322 && it.fileName == aFile })
        assert(both.any { it.code == 2322 && it.fileName == bFile })
        val whole = project.diagnostics().filter { it.fileName == aFile || it.fileName == bFile }
        assert(rows(both) == rows(whole))
    }

    @Test
    fun `every file answers the same narrow as it answers whole-program`() {
        // The equivalence over the WHOLE fixture rather than over the one file the
        // first pin picked: a per-file agreement that held only for the file the
        // author happened to choose would be an accident.
        val project = projectWith()
        for (file in listOf(aFile, bFile)) {
            val narrow = Project.open("/proj", vfsWith()).diagnosticsOf(listOf(file))
            assert(narrow == project.diagnostics(file))
        }
    }

    // --- COUNT: how many builds a query costs ------------------------------------

    @Test
    fun `a plain build reads the config exactly once`() {
        // The control every count pin below rests on. Without it, "the query cost one
        // config read" would be a fact about `TsConfigLoader` rather than about the
        // number of compiles.
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { project.diagnostics() } == 1)
        // (INC.46) ADDING AN EXPORT MOVES THE EXPORT SURFACE, so this edit costs TWO
        // builds and not one: the narrowed build that discovers the signature moved,
        // then the whole-program rebuild. That is the price of the incremental gate
        // being wrong and it is pinned as such in `ProjectIncrementalDiagnosticsTest`;
        // recorded here because this is the control every count pin below rests on,
        // and a reader comparing them needs the cost model to be visible in both.
        project.updateFile(aFile, aText + "export const extra = 1;\n")
        assert(buildsIn(counting) { project.diagnostics() } == 2)
        assert(buildsIn(counting) { project.diagnostics() } == 0)
        // And an edit whose export SURFACE is unchanged costs the one narrowed build,
        // which is the whole of (INC.46). Built from the text now on the project — the
        // `extra` export above included — because dropping it would be an export
        // REMOVED, i.e. a surface change wearing a body edit's clothes.
        project.updateFile(
            aFile,
            aText.replace("{ kind: 1 }", "{ kind: 1, }") + "export const extra = 1;\n",
        )
        assert(buildsIn(counting) { project.diagnostics() } == 1)
    }

    @Test
    fun `a narrow query on a dirty project costs ONE build`() {
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(aFile)) } == 1)
    }

    @Test
    fun `a narrow query on a clean project costs NO build`() {
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { project.diagnostics() } == 1)
        // The whole-program answer is already in hand and is strictly wider, so
        // filtering it beats compiling a partition.
        var narrow: List<Diagnostic> = emptyList()
        assert(buildsIn(counting) { narrow = project.diagnosticsOf(listOf(aFile)) } == 0)
        assert(narrow == project.diagnostics(aFile))
    }

    @Test
    fun `a repeated narrow query costs NO second build`() {
        val (project, counting) = countedProject()
        var first: List<Diagnostic> = emptyList()
        var second: List<Diagnostic> = emptyList()
        assert(buildsIn(counting) { first = project.diagnosticsOf(listOf(aFile)) } == 1)
        assert(buildsIn(counting) { second = project.diagnosticsOf(listOf(aFile)) } == 0)
        assert(second == first)
    }

    @Test
    fun `a narrow query does NOT make the whole-program query free`() {
        // The invariant: a partition's diagnostics are a SUBSET, so the narrowed
        // build must not be adopted as this project's build. If it were, this query
        // would cost nothing and would answer with `a.ts`'s errors alone — reported
        // as the whole program's.
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(aFile)) } == 1)
        var whole: List<Diagnostic> = emptyList()
        assert(buildsIn(counting) { whole = project.diagnostics() } == 1)
        assert(whole.any { it.fileName == bFile })
    }

    @Test
    fun `an edit between two narrow queries forces a rebuild`() {
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(aFile)) } == 1)
        project.updateFile(aFile, """
            import { Shape } from "./b";
            export const shape: Shape = { kind: "ok" };
        """.trimIndent() + "\n")
        var after: List<Diagnostic> = emptyList()
        assert(buildsIn(counting) { after = project.diagnosticsOf(listOf(aFile)) } == 1)
        // And the answer is about the buffer, not about the memo the edit dropped.
        assert(after.none { it.code == 2322 })
    }

    // --- negative controls -------------------------------------------------------

    @Test
    fun `negative control - an empty file set answers empty and does NOT build`() {
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { assert(project.diagnosticsOf(emptyList()).isEmpty()) } == 0)
    }

    @Test
    fun `negative control - an unknown file answers empty`() {
        val project = projectWith()
        assert(project.diagnosticsOf(listOf("/proj/src/nope.ts")).isEmpty())
    }

    @Test
    fun `a closed project refuses the narrow query`() {
        val project = projectWith()
        project.close()
        assertFailsWith<IllegalStateException> { project.diagnosticsOf(listOf(aFile)) }
        // Including for the empty set, which is answered without building but is
        // still a query: `checkOpen` comes first.
        assertFailsWith<IllegalStateException> { project.diagnosticsOf(emptyList()) }
    }

    // --- THE SEAM: the mechanism the member above is wired to --------------------

    /**
     * The partition seam itself, asked directly, WITHOUT this module in the way.
     *
     * This is the one pin here with a uniquely-its-own failure: it compares what a
     * `recheckOnly` build REPORTS against what a whole-program build reports, before
     * any filtering of ours. If the core ever stopped honouring the partition — the
     * per-file check passes walking every file regardless — `b.ts`'s own error would
     * come back in an answer about `a.ts`, and nothing else in this class would
     * notice. It also states the contract the equivalence pins depend on from the
     * other side: the partition still finds `a.ts`'s CROSS-FILE error, so what is
     * narrowed is the checking and not the program.
     */
    @Test
    fun `the compiler's own partition build reports the assigned file only`() {
        val vfs = vfsWith()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val partition = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(aFile))
        val wholeFiles = whole.diagnostics.mapNotNull { it.fileName }.toSet()
        val partitionFiles = partition.diagnostics.mapNotNull { it.fileName }.toSet()
        assert(aFile in wholeFiles)
        assert(bFile in wholeFiles)
        assert(aFile in partitionFiles)
        assert(bFile !in partitionFiles)
        assert(
            rows(partition.diagnostics) == rows(whole.diagnostics.filter { it.fileName == aFile }),
        )
    }

}
