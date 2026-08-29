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
import kotlin.test.assertFailsWith

/**
 * (API.3c) [Project.semanticsAt] and [Project.fileSemantics] — MANY spans, ONE
 * build.
 *
 * The claim this class exists to hold is a claim about COUNT, not about answers: a
 * batch that quietly looped over the single-caret members would return exactly the
 * same values and be worth nothing. So the count pins count BUILDS, through the one
 * seam that cannot be faked from inside the implementation — the reads that reach
 * the backing [Vfs] — with a control pin establishing the unit. A timed assertion is
 * deliberately not used anywhere here: a timed pin over a compile is a coin flip
 * (CLAUDE.md, round 868).
 *
 * The second family is EQUIVALENCE: span for span, the batch must say what
 * [Project.quickInfoAt] and [Project.definitionsAt] say one at a time. That pin is
 * sharp precisely because the two paths are separate code — `Project.semanticsOf`
 * says why it does not re-express the single-caret members on top of itself.
 *
 * Offsets are derived from the fixture by `indexOf`; a hardcoded offset would pin
 * this test's own arithmetic.
 */
class ProjectSemanticsTest {

    /**
     * `module` is an ES kind and the program has TWO files, for the reason
     * `ProjectDefinitionTest` states: below two program files, or without an ES
     * module kind, every import-related assertion is vacuous.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * `collide` is declared in the OTHER file and again as a body local here, so an
     * answer that lost the walk-scoped state names a different FILE and a different
     * TYPE — which is what makes the batched answers discriminating rather than
     * merely plausible.
     */
    private val main = """
        import { collide } from "./b";
        export const top: string = "t";
        export function f(param: number): number {
            const collide: number = 1;
            const useLocal = collide;
            const useParam = param;
            return useLocal + useParam;
        }
        export const holder = { member: 1 };
        export const readMember = holder.member;
    """.trimIndent() + "\n"

    private val other = """
        export const collide: string = "c";
    """.trimIndent() + "\n"

