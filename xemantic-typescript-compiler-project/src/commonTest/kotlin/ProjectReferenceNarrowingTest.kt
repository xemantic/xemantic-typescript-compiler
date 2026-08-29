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
 * (INC.44) THE SPELLING-NARROWED REFERENCE SWEEP: it must answer what the
 * whole-program sweep answers, and it must actually be the thing answering.
 *
 * Every pin here asserts BOTH halves, because either alone is satisfiable by a
 * broken implementation:
 *
 * * **the answer** — `referencesAt` with `narrowReferenceSweeps` on equals the same
 *   call with it off, element for element. A narrowing that drops an occurrence
 *   fails here and NOWHERE ELSE in this repository: a missing reference is not a
 *   diagnostic, not an emitted byte and not a counter, it is a highlight that is
 *   simply not drawn.
 * * **the path** — [Project.narrowedSweepFiles] says how many files the narrowed arm
 *   would check, or -1 when it refused. Without it a class of pins that all refuse
 *   is GREEN while comparing the fallback arm with itself, which is exactly round
 *   790's dead verifier and exactly what would happen if a later change made
 *   [SyntaxRoles.isAliasEscape] answer true everywhere.
 *
 * ## What the fixture is built to break
 *
 * A pure spelling filter with no alias closure. `renamed` is exported from `b.ts`
 * and imported into `a.ts` as `import { renamed as localAlias }`, so HALF its
 * occurrences spell a name the caret never typed — a filter on the caret's spelling
 * alone returns a strictly smaller set from either end, and the two alias tests are
 * the ones that catch it.
 *
 * The escape fixtures are the mirror: a default export, an `export =`-shaped
 * `import x = require(…)` and a namespace import bind a symbol to a spelling that is
 * written nowhere near the other one, and the pins assert that the search REFUSES to
 * narrow rather than silently answering from a partial population.
 *
 * Offsets come from `indexOf` on the fixture text; a hardcoded offset would pin this
 * test's own arithmetic.
 */
class ProjectReferenceNarrowingTest {

