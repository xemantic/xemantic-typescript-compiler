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
 * (API.11) A MEMBER DECLARATION NAME RESOLVES TO ITS OWN SYMBOL — the fourth
 * resolution mechanism, and what stood between a member rename and "it always works".
 *
 * ## Where every expectation comes from
 *
 * Read out of tsc 7.0.2's own language server (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`,
 * `scripts/lsp_member_refs.py` plus a definition/hover driver) over fixtures of these
 * shapes, twenty-two carets in two runs — never reasoned about. Two answers decided the
 * design:
 *
 * - a member declared TWICE in a merged `interface` answers **both** declarations from
 *   **either** of them, so resolving a declaration name to *itself* is not enough;
 * - an OVERLOAD set and an ACCESSOR PAIR behave identically — one member, several
 *   declaration names — which is the same fact one grammar over.
 *
 * ## The hazard this file exists to hold down
 *
 * The rename completeness net refuses when an identifier spelling the old name cannot be
 * shown unrelated. Making a declaration name resolve removes it from that net, so a
 * declaration resolving to the WRONG thing — or to only PART of its symbol — would turn
 * a loud refusal into a silently short rename. Hence every positive assertion here is on
 * the SPANS and not on a count, and hence the negative controls: an unrelated
 * `interface Other { p }` must stay out of `Shape.p`'s group, and a block-scoped
 * `interface Shape` whose name resolves to the IMPORTED one must contribute nothing.
 */
class ProjectMemberDeclarationTest {

    /**
     * An ES `module` kind and TWO program files, for `ProjectReferenceTest`'s reason:
     * below either, the unresolved-import region returns early and every
     * import-crossing assertion here would be vacuous.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * `Merged` declares `both` in BOTH blocks — the measured hazard — and `Other`
     * declares a `p` that must never join `Shape`'s group.
     */
    private val other = """
        export interface Shape { p: string; }
        export interface Merged { both: number; only1: string; }
        export interface Merged { both: number; only2: string; }
        export interface Other { p: boolean; }
    """.trimIndent() + "\n"

    /**
     * `export const p` is the HOVER discriminator: a member name asked as a free name
     * answers this `boolean` where the member is a `string`, which is exactly the
     * (BUG.4) failure one position over.
     */
    private val main = """
        import { Shape, Merged, Other } from "./b";
        export const p: boolean = true;
        export type Lit = { tl: string };
        export enum E { Alpha = 1, Beta = 2 }
        export interface Solo { unusedMember: string; }
        export class C implements Shape {
            p = "x";
            static stat = 1;
            #hidden = 2;
            ov(a: string): void;
            ov(a: number): void;
            ov(a: unknown): void {}
            get acc(): number { return 1; }
            set acc(v: number) {}
            peek(): number { return this.#hidden; }
        }
        export const objlit = { om(): number { return 1; } };
        export function scoped(): string {
            interface Shape { p: string; }
            const v: Shape = { p: "a" };
            return v.p;
        }
        declare const s: Shape;
        declare const g: Merged;
        declare const o: Other;
        declare const l: Lit;
        export function use(): void {
            s.p; g.both; o.p; l.tl; E.Alpha; C.stat; objlit.om();
            const c = new C();
            c.p; c.ov("a"); c.acc; c.acc = 2; c.peek();
        }
    """.trimIndent() + "\n"

    private fun projectWith(): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to main,
                otherFile to other,
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

    /** [locations] as `file@start` strings, so a failure diagram names the places. */
    private fun defPlaces(locations: List<DefinitionLocation>): List<String> =
        locations.map { "${it.fileName.substringAfterLast('/')}@${it.start}" }

    private fun refPlaces(references: List<ReferenceLocation>): List<String> =
        references.map { "${it.fileName.substringAfterLast('/')}@${it.start}" }

    /** [plan] applied to [text] — back to front, which is the documented way. */
    private fun applied(plan: RenamePlan, fileName: String, text: String): String {
        var result = text
        val file = plan.files.firstOrNull { it.fileName == fileName } ?: return text
        for (edit in file.edits.asReversed()) {
            result = result.substring(0, edit.start) + edit.newText + result.substring(edit.end)
        }
        return result
    }

    // --- go to definition: it answers ITSELF, and its WHOLE symbol -----------------

    /**
     * The base case. A member declaration name navigates to itself — measured on tsc,
     * where every one of eighteen member declaration positions does.
     *
     * The discriminator is `export const p`, a file-level binding of the same spelling
     * and another type: a scope-chain answer would name it.
     */
    @Test
    fun `an interface member's declaration name answers itself`() {
        val project = projectWith()
        val at = offsetOf("p: string;", 0, other)
        assert(defPlaces(project.definitionsAt(otherFile, at)) == listOf("b.ts@$at"))
    }

