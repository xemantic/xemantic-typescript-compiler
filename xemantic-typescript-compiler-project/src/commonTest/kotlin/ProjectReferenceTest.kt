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
 * (API.5) [Project.referencesAt] and [Project.documentHighlightsAt], end to end: a
 * caret offset in, every place that refers to the same thing out, through a real
 * build of a real (in-memory) project.
 *
 * ## What every pin here is built to fail against
 *
 * A TEXT SEARCH. Grouping by spelling passes every positive assertion in this class
 * and fails exactly one — `two different bindings spelled alike are two groups` —
 * which is why that test is written first and why the fixture carries the SAME
 * SPELLING three times, in two files, at three different binding levels. Everything
 * else here (imports, merged declarations, members, edits) is a positive control
 * that the real mechanism reaches the case at all.
 *
 * The second thing they fail against is a resolution that lost the walk's lexical
 * chain: the shadowing rows name a FILE as well as an offset, so a post-hoc answer
 * is not merely coarser, it points somewhere else.
 *
 * Offsets are derived from the fixture text with `indexOf`; a hardcoded offset would
 * pin this test's own arithmetic and pass for an implementation that ignored its
 * argument.
 */
class ProjectReferenceTest {

    /**
     * `module` is an ES kind ON PURPOSE and the program has TWO files: the
     * unresolved-module region returns early below two program files, and its
     * relative-specifier leg additionally demands an ES module kind — with either
     * missing, every import-crossing assertion here would be vacuous. The negative
     * control below pins that the import genuinely resolves.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * `collide` is spelled three times over two files and means three different
     * things: a body local here, a file-level `const` here, and an exported `const`
     * over there. That is the discriminator, and it is why nothing in this class
     * asserts a COUNT alone — an answer of the right size can still be the wrong set.
     */
    private val main = """
        import { imported, Shape, Overloaded } from "./b";
        export function f(param: number): number {
            const collide: number = 1;
            const useLocal = collide;
            return useLocal + param;
        }
        const collide: string = "file level";
        export const useFileLevel = collide;
        export const useImportedOnce = imported;
        export const useImportedTwice = imported;
        export interface Merged { a: string; }
        export interface Merged { b: string; }
        export const merged: Merged = { a: "a", b: "b" };
        declare const shape: Shape;
        export const readWidth = shape.width;
        export const readWidthAgain = shape.width;
        interface Local { prop: string; unused: number; }
        declare const local: Local;
        export const readProp = local.prop;
        declare const over: Overloaded;
        export const readOver = over.method;
        declare const united: { shared: string } | { shared: number };
        export const readShared = united.shared;
        export let counter: number = 0;
        export function bump(): void { counter = counter + 1; }
    """.trimIndent() + "\n"