    /**
     * An ES module kind and >= 2 program files, both load-bearing: the
     * unresolved-module region returns early below two program files and its
     * relative-specifier leg demands an ES kind, so with either missing every
     * import-crossing row here would be vacuous (CLAUDE.md, round 909).
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"
    private val thirdFile = "/proj/src/c.ts"

    private val main = """
        import { plain, renamed as localAlias } from "./b";
        export const usePlainOnce = plain;
        export const usePlainTwice = plain;
        export const useAliasOnce = localAlias;
        export const useAliasTwice = localAlias;
        const unrelated: number = 1;
        export const useUnrelated = unrelated;
    """.trimIndent() + "\n"

    private val other = """
        export const plain: string = "p";
        export const renamed: string = "r";
        export const alsoHere = renamed;
    """.trimIndent() + "\n"

    /**
     * A third file that mentions NEITHER name, so the partition count is a real
     * observable: a narrowed search for `unrelated` must reach one file, not three.
     */
    private val third = """
        export const untouched: number = 2;
        export const readUntouched = untouched;
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

    /**
     * THE DIFFERENTIAL, as one call: the narrowed answer and the whole-program one,
     * for the same caret, on the same project.
     *
     * Returned rather than asserted here so each test can also say what the answer
     * IS — an equivalence that holds because both arms are empty would otherwise
     * read as coverage.
     */
    private fun bothArms(
        project: Project,
        file: String,
        offset: Int,
    ): Pair<List<ReferenceLocation>, List<ReferenceLocation>> {
        project.narrowReferenceSweeps = true
        val narrow = project.referencesAt(file, offset)
        project.narrowReferenceSweeps = false
        val whole = project.referencesAt(file, offset)
        project.narrowReferenceSweeps = true
        return narrow to whole
    }

    // --- the ordinary case, which is what the narrowing is FOR ------------------

    /**
     * A name written in one file reaches one file. The partition count is the pin:
     * an answer of the right size proves nothing about whether anything narrowed.
     */
    @Test
    fun `a name written in one file checks one file and answers what the whole sweep answers`() {
        val project = projectWith()
        val caret = offsetOf("unrelated", 1)
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(
            places(narrow) ==
                listOf("a.ts@${offsetOf("unrelated", 0)}", "a.ts@$caret"),
        )
        assert(places(narrow) == places(whole))
        assert(project.narrowedSweepFiles(mainFile, caret) == 1)
    }

    /**
     * A name written in two files reaches two — and NOT the third, which is what
     * makes this a narrowing rather than a re-spelling of the same sweep.
     */
    @Test
    fun `an imported name checks only the files that mention it`() {
        val project = projectWith()
        val caret = offsetOf("plain", 1)
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(places(narrow) == places(whole))
        assert(narrow.size == 4)
        assert(narrow.none { it.fileName == thirdFile })
        assert(project.narrowedSweepFiles(mainFile, caret) == 2)
    }

    // --- THE DISCRIMINATOR: an alias spells the symbol two ways -----------------

    /**
     * The pin a spelling filter with no closure fails, from the EXPORT end.
     *
     * `renamed` is imported as `localAlias`, so two of this symbol's occurrences
     * spell a name the caret never typed. The whole-program arm finds them; a
     * narrowed arm finds them only because [SyntaxRoles.aliasLink] put `localAlias`
     * in the closure, and the assertion is on the SET rather than the size because a
     * four-element answer of the wrong four would satisfy a count.
     */
    @Test
    fun `an aliased import is reached from the exporting end`() {
        val project = projectWith()
        val caret = offsetOf("renamed", 0, other)
        val (narrow, whole) = bothArms(project, otherFile, caret)
        assert(places(narrow) == places(whole))
        assert(narrow.any { it.fileName == mainFile })
        assert(
            places(narrow) == listOf(
                // The specifier's LOCAL name, not its `propertyName`: the group is the
                // alias binding, which is what makes this the pin a spelling filter
                // fails — the caret typed `renamed` and three of the five answers
                // spell `localAlias`.
                "a.ts@${offsetOf("localAlias", 0)}",
                "a.ts@${offsetOf("localAlias", 1)}",
                "a.ts@${offsetOf("localAlias", 2)}",
                "b.ts@${offsetOf("renamed", 0, other)}",
                "b.ts@${offsetOf("renamed", 1, other)}",
            ),
        )
        assert(project.narrowedSweepFiles(otherFile, caret) == 2)
    }

    /** The same edge from the IMPORT end — the closure has to run both ways. */
    @Test
    fun `an aliased import is reached from the importing end`() {
        val project = projectWith()
        val caret = offsetOf("localAlias", 1)
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(places(narrow) == places(whole))
        assert(narrow.any { it.fileName == otherFile })
        assert(project.narrowedSweepFiles(mainFile, caret) == 2)
    }

    // --- the escapes, which must REFUSE rather than answer partially ------------

    /**
     * `export default` binds this symbol to whatever spelling an importer chooses, so
     * no scan starting from `exported` can name it — the search must fall back.
     *
     * The pin is the REFUSAL (-1) and the equivalence together: without the refusal
     * the equivalence would be a statement about a fixture that happens to have no
     * default import in it.
     */
    @Test
    fun `a default-exported declaration refuses to narrow`() {
        val project = projectWith(
            mainText = """
                import brandNew from "./b";
                export const useDefault = brandNew;
            """.trimIndent() + "\n",
            otherText = """
                export default function exported(): number { return 1; }
                export const alsoHere = exported;
            """.trimIndent() + "\n",
        )
        val text = "export default function exported(): number { return 1; }\n" +
            "export const alsoHere = exported;\n"
        val caret = text.indexOf("exported")
        assert(project.narrowedSweepFiles(otherFile, caret) == -1)
        val (narrow, whole) = bothArms(project, otherFile, caret)
        assert(places(narrow) == places(whole))
        assert(narrow.isNotEmpty())
    }

    /** The other end of the same edge: the LOCAL a default import binds. */
    @Test
    fun `a default import binding refuses to narrow`() {
        val project = projectWith(
            mainText = """
                import brandNew from "./b";
                export const useDefault = brandNew;
            """.trimIndent() + "\n",
            otherText = """
                export default function exported(): number { return 1; }
            """.trimIndent() + "\n",
        )
        val text = "import brandNew from \"./b\";\nexport const useDefault = brandNew;\n"
        val caret = text.indexOf("brandNew")
        assert(project.narrowedSweepFiles(mainFile, caret) == -1)
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(places(narrow) == places(whole))
        assert(narrow.isNotEmpty())
    }

    /** `import * as ns` — a namespace binding, refused for conservatism. */
    @Test
    fun `a namespace import binding refuses to narrow`() {
        val project = projectWith(
            mainText = """
                import * as ns from "./b";
                export const useNs = ns.plain;
            """.trimIndent() + "\n",
        )
        val text = "import * as ns from \"./b\";\nexport const useNs = ns.plain;\n"
        val caret = text.indexOf("ns")
        assert(project.narrowedSweepFiles(mainFile, caret) == -1)
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(places(narrow) == places(whole))
    }

    /**
     * `export { renamed as default }` puts the spelling `default` in the closure, and
     * the far side of that edge is an `import d from …` reachable from neither name —
     * so reaching `default` gives up, by NAME rather than by shape.
     */
    @Test
    fun `an export renamed to default refuses to narrow`() {
        val project = projectWith(
            otherText = """
                export const plain: string = "p";
                const renamed: string = "r";
                export { renamed as default };
            """.trimIndent() + "\n",
        )
        val text = "export const plain: string = \"p\";\nconst renamed: string = \"r\";\n" +
            "export { renamed as default };\n"
        val caret = text.indexOf("renamed")
        assert(project.narrowedSweepFiles(otherFile, caret) == -1)
    }

    /**
     * A negative control for the escapes: an ORDINARY caret in the very same project
     * that carries a default export still narrows.
     *
     * Without it, "refuses to narrow" would be satisfiable by a build that refuses
     * everything, which is the failure the whole class is written against.
     */
    @Test
    fun `negative control - an ordinary name still narrows in a project that has a default export`() {
        val project = projectWith(
            mainText = """
                import brandNew from "./b";
                const unrelated: number = 1;
                export const useUnrelated = unrelated;
                export const useDefault = brandNew;
            """.trimIndent() + "\n",
            otherText = """
                export default function exported(): number { return 1; }
            """.trimIndent() + "\n",
        )
        val text = "import brandNew from \"./b\";\nconst unrelated: number = 1;\n" +
            "export const useUnrelated = unrelated;\nexport const useDefault = brandNew;\n"
        val caret = text.indexOf("unrelated")
        assert(project.narrowedSweepFiles(mainFile, caret) == 1)
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(places(narrow) == places(whole))
        assert(narrow.size == 2)
    }

    // --- the population itself --------------------------------------------------

    /**
     * THE ESCAPE-SEQUENCE PIN, and the reason the file filter is not a plain substring
     * test.
     *
     * `s["pl\ain"]` names the member `plain` — `\a` is an identity escape, so the
     * COOKED value carries a name the source does not spell. A filter that skipped a
     * file because its text does not contain `plain` would drop this occurrence
     * silently, and it would drop it in the direction nothing else here can see.
     *
     * Written as a SECOND file so the skip is the thing under test: `b.ts` would be
     * excluded by the substring test and is included by
     * `Project.mayHideAnEscapedName`.
     */
    @Test
    fun `a member spelled by an escape sequence is not skipped by the file filter`() {
        val project = projectWith(
            mainText = """
                export interface Shape { plain: number; }
                declare const s: Shape;
                export const direct = s.plain;
            """.trimIndent() + "\n",
            otherText = """
                import { Shape } from "./a";
                declare const t: Shape;
                export const escaped = t["pl\ain"];
            """.trimIndent() + "\n",
        )
        val text = "export interface Shape { plain: number; }\ndeclare const s: Shape;\n" +
            "export const direct = s.plain;\n"
        val caret = text.indexOf("s.plain") + 2
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(places(narrow) == places(whole))
        // The control that makes this a pin rather than a coincidence: the escaped
        // occurrence really is in the whole-program answer, so dropping it would be a
        // divergence rather than an empty set matching an empty set.
        assert(whole.any { it.fileName == otherFile })
        assert(narrow.any { it.fileName == otherFile })
        assert(project.narrowedSweepFiles(mainFile, caret) == 2)
    }

    /**
     * A member named by a STRING LITERAL is in the occurrence population ((API.9) /
     * (API.17)) and its text is the member name, so the closure must select it — a
     * narrowing that filtered identifiers only would drop every `o["p"]`.
     */
    @Test
    fun `a member named by a string literal is selected by its own text`() {
        val project = projectWith(
            mainText = """
                import { Shape } from "./b";
                declare const s: Shape;
                export const one = s.width;
                export const two = s["width"];
            """.trimIndent() + "\n",
            otherText = """
                export const plain: string = "p";
                export interface Shape { width: number; }
            """.trimIndent() + "\n",
        )
        val text = "import { Shape } from \"./b\";\ndeclare const s: Shape;\n" +
            "export const one = s.width;\nexport const two = s[\"width\"];\n"
        val caret = text.indexOf("s.width") + 2
        val (narrow, whole) = bothArms(project, mainFile, caret)
        assert(places(narrow) == places(whole))
        assert(narrow.size == 3)
        assert(project.narrowedSweepFiles(mainFile, caret) == 2)
    }
}
