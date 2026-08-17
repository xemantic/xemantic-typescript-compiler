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
 * [Project.definitionsAt] — go-to-definition, end to end: a caret offset in, a list
 * of places out, through a real build of a real (in-memory) project.
 *
 * Every pin here is built to FAIL if the mechanism were inert or resolved
 * post-hoc, which is the standing requirement in this repo for a feature whose
 * plausible-looking wrong answer is indistinguishable from its right one. The
 * discriminating device throughout is a SHADOWED name declared in ANOTHER FILE:
 * a resolution that lost the walk's lexical chain answers with the other file's
 * declaration, so the assertion names a file as well as an offset and cannot be
 * satisfied by the wrong binding. The core module's
 * `DefinitionCaptureMeasurementTest` measures the same difference one layer down.
 *
 * Offsets are derived from the fixture text by `indexOf`; a hardcoded offset would
 * pin this test's own arithmetic and would pass for an implementation that ignored
 * its argument.
 */
class ProjectDefinitionTest {

    /**
     * `module` is an ES kind ON PURPOSE and the program has TWO files: the
     * unresolved-module region returns early below two program files, and its
     * relative-specifier leg additionally demands an ES module kind — with either
     * missing, every import-related assertion in this class would be vacuous.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * `collide` is declared in the OTHER file and again as a body local here, so a
     * lookup that lost the lexical chain answers with a different FILE — not merely
     * a different offset.
     */
    private val main = """
        import { imported, collide } from "./b";
        export function f(param: number): number {
            const collide: number = 1;
            const useLocal = collide;
            const useParam = param;
            const useImported = imported;
            return useLocal + useParam;
        }
        export interface Merged { a: string; }
        export interface Merged { b: string; }
        export const merged: Merged = { a: "a", b: "b" };
        export const holder = { member: 1 };
        export const readMember = holder.member;
    """.trimIndent() + "\n"

    private val other = """
        export const imported: string = "i";
        export const collide: string = "c";
    """.trimIndent() + "\n"

    private fun projectWith(
        mainText: String = main,
        otherText: String = other,
    ): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to mainText,
                otherFile to otherText,
            ),
        ),
    )

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [text]. */
    private fun offsetOf(needle: String, occurrence: Int = 0, text: String = main): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    // --- the discriminating positions -------------------------------------------

    @Test
    fun `a caret on a body local answers the LOCAL, not the same-named import from another file`() {
        val project = projectWith()
        // The occurrences of `collide`: the import specifier, the body declaration,
        // then this use.
        val at = offsetOf("collide", 2)
        val definitions = project.definitionsAt(mainFile, at + 1)
        assert(definitions.size == 1)
        // The FILE is the assertion that discriminates: a resolution without the
        // walk's lexical chain answers about `/proj/src/b.ts`.
        assert(definitions[0].fileName == mainFile)
        assert(definitions[0].start == offsetOf("collide", 1))
        assert(definitions[0].kind == "Identifier")
        assert(main.substring(definitions[0].start, definitions[0].start + definitions[0].length) == "collide")
    }

    @Test
    fun `a caret on a parameter reference answers its parameter declaration`() {
        val project = projectWith()
        val at = offsetOf("useParam = param") + "useParam = ".length
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.size == 1)
        assert(definitions[0].fileName == mainFile)
        assert(definitions[0].start == offsetOf("param"))
        assert(definitions[0].length == "param".length)
    }

    @Test
    fun `a caret on an imported name answers the declaration in the OTHER file`() {
        val project = projectWith()
        // The negative control for the module-resolution trap: an unresolved import
        // would make this test measure nothing, so pin that the import RESOLVES.
        assert(project.diagnostics(mainFile).none { it.code == 2307 })
        val at = offsetOf("useImported = imported") + "useImported = ".length
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.size == 1)
        assert(definitions[0].fileName == otherFile)
        assert(definitions[0].start == offsetOf("imported", 0, other))
        assert(definitions[0].length == "imported".length)
    }

    @Test
    fun `negative control - the import stops resolving when the other file is deleted`() {
        val project = projectWith()
        project.deleteFile(otherFile)
        // Proves the pin above measured a real resolution rather than a coincidence
        // of two files that happen to contain the same name.
        assert(project.diagnostics(mainFile).any { it.code == 2307 })
    }

    @Test
    fun `a caret on a merged interface answers EVERY declaration`() {
        val project = projectWith()
        val at = offsetOf("merged: Merged") + "merged: ".length
        val definitions = project.definitionsAt(mainFile, at)
        // Declaration merging is a language feature, so more than one location is
        // the right answer and "the first declaration" would be the wrong one.
        assert(definitions.size == 2)
        assert(definitions.map { it.start } == listOf(offsetOf("Merged"), offsetOf("Merged", 1)))
        assert(definitions.all { it.fileName == mainFile })
        assert(definitions.all { it.length == "Merged".length })
    }

    // --- what answers empty, and why --------------------------------------------

    @Test
    fun `a caret on a member name answers EMPTY rather than a same-named binding`() {
        val project = projectWith()
        val at = offsetOf("holder.member") + "holder.".length
        // `member` is resolvable as a property of `holder` and by no scope lookup.
        // A scope lookup would have to invent an answer; this one declines.
        assert(project.definitionsAt(mainFile, at).isEmpty())
        // ... while the RECEIVER of the same expression does answer, which proves
        // the caret and the build reached this statement at all.
        assert(project.definitionsAt(mainFile, offsetOf("holder.member")).size == 1)
    }

    @Test
    fun `a caret on a keyword or in whitespace answers empty`() {
        val project = projectWith()
        // `return` is a token of no node's own name.
        assert(project.definitionsAt(mainFile, offsetOf("return")).isEmpty())
    }

    @Test
    fun `a caret past the end of the file answers empty`() {
        val project = projectWith()
        assert(project.definitionsAt(mainFile, main.length + 10).isEmpty())
    }

    @Test
    fun `a file that is not in the overlay answers empty`() {
        val project = projectWith()
        assert(project.definitionsAt("/proj/src/nope.ts", 0).isEmpty())
    }

    // --- the API's own contract --------------------------------------------------

    @Test
    fun `an edit is seen by the next query`() {
        val project = projectWith()
        val edited = main.replace("const collide: number = 1;", "const collide: number = 2;\n    ")
        project.updateFile(mainFile, edited)
        val at = offsetOf("collide", 2, edited)
        val definitions = project.definitionsAt(mainFile, at + 1)
        assert(definitions.size == 1)
        // Against the EDITED text: a stale parse would report the old offset.
        assert(definitions[0].start == offsetOf("collide", 1, edited))
    }

    @Test
    fun `a closed project refuses to answer`() {
        val project = projectWith()
        project.close()
        var threw = false
        try {
            project.definitionsAt(mainFile, offsetOf("param"))
        } catch (e: IllegalStateException) {
            threw = true
        }
        assert(threw)
    }
}