    /**
     * THE HAZARD, and the reason this leg resolves to a SYMBOL rather than to the node
     * under the caret. `both` is declared in two `interface Merged` blocks; tsc answers
     * BOTH declarations from EITHER of them, and so must this — a group missing the
     * other block is precisely the thing the rename completeness net exists to catch,
     * and this leg is what removes those spans from the net's view.
     */
    @Test
    fun `a member declared in two merged interface blocks answers both declarations`() {
        val project = projectWith()
        val first = offsetOf("both: number; only1", 0, other)
        val second = offsetOf("both: number; only2", 0, other)
        val expected = listOf("b.ts@$first", "b.ts@$second")
        assert(defPlaces(project.definitionsAt(otherFile, first)) == expected)
        assert(defPlaces(project.definitionsAt(otherFile, second)) == expected)
    }

    /** An OVERLOAD set is one member with three declaration names, from any of them. */
    @Test
    fun `an overloaded method answers every one of its declarations`() {
        val project = projectWith()
        val signatures = listOf(
            offsetOf("ov(a: string)"),
            offsetOf("ov(a: number)"),
            offsetOf("ov(a: unknown)"),
        )
        val expected = signatures.map { "a.ts@$it" }
        for (at in signatures) assert(defPlaces(project.definitionsAt(mainFile, at)) == expected)
    }

    /** An ACCESSOR PAIR is the same fact one grammar over: one member, two names. */
    @Test
    fun `a getter and its setter answer both declarations`() {
        val project = projectWith()
        val getter = offsetOf("acc(): number")
        val setter = offsetOf("acc(v: number)")
        val expected = listOf("a.ts@$getter", "a.ts@$setter")
        assert(defPlaces(project.definitionsAt(mainFile, getter)) == expected)
        assert(defPlaces(project.definitionsAt(mainFile, setter)) == expected)
    }

    /**
     * The other owners: a TYPE LITERAL (which has no name to resolve, so it is its own
     * only container), an ENUM, a STATIC and a `#private` field.
     */
    @Test
    fun `a type-literal member an enum member a static and a private field all answer themselves`() {
        val project = projectWith()
        for (needle in listOf("tl: string", "Alpha = 1", "stat = 1", "#hidden = 2")) {
            val at = offsetOf(needle)
            assert(defPlaces(project.definitionsAt(mainFile, at)) == listOf("a.ts@$at"))
        }
    }

    /**
     * NEGATIVE CONTROL for the owner route's soundness condition. The inner
     * `interface Shape` is block-scoped, which this binder does not bind, so resolving
     * its NAME finds the IMPORTED `Shape` — whose `p` must contribute nothing, because
     * the owner we are standing in is not one of that symbol's declarations. Drop the
     * identity check and this answers two places in two files.
     */
    @Test
    fun `a block-scoped interface whose name resolves elsewhere contributes no container`() {
        val project = projectWith()
        val at = offsetOf("p: string; }", 0, main)
        assert(defPlaces(project.definitionsAt(mainFile, at)) == listOf("a.ts@$at"))
    }

    /**
     * NEGATIVE CONTROL for the exclusion. An OBJECT LITERAL's own member is left to
     * (API.10)'s key leg and to what preceded it — a contextually typed literal's
     * member is an occurrence of the CONTEXTUAL type's member, and resolving it to
     * itself would take it out of the rename completeness net WITHOUT putting it in the
     * group. tsc answers it; this is a stated divergence, and it is the conservative
     * direction.
     */
    @Test
    fun `an object literal's own method is deliberately not answered`() {
        val project = projectWith()
        assert(project.definitionsAt(mainFile, offsetOf("om(): number")).isEmpty())
    }

    // --- find references ----------------------------------------------------------

    /**
     * The group from a declaration is the group from a use — including, for a merged
     * member, the OTHER block's declaration, which round 927 could not reach.
     */
    @Test
    fun `a merged member's group is the same from either declaration and from a use`() {
        val project = projectWith()
        val first = offsetOf("both: number; only1", 0, other)
        val second = offsetOf("both: number; only2", 0, other)
        val use = offsetOf("g.both") + 2
        val expected = listOf("a.ts@$use", "b.ts@$first", "b.ts@$second")
        assert(refPlaces(project.referencesAt(otherFile, first)) == expected)
        assert(refPlaces(project.referencesAt(otherFile, second)) == expected)
        assert(refPlaces(project.referencesAt(mainFile, use)) == expected)
    }

    /**
     * THE DISCRIMINATOR against a spelling scan, and the shape that used to refuse the
     * rename below: `Other.p` is a second declaration of the same member NAME and is a
     * different member, so neither group may contain the other's spans.
     */
    @Test
    fun `an unrelated interface's member of the same name is a different group`() {
        val project = projectWith()
        val shape = project.referencesAt(otherFile, offsetOf("p: string;", 0, other))
        val unrelated = project.referencesAt(otherFile, offsetOf("p: boolean", 0, other))
        assert("b.ts@${offsetOf("p: boolean", 0, other)}" !in refPlaces(shape))
        assert("b.ts@${offsetOf("p: string;", 0, other)}" !in refPlaces(unrelated))
        // …and the file-level `const p` is in neither.
        assert("a.ts@${offsetOf("p: boolean = true")}" !in refPlaces(shape))
    }