    private fun vfsWith(mainText: String = main) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            mainFile to mainText,
            otherFile to other,
        ),
    )

    private fun projectWith(mainText: String = main): Project =
        Project.open("/proj", vfsWith(mainText))

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [text]. */
    private fun offsetOf(needle: String, occurrence: Int = 0, text: String = main): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /** Carets inside six distinct identifiers, spread over the fixture. */
    private fun sixOffsets(): List<Int> = listOf(
        offsetOf("top") + 1,
        offsetOf("function f") + "function ".length,
        offsetOf("param: number") + 1,
        offsetOf("useLocal = collide") + "useLocal = ".length + 1,
        offsetOf("useParam = param") + "useParam = ".length + 1,
        offsetOf("readMember") + 1,
    )

    // --- THE MECHANISM: N spans cost ONE build ----------------------------------

    /**
     * How many builds [block] performed, counted at the backing [Vfs].
     *
     * The unit is reads of `tsconfig.json`, and it is that rather than the sum of
     * all reads because the SUM is not stable: some cache inside the compiler warms
     * across builds within one JVM and takes a source read with it, which showed up
     * as a build costing 4 touches where its predecessors cost 5 — an
     * order-dependent pin, i.e. the kind that cries wolf (CLAUDE.md, round 868, one
     * mechanism over). Every `ProjectCompiler.build` loads the config exactly once
     * and nothing caches that across builds, which `a plain build reads the config
     * exactly once, every time` establishes as a control rather than assuming.
     */
    private fun buildsIn(counting: CountingVfs, block: () -> Unit): Int {
        val before = counting.readsOf("/proj/tsconfig.json")
        block()
        return counting.readsOf("/proj/tsconfig.json") - before
    }

    /** A project over a counting Vfs, with everything that is NOT a build warmed. */
    private fun countedProject(mainText: String = main): Pair<Project, CountingVfs> {
        val counting = CountingVfs(vfsWith(mainText))
        val project = Project.open("/proj", counting)
        // Warms the project's own parse and resolved options — both read through this
        // Vfs and neither is a build.
        project.nodeInfoAt(mainFile, 0)
        return project to counting
    }

    @Test
    fun `a plain build reads the config exactly once, every time`() {
        // The control the two count pins below rest on. Without it, "the batch cost
        // one config read" would be a fact about `TsConfigLoader` rather than about
        // the number of compiles.
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { project.diagnostics() } == 1)
        // (INC.46) each of these ADDS AN EXPORT, which moves the file's export surface,
        // so each costs TWO builds: the narrowed one that discovers the signature moved
        // and then the whole-program rebuild. `ProjectIncrementalDiagnosticsTest` pins
        // the cost model itself; it is spelled out here because this is the control the
        // two count pins below rest on.
        project.updateFile(mainFile, main + "export const extra = 1;\n")
        assert(buildsIn(counting) { project.diagnostics() } == 2)
        project.updateFile(mainFile, main + "export const extra2 = 2;\n")
        assert(buildsIn(counting) { project.diagnostics() } == 2)
        // And a query on a CLEAN project builds not at all.
        assert(buildsIn(counting) { project.diagnostics() } == 0)
    }

    @Test
    fun `six carets in one buffer are ONE build, batched or not`() {
        // (INC.13) INVERTED IN PLACE, and the inversion is the finding rather than an
        // accounting change. This used to read `loopBuilds == offsets.size` and was
        // named "a batched query over six spans costs ONE build where six single
        // queries cost six": a caret-scoped request named ONE span, so six carets were
        // six compiles and BATCHING was the whole saving. The question put to the
        // compiler is now the FILE's (`Project.captureAround`), so the batch and the
        // loop ask the same thing and a host gets the ratio without batching for it.
        val (project, counting) = countedProject()
        val offsets = sixOffsets()

        var batched: List<SemanticInfo> = emptyList()
        val batchBuilds = buildsIn(counting) { batched = project.semanticsAt(mainFile, offsets) }
        val loopBuilds = buildsIn(counting) {
            for (offset in offsets) project.quickInfoAt(mainFile, offset)
        }

        assert(batched.size == offsets.size)
        assert(batchBuilds == 1)
        assert(loopBuilds == 0)

        // …and from a FRESH state, where nothing is memoized, the unbatched loop is
        // still ONE build and not six. That is the sharp form of the claim: the compile
        // count does not scale with the number of carets in a buffer, whoever asks.
        project.updateFile(mainFile, main)
        val loopFresh = buildsIn(counting) {
            for (offset in offsets) project.quickInfoAt(mainFile, offset)
        }
        assert(loopFresh == 1)
    }

    @Test
    fun `describing one caret BOTH ways costs ONE build either way since the memo`() {
        // (INC.12) INVERTED IN PLACE, and the inversion is the finding rather than an
        // accounting change. This used to read `== 2` and the comment said asking for
        // quick info and go-to-definition separately "doubles the compiles for a single
        // caret" — which was true and is exactly what `Project.captures` closed: the two
        // members build an IDENTICAL `TypeCaptureRequest` and read different channels of
        // the one answer, so the second is served without building.
        //
        // What batching still buys is the pin above: SIX carets are one build batched
        // and six unbatched, because six carets are six different requests. The saving
        // for ONE caret is now automatic.
        val (project, counting) = countedProject()
        val at = offsetOf("useLocal = collide") + "useLocal = ".length + 1

        assert(buildsIn(counting) { project.semanticsAt(mainFile, listOf(at)) } == 1)
        // ZERO, and for a third reason worth naming: a ONE-CARET batch asks exactly the
        // question hover asks, so these two do not merely share a request with each
        // other — they repeat the batched one.
        assert(
            buildsIn(counting) {
                project.quickInfoAt(mainFile, at)
                project.definitionsAt(mainFile, at)
            } == 0,
        )
        // From a FRESH state the pair is ONE build, which is the claim this test was
        // written for, now with the opposite answer.
        project.updateFile(mainFile, main)
        assert(
            buildsIn(counting) {
                project.quickInfoAt(mainFile, at)
                project.definitionsAt(mainFile, at)
            } == 1,
        )
    }

    @Test
    fun `a whole-file sweep is also ONE build, however many identifiers it finds`() {
        val (project, counting) = countedProject()
        var sweep: List<SemanticInfo> = emptyList()
        assert(buildsIn(counting) { sweep = project.fileSemantics(mainFile) } == 1)
        // Not a token or two: the sweep really did ask about the whole file.
        assert(sweep.size > 10)
    }

    // --- EQUIVALENCE: the batch says what the single-caret members say -----------

    @Test
    fun `every batched entry equals what quickInfoAt and definitionsAt answer alone`() {
        val project = projectWith()
        val offsets = sixOffsets()
        val batched = project.semanticsAt(mainFile, offsets)
        assert(batched.size == offsets.size)
        for (offset in offsets) {
            val entry = batched.single { offset >= it.start && offset < it.end }
            assert(entry.quickInfo == project.quickInfoAt(mainFile, offset))
            assert(entry.definitions == project.definitionsAt(mainFile, offset))
        }
    }

    @Test
    fun `a whole-file sweep agrees with the single-caret members at every span it reports`() {
        val project = projectWith()
        val sweep = project.fileSemantics(mainFile)
        assert(sweep.isNotEmpty())
        for (entry in sweep) {
            assert(entry.quickInfo == project.quickInfoAt(mainFile, entry.start))
            assert(entry.definitions == project.definitionsAt(mainFile, entry.start))
        }
    }

    // --- the walk-scoped answers survive batching -------------------------------

    @Test
    fun `a batched body local answers the LOCAL type, not the same-named import's`() {
        // Rounds 911/913's discriminating position, asked in bulk: `collide` is a
        // `number` here and a `string` in the other file, so a batch that lost the
        // walk's ambient reads `string`.
        val project = projectWith()
        val at = offsetOf("useLocal = collide") + "useLocal = ".length
        val entry = project.semanticsAt(mainFile, sixOffsets() + at).single { it.start == at }
        assert(entry.quickInfo != null)
        assert(entry.quickInfo.displayString == "number")
    }

    @Test
    fun `a batched body local answers the LOCAL declaration, not the other file's`() {
        val project = projectWith()
        val at = offsetOf("useLocal = collide") + "useLocal = ".length
        val entry = project.semanticsAt(mainFile, sixOffsets() + at).single { it.start == at }
        assert(entry.definitions.size == 1)
        assert(entry.definitions[0].fileName == mainFile)
        // Occurrence 0 is the import specifier, 1 the body declaration, 2 the use.
        assert(entry.definitions[0].start == offsetOf("collide", 1))
    }

    @Test
    fun `a batched parameter reference answers the parameter, which nothing durable binds`() {
        // Post-hoc this position answers `any` and no definition at all
        // (`TypeCaptureMeasurementTest`, `DefinitionCaptureMeasurementTest`), so both
        // halves of this pin fail if a batch resolved after the walk.
        val project = projectWith()
        val at = offsetOf("useParam = param") + "useParam = ".length
        val entry = project.semanticsAt(mainFile, sixOffsets() + at).single { it.start == at }
        assert(entry.quickInfo != null)
        assert(entry.quickInfo.displayString == "number")
        assert(entry.definitions.size == 1)
        assert(entry.definitions[0].start == offsetOf("param: number"))
    }

    // --- the candidate set of a sweep -------------------------------------------

    /**
     * (API.3d) THE MEANING OF THIS PIN CHANGED. It used to assert that a swept
     * member name carried a type and, deliberately, NO definition — the shape that
     * proved round 913's refusal was a refusal. Member go-to-definition now exists,
     * so the same span asserts the same include rule with the other half inverted:
     * a member name is still swept, and its definition is now the MEMBER's
     * declaration. The refusal it used to pin is gone because the gap it described
     * is closed, not because the assertion became inconvenient.
     */
    @Test
    fun `a sweep reports a MEMBER name with a type and its own declaration`() {
        val project = projectWith()
        val at = offsetOf("holder.member") + "holder.".length
        val entry = project.fileSemantics(mainFile).single { it.start == at }
        assert(entry.kind == "Identifier")
        assert(entry.quickInfo != null)
        // (BUG.4) STRENGTHENED, round 924: this used to assert only that the swept
        // member carried SOME type, because the type it carried was a free-name
        // resolution of the member's spelling and therefore not worth asserting. A
        // sweep now carries the member's own type, so the batch pins it. `number`
        // rather than `1`: an object literal's property initialized with a numeric
        // literal widens, which is the compiler's own inference and is exactly why
        // this is read out of a run rather than predicted.
        assert(entry.quickInfo.displayString == "number")
        // Resolved through the receiver, so it is the object literal's own `member`
        // — a batch that dropped the member mechanism reads an empty list here.
        assert(entry.definitions.size == 1)
        assert(entry.definitions[0].start == offsetOf("member: 1"))
        assert(entry.definitions[0].length == "member".length)
    }

    @Test
    fun `a sweep reports only identifiers - no keyword, punctuation or literal span`() {
        val project = projectWith()
        val sweep = project.fileSemantics(mainFile)
        assert(sweep.all { it.kind == "Identifier" })
        // Every reported span really is an identifier in the text.
        assert(sweep.all { main.substring(it.start, it.end).first().isLetter() })
        // The `= 1` literal, the `number` keyword and the `{` are not spans.
        val literalAt = offsetOf("collide: number = 1") + "collide: number = ".length
        assert(sweep.none { literalAt >= it.start && literalAt < it.end })
    }

    @Test
    fun `a sweep finds the identifiers on both sides of a declaration`() {
        val project = projectWith()
        val sweep = project.fileSemantics(mainFile)
        val starts = sweep.map { it.start }.toSet()
        assert(offsetOf("top") in starts)
        assert(offsetOf("function f") + "function ".length in starts)
        assert(offsetOf("param: number") in starts)
        assert(offsetOf("useLocal = collide") + "useLocal = ".length in starts)
    }

    // --- dedup and ordering -----------------------------------------------------

    @Test
    fun `several carets inside one identifier collapse to ONE entry`() {
        val project = projectWith()
        val at = offsetOf("readMember")
        val entries = project.semanticsAt(mainFile, listOf(at, at + 1, at + 2, at + 3))
        assert(entries.size == 1)
        assert(entries[0].start == at)
    }

    @Test
    fun `entries come back sorted by position whatever order the offsets arrive in`() {
        val project = projectWith()
        val offsets = sixOffsets()
        val forwards = project.semanticsAt(mainFile, offsets).map { it.start }
        val backwards = project.semanticsAt(mainFile, offsets.reversed()).map { it.start }
        assert(forwards == forwards.sorted())
        assert(backwards == forwards)
    }

    // --- ordinary answers, not crashes ------------------------------------------

    @Test
    fun `negative control - an empty offset list answers empty and does NOT build`() {
        val (project, counting) = countedProject()
        assert(buildsIn(counting) { assert(project.semanticsAt(mainFile, emptyList()).isEmpty()) } == 0)
    }

    @Test
    fun `negative control - offsets that land in no node answer empty and do NOT build`() {
        val (project, counting) = countedProject()
        val outside = listOf(-1, main.length + 10)
        assert(buildsIn(counting) { assert(project.semanticsAt(mainFile, outside).isEmpty()) } == 0)
    }

    @Test
    fun `negative control - a file with no identifiers sweeps to empty and does NOT build`() {
        val (project, counting) = countedProject(mainText = "// only a comment\n")
        assert(buildsIn(counting) { assert(project.fileSemantics(mainFile).isEmpty()) } == 0)
    }

    @Test
    fun `negative control - an unknown file answers empty`() {
        val project = projectWith()
        assert(project.semanticsAt("/proj/src/nope.ts", listOf(0, 1)).isEmpty())
        assert(project.fileSemantics("/proj/src/nope.ts").isEmpty())
    }

    @Test
    fun `a closed project refuses both semantic sweeps`() {
        val project = projectWith()
        project.close()
        assertFailsWith<IllegalStateException> { project.semanticsAt(mainFile, listOf(0)) }
        assertFailsWith<IllegalStateException> { project.fileSemantics(mainFile) }
    }

    // --- the overlay ------------------------------------------------------------

    @Test
    fun `a sweep of an edited file describes the unsaved buffer`() {
        val project = projectWith()
        val edited = "export const top: boolean = true;\n"
        project.updateFile(mainFile, edited)
        val entry = project.fileSemantics(mainFile).single { it.start == edited.indexOf("top") }
        assert(entry.quickInfo != null)
        assert(entry.quickInfo.displayString == "boolean")
    }

    @Test
    fun `negative control - before the edit the same span answers the on-disk type`() {
        val project = projectWith()
        val entry = project.fileSemantics(mainFile).single { it.start == offsetOf("top") }
        assert(entry.quickInfo != null)
        assert(entry.quickInfo.displayString == "string")
    }

    @Test
    fun `a sweep does not disturb the diagnostics the project reports`() {
        // A capture build types expressions the checker had no reason to type, so it
        // is deliberately never cached as THE build — and a whole-file sweep types far
        // more of them than a single hover does.
        val project = projectWith()
        val before = project.diagnostics().map { "${it.code}@${it.start}" }
        project.fileSemantics(mainFile)
        val after = project.diagnostics().map { "${it.code}@${it.start}" }
        assert(after == before)
    }
}
