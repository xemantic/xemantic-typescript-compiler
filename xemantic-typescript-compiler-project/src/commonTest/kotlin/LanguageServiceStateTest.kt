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
 * The two silent-miss tests assert what the compiler does TODAY and what tsc 7.0.2
 * does instead, so that closing either is a deliberate edit here rather than an
 * accident nobody notices — which is the same reason § 14 lists them at all. Each says
 * so in its own comment. It works: the third such pin, the enum member declaration
 * name's `any`, was INVERTED in place by (API.15) in round 931.
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
    fun `an enum member's declaration name reports the SAME type its use reports`() {
        // (API.15), round 931 — WAS A DEFECT PIN. Round 930 measured this caret
        // answering `any`: a plausible wrong answer, which is what *prove to offer*
        // exists to forbid, and § 14's gap 7. It now answers the member's own type, the
        // very instance the USE site answers. tsc 7.0.2 answers
        // `(enum member) Plain.Alpha = 0` here; the value is tsc's decoration and this
        // API renders TYPES, so agreeing about the type is agreeing (§ 8).
        val source = "enum Plain { Alpha, Beta }\nconst u = Plain.Alpha;\nexport { u };\n"
        val project = projectWith(source)
        val declaration = project.quickInfoAt(file, offsetOf(source, "Alpha", plus = 1))
        assert(declaration?.displayString == "Plain.Alpha")
        val use = project.quickInfoAt(file, offsetOf(source, "Alpha", occurrence = 1, plus = 1))
        assert(use?.displayString == "Plain.Alpha")
    }

    @Test
    fun `every enum shape reports its member declaration name and its use alike`() {
        // (API.15) The four shapes round 930 measured all-`any`, plus the AMBIENT one
        // whose member tsc reports WITHOUT a value (`(enum member) Amb.Iota`) because a
        // non-const ambient member with no initializer has none — a distinction that
        // does not reach this surface at all, since what is rendered is the type.
        val source =
            "enum Plain { Alpha, Beta }\n" +
                "enum Valued { Gamma = 5 }\n" +
                "const enum Konst { Eps }\n" +
                "enum Str { Zeta = \"z\" }\n" +
                "declare enum Amb { Iota }\n" +
                "const u = [Plain.Beta, Valued.Gamma, Konst.Eps, Str.Zeta, Amb.Iota];\n" +
                "export { u };\n"
        val project = projectWith(source)
        for ((needle, expected) in listOf(
            "Beta" to "Plain.Beta",
            "Gamma = 5" to "Valued.Gamma",
            "Eps" to "Konst.Eps",
            "Zeta = " to "Str.Zeta",
            "Iota" to "Amb.Iota",
        )) {
            val declaration = project.quickInfoAt(file, offsetOf(source, needle, plus = 1))
            assert(declaration?.displayString == expected)
        }
    }

    @Test
    fun `a local enum shadowing an IMPORTED one reports its own member and not the import's`() {
        // (API.15)'s NEGATIVE CONTROL. The leg answers a NAME, so the failure it must not
        // have is resolving the OWNER to some other same-spelled enum: here an
        // `import { Kind as Local }` puts a foreign enum with the same member spelling in
        // scope, and the block-scoped `enum Local` is the one the caret stands in. The
        // two are told apart by the name in the answer — `Local.Alpha`, never
        // `Kind.Alpha` — which is why the shadow is given a DIFFERENT enum name than the
        // import's own.
        val project = Project.open(
            "/proj",
            InMemoryVfs(
                mapOf(
                    "/proj/tsconfig.json" to config,
                    "/proj/src/b.ts" to "export enum Kind { Alpha = 1 }\n",
                    file to "import { Kind as Local } from \"./b\";\n" +
                        "function g(): number { enum Local { Alpha = 2 } return Local.Alpha; }\n" +
                        "export { g, Kind };\n",
                ),
            ),
        )
        val source = "import { Kind as Local } from \"./b\";\n" +
            "function g(): number { enum Local { Alpha = 2 } return Local.Alpha; }\n" +
            "export { g, Kind };\n"
        val local = project.quickInfoAt(file, offsetOf(source, "Alpha = 2", plus = 1))
        assert(local?.displayString == "Local.Alpha")
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

    // --- gap 2: a computed object-literal key, CLOSED round 932 -----------------

    @Test
    fun `a computed key is REWRITTEN by a rename in both its contextual shapes`() {
        // (API.17), round 932 — WAS a pin on two REFUSALS. Round 930 measured a
        // computed key as "usually reported": loudly through the apply-and-recheck gate
        // where the member is required, and loudly through the unresolved-occurrence
        // gate where the literal has no contextual type. Both are now ANSWERS: the key
        // is an ordinary occurrence, so the rename edits it, which is what tsc does.
        val contextual =
            "interface I { p: number }\nconst o: I = { [\"p\"]: 1 };\nconst v = o.p;\nexport { o, v };\n"
        val required = projectWith(contextual)
            .renameAt(file, offsetOf(contextual, "o.p", plus = 2), "q")
        assert(required.refusal == null)
        assert(applied(contextual, required).contains("{ [\"q\"]: 1 }"))

        // With NO contextual type the key declares the literal's own property, so the
        // rename is that property's — one file, three spans, and the key among them.
        val inferred = "const o = { [\"p\"]: 1 };\nconst v = o.p;\nexport { o, v };\n"
        val uncontextual = projectWith(inferred)
            .renameAt(file, offsetOf(inferred, "o.p", plus = 2), "q")
        assert(uncontextual.refusal == null)
        assert(applied(inferred, uncontextual).contains("{ [\"q\"]: 1 }"))
    }

    @Test
    fun `a computed key supplying an OPTIONAL member is rewritten and not stranded`() {
        // (API.17), round 932 — WAS A DEFECT PIN, and the LAST silent shape in this API:
        // with the contextual member optional, dropping the key costs no diagnostic, so
        // the recheck had nothing to refuse on and the literal was left spelling the old
        // name. Nothing in this repository could see that — the applied program compiled
        // clean. tsc renames it, measured over its LSP, and so does this now.
        val source =
            "interface I { p?: number }\nconst o: I = { [\"p\"]: 1 };\nconst v = o.p;\nexport { o, v };\n"
        val project = projectWith(source)
        val plan = project.renameAt(file, offsetOf(source, "o.p", plus = 2), "q")
        assert(plan.isApplicable)
        assert(plan.conflicts.isEmpty())
        val after = applied(source, plan)
        assert(!after.contains("[\"p\"]"))
        assert(after.contains("{ [\"q\"]: 1 }"))
    }

    @Test
    fun `a literal key this API cannot place is refused LOUDLY rather than stranded`() {
        // (API.17)'s stated boundary, and the reason the population admits a literal it
        // cannot RESOLVE. A computed METHOD key (`{ ["m"]() {} }`) declares a member the
        // checker does not put in the object literal's member table at all, so nothing
        // places it — and because it is nonetheless SWEPT it becomes a conflict instead
        // of a rename that leaves it spelling the old name. `m` is OPTIONAL here, which
        // is precisely the shape that used to go through in silence.
        val source =
            "interface I { m?(): void }\nconst o: I = { [\"m\"]() {} };\n" +
                "declare const u: I;\nconst v = u.m;\nexport { o, v };\n"
        val plan = projectWith(source).renameAt(file, offsetOf(source, "u.m", plus = 2), "q")
        assert(plan.refusal == RenameRefusal.OCCURRENCES_INCOMPLETE)
        assert(plan.conflicts.any { it.kind == RenameConflictKind.ELEMENT_ACCESS })
    }

    // --- gap 6: a template element access, CLOSED round 931 ---------------------

    private val templateSource =
        """
        interface I { p: number }
        declare const o: I;
        const a = o.p;
        const b = o[`p`];
        export { a, b };
        """.trimIndent() + "\n"

    @Test
    fun `a template element access is INSIDE the occurrence set and the rename`() {
        // (API.16), round 931 — WAS A DEFECT PIN, and the sharper of § 14's two silent
        // gaps: the rename applied, the template kept spelling the old name, and the
        // resulting program still compiled clean, so nothing anywhere reported it. tsc
        // counts the template's `p` as a reference (measured over its LSP) and now so
        // does this, which is what makes the rename complete rather than quiet.
        val project = projectWith(templateSource)
        val caret = offsetOf(templateSource, "o.p", plus = 2)
        val references = project.referencesAt(file, caret)
        assert(references.size == 3)
        assert(references.any { it.start == offsetOf(templateSource, "o[`p`]", plus = 3) })

        assert(project.diagnostics().isEmpty())
        val plan = project.renameAt(file, caret, "q")
        assert(plan.isApplicable)
        assert(plan.conflicts.isEmpty())
        val after = applied(templateSource, plan)
        assert(after.contains("o[`q`]"))
        assert(after.contains("interface I { q: number }"))
        // The applied program still compiles — which it did BEFORE this was fixed too,
        // with the old name stranded in the template. That is why the assertion above
        // reads the TEXT: a clean recheck is a control here, never the evidence.
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
        // (INC.12) Each row is measured from a FRESH project state, because the claim
        // is "one query is one build" and the capture memo now makes a query that
        // repeats an earlier REQUEST cost zero — which is a different (and separately
        // pinned) property. Re-dirtying between rows is what keeps this row measuring
        // the compile count rather than the memo's hit rate.
        fun fresh() = project.updateFile(file, costSource)
        fresh(); assert(buildsIn(counting) { project.quickInfoAt(file, member) } == 1)
        fresh(); assert(buildsIn(counting) { project.definitionsAt(file, member) } == 1)
        fresh(); assert(buildsIn(counting) { project.completionsAt(file, member) } == 1)
        fresh()
        assert(buildsIn(counting) { project.signatureHelpAt(file, offsetOf(costSource, "o.q(1)", plus = 5)) } == 1)
        fresh(); assert(buildsIn(counting) { project.fileSemantics(file) } == 1)
        fresh(); assert(buildsIn(counting) { project.documentHighlightsAt(file, member) } == 1)
        // ...and the memo's own row, kept HERE rather than only in
        // `ProjectCaptureMemoTest`, because the cost table is what a host author reads:
        // hover then navigate at one caret is ONE build, not two.
        fresh()
        assert(buildsIn(counting) { project.quickInfoAt(file, member) } == 1)
        assert(buildsIn(counting) { project.definitionsAt(file, member) } == 0)
        // (INC.13) …and the row that changed the table: the question is the FILE's, so
        // a caret NOBODY has visited is free after the first hover, and the two
        // file-wide members join the same build rather than each starting one.
        fresh()
        assert(buildsIn(counting) { project.quickInfoAt(file, member) } == 1)
        assert(buildsIn(counting) { project.quickInfoAt(file, offsetOf(costSource, "o.q", plus = 2)) } == 0)
        assert(buildsIn(counting) { project.documentHighlightsAt(file, member) } == 0)
        assert(buildsIn(counting) { project.fileSemantics(file) } == 0)
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