    private val other = """
        export const imported: string = "i";
        export const collide: boolean = true;
        export interface Shape { width: number; }
        export interface Overloaded { method(): void; }
        export interface Overloaded { method(x: number): void; }
        export const useImportedThere = imported;
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

    /** [references] as `file@start` strings, so a failure diagram names the places. */
    private fun places(references: List<ReferenceLocation>): List<String> =
        references.map { "${it.fileName.substringAfterLast('/')}@${it.start}" }

    // --- THE DISCRIMINATOR -------------------------------------------------------

    /**
     * The one pin a `grep` fails. Three bindings, one spelling, two files: the body
     * local, the file-level `const` and the export in the other file must come back
     * as three DISJOINT sets, each containing its own declaration and its own uses.
     *
     * Asserted as exact sets rather than as sizes, because a name match answers a
     * six-element set for all three carets and a size assertion would be satisfied by
     * any three of them.
     */
    @Test
    fun `two different bindings spelled alike are two groups - the caret decides which`() {
        val project = projectWith()
        val localDeclaration = offsetOf("collide", 0)
        val localUse = offsetOf("collide", 1)
        val fileLevelDeclaration = offsetOf("collide", 2)
        val fileLevelUse = offsetOf("collide", 3)
        val otherDeclaration = offsetOf("collide", 0, other)

        assert(
            places(project.referencesAt(mainFile, localUse)) ==
                listOf("a.ts@$localDeclaration", "a.ts@$localUse"),
        )
        assert(
            places(project.referencesAt(mainFile, fileLevelUse)) ==
                listOf("a.ts@$fileLevelDeclaration", "a.ts@$fileLevelUse"),
        )
        assert(
            places(project.referencesAt(otherFile, otherDeclaration)) ==
                listOf("b.ts@$otherDeclaration"),
        )
    }

    /**
     * The discriminator's mirror: only the declaration site carries
     * [ReferenceLocation.isDeclaration], and it carries it for the binding the caret
     * chose rather than for the first same-named thing in the file.
     */
    @Test
    fun `the declaration is included and flagged - and it is the caret's declaration`() {
        val project = projectWith()
        val references = project.referencesAt(mainFile, offsetOf("collide", 3))
        assert(references.single { it.isDeclaration }.start == offsetOf("collide", 2))
        assert(references.single { !it.isDeclaration }.start == offsetOf("collide", 3))
    }

    // --- across the import boundary ----------------------------------------------

    /**
     * The mirror of the discriminator: the SAME thing reached through an IMPORT in
     * another file must be found. The import specifier itself counts as a reference —
     * it names the original, which the capture's alias hop resolves — so the answer
     * spans both files and includes the `import { imported }` clause.
     */
    @Test
    fun `an imported name is one group across both files - the import clause included`() {
        val project = projectWith()
        // The negative control for the module-resolution trap: an unresolved import
        // would leave this measuring nothing.
        assert(project.diagnostics(mainFile).none { it.code == 2307 })
        val references = project.referencesAt(mainFile, offsetOf("useImportedOnce = imported") + "useImportedOnce = ".length)
        assert(
            places(references) == listOf(
                "a.ts@${offsetOf("imported", 0)}",
                "a.ts@${offsetOf("imported", 1)}",
                "a.ts@${offsetOf("imported", 2)}",
                "b.ts@${offsetOf("imported", 0, other)}",
                "b.ts@${offsetOf("imported", 1, other)}",
            ),
        )
        // The declaration is the one in the OTHER file, not the import clause.
        assert(
            references.single { it.isDeclaration }.fileName == otherFile,
        )
    }

    /**
     * The same group, asked from the declaring side. A search seeded at the export
     * must reach the importer — which is the direction that fails when the alias hop
     * runs only one way.
     */
    @Test
    fun `the same group is reached from the other file's declaration`() {
        val project = projectWith()
        val fromThere = project.referencesAt(otherFile, offsetOf("imported", 0, other))
        val fromHere = project.referencesAt(mainFile, offsetOf("imported", 1))
        assert(places(fromThere) == places(fromHere))
    }

    /**
     * A document highlight is the same identity restricted to one buffer, which is
     * the whole reason it is a separate member: the imported name has occurrences in
     * both files and only this file's may be drawn.
     */
    @Test
    fun `a document highlight answers this file only`() {
        val project = projectWith()
        val at = offsetOf("useImportedOnce = imported") + "useImportedOnce = ".length
        assert(
            places(project.documentHighlightsAt(mainFile, at)) == listOf(
                "a.ts@${offsetOf("imported", 0)}",
                "a.ts@${offsetOf("imported", 1)}",
                "a.ts@${offsetOf("imported", 2)}",
            ),
        )
        assert(project.documentHighlightsAt(mainFile, at).none { it.isDeclaration })
    }

    // --- declaration merging ------------------------------------------------------

    /**
     * Two `interface Merged` blocks are ONE symbol with TWO declarations, so every
     * occurrence must come back as one group with both declarations flagged. An
     * identity keyed on a single "primary" declaration splits this in half.
     */
    @Test
    fun `a merged symbol is one group with every contributing declaration flagged`() {
        val project = projectWith()
        val references = project.referencesAt(mainFile, offsetOf("merged: Merged") + "merged: ".length)
        assert(
            places(references) == listOf(
                "a.ts@${offsetOf("Merged", 0)}",
                "a.ts@${offsetOf("Merged", 1)}",
                "a.ts@${offsetOf("Merged", 2)}",
            ),
        )
        assert(references.count { it.isDeclaration } == 2)
        assert(references.single { !it.isDeclaration }.start == offsetOf("Merged", 2))
    }

    // --- members -------------------------------------------------------------------

    /**
     * A member is resolved through its RECEIVER, so its uses group with the
     * declaration in the file that declares the interface — across the import
     * boundary, and without the member name ever being looked up in a scope.
     */
    @Test
    fun `a member's uses group with its declaration in the declaring file`() {
        val project = projectWith()
        val references = project.referencesAt(mainFile, offsetOf("shape.width") + "shape.".length)
        assert(
            places(references) == listOf(
                "a.ts@${offsetOf("shape.width", 0) + "shape.".length}",
                "a.ts@${offsetOf("shape.width", 1) + "shape.".length}",
                "b.ts@${offsetOf("width", 0, other)}",
            ),
        )
        assert(references.single { it.isDeclaration }.fileName == otherFile)
    }

    /**
     * The recovery leg: a caret on a MEMBER's own declaration name is bound by no
     * scope and has no receiver, so the capture resolves it to nothing — the answer
     * comes from the sweep's own evidence that something resolved TO that span.
     */
    @Test
    fun `a caret on a member's own declaration name answers the same group`() {
        val project = projectWith()
        val fromDeclaration = project.referencesAt(mainFile, offsetOf("prop: string"))
        val fromUse = project.referencesAt(mainFile, offsetOf("local.prop") + "local.".length)
        assert(
            places(fromDeclaration) == listOf(
                "a.ts@${offsetOf("prop: string")}",
                "a.ts@${offsetOf("local.prop") + "local.".length}",
            ),
        )
        assert(places(fromUse) == places(fromDeclaration))
        assert(fromDeclaration.single { it.isDeclaration }.start == offsetOf("prop: string"))
    }

    /**
     * The stated limit of that recovery, pinned rather than left to be discovered: a
     * member nothing refers to has no occurrence naming its span, so there is no
     * evidence it is a declaration at all and the answer is EMPTY rather than a list
     * of one. tsc answers one; this says so in `Project.referencesAt`.
     */
    @Test
    fun `a member declared and never used answers empty - the stated limit`() {
        val project = projectWith()
        assert(project.referencesAt(mainFile, offsetOf("unused: number")).isEmpty())
    }

    /**
     * An OVERLOADED member is one symbol with two declarations, reached through a
     * receiver — so a use groups with both, exactly as a merged interface does one
     * mechanism over.
     */
    @Test
    fun `an overloaded member's use answers both of its declarations`() {
        val project = projectWith()
        val references = project.referencesAt(mainFile, offsetOf("over.method") + "over.".length)
        assert(references.count { it.isDeclaration } == 2)
        assert(references.filter { it.isDeclaration }.all { it.fileName == otherFile })
        assert(
            places(references) == listOf(
                "a.ts@${offsetOf("over.method") + "over.".length}",
                "b.ts@${offsetOf("method(): void", 0, other)}",
                "b.ts@${offsetOf("method(x: number)", 0, other)}",
            ),
        )
    }

    /**
     * A use through a UNION receiver names one declaration PER CONSTITUENT — which
     * is what makes intersection rather than equality the identity — so it groups
     * with both.
     */
    @Test
    fun `a member of a union receiver groups with every constituent's declaration`() {
        val project = projectWith()
        val references = project.referencesAt(mainFile, offsetOf("united.shared") + "united.".length)
        assert(
            places(references) == listOf(
                "a.ts@${offsetOf("shared: string")}",
                "a.ts@${offsetOf("shared: number")}",
                "a.ts@${offsetOf("united.shared") + "united.".length}",
            ),
        )
        assert(references.count { it.isDeclaration } == 2)
    }

    /**
     * And the direction that keeps the union from OVER-grouping: a caret on ONE
     * constituent's declaration names that declaration alone, so the unrelated
     * `shared` of the other constituent is not a reference to it. Adopting the whole
     * set the union occurrence carried — the obvious way to write the recovery leg —
     * fails exactly here and nowhere else.
     */
    @Test
    fun `a caret on one union constituent's member does not group with the other`() {
        val project = projectWith()
        val references = project.referencesAt(mainFile, offsetOf("shared: string"))
        assert(
            places(references) == listOf(
                "a.ts@${offsetOf("shared: string")}",
                "a.ts@${offsetOf("united.shared") + "united.".length}",
            ),
        )
        assert(references.single { it.isDeclaration }.start == offsetOf("shared: string"))
    }

    // --- edits --------------------------------------------------------------------

    /**
     * The overlay is read, and the answer describes the buffer rather than the disk:
     * a use added in memory appears, and it appears in the group the edit put it in.
     */
    @Test
    fun `an in-memory edit changes the answer`() {
        val project = projectWith()
        val before = project.referencesAt(mainFile, offsetOf("useFileLevel = collide") + "useFileLevel = ".length)
        assert(before.size == 2)
        val edited = main + "export const anotherUse = collide;\n"
        project.updateFile(mainFile, edited)
        val at = edited.indexOf("anotherUse = collide") + "anotherUse = ".length
        val after = project.referencesAt(mainFile, at)
        assert(after.size == 3)
        assert(after.count { it.isDeclaration } == 1)
        assert(after.last().start == at)
    }

    // --- the boundaries -------------------------------------------------------------

    /** A caret on a keyword names nothing; the answer is empty, not a crash. */
    @Test
    fun `a caret on a keyword answers empty`() {
        val project = projectWith()
        assert(project.referencesAt(mainFile, offsetOf("return")).isEmpty())
        assert(project.documentHighlightsAt(mainFile, offsetOf("return")).isEmpty())
    }

    /** Past the end of the file there is no node at all — the spans are half-open. */
    @Test
    fun `a caret past the end of the file answers empty`() {
        val project = projectWith()
        assert(project.referencesAt(mainFile, main.length + 10).isEmpty())
        assert(project.documentHighlightsAt(mainFile, main.length + 10).isEmpty())
    }

    /** An unknown file has no text to index, so neither query has a caret to resolve. */
    @Test
    fun `an unknown file answers empty`() {
        val project = projectWith()
        assert(project.referencesAt("/proj/src/nope.ts", 0).isEmpty())
        assert(project.documentHighlightsAt("/proj/src/nope.ts", 0).isEmpty())
    }

    /** A closed project answers nothing at all — it throws, like every other member. */
    @Test
    fun `a closed project refuses both queries`() {
        val project = projectWith()
        project.close()
        var referencesThrew = false
        try {
            project.referencesAt(mainFile, offsetOf("collide", 1))
        } catch (_: IllegalStateException) {
            referencesThrew = true
        }
        var highlightsThrew = false
        try {
            project.documentHighlightsAt(mainFile, offsetOf("collide", 1))
        } catch (_: IllegalStateException) {
            highlightsThrew = true
        }
        assert(referencesThrew)
        assert(highlightsThrew)
    }

    /**
     * The ordering is imposed by the API rather than inherited from the walk, so it
     * is asserted directly: `(fileName, start)` ascending, one entry per span.
     */
    @Test
    fun `the answer is sorted by file then offset with no duplicates`() {
        val project = projectWith()
        val references = project.referencesAt(mainFile, offsetOf("imported", 1))
        val keys = references.map { it.fileName to it.start }
        assert(keys == keys.sortedWith(compareBy({ it.first }, { it.second })))
        assert(keys.toSet().size == keys.size)
    }

    /**
     * The span is EXACT — the identifier's own text — rather than the raw `Node.end`,
     * which in this parser runs past the following token. A regression there is
     * invisible to every set assertion above, which compares starts.
     */
    @Test
    fun `every span covers exactly the identifier's text`() {
        val project = projectWith()
        for (reference in project.referencesAt(mainFile, offsetOf("imported", 1))) {
            val text = if (reference.fileName == mainFile) main else other
            assert(text.substring(reference.start, reference.end) == "imported")
        }
    }
}
