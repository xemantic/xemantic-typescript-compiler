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
        import { imported, collide, Shape, ns } from "./b";
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
        export const member: string = "an unrelated top-level binding";
        export const holder = { member: 1 };
        export const readMember = holder.member;
        declare const shape: Shape;
        export const readImportedMember = shape.width;
        interface BaseIface { inherited: string; }
        interface DerivedIface extends BaseIface { own: number; }
        declare const derived: DerivedIface;
        export const readInherited = derived.inherited;
        export interface Overloaded { over(): void; }
        export interface Overloaded { over(x: number): void; }
        declare const overloaded: Overloaded;
        export const readOverload = overloaded.over;
        declare const united: { shared: string } | { shared: number };
        export const readUnion = united.shared;
        export const readLibMember = "abc".length;
        export const readNsMember = ns.inside;
        export const readUnresolved = (holder as any).absent;
    """.trimIndent() + "\n"

    private val other = """
        export const imported: string = "i";
        export const collide: string = "c";
        export interface Shape { width: number; }
        export namespace ns { export const inside = 7; }
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

    // --- (API.3d) member names, resolved through the RECEIVER ---------------------

    /**
     * THE DISCRIMINATOR. `member` is a property of `holder` and, one line above, an
     * unrelated exported `const` of the same spelling. A scope lookup — which is
     * what round 913 refused to do here, and what a member path that quietly reused
     * the free-name resolution would do — answers the `const`. Only a resolution
     * through the receiver's TYPE answers the object literal's own property.
     *
     * The wrong answer is a plausible location in the right file, so only the
     * OFFSET separates them and both are asserted.
     */
    @Test
    fun `a caret on a member name answers the MEMBER, not the same-named top-level binding`() {
        val project = projectWith()
        val at = offsetOf("holder.member") + "holder.".length
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.size == 1)
        assert(definitions[0].fileName == mainFile)
        assert(definitions[0].start == offsetOf("member: 1"))
        assert(definitions[0].start != offsetOf("member: string"))
        assert(definitions[0].length == "member".length)
        assert(definitions[0].kind == "Identifier")
        // ... while the RECEIVER of the same expression still answers through the
        // scope chain, which proves both mechanisms are live in one build.
        assert(project.definitionsAt(mainFile, offsetOf("holder.member")).size == 1)
    }

    @Test
    fun `a caret on a member of an IMPORTED interface answers in the declaring file`() {
        val project = projectWith()
        // The module-resolution trap: without a resolved import this would measure
        // nothing at all, so pin that it resolves before reading the answer.
        assert(project.diagnostics(mainFile).none { it.code == 2307 })
        val at = offsetOf("shape.width") + "shape.".length
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.size == 1)
        assert(definitions[0].fileName == otherFile)
        assert(definitions[0].start == offsetOf("width", 0, other))
        assert(definitions[0].length == "width".length)
    }

    @Test
    fun `a caret on an INHERITED member answers the BASE's declaration`() {
        val project = projectWith()
        val at = offsetOf("derived.inherited") + "derived.".length
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.size == 1)
        // `BaseIface` declares it, `DerivedIface` only extends — an implementation
        // that read the derived type's own member list would answer nothing, and one
        // that answered the derived declaration would be pointing at `own`.
        assert(definitions[0].start == offsetOf("inherited: string"))
        assert(definitions[0].length == "inherited".length)
    }

    @Test
    fun `a caret on a MERGED member answers every contributing declaration`() {
        val project = projectWith()
        val at = offsetOf("overloaded.over") + "overloaded.".length
        val definitions = project.definitionsAt(mainFile, at)
        // Two interface declarations of the same name each contribute an overload of
        // `over`, so both are the answer and "the first" would be the wrong one.
        assert(definitions.size == 2)
        assert(definitions.map { it.start } == listOf(offsetOf("over()"), offsetOf("over(x")))
        assert(definitions.all { it.length == "over".length })
    }

    @Test
    fun `a caret on a member of a UNION receiver answers one location per constituent`() {
        val project = projectWith()
        val at = offsetOf("united.shared") + "united.".length
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.size == 2)
        assert(
            definitions.map { it.start } ==
                listOf(offsetOf("shared: string"), offsetOf("shared: number")),
        )
    }

    @Test
    fun `a caret on a member of an imported NAMESPACE answers in the declaring file`() {
        val project = projectWith()
        val at = offsetOf("ns.inside") + "ns.".length
        val definitions = project.definitionsAt(mainFile, at)
        // A namespace's members are not on any TYPE — an enum's are not either — so
        // this leg is the export table, and a type-only implementation reads empty.
        assert(definitions.size == 1)
        assert(definitions[0].fileName == otherFile)
        assert(definitions[0].start == offsetOf("inside = 7", 0, other))
        assert(definitions[0].length == "inside".length)
    }

    /**
     * A LIB member answers, and the answer names a file the host may not be able to
     * open — which is the policy `definitionsAt` already documents for a free name
     * that resolves into a lib, so a member is not given a different rule. The span
     * is still exact, because it is computed inside the compiler from the declaring
     * file's own text.
     */
    @Test
    fun `a caret on a LIB member answers in the lib file, with an exact span`() {
        val project = projectWith()
        val at = offsetOf("\"abc\".length") + "\"abc\".".length
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.isNotEmpty())
        assert(definitions.all { it.fileName.startsWith("lib.") })
        assert(definitions.all { it.fileName.endsWith(".d.ts") })
        // Not `mainFile`: a receiver whose type came from the lib must not be
        // answered out of the file the caret is in.
        assert(definitions.none { it.fileName == mainFile })
        assert(definitions[0].length == "length".length)
        assert(definitions[0].kind == "Identifier")
    }

    // --- what answers empty, and why --------------------------------------------

    @Test
    fun `a caret on an UNRESOLVABLE member answers empty rather than guessing`() {
        val project = projectWith()
        val at = offsetOf("as any).absent") + "as any).".length
        // Nothing declares `absent`, so there is nothing to navigate to and the
        // answer is silence — never the nearest same-named anything.
        assert(project.definitionsAt(mainFile, at).isEmpty())
    }

    /**
     * (API.10) REPLACES round 913's `a caret on an object-literal KEY being declared
     * answers empty`. The third mechanism it named — the literal's CONTEXTUAL type —
     * now exists, so the two halves of the shape answer differently and both were read
     * out of tsc 7.0.2: a key with no contextual type is its own declaration and
     * navigates to ITSELF, while a key that a contextual type supplies navigates to
     * THAT type's member.
     *
     * The discriminator is the unrelated top-level `member`: an answer derived from the
     * scope chain would name it, and neither of these does.
     */
    @Test
    fun `an object-literal KEY answers its own declaration, or the CONTEXTUAL member`() {
        val project = projectWith()
        val free = project.definitionsAt(mainFile, offsetOf("member: 1"))
        assert(free.map { it.fileName to it.start } == listOf(mainFile to offsetOf("member: 1")))
        // `merged` is annotated `Merged`, an interface declared TWICE — so the answer is
        // the `a` of the declaration that carries it, and not the other one.
        val contextual = project.definitionsAt(mainFile, offsetOf("= { a:") + 4)
        assert(
            contextual.map { it.fileName to it.start } ==
                listOf(mainFile to offsetOf("Merged { a: string; }") + "Merged { ".length),
        )
    }

    @Test
    fun `a caret on an interface member's own declaration name answers empty`() {
        val project = projectWith()
        // It already IS the declaration; answering with itself is not useful and is
        // not what this round set out to do.
        assert(project.definitionsAt(mainFile, offsetOf("inherited: string")).isEmpty())
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