    /** An overload set's declarations are all flagged, and all in one group. */
    @Test
    fun `every overload declaration is flagged in the one group`() {
        val project = projectWith()
        val group = project.referencesAt(mainFile, offsetOf("ov(a: string)"))
        assert(group.count { it.isDeclaration } == 3)
        assert(group.count { !it.isDeclaration } == 1)
    }

    // --- hover --------------------------------------------------------------------

    /**
     * (BUG.4) one position over. A member declaration name used to report the type of
     * whatever unrelated binding shared its spelling — here `export const p: boolean`
     * against a `string` member, which is the collider shape round 924 measured twelve
     * times over.
     */
    @Test
    fun `a member declaration name reports the member's type and not a collider's`() {
        val project = projectWith()
        assert(
            project.quickInfoAt(otherFile, offsetOf("p: string;", 0, other))?.displayString ==
                "string",
        )
        assert(
            project.quickInfoAt(otherFile, offsetOf("p: boolean", 0, other))?.displayString ==
                "boolean",
        )
        assert(project.quickInfoAt(mainFile, offsetOf("stat = 1"))?.displayString == "number")
        assert(project.quickInfoAt(mainFile, offsetOf("tl: string"))?.displayString == "string")
    }

    // --- rename: the point of the round -------------------------------------------

    /**
     * THE HEADLINE. A member rename FROM ITS OWN DECLARATION, in a program that also
     * declares `Other.p` — the "second declaration of the same member name" that
     * refused every such rename up to round 927 — produces a plan, and the plan is
     * applied and the program recompiled to exactly the diagnostics it had.
     *
     * That last step is the strongest pin available here and it is an INDEPENDENT
     * oracle of `renameAt`'s own verification: that one runs on a scratch overlay
     * through the capture path, this one through `updateFile` and the ordinary
     * diagnostic path.
     */
    @Test
    fun `a member renames from its own declaration name and the program still compiles`() {
        val project = projectWith()
        val before = project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" }
        val plan = project.renameAt(otherFile, offsetOf("p: string;", 0, other), "renamedP")
        assert(plan.refusal == null)
        val newMain = applied(plan, mainFile, main)
        val newOther = applied(plan, otherFile, other)
        assert("export interface Shape { renamedP: string; }" in newOther)
        // …and the unrelated `Other.p` and the file-level `const p` are untouched.
        assert("export interface Other { p: boolean; }" in newOther)
        assert("export const p: boolean = true;" in newMain)
        assert("renamedP = \"x\";" in newMain)
        project.updateFile(mainFile, newMain)
        project.updateFile(otherFile, newOther)
        assert(project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" } == before)
    }

    /**
     * The hazard, cashed. Renaming a member declared in TWO merged blocks must rewrite
     * BOTH declarations; a plan that rewrote one would leave a program that still
     * compiles under a different meaning, which is why this asserts the resulting TEXT
     * as well as recompiling it.
     */
    @Test
    fun `renaming a merged member rewrites both declarations`() {
        val project = projectWith()
        val before = project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" }
        val plan = project.renameAt(otherFile, offsetOf("both: number; only1", 0, other), "b2")
        assert(plan.refusal == null)
        val newOther = applied(plan, otherFile, other)
        assert("export interface Merged { b2: number; only1: string; }" in newOther)
        assert("export interface Merged { b2: number; only2: string; }" in newOther)
        val newMain = applied(plan, mainFile, main)
        assert("g.b2;" in newMain)
        project.updateFile(mainFile, newMain)
        project.updateFile(otherFile, newOther)
        assert(project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" } == before)
    }

    /** An overload set renames all three signatures together, from any one of them. */
    @Test
    fun `renaming an overloaded method from one signature rewrites all of them`() {
        val project = projectWith()
        val before = project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" }
        val plan = project.renameAt(mainFile, offsetOf("ov(a: number)"), "renamedOv")
        assert(plan.refusal == null)
        val newMain = applied(plan, mainFile, main)
        assert("renamedOv(a: string): void;" in newMain)
        assert("renamedOv(a: number): void;" in newMain)
        assert("renamedOv(a: unknown): void {}" in newMain)
        assert("""c.renamedOv("a");""" in newMain)
        project.updateFile(mainFile, newMain)
        assert(project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" } == before)
    }

    /** A getter and its setter rename together — one member, two declaration names. */
    @Test
    fun `renaming an accessor from its getter rewrites the setter too`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("acc(): number"), "renamedAcc")
        assert(plan.refusal == null)
        val newMain = applied(plan, mainFile, main)
        assert("get renamedAcc(): number" in newMain)
        assert("set renamedAcc(v: number)" in newMain)
        assert("c.renamedAcc;" in newMain)
    }

    /** A member declared and NEVER used renames — there was no evidence to recover. */
    @Test
    fun `a member declared and never used renames from its declaration`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("unusedMember: string"), "renamedUnused")
        assert(plan.refusal == null)
        assert("interface Solo { renamedUnused: string; }" in applied(plan, mainFile, main))
    }
}
