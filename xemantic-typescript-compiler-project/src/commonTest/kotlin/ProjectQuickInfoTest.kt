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
 * [Project.quickInfoAt] — the hover answer, end to end: caret offset in, a type
 * string out, through a real build of a real (in-memory) project.
 *
 * The positions are the ones the design argument is about. A top-level `const` is
 * the easy one and a post-hoc query would answer it too; a function-body local, a
 * parameter and a guard-narrowed reference are the ones the compiler can only
 * answer WHILE it walks, and the core module's `TypeCaptureMeasurementTest`
 * measures how a post-hoc query answers them instead (a same-named global's type,
 * and `any`).
 *
 * Every offset is derived from the fixture text by `indexOf`. A hardcoded offset
 * would pin this test's own arithmetic and would pass for an implementation that
 * ignored its argument.
 */
class ProjectQuickInfoTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val fileName = "/proj/src/a.ts"

    /**
     * `collide` is declared both as a file-level `const` of one type and as a
     * function-body local of another, which is what makes the body-local hover
     * discriminating rather than merely plausible.
     */
    private val source = """
        export const topConst: string = "t";
        export const collide: string = "g";
        export function f(u: string | number, p: number): void {
            const collide: number = 1;
            const useLocal = collide;
            if (typeof u === "string") {
                const useNarrow = u;
            }
            const useParam = p;
        }
        export const arrow = (q: string) => {
            const useArrow = q;
            return useArrow;
        };
    """.trimIndent() + "\n"

    private fun projectWith(text: String = source): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                fileName to text,
            ),
        ),
    )

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [text]. */
    private fun offsetOf(needle: String, occurrence: Int = 0, text: String = source): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    // --- the positions the design is about --------------------------------------

    @Test
    fun `a caret on a top-level annotated const answers its declared type`() {
        val project = projectWith()
        val at = offsetOf("topConst")
        val info = project.quickInfoAt(fileName, at + 1)
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(info.displayString == "string")
        assert(info.start == at)
        assert(info.end == at + "topConst".length)
    }

    @Test
    fun `a caret on a function-body local answers the LOCAL, not the same-named global`() {
        val project = projectWith()
        // The third `collide`: the file-level const, the body declaration, then this
        // use inside the body.
        val at = offsetOf("collide", 2)
        val info = project.quickInfoAt(fileName, at + 1)
        assert(info != null)
        assert(info.displayString == "number")
    }

    @Test
    fun `a caret on a guard-narrowed reference answers the NARROWED type`() {
        val project = projectWith()
        val at = offsetOf("useNarrow = u") + "useNarrow = ".length
        val info = project.quickInfoAt(fileName, at)
        assert(info != null)
        assert(info.displayString == "string")
    }

    @Test
    fun `a caret on a parameter reference answers the parameter's type`() {
        val project = projectWith()
        val at = offsetOf("useParam = p") + "useParam = ".length
        val info = project.quickInfoAt(fileName, at)
        assert(info != null)
        assert(info.displayString == "number")
    }

    @Test
    fun `a caret inside an arrow body answers from the arrow's own scope`() {
        val project = projectWith()
        val at = offsetOf("useArrow = q") + "useArrow = ".length
        val info = project.quickInfoAt(fileName, at)
        assert(info != null)
        assert(info.displayString == "string")
    }

    @Test
    fun `a caret on a function's name answers something about the function`() {
        val project = projectWith()
        val at = offsetOf("function f") + "function ".length
        val info = project.quickInfoAt(fileName, at)
        // The name of a function declaration IS an identifier in the tree, so the
        // lookup lands on it; what the checker answers there is recorded rather than
        // asserted to be tsc's signature rendering, which this API does not build.
        assert(info != null)
        assert(info.kind == "Identifier")
        assert(info.start == at)
    }

    // --- the span rules, inherited from the position lookup ---------------------

    @Test
    fun `a caret on the equals sign is NOT reported as the declared identifier`() {
        // The pin the naive rule fails. `Node.end` overshoots by a token, so the
        // identifier `topConst` spans past the `=`; only a lookup that snaps ends back
        // to the token stream refuses this position. The `=` is covered by no child of
        // the declaration at all, so the narrowest node there is not an expression and
        // there is no type to report.
        val project = projectWith()
        val at = offsetOf("topConst: string = ") + "topConst: string ".length
        assert(source[at] == '=')
        val info = project.quickInfoAt(fileName, at)
        assert(info == null)
    }

    @Test
    fun `a caret immediately after an identifier is not on that identifier`() {
        // The boundary convention, stated once in SourceIndex and inherited here:
        // spans are half-open, so `topConst|` is outside `topConst`.
        val project = projectWith()
        val after = offsetOf("topConst") + "topConst".length
        val info = project.quickInfoAt(fileName, after)
        assert(info == null)
    }

    // --- negative answers, all of them legitimate -------------------------------

    @Test
    fun `negative control - a position in whitespace has no type`() {
        val project = projectWith()
        val at = offsetOf("export const collide") - 1
        assert(source[at] == '\n')
        assert(project.quickInfoAt(fileName, at) == null)
    }

    @Test
    fun `negative control - an unknown file answers null`() {
        val project = projectWith()
        assert(project.quickInfoAt("/proj/src/nope.ts", 0) == null)
    }

    @Test
    fun `negative control - an offset past the end of the file answers null`() {
        val project = projectWith()
        assert(project.quickInfoAt(fileName, source.length + 10) == null)
    }

    @Test
    fun `negative control - a negative offset answers null`() {
        val project = projectWith()
        assert(project.quickInfoAt(fileName, -1) == null)
    }

    // --- the overlay ------------------------------------------------------------

    @Test
    fun `a hover reflects the unsaved buffer, not the file on disk`() {
        val project = projectWith()
        val edited = "export const topConst: number = 1;\n"
        project.updateFile(fileName, edited)
        val at = edited.indexOf("topConst")
        val info = project.quickInfoAt(fileName, at + 1)
        assert(info != null)
        assert(info.displayString == "number")
    }

    @Test
    fun `negative control - before the edit the same position answers the on-disk type`() {
        // The control for the test above: without it, "number" could have come from
        // the fixture rather than from the overlay being read.
        val project = projectWith()
        val at = offsetOf("topConst")
        val info = project.quickInfoAt(fileName, at + 1)
        assert(info != null)
        assert(info.displayString == "string")
    }

    @Test
    fun `a hover on a file added only in the overlay answers`() {
        val project = projectWith()
        val added = "export const fresh: boolean = true;\n"
        project.updateFile("/proj/src/b.ts", added)
        val at = added.indexOf("fresh")
        val info = project.quickInfoAt("/proj/src/b.ts", at + 1)
        assert(info != null)
        assert(info.displayString == "boolean")
    }

    @Test
    fun `a hover does not disturb the diagnostics the project reports`() {
        // A capture build types expressions the checker had no reason to type, so it
        // is deliberately not cached as THE build. What a caller must never see is the
        // diagnostics changing because of where it hovered.
        val project = projectWith()
        val before = project.diagnostics().map { "${it.code}@${it.start}" }
        project.quickInfoAt(fileName, offsetOf("topConst") + 1)
        val after = project.diagnostics().map { "${it.code}@${it.start}" }
        assert(after == before)
    }

    @Test
    fun `a closed project refuses a hover`() {
        val project = projectWith()
        project.close()
        assertFailsWith<IllegalStateException> { project.quickInfoAt(fileName, 0) }
    }
}
