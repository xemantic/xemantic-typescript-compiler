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
 * (API.13) `docs/language-service.md` § 14 — "State of the API" — made CHECKABLE.
 *
 * ## Why a whole class for a summary section
 *
 * § 14 is the page a host author and a next agent read INSTEAD of nineteen rounds of
 * session notes, and prose drifts silently: three rounds after it was written it
 * already carried a defect that had been fixed before it (the lone-`\r` line
 * numbering, closed in round 915 and still listed as open in round 929). Round 930
 * audited every claim in it by EXECUTION and this class is the half of that audit
 * that survives the round — one test per claim that no other class already pins, so
 * a behaviour change that outruns the page reddens here rather than being discovered
 * by a reader.
 *
 * Claims already pinned elsewhere are deliberately NOT duplicated: positions
 * (`LineMapTest`, `ProjectPositionTest` — including `a lone CR file's diagnostics
 * agree with this map too`, which is what makes the old defect note stale), the
 * `o["` completion and its template refusal (`ProjectStringMemberCompletionTest`),
 * read-versus-write (`ProjectReferenceTest`, `SyntaxRoleTest`), batching
 * (`ProjectSemanticsTest`), the rename refusals (`ProjectRenameTest`), the shorthand
 * subject (`ProjectContextualKeyTest`).
 *
 * ## Two of these pin a DEFECT rather than a guarantee
 *
 * `an enum member's declaration name reports the wrong type` and the two silent-miss
 * tests assert what the compiler does TODAY and what tsc 7.0.2 does instead, so that
 * closing either is a deliberate edit here rather than an accident nobody notices —
 * which is the same reason § 14 lists them at all. Each says so in its own comment.
 */
class LanguageServiceStateTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val file = "/proj/src/a.ts"

    private fun projectWith(source: String): Project = Project.open(
        "/proj",
        InMemoryVfs(mapOf("/proj/tsconfig.json" to config, file to source)),
    )

    /** The offset of the [occurrence]-th (0-based) [needle] in [text], plus [plus]. */
    private fun offsetOf(text: String, needle: String, occurrence: Int = 0, plus: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at + plus
    }

    /** [text] with [plan]'s edits for [file] applied, so a rename can be READ. */
    private fun applied(text: String, plan: RenamePlan): String {
        var out = text
        plan.files.firstOrNull { it.fileName == file }?.edits
            ?.sortedByDescending { it.start }
            ?.forEach { out = out.substring(0, it.start) + it.newText + out.substring(it.end) }
        return out
    }

    // --- go to definition through `super` ---------------------------------------

    private val superSource =
        """
        class Base { pb: number = 1; mb(): number { return 1; } }
        class Derived extends Base {
            pb: number = 2;
            use(): number { return super.pb + super.mb(); }
        }
        export { Derived };
        """.trimIndent() + "\n"

    @Test
    fun `a super member answers the BASE declaration and not the override`() {
        // § 9's own table promises `super.p` the property declaration and § 14's
        // maturity row repeats it; until round 930 both were false — the receiver leg
        // had a `this` carrier and no `super` one, so the answer was EMPTY while hover
        // at the same caret answered correctly. tsc 7.0.2, asked over its own LSP,
        // navigates to `Base.pb` in the overridden shape and to `Base.mb` in the
        // inherited one, which is what this asserts.
        val project = projectWith(superSource)
        val basePb = offsetOf(superSource, "pb: number")
        val derivedPb = offsetOf(superSource, "pb: number", occurrence = 1)
        val baseMb = offsetOf(superSource, "mb()")

        val overridden = project.definitionsAt(file, offsetOf(superSource, "super.pb", plus = 6))
        assert(overridden.size == 1)
        assert(overridden[0].start == basePb)
        assert(overridden[0].start != derivedPb)

        val inherited = project.definitionsAt(file, offsetOf(superSource, "super.mb", plus = 6))
        assert(inherited.size == 1)
        assert(inherited[0].start == baseMb)
    }

    @Test
    fun `negative control - a super member with no base class answers nothing`() {
        val source = "class Solo { p: number = 1; use(): number { return super.p; } }\nexport { Solo };\n"
        val project = projectWith(source)
        assert(project.definitionsAt(file, offsetOf(source, "super.p", plus = 6)).isEmpty())
    }

    @Test
    fun `negative control - a static super member answers nothing rather than instance members`() {
        // The same rule § 9 states for `this`: the frame deliberately holds no class
        // inside a static member, so the leg declines. tsc answers here; declining is
        // the conservative half of *prove to offer* and is stated in the page.
        val source =
            "class B { static sp = 1; }\nclass D extends B { static use(): number { return super.sp; } }\n" +
                "export { D };\n"
        val project = projectWith(source)
        assert(project.definitionsAt(file, offsetOf(source, "super.sp", plus = 6)).isEmpty())
    }

    // --- gap 7: an enum member's declaration name -------------------------------

    @Test
    fun `an enum member's declaration name reports the WRONG type and its use reports the right one`() {
        // PINS A DEFECT. § 14's gap 7 said this position "reports no type"; measured, it
        // reports `any` — a plausible wrong answer, which is exactly what the page's
        // *prove to offer* rule exists to forbid. tsc 7.0.2 answers
        // `(enum member) Plain.Alpha = 0` at the same caret. Closing it must edit this
        // test, § 14's gap list and § 8 together.
        val source = "enum Plain { Alpha, Beta }\nconst u = Plain.Alpha;\nexport { u };\n"
        val project = projectWith(source)
        val declaration = project.quickInfoAt(file, offsetOf(source, "Alpha", plus = 1))
        assert(declaration?.displayString == "any")
        val use = project.quickInfoAt(file, offsetOf(source, "Alpha", occurrence = 1, plus = 1))
        assert(use?.displayString == "Plain.Alpha")
    }

    // --- gap 3: an object literal's own method ----------------------------------

    private val methodSource =
        "const o = { om() { return 1; } };\nconst v = o.om();\nexport { o, v };\n"

    @Test
    fun `an object literal's own method has no definition of its own`() {
        val project = projectWith(methodSource)
        assert(project.definitionsAt(file, offsetOf(methodSource, "om()", plus = 1)).isEmpty())
        // …while a USE of it answers the method, which is what makes the next test's
        // rename complete rather than a guess.
        val use = project.definitionsAt(file, offsetOf(methodSource, "o.om()", plus = 3))
        assert(use.size == 1)
        assert(use[0].start == offsetOf(methodSource, "om()"))
    }

    @Test
    fun `an object literal's own method RENAMES completely from either end`() {
        // § 14's gap 3 said this "refuses a rename loudly". Measured in round 930 it
        // does not refuse and does not need to: the occurrence set is the declaration
        // and every use, from a caret at either end.
        val project = projectWith(methodSource)
        val expected = "const o = { qq() { return 1; } };\nconst v = o.qq();\nexport { o, v };\n"
        for (caret in listOf(
            offsetOf(methodSource, "om()", plus = 1),
            offsetOf(methodSource, "o.om()", plus = 3),
        )) {
            val plan = project.renameAt(file, caret, "qq")
            assert(plan.isApplicable)
            assert(plan.refusal == null)
            assert(applied(methodSource, plan) == expected)
        }
    }

    @Test
    fun `an object literal's own method REFUSES the rename once a contextual type supplies it`() {
        // …and this is the half § 14's gap 3 was right about, which round 930 found by
        // measuring its own correction: the moment the literal HAS a contextual type, the
        // literal's own `om` is an identifier spelling the name that resolves to nothing
        // (§ 9's deliberate refusal), so the completeness net cannot rule it out and the
        // rename refuses loudly rather than shipping a plan that would strand it. The two
        // shapes together are the whole answer, and neither alone is.
        val source =
            "interface Handler { om(): number }\nconst o: Handler = { om() { return 1; } };\n" +
                "const v = o.om();\nexport { o, v };\n"
        val project = projectWith(source)
        val plan = project.renameAt(file, offsetOf(source, "o.om()", plus = 3), "qq")
        assert(plan.refusal == RenameRefusal.OCCURRENCES_INCOMPLETE)
        assert(plan.files.isEmpty())
        val literalKey = offsetOf(source, "om() { return")
        assert(plan.conflicts.any { it.kind == RenameConflictKind.UNRESOLVED_OCCURRENCE && it.start == literalKey })
    }

    // --- gap 2: a computed object-literal key -----------------------------------

    @Test
    fun `a computed key that a rename would strand is REFUSED with the evidence`() {
        // § 14's gap 2 said a computed key is "not reported either". Two of its three
        // shapes report loudly, each through a different gate: the apply-and-recheck
        // one, and the unresolved-occurrence one.
        val contextual =
            "interface I { p: number }\nconst o: I = { [\"p\"]: 1 };\nconst v = o.p;\nexport { o, v };\n"
        val required = projectWith(contextual)
            .renameAt(file, offsetOf(contextual, "o.p", plus = 2), "q")
        assert(required.refusal == RenameRefusal.WOULD_NOT_COMPILE)
        assert(required.conflicts.any { it.kind == RenameConflictKind.NEW_DIAGNOSTIC })

        val inferred = "const o = { [\"p\"]: 1 };\nconst v = o.p;\nexport { o, v };\n"
        val uncontextual = projectWith(inferred)
            .renameAt(file, offsetOf(inferred, "o.p", plus = 2), "q")
        assert(uncontextual.refusal == RenameRefusal.OCCURRENCES_INCOMPLETE)
        assert(uncontextual.conflicts.any { it.kind == RenameConflictKind.UNRESOLVED_OCCURRENCE })
    }

    @Test
    fun `a computed key IS silently stranded where the member it supplies is OPTIONAL`() {
        // PINS A DEFECT, and the one shape of gap 2 that is genuinely silent: with the
        // contextual member optional, dropping it costs no diagnostic, so the recheck
        // has nothing to refuse on and the key is left spelling the old name. tsc
        // renames it — measured over its LSP, the key's string IS in the member's
        // reference set.
        val source =
            "interface I { p?: number }\nconst o: I = { [\"p\"]: 1 };\nconst v = o.p;\nexport { o, v };\n"
        val project = projectWith(source)
        val plan = project.renameAt(file, offsetOf(source, "o.p", plus = 2), "q")
        assert(plan.isApplicable)
        assert(plan.conflicts.isEmpty())
        assert(applied(source, plan).contains("[\"p\"]"))
    }

    // --- gap 6: a template element access ---------------------------------------

    private val templateSource =
        """
        interface I { p: number }
        declare const o: I;
        const a = o.p;
        const b = o[`p`];
        export { a, b };
        """.trimIndent() + "\n"

    @Test
    fun `a template element access is outside the occurrence set and outside the rename`() {
        // PINS A DEFECT — the second of § 14's two silent gaps, and the sharper one:
        // the rename applies, the template keeps spelling the old name, and the
        // resulting program still compiles clean, so nothing anywhere reports it. tsc
        // counts the template's `p` as a reference (measured over its LSP).
        val project = projectWith(templateSource)
        val caret = offsetOf(templateSource, "o.p", plus = 2)
        val references = project.referencesAt(file, caret)
        assert(references.size == 2)
        assert(references.none { it.start == offsetOf(templateSource, "o[`p`]", plus = 3) })

        assert(project.diagnostics().isEmpty())
        val plan = project.renameAt(file, caret, "q")
        assert(plan.isApplicable)
        assert(plan.conflicts.isEmpty())
        val after = applied(templateSource, plan)
        assert(after.contains("o[`p`]"))
        assert(after.contains("interface I { q: number }"))
        // The whole reason it is silent: the stranded access is an error in neither
        // program, so no gate this API has can see it.
        assert(projectWith(after).diagnostics().isEmpty())
    }

    // --- signature help refusals -------------------------------------------------

    @Test
    fun `signature help refuses a tagged template at the API and not only at its anchor`() {
        val source =
            "function tag(s: TemplateStringsArray, x: number): string { return s[0] + x; }\n" +
                "const t = tag`abc ${'$'}{1}`;\nexport { t };\n"
        val project = projectWith(source)
        assert(project.signatureHelpAt(file, offsetOf(source, "abc ", plus = 6)) == null)
    }

    // --- § 14's cost table, its BUILD column ------------------------------------

    private val costSource =
        """
        // a comment, in which nothing completes and nothing renames
        interface I { p: number; q(a: number): number }
        declare const o: I;
        const a = o.p;
        const b = o.q(1);
        let c = 1;
        c = a + b;
        export { a, b, c };
        """.trimIndent() + "\n"

    /**
     * A project over a [CountingVfs] with everything that is NOT a build already warm.
     *
     * The build unit is `ProjectSemanticsTest`'s: reads of `tsconfig.json`, one per
     * `ProjectCompiler.build` and cached by nothing across builds.
     */
    private fun costProject(): Pair<Project, CountingVfs> {
        val counting = CountingVfs(
            InMemoryVfs(mapOf("/proj/tsconfig.json" to config, file to costSource)),
        )
        val project = Project.open("/proj", counting)
        project.nodeInfoAt(file, 0)
        return project to counting
    }

    private fun buildsIn(counting: CountingVfs, block: () -> Unit): Int {
        val before = counting.readsOf("/proj/tsconfig.json")
        block()
        return counting.readsOf("/proj/tsconfig.json") - before
    }

    @Test
    fun `the cost table's build column - every single-caret query is ONE build`() {
        // The wall figures in § 14 are a property of one box on one day and no test can
        // defend them. The BUILD COUNTS are the part that is a property of the API, they
        // are what the table's ratios rest on, and they are deterministic — so this is
        // the row of the cost table that is pinned, and § 14 says which is which.
        val (project, counting) = costProject()
        val member = offsetOf(costSource, "o.p", plus = 2)
        assert(buildsIn(counting) { project.diagnostics() } == 1)
        assert(buildsIn(counting) { project.diagnostics() } == 0)
        assert(buildsIn(counting) { project.quickInfoAt(file, member) } == 1)
        assert(buildsIn(counting) { project.definitionsAt(file, member) } == 1)
        assert(buildsIn(counting) { project.completionsAt(file, member) } == 1)
        assert(buildsIn(counting) { project.signatureHelpAt(file, offsetOf(costSource, "o.q(1)", plus = 5)) } == 1)
        assert(buildsIn(counting) { project.fileSemantics(file) } == 1)
        assert(buildsIn(counting) { project.documentHighlightsAt(file, member) } == 1)
    }

    @Test
    fun `the cost table's build column - a sweep is ONE build clean and TWO dirty`() {
        val (project, counting) = costProject()
        val local = offsetOf(costSource, "let c", plus = 4)
        assert(buildsIn(counting) { project.diagnostics() } == 1)
        assert(buildsIn(counting) { project.referencesAt(file, local) } == 1)
        project.updateFile(file, costSource + "export const extra = 1;\n")
        assert(buildsIn(counting) { project.referencesAt(file, local) } == 2)
    }

    @Test
    fun `the cost table's build column - a rename is TWO builds clean and THREE dirty`() {
        val (project, counting) = costProject()
        val local = offsetOf(costSource, "let c", plus = 4)
        assert(buildsIn(counting) { project.diagnostics() } == 1)
        assert(buildsIn(counting) { project.renameAt(file, local, "cc") } == 2)
        project.updateFile(file, costSource + "export const extra = 1;\n")
        assert(buildsIn(counting) { project.renameAt(file, local, "cc") } == 3)
    }

    @Test
    fun `the cost table's build column - a refusal decided on syntax alone builds nothing`() {
        val (project, counting) = costProject()
        assert(buildsIn(counting) { project.diagnostics() } == 1)
        assert(buildsIn(counting) { project.renameAt(file, offsetOf(costSource, "interface"), "zz") } == 0)
        assert(buildsIn(counting) { project.completionsAt(file, offsetOf(costSource, "nothing completes") + 2) } == 0)
    }
}
