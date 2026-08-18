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
 * (BUG.2) That one template literal with a substitution does not de-synchronise
 * [SourceIndex]'s token index for the rest of the file.
 *
 * ## The defect these pin, measured rather than argued
 *
 * `Scanner.scan()` is context-free; the parser re-scans the `}` that closes a
 * `${…}` into a template middle or tail. Without that re-scan the `}` reads as a
 * CloseBrace, whatever follows reads as operators, and the CLOSING BACKTICK opens a
 * fresh `NoSubstitutionTemplateLiteral` that runs to the next backtick anywhere in
 * the file — so the token stream is wrong from there to end of file, every later
 * node's real end snaps back to some earlier token, and every position-directed
 * query answers about a huge enclosing node instead of the one at the caret.
 *
 * Measured on this repo's own compiler profile before the fix: `checker.ts` scanned
 * as 50,684 tokens for 3,151,772 characters, the longest of them 62,089 characters,
 * and a caret on a top-level function's name resolved to the whole file's `Block`.
 *
 * ## Why these are written AFTER the template rather than inside it
 *
 * The failure is not local. A pin standing on the template itself passes on the
 * broken scan (the head token is still a head); what breaks is everything DOWNSTREAM,
 * so every assertion here stands on a position AFTER a substituting template and
 * asserts the narrow answer.
 */
class TemplateTokenSyncTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"

    /**
     * The `|` between the two substitutions is what makes this the measured shape
     * rather than a toy: with the `}` unhandled it is scanned as an operator, and the
     * closing backtick then opens a literal that swallows everything up to the next
     * backtick — which the second template provides, two declarations later.
     */
    private val main = """
        declare const first: { path: string };
        declare const second: { path: string };
        export const key = `${'$'}{first.path}|${'$'}{second.path}`;
        export const afterTemplate: number = 1;
        export const alsoAfter = afterTemplate;
        export const nested = `outer ${'$'}{`inner ${'$'}{first.path}`} done`;
        export const afterNested: number = 2;
        export const readsNested = afterNested;
    """.trimIndent() + "\n"

    private fun project(): Project = Project.open(
        "/proj",
        InMemoryVfs(mapOf("/proj/tsconfig.json" to config, mainFile to main)),
    )

    private fun offsetOf(needle: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = main.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /**
     * The whole-file measurement, and the sharp one: **every identifier in the file
     * must be reachable by a descent to its own first character.**
     *
     * That is the property the de-synchronisation destroys and it is the property
     * every position query is built on. It is asserted this way rather than as "the
     * gap between the templates is covered by no token" because `pathAt` answers the
     * SOURCE FILE for any offset inside the file — so a containment assertion there
     * is satisfied by the broken index too, and is no pin at all. Reaching the
     * identifier requires the descent to enter every node above it, which requires
     * every one of their real ends to be right.
     */
    @Test
    fun `every identifier in the file is reachable by a descent to its own position`() {
        val index = SourceIndex.of(
            main,
            mainFile,
            com.xemantic.typescript.compiler.ParserFlags(
                forceJsx = false,
                topLevelAwait = false,
                needsJsxFlag = false,
                noImplicitAny = true,
            ),
        )
        val identifiers = index.identifiers()
        // A non-empty population, or the loop below asserts nothing (round 849).
        assert(identifiers.size > 10)
        for (identifier in identifiers) {
            assert(index.pathAt(identifier.pos).lastOrNull() === identifier)
        }
    }

    /** A caret on a name declared AFTER a substituting template resolves to it. */
    @Test
    fun `a caret after a substituting template still finds the narrowest node`() {
        val info = project().nodeInfoAt(mainFile, offsetOf("afterTemplate", 1))
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(main.substring(info.start, info.end) == "afterTemplate")
    }

    /** And the same after a NESTED one, which needs the stack rather than a flag. */
    @Test
    fun `a caret after a nested template still finds the narrowest node`() {
        val info = project().nodeInfoAt(mainFile, offsetOf("afterNested", 1))
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(main.substring(info.start, info.end) == "afterNested")
    }

    /**
     * The semantic consequence, which is what makes this a defect rather than an
     * index detail: a query past the template resolves the caret to a huge enclosing
     * node on the broken scan, and enclosing nodes are not identifiers, so every
     * answer is null or empty.
     */
    @Test
    fun `go to definition works past a substituting template`() {
        val project = project()
        val definitions = project.definitionsAt(mainFile, offsetOf("afterTemplate", 1))
        assert(definitions.size == 1)
        assert(definitions[0].start == offsetOf("afterTemplate", 0))
        val references = project.referencesAt(mainFile, offsetOf("afterNested", 1))
        assert(references.size == 2)
        assert(references[0].start == offsetOf("afterNested", 0))
        assert(references[1].start == offsetOf("afterNested", 1))
    }

    /**
     * A caret INSIDE a substitution is ordinary code and completes as a free name;
     * a caret inside the template's literal TEXT completes nothing. Both are decided
     * by the token kinds the re-scan produces, so both move when it is dropped.
     */
    @Test
    fun `a caret inside a substitution is code and inside the literal text is not`() {
        val project = project()
        val insideSubstitution = project.completionsAt(mainFile, offsetOf("second.path") + "second.".length)
        assert(insideSubstitution.kind == CompletionKind.MEMBER)
        assert(insideSubstitution.items.any { it.name == "path" })
        val insideText = project.completionsAt(mainFile, offsetOf("outer ") + 2)
        assert(insideText.kind == CompletionKind.NONE)
        assert(insideText.refusal == CompletionRefusal.NO_COMPLETION_CONTEXT)
    }
}
