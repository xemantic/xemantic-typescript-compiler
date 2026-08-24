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
import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.40) THE WIRING OF THE RE-ENTRANT RECHECK INTO `Project.diagnosticsOf` — and
 * ITS BOUNDARY.
 *
 * `ProjectRecheckTest` pins the MECHANISM (`Recheck.kt`'s `ProgramRecheck`, driven
 * directly through a `RecheckHolder`). This class pins the POLICY: that
 * `diagnosticsOf` reaches it, that everything else structurally cannot, that an
 * edit drops it, and that what it hands back is what a fresh narrowed build hands
 * back.
 *
 * ## Why it is worth wiring at all — the number, re-priced at HEAD
 *
 * CLAUDE.md's standing law is that every round shrinking the incremental floor
 * shrinks the replay's reason to exist (3.06x -> 1.91x -> 1.68x with no change to
 * the replay). (INC.40) re-measured it for the DIAGNOSTICS channel with no capture
 * request in either arm — `scripts/inc40-replay-cost.sh`, tsc's own 78 sources,
 * three rotations after six warm-ups:
 *
 * ```
 * k  queries  freshTotal  replayTotal  ratio   freshPerQueryMed  replayPerQueryMed
 * 1  77       10,656 ms   4,728 ms     2.25x   104 ms            25 ms
 * 2  39        7,716 ms   4,500 ms     1.72x   141 ms            55 ms
 * 8  10        5,342 ms   4,228 ms     1.26x   377 ms           260 ms
 * ```
 *
 * The ratio FALLS with the working-set size because the thing the replay deletes —
 * the incremental floor — is paid once per QUERY, not per file. What a user feels
 * is the k = 1 row: an editor opening buffers one at a time answers the second
 * buffer's errors in **25 ms instead of 104**.
 *
 * ## Why it may serve THIS and nothing else
 *
 * `scripts/replay-differential.sh` at HEAD: **0 divergent diagnostic rows** on both
 * arms (46 rows over tsc's sources, 178 rows over 71 files on
 * `test-fixtures/partition-gate`) and **0 divergent definition spans of 352,713**,
 * against **43 of 75 files diverging in CAPTURED TYPES**. The valve
 * (`DiagnosticsOnlyRecheck`) makes the split a TYPE rather than a comment.
 *
 * ## The counting rule these pins obey
 *
 * The observable is a COUNT of builds, never a time (round 868: a timed assertion
 * over a sub-millisecond region is a coin flip). A build is counted as a read of
 * the project's `tsconfig.json` — exactly one per `ProjectCompiler.build`, served
 * by no cross-build cache (CLAUDE.md round 914: a count of ALL Vfs reads is not a
 * count of builds).
 */
class ProjectRecheckWiringTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val typesFile = "/proj/src/types.ts"
    private val apiFile = "/proj/src/api.ts"
    private val alphaFile = "/proj/src/alpha.ts"
    private val betaFile = "/proj/src/beta.ts"
    private val gammaFile = "/proj/src/gamma.ts"

    /** `ProjectRecheckTest`'s fixture — a type reached through a foreign file's
     *  ANONYMOUS OBJECT TYPE LITERAL and through `Readonly<...>`, the shape (INC.2)
     *  measured collapsing to `any` under a partition. Every consumer reaches the
     *  same declarations, so whichever file the seed build walked first decides the
     *  cache entries a re-entry then reads. */
    private val files = mapOf(
        "/proj/tsconfig.json" to config,
        typesFile to (
            """
            export interface Program {
                readonly kind: string;
                readonly count: number;
            }
            export type Frozen = Readonly<Program>;
            """.trimIndent() + "\n"
            ),
        apiFile to (
            """
            import { Program, Frozen } from "./types";
            export function make(): { program: Program; frozen: Frozen } {
                return null as unknown as { program: Program; frozen: Frozen };
            }
            """.trimIndent() + "\n"
            ),
        alphaFile to (
            """
            import { make } from "./api";
            export const alphaKind = make().program.kind;
            const alphaWrong: number = make().program.kind;
            export { alphaWrong };
            """.trimIndent() + "\n"
            ),
        betaFile to (
            """
            import { make } from "./api";
            export const betaCount = make().program.count;
            const betaWrong: string = make().program.count;
            const betaMissing = make().program.nope;
            export { betaWrong, betaMissing };
            """.trimIndent() + "\n"
            ),
        gammaFile to (
            """
            import { make } from "./api";
            const gammaWrong: boolean = make().frozen.kind;
            export { gammaWrong };
            """.trimIndent() + "\n"
            ),
    )

    private fun projectWith(): Pair<Project, CountingVfs> {
        val counting = CountingVfs(InMemoryVfs(files))
        return Project.open("/proj", counting) to counting
    }

    /** How many builds [block] performs. */
    private fun buildsIn(counting: CountingVfs, block: () -> Unit): Int {
        val before = counting.readsOf("/proj/tsconfig.json")
        block()
        return counting.readsOf("/proj/tsconfig.json") - before
    }

    private fun rows(diagnostics: List<Diagnostic>, file: String): List<String> =
        diagnostics.filter { it.fileName?.let { n -> PathUtil.normalize(n) } == PathUtil.normalize(file) }
            .map { "TS${it.code}@${it.start}+${it.length} ${it.message}" }
            .sorted()

    /** What a build narrowed to [file] and NOTHING ELSE reports for it — the arm the
     *  replay is graded against, computed in a project of its own so no handle of the
     *  project under test can serve it. */
    private fun alone(file: String): List<String> = rows(
        ProjectCompiler(InMemoryVfs(files))
            .build("/proj", noEmit = true, recheckOnly = setOf(file)).diagnostics,
        file,
    )

    // --- the control ------------------------------------------------------------

    @Test
    fun `the control - a dirty project's per-file queries would otherwise be one build each`() {
        // Without this every count below could be zero because the fixture reports
        // nothing, or because `diagnosticsOf` never builds at all. Both are asserted
        // here on a project whose handle is dropped between queries, which is exactly
        // what the wiring changes.
        val (project, counting) = projectWith()
        assert(alone(alphaFile).isNotEmpty())
        assert(alone(betaFile).isNotEmpty())
        assert(alone(gammaFile).isNotEmpty())
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(alphaFile)) } == 1)
        project.updateFile(alphaFile, files.getValue(alphaFile))
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(betaFile)) } == 1)
        project.updateFile(alphaFile, files.getValue(alphaFile))
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(gammaFile)) } == 1)
    }

    // --- the wiring -------------------------------------------------------------

    @Test
    fun `a second file's diagnostics cost NO build - the receipt is a count, not a time`() {
        val (project, counting) = projectWith()
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(alphaFile)) } == 1)
        // The whole of (INC.40): a file the first query's checker never walked is
        // answered by re-entering that checker, so the build count does not move.
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(betaFile)) } == 0)
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(gammaFile)) } == 0)
    }

    @Test
    fun `and the answer is the one a fresh narrowed build gives`() {
        // A count-only pin is satisfied by a handle that answers an EMPTY list, which
        // is why the value is asserted too — against a build narrowed to that file and
        // nothing else, in a project of its own.
        val (project, _) = projectWith()
        project.diagnosticsOf(listOf(alphaFile))
        assert(rows(project.diagnosticsOf(listOf(betaFile)), betaFile) == alone(betaFile))
        assert(rows(project.diagnosticsOf(listOf(gammaFile)), gammaFile) == alone(gammaFile))
        // …and a file the seed build DID walk still answers what it answered.
        assert(rows(project.diagnosticsOf(listOf(alphaFile)), alphaFile) == alone(alphaFile))
    }

    @Test
    fun `a multi-file query after a single-file one is also free`() {
        val (project, counting) = projectWith()
        project.diagnosticsOf(listOf(alphaFile))
        val many = ArrayList<String>()
        assert(
            buildsIn(counting) {
                many.addAll(rows(project.diagnosticsOf(listOf(betaFile, gammaFile)), betaFile))
            } == 0,
        )
        assert(many == alone(betaFile))
    }

    // --- invalidation -----------------------------------------------------------

    @Test
    fun `an edit drops the handle - the next query builds again`() {
        val (project, counting) = projectWith()
        project.diagnosticsOf(listOf(alphaFile))
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(betaFile)) } == 0)
        // `ProgramRecheck` has no invalidation protocol by design: its program's text
        // is fixed at the build that produced it, so a handle used after an edit would
        // answer about the previous text with no way for a caller to tell.
        project.updateFile(gammaFile, files.getValue(gammaFile) + "const extra: number = 1;\n")
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(betaFile)) } == 1)
    }

    @Test
    fun `a deletion drops the handle too`() {
        val (project, counting) = projectWith()
        project.diagnosticsOf(listOf(alphaFile))
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(betaFile)) } == 0)
        project.deleteFile(gammaFile)
        assert(buildsIn(counting) { project.diagnosticsOf(listOf(betaFile)) } == 1)
    }

    @Test
    fun `an edit is SEEN through the handle - a stale answer would be silent`() {
        // The dangerous direction: a handle that survived an edit answers about the
        // previous text and nothing anywhere says so. So this asserts the CONTENT
        // moved, not only that a build happened.
        val (project, _) = projectWith()
        project.diagnosticsOf(listOf(alphaFile))
        assert(project.diagnosticsOf(listOf(betaFile)).isNotEmpty())
        project.updateFile(betaFile, "export const clean: number = 1;\n")
        assert(project.diagnosticsOf(listOf(betaFile)).isEmpty())
    }

    // --- the boundary -----------------------------------------------------------

    @Test
    fun `the capture channels do NOT reach the handle - they still build`() {
        // THE REFUSAL, as a count. The replay's CAPTURED-TYPE channel diverges from a
        // fresh build in 43 of 75 files of the compiler profile, so a hover must never
        // be served from it. `DiagnosticsOnlyRecheck` makes that structural — it can
        // express no question but "the diagnostics of these files" — and this is what
        // notices if the valve is ever widened: every capture-serving member must
        // still pay a build after `diagnosticsOf` has left a handle behind.
        val (project, counting) = projectWith()
        project.diagnosticsOf(listOf(alphaFile))
        // A warm-up for the SourceIndex, which needs the compiler options and so a
        // build of its own — otherwise the first row below reads 2 and the boundary
        // is buried in an artefact of where the index came from.
        project.nodeInfoAt(betaFile, 0)

        assert(buildsIn(counting) { project.quickInfoAt(betaFile, betaCaret) } >= 1)
        assert(buildsIn(counting) { project.definitionsAt(gammaFile, gammaCaret) } >= 1)
    }

    @Test
    fun `a hover after a diagnostics query still answers correctly`() {
        // The other half of the boundary: refusing to serve a hover from the handle
        // must not make the hover WRONG or absent — a pin that only counted builds
        // would be satisfied by a broken one.
        val (project, _) = projectWith()
        project.diagnosticsOf(listOf(alphaFile))
        val info = project.quickInfoAt(betaFile, betaCaret)
        assert(info != null)
        assert(info.displayString.contains("number"))
    }

    /** The caret on `count` in beta's `make().program.count`. */
    private val betaCaret: Int
        get() = files.getValue(betaFile).indexOf("program.count") + "program.".length + 1

    /** The caret on `kind` in gamma's `make().frozen.kind`. */
    private val gammaCaret: Int
        get() = files.getValue(gammaFile).indexOf("frozen.kind") + "frozen.".length + 1
}
