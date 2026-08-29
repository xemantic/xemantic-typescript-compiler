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
 * (INC.46) [Project.diagnostics] after an edit — the project-wide answer served
 * WITHOUT rebuilding the whole program when the edit moved no exported signature.
 *
 * ## The two families, and why both are needed
 *
 * **VALUE.** The incremental answer must equal a fresh whole-program build's, row
 * for row. That is the only family that can see the failure that matters — stale
 * rows reported as current — and it is graded against a SECOND project opened on
 * the same text, so nothing about the first project's state can flatter it.
 *
 * **COST.** How many BUILDS the answer took, counted at the backing [Vfs] exactly as
 * `ProjectNarrowDiagnosticsTest` counts them. Without this family every pin below
 * passes against an implementation that simply rebuilds every time — which is the
 * pre-(INC.46) behaviour, i.e. the whole thing under test.
 *
 * Neither family is timed: a timed pin over a compile is a coin flip (CLAUDE.md,
 * round 868), and the wall-clock figures live in the session note.
 *
 * ## What each pin is FOR
 *
 * The mechanism's five preconditions each have a pin that fails uniquely if it is
 * dropped: a body-only edit is served, a SIGNATURE edit is not, an edit that changes
 * the program's FILE SET is not, a config edit is not, and a file that ESCAPES is
 * never served. The body-only and signature pins are a PAIR on purpose — an
 * implementation that always serves passes the first, one that never serves passes
 * the second, and only both together say the gate discriminates.
 */
class ProjectIncrementalDiagnosticsTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val aFile = "/proj/src/a.ts"
    private val bFile = "/proj/src/b.ts"

    /** `b.ts` exports the type `a.ts` depends on, so the two are genuinely coupled. */
    private val bText = """
        export interface Shape {
            readonly kind: string;
        }
        export function describe(s: Shape): string {
            return s.kind;
        }
        export const wrong: number = "text";
    """.trimIndent() + "\n"

    private val aText = """
        import { Shape, describe } from "./b";
        export const shape: Shape = { kind: 1 };
        export const label: string = describe(shape);
    """.trimIndent() + "\n"

    private fun files(a: String = aText, b: String = bText) = mapOf(
        "/proj/tsconfig.json" to config,
        aFile to a,
        bFile to b,
    )

    /** The diagnostics as comparable rows — sorted, so a cross-build order difference
     *  is not mistaken for a content one (`ProjectNarrowDiagnosticsTest`'s reasoning). */
    private fun rows(diagnostics: List<Diagnostic>): List<String> =
        diagnostics.map { "${it.fileName}|${it.start}|${it.code}|${it.message}" }.sorted()

    /** What a project opened FRESH on [contents] reports — the reference answer. */
    private fun freshRows(contents: Map<String, String>): List<String> {
        val project = Project.open("/proj", InMemoryVfs(contents))
        try {
            return rows(project.diagnostics())
        } finally {
            project.close()
        }
    }

    /**
     * How many builds [block] performed, counted at the backing [Vfs].
     *
     * The unit is reads of one PATH, defaulting to `tsconfig.json` because every
     * `ProjectCompiler.build` loads it exactly once and nothing caches that across
     * builds (the control pin below establishes it rather than assuming it).
     *
     * **The default is wrong for a test that EDITS the config**, and that is why the
     * path is a parameter: an overlaid file is served from the overlay and never
     * reaches the backing store, so a config edit makes the config's read count stop
     * moving — which reads as "no build" when a build certainly happened. Such a test
     * counts a SOURCE file instead, with its own control.
     */
    private fun buildsIn(
        counting: CountingVfs,
        path: String = "/proj/tsconfig.json",
        block: () -> Unit,
    ): Int {
        val before = counting.readsOf(path)
        block()
        return counting.readsOf(path) - before
    }

    /** A project whose first whole-program build has already happened. */
    private fun builtProject(
        contents: Map<String, String> = files(),
    ): Pair<Project, CountingVfs> {
        val counting = CountingVfs(InMemoryVfs(contents))
        val project = Project.open("/proj", counting)
        project.diagnostics()
        return project to counting
    }

    // ---- VALUE ------------------------------------------------------------------

    /**
     * A change confined to a function BODY leaves `b.ts`'s export surface intact, so
     * every other file's rows carry over — and the answer must still equal a fresh
     * build's, including `a.ts`'s cross-file error, which only a whole program can
     * find.
     */
    @Test
    fun `a body-only edit answers exactly what a fresh whole-program build answers`() {
        val editedB = bText.replace("return s.kind;", "const k = s.kind;\n    return k;")
        val (project, _) = builtProject()
        try {
            project.updateFile(bFile, editedB)
            assert(rows(project.diagnostics()) == freshRows(files(b = editedB)))
        } finally {
            project.close()
        }
    }

    /**
     * The other direction, and the one a serving implementation cannot fake: an edit
     * that MOVES an exported signature must fall back, and the answer must still be
     * the whole program's — here the changed return type makes `a.ts`'s `label`
     * wrong, a row in a file the edit did not touch.
     */
    @Test
    fun `a signature edit answers exactly what a fresh whole-program build answers`() {
        val editedB = bText
            .replace("export function describe(s: Shape): string {", "export function describe(s: Shape): number {")
            .replace("return s.kind;", "return s.kind.length;")
        val (project, _) = builtProject()
        try {
            project.updateFile(bFile, editedB)
            assert(rows(project.diagnostics()) == freshRows(files(b = editedB)))
        } finally {
            project.close()
        }
    }

    /**
     * A SEQUENCE, which is what the (INC.46) gate asks for and what a single-edit pin
     * cannot see: the surface must carry forward, so the second body-only edit is
     * served from the FIRST one's answer rather than from a rebuild — and a
     * signature edit in the middle must still be correct afterwards.
     */
    @Test
    fun `a sequence of edits agrees with a fresh build at every step`() {
        val (project, _) = builtProject()
        try {
            var b = bText
            for (replacement in listOf(
                "const k = s.kind;\n    return k;",
                "const k = s.kind;\n    const j = k;\n    return j;",
                "return s.kind;",
            )) {
                b = bText.replace("return s.kind;", replacement)
                project.updateFile(bFile, b)
                assert(rows(project.diagnostics()) == freshRows(files(b = b)))
            }
            // …and a signature edit at the end, from an incrementally-maintained state.
            val signature = b
                .replace("export function describe(s: Shape): string {", "export function describe(s: Shape): number {")
                .replace("return s.kind;", "return s.kind.length;")
                .replace("return k;", "return k.length;")
                .replace("return j;", "return j.length;")
            project.updateFile(bFile, signature)
            assert(rows(project.diagnostics()) == freshRows(files(b = signature)))
        } finally {
            project.close()
        }
    }

    /**
     * An edit that ADDS AN IMPORT changes the program's file set, which no signature
     * comparison can see — the crawl finds a different program, and that must fall
     * back rather than answer from a baseline describing the old one.
     */
    @Test
    fun `an edit that adds a file to the program answers what a fresh build answers`() {
        val cFile = "/proj/src/c.ts"
        val cText = "export const extra: number = \"wrong\";\n"
        val base = files() + (cFile to cText)
        val counting = CountingVfs(InMemoryVfs(files()))
        val project = Project.open("/proj", counting)
        try {
            project.diagnostics()
            project.updateFile(cFile, cText)
            project.updateFile(aFile, "import \"./c\";\n" + aText)
            val expected = freshRows(base + (aFile to ("import \"./c\";\n" + aText)))
            assert(rows(project.diagnostics()) == expected)
        } finally {
            project.close()
        }
    }

    // ---- COST -------------------------------------------------------------------

    /**
     * THE CONTROL that establishes the unit, exactly as `ProjectNarrowDiagnosticsTest`
     * does: one plain build reads the config once. Without it every count below is a
     * number with no scale.
     */
    @Test
    fun `a plain build reads the config exactly once`() {
        val counting = CountingVfs(InMemoryVfs(files()))
        val project = Project.open("/proj", counting)
        try {
            assert(buildsIn(counting) { project.diagnostics() } == 1)
        } finally {
            project.close()
        }
    }

    /**
     * A body-only edit costs exactly ONE build — the narrowed one that recomputes the
     * edited file's rows and its fingerprint. Two would mean the gate ran and then
     * rebuilt anyway; the pin above says the answer is right, and this one says it was
     * not obtained by rebuilding.
     */
    @Test
    fun `a body-only edit costs one build`() {
        val editedB = bText.replace("return s.kind;", "const k = s.kind;\n    return k;")
        val (project, counting) = builtProject()
        try {
            project.updateFile(bFile, editedB)
            assert(buildsIn(counting) { project.diagnostics() } == 1)
        } finally {
            project.close()
        }
    }

    /**
     * A signature edit costs TWO — the narrowed build that discovers the surface moved,
     * then the rebuild. That is the price of the gate being wrong, and it is stated
     * rather than hidden: the mechanism is a bet that most edits are body-only, which
     * (INC.46)(2) measured at 67% over real commits.
     */
    @Test
    fun `a signature edit costs two builds - the gate then the rebuild`() {
        val editedB = bText
            .replace("export function describe(s: Shape): string {", "export function describe(s: Shape): number {")
            .replace("return s.kind;", "return s.kind.length;")
        val (project, counting) = builtProject()
        try {
            project.updateFile(bFile, editedB)
            assert(buildsIn(counting) { project.diagnostics() } == 2)
        } finally {
            project.close()
        }
    }

    /**
     * A repeated query after an incremental answer costs NOTHING, like any other
     * query on a clean project — the incremental answer must become this project's
     * standing answer, or an editor asking twice pays twice.
     */
    @Test
    fun `a repeated query after an incremental answer costs no build`() {
        val editedB = bText.replace("return s.kind;", "const k = s.kind;\n    return k;")
        val (project, counting) = builtProject()
        try {
            project.updateFile(bFile, editedB)
            project.diagnostics()
            assert(buildsIn(counting) { project.diagnostics() } == 0)
        } finally {
            project.close()
        }
    }

    /**
     * A CONFIG edit changes what the program IS, so nothing about the previous one
     * survives — no signature comparison is even attempted.
     */
    @Test
    fun `a config edit rebuilds`() {
        val (project, counting) = builtProject()
        try {
            // The unit, calibrated on THIS project for a source file: an edited config
            // is overlaid and no longer read from the backing store, so the usual
            // config-read unit is blind here.
            val perBuild = buildsIn(counting, bFile) { project.updateFile(aFile, aText); project.diagnostics() }
            assert(perBuild > 0)
            project.updateFile("/proj/tsconfig.json", config)
            // One build, and it is the WHOLE-program one: the gate refuses before
            // spending a narrowed build it would have to discard.
            assert(buildsIn(counting, bFile) { project.diagnostics() } == perBuild)
            assert(rows(project.diagnostics()) == freshRows(files()))
        } finally {
            project.close()
        }
    }

    /**
     * A file with no module syntax is a SCRIPT: its top-level names are program-wide,
     * so an edit to it can reach a file importing nothing from it. It ESCAPES, and an
     * escape must never be served — the pin is the COST, because the answer would be
     * right here either way and only the count says the gate refused.
     */
    @Test
    fun `an edit to a script file is never served incrementally`() {
        val scriptFile = "/proj/src/globals.ts"
        val scriptText = "declare const zzzGlobalThing: number;\n"
        val contents = files() + (scriptFile to scriptText)
        val counting = CountingVfs(InMemoryVfs(contents))
        val project = Project.open("/proj", counting)
        try {
            project.diagnostics()
            project.updateFile(scriptFile, scriptText + "declare const zzzOther: string;\n")
            // A rebuild, not a narrowed gate build followed by one.
            assert(buildsIn(counting) { project.diagnostics() } == 1)
        } finally {
            project.close()
        }
    }

    /**
     * `diagnostics(fileName)` inherits the incremental answer rather than forcing the
     * rebuild the mechanism exists to avoid — the per-file question is what an editor's
     * annotator actually asks.
     */
    @Test
    fun `the per-file question inherits the incremental answer`() {
        val editedB = bText.replace("return s.kind;", "const k = s.kind;\n    return k;")
        val (project, counting) = builtProject()
        try {
            project.updateFile(bFile, editedB)
            assert(buildsIn(counting) { project.diagnostics(aFile) } == 1)
            assert(buildsIn(counting) { project.diagnostics(bFile) } == 0)
        } finally {
            project.close()
        }
    }
}
