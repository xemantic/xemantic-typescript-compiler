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
 * (INC.45) THE SPELLING-NARROWED RENAME: the same plan, and the widening that keeps
 * its safety net alive.
 *
 * A rename is a bigger claim than a reference search, so these pins compare the WHOLE
 * `RenamePlan` — it is a data class, so equality covers every edit's file, span and
 * text, the refusal and the conflict list — rather than a count of anything.
 *
 * ## The one thing a plan comparison cannot see, and why there is a COUNT pin
 *
 * The rename population is `referencesAt`'s spelling closure WIDENED by the NEW name,
 * because `Project.verifyRename`'s third check scans for occurrences that already
 * spell it and asserts each still resolves where it did. On a fixture whose new name
 * is genuinely fresh, that scan finds nothing either way and both arms agree with or
 * without the widening — so a pin written on the plan alone is blind to it. The
 * partition COUNT is the observable that is not:
 * `Project.narrowedRenameFiles` reaches a file that spells only the new name, and
 * `Project.narrowedSweepFiles` at the same caret does not.
 */
class ProjectRenameNarrowingTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"
    private val thirdFile = "/proj/src/c.ts"
    private val erroringFile = "/proj/src/d.ts"

    private val main = """
        export const plain: string = "p";
        export const usePlain = plain;
    """.trimIndent() + "\n"

    private val other = """
        import { plain } from "./a";
        export const useThere = plain;
    """.trimIndent() + "\n"

    /**
     * Spells the NEW name and nothing else — no `plain` anywhere in it. It is what
     * separates the rename population from the reference one.
     */
    private val third = """
        export const zzzFresh: number = 2;
        export const readFresh = zzzFresh;
    """.trimIndent() + "\n"

    /**
     * A file that CARRIES A DIAGNOSTIC and spells none of the names any pin here
     * renames — so it is outside every narrowed partition, and it is what makes the
     * diagnostic comparison in `Project.verifyRename` discriminating.
     *
     * Without it every fixture is a clean program, the before-bag and the after-bag are
     * both empty whatever partition either build walked, and an after-build that
     * forgot the sweep's partition would compare empty against empty and pass. That
     * was measured: the ablation arm which drops `recheckOnly` from the second build
     * reddened NOTHING until this file existed.
     */
    private val erroring = """
        export const zzzBroken: string = 1;
    """.trimIndent() + "\n"

    private fun projectWith(
        mainText: String = main,
        otherText: String = other,
        thirdText: String = third,
    ): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to mainText,
                otherFile to otherText,
                thirdFile to thirdText,
                erroringFile to erroring,
            ),
        ),
    )

    private fun bothArms(
        project: Project,
        file: String,
        offset: Int,
        newName: String,
    ): Pair<RenamePlan, RenamePlan> {
        project.narrowReferenceSweeps = true
        val narrow = project.renameAt(file, offset, newName)
        project.narrowReferenceSweeps = false
        val whole = project.renameAt(file, offset, newName)
        project.narrowReferenceSweeps = true
        return narrow to whole
    }

    /**
     * The plan is the same plan. Asserted as `RenamePlan` equality and additionally as
     * an APPLICABLE one, because two refusals compare equal and a class of pins that
     * all refuse would be green having compared two empty edit lists.
     */
    @Test
    fun `a narrowed rename produces the same plan as the whole-program one`() {
        val project = projectWith()
        val caret = main.indexOf("plain")
        val (narrow, whole) = bothArms(project, mainFile, caret, "zzzRenamed")
        assert(narrow == whole)
        assert(narrow.isApplicable)
        assert(narrow.refusal == null)
        // Both files that spell the name, and only those.
        assert(narrow.files.map { it.fileName }.sorted() == listOf(mainFile, otherFile))
        assert(narrow.files.sumOf { it.edits.size } == 4)
        // THE PARTITION-AGREEMENT PIN. `d.ts` carries a diagnostic and is in neither
        // build's partition, so the before- and after-bags agree and the rename is
        // applicable. An after-build that walked the whole program would see that row
        // as NEW and refuse `WOULD_NOT_COMPILE` — which is exactly what the ablation
        // arm dropping `recheckOnly` from it does.
        assert(project.diagnostics().any { it.fileName == erroringFile })
    }

    /**
     * THE WIDENING PIN. The rename partition reaches `c.ts`, which spells only the new
     * name; the reference partition at the same caret does not. Without the widening
     * `verifyRename`'s "nothing moved" check would run over a population that excludes
     * every occurrence already spelling the new name, i.e. it would pass vacuously —
     * a narrowing that switches the safety net off rather than paying less for it.
     */
    @Test
    fun `the rename partition is the reference one widened by the NEW name`() {
        val project = projectWith()
        val caret = main.indexOf("plain")
        // Four files in the program; `d.ts` spells neither name and is in neither
        // partition, which is what makes these counts a measurement.
        assert(project.files.size == 4)
        assert(project.narrowedSweepFiles(mainFile, caret) == 2)
        assert(project.narrowedRenameFiles(mainFile, caret, "zzzFresh") == 3)
        // …and a new name nothing spells does NOT widen it, which is what makes the
        // row above a statement about the new name rather than about the count 3.
        assert(project.narrowedRenameFiles(mainFile, caret, "zzzNobodySpellsThis") == 2)
    }

    /**
     * A COLLISION is still caught. The rename would give `b.ts`'s import binding a name
     * that file already declares, which `verifyRename`'s second check sees as a new
     * diagnostic — and it sees it only because `b.ts` is in the partition, under BOTH
     * arms and for the same reason in each.
     */
    @Test
    fun `a collision in another file still refuses, in both arms`() {
        val project = projectWith(
            otherText = """
                import { plain } from "./a";
                const zzzTaken: number = 1;
                export const useThere = plain;
                export const useTaken = zzzTaken;
            """.trimIndent() + "\n",
        )
        val caret = main.indexOf("plain")
        val (narrow, whole) = bothArms(project, mainFile, caret, "zzzTaken")
        assert(narrow == whole)
        assert(narrow.refusal != null)
        assert(!narrow.isApplicable)
    }

    /**
     * The fallback: a caret whose spellings cannot be bounded renames through the
     * whole-program sweep, and the plan is the same plan there too — which is the
     * control that the two paths have not drifted apart.
     */
    @Test
    fun `a default-exported declaration renames through the fallback`() {
        val project = projectWith(
            mainText = """
                export default function exported(): number { return 1; }
                export const alsoHere = exported;
            """.trimIndent() + "\n",
            otherText = """
                export const unrelated: number = 1;
            """.trimIndent() + "\n",
        )
        val text = "export default function exported(): number { return 1; }\n" +
            "export const alsoHere = exported;\n"
        val caret = text.indexOf("exported")
        assert(project.narrowedRenameFiles(mainFile, caret, "zzzRenamed") == -1)
        val (narrow, whole) = bothArms(project, mainFile, caret, "zzzRenamed")
        assert(narrow == whole)
    }

    /**
     * An ALIASED symbol refuses identically in both arms — `import { p as q }` makes
     * one symbol with two spellings and one new name cannot spell two things. The pin
     * is here because the alias closure is what puts the far spelling in the group at
     * all: without it the narrowed arm would carry only one spelling and would produce
     * an APPLICABLE plan that renames half the symbol.
     */
    @Test
    fun `an aliased symbol refuses in both arms rather than renaming half of it`() {
        val project = projectWith(
            otherText = """
                import { plain as localAlias } from "./a";
                export const useThere = localAlias;
            """.trimIndent() + "\n",
        )
        val caret = main.indexOf("plain")
        val (narrow, whole) = bothArms(project, mainFile, caret, "zzzRenamed")
        assert(narrow == whole)
        assert(narrow.refusal == RenameRefusal.ALIASED_SYMBOL)
    }
}
