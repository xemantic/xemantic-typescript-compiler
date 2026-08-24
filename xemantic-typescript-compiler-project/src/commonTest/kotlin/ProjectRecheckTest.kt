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
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.ProgramRecheck
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.RecheckHolder
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.Vfs
import com.xemantic.typescript.compiler.computeParserFlags
import kotlin.test.Test

/**
 * (INC.17) THE RE-ENTRANT RECHECK, AT FIXTURE SCALE — **the mechanism itself.**
 * Since (INC.40) it IS a shipped path, for `Project.diagnosticsOf` and for nothing
 * else; `ProjectRecheckWiringTest` pins that wiring and its boundary, this class
 * pins the mechanism underneath it.
 *
 * `ProgramRecheck` answers about a file its checker's first walk did not cover by
 * re-entering only the 206 of 417 `init` rows whose answer depends on the check
 * partition, leaving the 211 that carry **350.89 ms of a 366.47 ms floor** alone.
 * (INC.17) measured it at **3.06x** on tsc's own sources (replay 12,572 ms against
 * 38,498 ms of fresh narrowed builds over 75 questions) and then **REFUSED to wire
 * it into `Project`**, because `scripts/replay-differential.sh` found the CAPTURE
 * channel diverging in **8 of 75 files** — a lost type-parameter constraint,
 * `<T extends Node, U>` where a fresh build renders `<T extends Node, U extends
 * T>`. (INC.19) closed THAT class and pinned it in
 * [ProjectRecheckConstraintTest], which is the fixture this class says below it
 * cannot host.
 *
 * **RE-MEASURED AT HEAD BY (INC.40) (2026-08-24, `8d4e95b0`): the differential
 * reads 0 `DIVERGE-DIAG`, 0 `DIVERGE-DEF` and **43** `DIVERGE-TYPE` of 75 files.**
 * The "5 of 75, all lost generic inference" this comment used to carry was stale
 * on BOTH counts: 43 is the pre-existing HEAD state, verified on a clean tree
 * before any edit, and the surviving rows are overwhelmingly the union-alias
 * DISPLAY family ((INC.26)/(INC.27)) — where the FRESH arm is not automatically
 * the right one — rather than lost inference.
 *
 * So the refusal stands **for the capture channel only**. (INC.40) wired the
 * DIAGNOSTICS channel into `Project.diagnosticsOf` behind a type-level valve
 * (`DiagnosticsOnlyRecheck`, which cannot express a `TypeCaptureRequest`), on the
 * strength of that measured `0 DIVERGE-DIAG`; see `docs/language-service.md` § 4a.
 *
 * ## What these pins do and do NOT assert
 *
 * They pin **what the replay currently does**, which includes its defect. A pin
 * asserting soundness would be FALSE, so there is none:
 *
 * * **the DIAGNOSTIC channel agrees** — the half measured equivalent on both arms
 *   of the differential, and the half a future wiring would depend on;
 * * **the CAPTURE channel is deliberately NOT pinned equivalent.** Its rows are
 *   asserted to EXIST (the channel is wired) and nothing more — the differential,
 *   not a two-consumer fixture, is what grades it, and it grades it WRONG;
 * * **the arming is behaviour-free** — a build that hands its program back must
 *   answer exactly what an unarmed one answers, or "wired for diagnostics only"
 *   would be a fiction and every compile would be paying for a path it never
 *   asked for. (Before (INC.40) this bullet also said "nothing reaches it by
 *   default"; that is now `ProjectRecheckWiringTest`'s subject, which pins WHICH
 *   members reach it and which structurally cannot.);
 * * **the re-entry is a re-entry** — the receipt is a COUNT of builds, not a time.
 *   A "re-entry" that quietly performed a whole build would agree with everything
 *   and buy nothing, which is exactly how a green run tests nothing (CLAUDE.md
 *   rounds 853/873/895). Every `ProjectCompiler.build` reads the project's
 *   `tsconfig.json` exactly once and no cross-build cache serves it (CLAUDE.md's
 *   round-914 entry: a count of ALL Vfs reads is NOT a count of builds), so that
 *   count IS the build count.
 *
 * ## The fixture
 *
 * Deliberately the one `ProjectCheckerSharingTest` uses — a type reached through a
 * foreign file's ANONYMOUS OBJECT TYPE LITERAL and through `Readonly<...>`, the
 * shape (INC.2) measured collapsing to `any` under a partition and (INC.6) fixed.
 * Both consumer files reach the same declarations, so whichever the seed build
 * walked first decides every cache entry the re-entry then reads.
 */
class ProjectRecheckTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val configPath = "/proj/tsconfig.json"
    private val typesFile = "/proj/src/types.ts"
    private val apiFile = "/proj/src/api.ts"
    private val alphaFile = "/proj/src/alpha.ts"
    private val betaFile = "/proj/src/beta.ts"
    private val gammaFile = "/proj/src/gamma.ts"

    private val typesText = """
        export interface Program {
            readonly kind: string;
            readonly count: number;
        }
        export type Frozen = Readonly<Program>;
    """.trimIndent() + "\n"

    private val apiText = """
        import { Program, Frozen } from "./types";
        export function make(): { program: Program; frozen: Frozen } {
            return null as unknown as { program: Program; frozen: Frozen };
        }
    """.trimIndent() + "\n"

    private val alphaText = """
        import { make } from "./api";
        export const alphaKind = make().program.kind;
        const alphaWrong: number = make().program.kind;
        export { alphaWrong };
    """.trimIndent() + "\n"

    private val betaText = """
        import { make } from "./api";
        export const betaCount = make().program.count;
        const betaWrong: string = make().program.count;
        const betaMissing = make().program.nope;
        export { betaWrong, betaMissing };
    """.trimIndent() + "\n"

    private val gammaText = """
        import { make } from "./api";
        const gammaWrong: boolean = make().frozen.kind;
        export { gammaWrong };
    """.trimIndent() + "\n"

    /**
     * An [InMemoryVfs] that counts reads of ONE path — the project's
     * `tsconfig.json`, which every build reads exactly once and no cross-build
     * cache serves.
     */
    private class CountingVfs(private val backing: Vfs, private val counted: String) : Vfs by backing {
        var reads: Int = 0
            private set

        override fun readText(path: String): String? {
            if (PathUtil.normalize(path) == counted) reads++
            return backing.readText(path)
        }
    }

    private fun vfs() = CountingVfs(
        InMemoryVfs(
            mapOf(
                configPath to config,
                typesFile to typesText,
                apiFile to apiText,
                alphaFile to alphaText,
                betaFile to betaText,
                gammaFile to gammaText,
            ),
        ),
        PathUtil.normalize(configPath),
    )

    /** The rows a host would show in one buffer's gutter. */
    private fun rows(diagnostics: List<Diagnostic>, file: String): List<String> =
        diagnostics.filter { it.fileName?.let { n -> PathUtil.normalize(n) } == PathUtil.normalize(file) }
            .map { "TS${it.code}@${it.start}+${it.length} ${it.message}" }
            .sorted()

    /** What a build narrowed to [file] and NOTHING ELSE reports for it. */
    private fun alone(file: String): List<String> =
        rows(
            ProjectCompiler(vfs()).build("/proj", noEmit = true, recheckOnly = setOf(file)).diagnostics,
            file,
        )

    /** A seed build over [seed], with its live program handed back. */
    private fun seeded(vfs: Vfs, seed: String): ProgramRecheck {
        val holder = RecheckHolder()
        ProjectCompiler(vfs).build(
            "/proj", noEmit = true, recheckOnly = setOf(seed), recheckHolder = holder,
        )
        return requireNotNull(holder.recheck)
    }

    @Test
    fun `the control - every fixture file reports errors of its own`() {
        // Without this every equality below could hold over three empty lists, which
        // is exactly how a differential passes while measuring nothing.
        assert(alone(alphaFile).isNotEmpty())
        assert(alone(betaFile).isNotEmpty())
        assert(alone(gammaFile).isNotEmpty())
    }

    @Test
    fun `a file the seed build never checked is answered exactly as a fresh narrowed build answers it`() {
        val program = seeded(vfs(), alphaFile)
        assert(rows(program.recheck(setOf(betaFile)).diagnostics, betaFile) == alone(betaFile))
    }

    @Test
    fun `a THIRD file, answered by a second re-entry, still matches a fresh narrowed build`() {
        val program = seeded(vfs(), alphaFile)
        program.recheck(setOf(betaFile))
        assert(rows(program.recheck(setOf(gammaFile)).diagnostics, gammaFile) == alone(gammaFile))
    }

    @Test
    fun `re-entering does not disturb the answer for a file already walked`() {
        val program = seeded(vfs(), alphaFile)
        val first = alone(alphaFile)
        program.recheck(setOf(betaFile))
        program.recheck(setOf(gammaFile))
        assert(rows(program.recheck(setOf(alphaFile)).diagnostics, alphaFile) == first)
        assert(first.isNotEmpty())
    }

    @Test
    fun `the walked set grows by exactly the files asked about`() {
        val program = seeded(vfs(), alphaFile)
        assert(program.walkedFiles == setOf(alphaFile))
        program.recheck(setOf(betaFile))
        assert(program.walkedFiles == setOf(alphaFile, betaFile))
        // A file already walked costs nothing and changes nothing.
        program.recheck(setOf(betaFile))
        assert(program.walkedFiles == setOf(alphaFile, betaFile))
    }

    @Test
    fun `three files cost ONE build - the receipt is a count of builds, not a time`() {
        val vfs = vfs()
        val program = seeded(vfs, alphaFile)
        val afterSeed = vfs.reads
        program.recheck(setOf(betaFile))
        program.recheck(setOf(gammaFile))
        // The control: a build DID happen for the seed, so the equality below is a
        // reading from a live instrument rather than a zero from a dead one.
        assert(afterSeed == 1)
        assert(vfs.reads == 1)
    }

    @Test
    fun `a re-entry runs a strict SUBSET of the init passes, and it is the recorded one`() {
        val program = seeded(vfs(), alphaFile)
        program.recheck(setOf(betaFile))
        // The receipt of the whole mechanism. Both bounds matter: an empty set would
        // mean nothing re-enters (and the answers above would be the seed build's),
        // and a set as large as the dispatch would mean nothing is being saved.
        //
        // (INC.21) RAISED THE UPPER BOUND FROM 300 TO 360, AND THE REASON IS THE
        // POINT OF THE PIN RATHER THAN AN EXCUSE FOR IT. The classified set is not
        // a constant: it is exactly the passes that read the partition view, so
        // every pass this arc narrows JOINS it. (INC.17) measured 204 of 417 init
        // rows; after (INC.21)'s 24 it is 300 here and 304 on tsc's own sources.
        // The mechanism is unchanged and still a strict subset of the dispatch --
        // but the replay's own advantage shrinks as the FLOOR it is replayed
        // against shrinks, which is measured in that round's note and is one more
        // reason (INC.19)'s refusal of the replay as a default path stands.
        assert(program.replayedPasses.isNotEmpty())
        assert(program.replayedPasses.size < 360)
        // `checkSpine` reads the partition directly and is the one row that MUST be
        // in it — without the spine the new file is never walked at all.
        assert("checkSpine" in program.replayedPasses)
    }

    /**
     * THE OPT-IN PIN — the load-bearing one, because (INC.17) is REFUSED and the
     * only thing standing between the refusal and a wrong hover is that nothing
     * reaches this code.
     *
     * Arming a [RecheckHolder] turns on the partition-read recording, installs the
     * `RecheckWitnessList` over the diagnostics list and makes the checker retain
     * itself. None of that may be observable in the build's OWN answer — if it
     * were, the "experimental, off by default" framing would be a fiction and
     * every ordinary compile would be paying for, or perturbed by, a path it never
     * asked for.
     */
    @Test
    fun `arming a holder does not change the build's own answer - the opt-in is behaviour-free`() {
        fun build(hold: Boolean, narrow: Boolean): List<String> {
            val holder = if (hold) RecheckHolder() else null
            val result = ProjectCompiler(vfs()).build(
                "/proj",
                noEmit = true,
                recheckOnly = if (narrow) setOf(alphaFile) else null,
                recheckHolder = holder,
            )
            // The control: arming must actually have happened, or this compares two
            // identical unarmed builds and proves nothing (CLAUDE.md round 873).
            if (hold) assert(holder!!.recheck != null) else assert(holder == null)
            return result.diagnostics
                .map { "${it.fileName}|TS${it.code}@${it.start}+${it.length} ${it.message}" }
                .sorted()
        }
        assert(build(hold = true, narrow = true) == build(hold = false, narrow = true))
        assert(build(hold = true, narrow = false) == build(hold = false, narrow = false))
        // …over a non-empty population, so neither equality is two empty lists.
        assert(build(hold = false, narrow = false).isNotEmpty())
    }

    /**
     * THE CAPTURE CHANNEL IS WIRED, AND IS DELIBERATELY **NOT** PINNED EQUIVALENT.
     *
     * `scripts/replay-differential.sh` grades it against a fresh narrowed build
     * over 373,879 spans and finds it DIVERGING in **43 of 75 files** of the
     * compiler profile at HEAD ((INC.40), re-measured 2026-08-24 — the "5 of 75"
     * this comment used to carry predated (INC.26)/(INC.28)); the surviving rows
     * are overwhelmingly the union-alias DISPLAY family rather than the lost
     * generic inference named here, and (INC.19) closed the lost
     * type-parameter constraint. THIS fixture cannot reproduce either (it has no
     * generic with a constraint referring to a sibling parameter — that shape is
     * [ProjectRecheckConstraintTest]'s, and it IS pinned equivalent there), so an
     * equality assertion here would pass and would mean "soundness", which is
     * false. What is honest to assert is that the re-entry installs the request
     * and produces rows at all — i.e. that the channel is live rather than dead.
     */
    @Test
    fun `the capture channel is wired for a re-entered file - equivalence is NOT asserted here`() {
        val program = seeded(vfs(), alphaFile)
        val spans = SourceIndex.of(
            betaText, betaFile, computeParserFlags(betaFile, betaText, CompilerOptions()),
        ).identifiers().map { TypeCaptureSpan(betaFile, it.pos, it.end) }.distinct()
        assert(spans.isNotEmpty())
        val answer = program.recheck(setOf(betaFile), TypeCaptureRequest(spans))
        assert(answer.capturedTypes.any { it.fileName == betaFile })
    }

    @Test
    fun `an unpartitioned program refuses a re-entry rather than pretending to do one`() {
        val holder = RecheckHolder()
        ProjectCompiler(vfs()).build("/proj", noEmit = true, recheckHolder = holder)
        val program = requireNotNull(holder.recheck)
        var refused = false
        try {
            program.recheck(setOf(betaFile))
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assert(refused)
    }
}
