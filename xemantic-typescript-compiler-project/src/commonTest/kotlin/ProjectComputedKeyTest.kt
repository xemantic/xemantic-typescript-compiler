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
 * (API.17) A MEMBER NAMED BY A LITERAL IN A KEY POSITION — `{ ["p"]: v }`,
 * ``{ [`p`]: v }`` and `{ "p": v }`.
 *
 * ## What this closes
 *
 * `docs/language-service.md` § 14's gap 2, and it was the LAST silent shape in this
 * API. A computed key was outside the swept population, so a member rename left it
 * spelling the old name; where the contextual member is REQUIRED that breaks the
 * program and the apply-and-recheck gate refuses, and where it is OPTIONAL it breaks
 * nothing — the applied program compiled clean, every gate in this repository was
 * green, and the key silently stopped naming the member. Round 930 measured exactly
 * that and pinned it as a defect; this closes it.
 *
 * ## Where the expectations come from
 *
 * Every one was READ OUT of tsc 7.0.2's own language server
 * (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, `scripts/lsp_member_refs.py`,
 * `lsp_rename.py`, `lsp_hover.py`, `lsp_definition.py`, `lsp_completion.py`) over a
 * fixture of this shape. Two answers decided design rather than confirming it: a
 * computed key that is a NAME (`{ [K]: v }`) is a reference to the BINDING and to
 * nothing else, and a caret inside a computed key offers NO completions at all.
 *
 * ## The discriminators
 *
 * `p` is also spelled by an unrelated exported top-level binding — the answer a hover
 * derived from the scope chain gives, and the one this used to give. `Other.p` is an
 * unrelated member of the same spelling, so an answer derived from the spelling merges
 * the two groups. And the fixture carries a plain `"p"` in a position that names no
 * member, so an answer derived from the TEXT finds one span too many.
 */
class ProjectComputedKeyTest {

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
     * The members are OPTIONAL, and that is a statement about the CHECKER rather than a
     * convenience.
     *
     * Round 932 recorded one gap here: a computed key whose literal is a no-substitution
     * TEMPLATE did not supply the member it names (`{ [`p`]: v }` against a required `p`
     * was TS2741) while the quoted and bare forms did. **Round 933 CLOSED that one** —
     * `computedLiteralKey` grew the template arm, so all three literal spellings are now
     * one member name at every extraction site.
     *
     * What is still open, and why the members stay optional: `{ [K]: v }` — the fixture's
     * `viaConst` — supplies nothing, because naming a member through a `const` binding
     * needs the key's TYPE, which this compiler does not late-bind (round 933 measured it
     * against tsc, which does). That gap is one layer below this API and is left alone
     * here; optional members keep this fixture about the language SERVICE.
     */
    private val other = """
        export interface Shape { p?: string; q?: number; n?: Nested; }
        export interface Nested { inner?: boolean; }
        export interface Other { p?: number; }
    """.trimIndent() + "\n"

    private val main = """
        import { Shape, Nested, Other } from "./b";
        export const p = "an unrelated top-level binding";
        export const K = "p";
        export const plain: Shape = { p: "id", q: 1, n: { inner: true } };
        export const computed: Shape = { ["p"]: "computed", q: 2, ["n"]: { ["inner"]: true } };
        export const templated: Shape = { [`p`]: "template", q: 3, n: { inner: true } };
        export const quoted: Shape = { "p": "quoted", q: 4, n: { inner: true } };
        export const viaConst: Shape = { [K]: "const", q: 5, n: { inner: true } };
        export const free = { ["p"]: "free" };
        export const unrelatedLiteral = "p";
        export const other: Other = { p: 6 };
        export const read = plain.p;
    """.trimIndent() + "\n"

    private fun projectWith(): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf("/proj/tsconfig.json" to config, mainFile to main, otherFile to other),
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

    /** The whole occurrence set of `Shape.p`, taken from the interface's declaration. */
    private fun shapeGroup(project: Project): List<ReferenceLocation> =
        project.referencesAt(otherFile, offsetOf("p?: string", 0, other))

    private val inComputed get() = offsetOf("""["p"]""") + 2
    private val inTemplate get() = offsetOf("[`p`]") + 2
    private val inQuoted get() = offsetOf(""""p": "quoted"""") + 1
    private val inConstKey get() = offsetOf("[K]") + 1
    private val inFree get() = offsetOf("""{ ["p"]: "free"""") + 4
    private val inIdentifier get() = offsetOf("""{ p: "id"""") + 2

    /**
     * THE FIXTURE MEASURES WHAT IT CLAIMS: it compiles clean, so no assertion below is
     * about a program the checker already rejects — and, specifically, the computed
     * keys are legal supplies of the members they name.
     */
    @Test
    fun `the fixture compiles with no diagnostics`() {
        assert(projectWith().diagnostics().isEmpty())
    }

    // --- the occurrence set -------------------------------------------------------

    /**
     * THE DISCRIMINATOR. `Shape.p`'s group is EXACTLY the six spans tsc 7.0.2 answers
     * over this shape, and the fixture carries three more `p`s that must not be in it:
     * an unrelated top-level `const p`, `Other`'s own `p`, and a plain string literal
     * `"p"` that names nothing. A spelling scan finds all nine.
     */
    @Test
    fun `every literal key spelling the member is in its group and nothing else is`() {
        val project = projectWith()
        val group = places(shapeGroup(project))
        assert(
            group.sorted() == listOf(
                "b.ts@${offsetOf("p?: string", 0, other)}",
                "a.ts@$inIdentifier",
                "a.ts@$inComputed",
                "a.ts@$inTemplate",
                "a.ts@$inQuoted",
                "a.ts@${offsetOf("plain.p") + 6}",
            ).sorted(),
        )
    }

    /**
     * The SPAN is the literal's text and not its delimiters — for a quote and for a
     * BACKTICK alike. Asserted as the TEXT, because the failure that matters is
     * `{ [newName]: v }` and `{ newName: v }`, which both compile and mean something
     * else. tsc edits the same spans (measured).
     */
    @Test
    fun `a literal key occurrence covers the text between its delimiters`() {
        val project = projectWith()
        val group = shapeGroup(project)
        assert(group.all { main.substring(it.start, it.end) == "p" || it.fileName == otherFile })
        assert(group.any { it.start == inComputed && it.end == inComputed + 1 })
        assert(group.any { it.start == inTemplate && it.end == inTemplate + 1 })
    }

    /** The caret may be IN the key, and then it answers the member's whole group. */
    @Test
    fun `a caret inside a computed key answers the member's whole group`() {
        val project = projectWith()
        assert(places(project.referencesAt(mainFile, inComputed)) == places(shapeGroup(project)))
        assert(places(project.referencesAt(mainFile, inTemplate)) == places(shapeGroup(project)))
        assert(places(project.referencesAt(mainFile, inQuoted)) == places(shapeGroup(project)))
    }

    /**
     * A NESTED literal's computed key resolves too, and it is not free: the step OUT of
     * the inner literal has to read the OUTER key's name, which for `{ ["n"]: { … } }`
     * is spelled by a literal as well. Without that the inner key resolves only to
     * itself — measured, mid-round, as a group of one.
     */
    @Test
    fun `a computed key nested under another computed key resolves`() {
        val project = projectWith()
        // The OUTER key is computed too, and that is what this pin is for: the step out
        // of the inner literal has to read a computed name to know which member of the
        // outer type received it. With an identifier outer key the shape exercises
        // nothing — measured, as a zero-red ablation arm.
        assert(main.contains("""["n"]: { ["inner"]"""))
        val nestedGroup = project.referencesAt(otherFile, offsetOf("inner?: boolean", 0, other))
        assert("a.ts@${offsetOf("""["inner"]""") + 2}" in places(nestedGroup))
        // …and the outer computed key is in ITS member's group, from the same walk.
        val outerGroup = project.referencesAt(otherFile, offsetOf("n?: Nested", 0, other))
        assert("a.ts@${offsetOf("""["n"]""") + 2}" in places(outerGroup))
    }

    /**
     * A COMPUTED NAME THAT IS A BINDING NAMES THE BINDING. `{ [K]: v }` spells no fixed
     * member — the value is decided at run time — and tsc 7.0.2 answers the const's own
     * two spans there, not the member's. Both directions, because the wrong answer is
     * available on both: the member's group must not contain the `K`, and a caret on it
     * must answer the const.
     */
    @Test
    fun `a computed key that is a name is a reference to that binding and not to the member`() {
        val project = projectWith()
        assert("a.ts@$inConstKey" !in places(shapeGroup(project)))
        assert(
            places(project.referencesAt(mainFile, inConstKey)).sorted() ==
                listOf("a.ts@${offsetOf("K = ")}", "a.ts@$inConstKey").sorted(),
        )
    }

    /**
     * A computed key with NO contextual type declares the literal's OWN property, so it
     * is a group of one — the same free-key branch a bare `{ p: v }` takes, and the same
     * answer tsc gives (one reference, the key itself).
     */
    @Test
    fun `a computed key with no contextual type is its own declaration alone`() {
        val project = projectWith()
        assert(places(project.referencesAt(mainFile, inFree)) == listOf("a.ts@$inFree"))
    }

    /**
     * A computed member DECLARATION — `interface I { ["ip"]: number }` — falls out of
     * the same population, and it is what made the round's smallest edit necessary: the
     * capture answers a declaration's location through its NAME node, and a computed
     * name used to fall back to the whole `["ip"]: number`. That is an offset no
     * occurrence begins at, so the rename's group held a key with no node behind it and
     * refused itself with `no identifier node at this occurrence` — coarser is not free.
     * tsc answers the same three spans over this shape (measured).
     */
    @Test
    fun `a computed member declaration is an occurrence with a text-only span`() {
        val project = Project.open(
            "/proj",
            InMemoryVfs(
                mapOf(
                    "/proj/tsconfig.json" to config,
                    mainFile to declMain,
                    otherFile to "export const unused = 1;\n",
                ),
            ),
        )
        val caret = declMain.indexOf("i.ip") + 2
        val group = project.referencesAt(mainFile, caret)
        assert(
            group.map { it.start }.sorted() == listOf(
                declMain.indexOf("""["ip"]""") + 2,
                declMain.indexOf("""i["ip"]""") + 3,
                caret,
            ).sorted(),
        )
        assert(group.all { declMain.substring(it.start, it.end) == "ip" })
        val plan = project.renameAt(mainFile, caret, "renamed")
        assert(plan.isApplicable)
        var after = declMain
        for (edit in plan.files.single().edits.asReversed()) {
            after = after.substring(0, edit.start) + edit.newText + after.substring(edit.end)
        }
        assert(after.contains("""["renamed"]: number"""))
        assert(after.contains("""i["renamed"]"""))
        assert(after.contains("i.renamed"))
    }

    private val declMain = """
        export interface IFace { ["ip"]: number; plain: string }
        declare const i: IFace;
        export const viaAccess = i["ip"];
        export const viaDot = i.ip;
    """.trimIndent() + "\n"

    // --- go to definition ---------------------------------------------------------

    /**
     * Go-to-definition on a computed key answers the CONTEXTUAL member, which is what
     * tsc navigates to; with no contextual type it answers the key itself.
     */
    @Test
    fun `go to definition on a computed key answers the contextual member`() {
        val project = projectWith()
        val target = project.definitionsAt(mainFile, inComputed)
        assert(target.size == 1)
        assert(target[0].fileName == otherFile)
        assert(target[0].start == offsetOf("p?: string", 0, other))
        assert(project.definitionsAt(mainFile, inFree).single().fileName == mainFile)
    }

    // --- hover --------------------------------------------------------------------

    /**
     * HOVER ON A KEY REPORTS THE MEMBER'S TYPE, and this pin is the audit finding that
     * came with the round: before it, EVERY object-literal key — computed or not —
     * answered the free-name path, which is `any` where nothing shares the spelling and
     * the COLLIDER'S TYPE where something does. This fixture's `p` is a `string` member
     * beside an unrelated top-level `const p: string`… so the collider is chosen to be a
     * DIFFERENT type from the member it collides with, `Other.p: number` against
     * `Shape.p: string`, and the free binding is a string. The discriminating pin is
     * therefore the one below on `n`, whose member type is `Nested` and whose only
     * same-spelled neighbour is nothing at all.
     */
    @Test
    fun `hover on a key reports the member's type and not the enclosing scope's`() {
        val project = projectWith()
        assert(project.quickInfoAt(mainFile, inIdentifier)?.displayString == "string")
        assert(project.quickInfoAt(mainFile, inComputed)?.displayString == "string")
        assert(project.quickInfoAt(mainFile, inTemplate)?.displayString == "string")
        assert(project.quickInfoAt(mainFile, inQuoted)?.displayString == "string")
    }

    /**
     * THE DISCRIMINATING HOVER: `n` is a `Nested` member and nothing else in the program
     * is spelled `n`, so before this round it answered `any` — a confidently blank
     * answer where tsc says `(property) Shape.n: Nested`. `any` is what a free-name
     * fallback produces, so this fails for any fix that merely stopped crashing.
     */
    @Test
    fun `hover on a key whose spelling nothing else carries is the member's type`() {
        val project = projectWith()
        val inNestedKey = offsetOf("""["inner"]""") + 2
        assert(project.quickInfoAt(mainFile, inNestedKey)?.displayString == "boolean")
        assert(
            project.quickInfoAt(mainFile, offsetOf("n: { inner: true }"))?.displayString ==
                "Nested",
        )
    }

    /**
     * A key with no contextual type reports its OWN value's type — tsc's answer too
     * (`(property) ["p"]: string`, of which this API renders the type half) — and a
     * computed key that is a NAME reports the BINDING's type, never a member's.
     */
    @Test
    fun `a free key reports its own value and a name key reports the binding`() {
        val project = projectWith()
        assert(project.quickInfoAt(mainFile, inFree)?.displayString == "string")
        assert(project.quickInfoAt(mainFile, inConstKey)?.displayString == "\"p\"")
    }

    // --- completion ---------------------------------------------------------------

    /**
     * NO COMPLETIONS INSIDE A COMPUTED KEY, which is parity rather than a limitation:
     * tsc 7.0.2 answers a NULL result at every one of these carets — empty, partial,
     * template and quoted alike — measured, where at the same caret inside an
     * `o["‸"]` it answers the member list. The refusal is stated, not silent.
     */
    @Test
    fun `a caret inside a computed key offers nothing`() {
        val project = projectWith()
        for (caret in listOf(inComputed, inTemplate, inQuoted)) {
            val list = project.completionsAt(mainFile, caret)
            assert(list.kind == CompletionKind.NONE)
            assert(list.items.isEmpty())
        }
    }

    // --- rename, verified by the resulting TEXT ------------------------------------

    /**
     * The rename fixture is a SECOND, smaller file pair, for `ProjectContextualKeyTest`'s
     * reason: `Other`'s own `p` is a member DECLARATION name this API resolves to
     * nothing, so its presence refuses every member rename in the query fixture above.
     */
    private val renameOther = """
        export interface Shape { p?: string; q?: number; }
    """.trimIndent() + "\n"

    private val renameMain = """
        import { Shape } from "./b";
        export const p = "an unrelated top-level binding";
        export const computed: Shape = { ["p"]: "computed", q: 1 };
        export const templated: Shape = { [`p`]: "template", q: 2 };
        export const quoted: Shape = { "p": "quoted", q: 3 };
        export const read = computed.p;
    """.trimIndent() + "\n"

    private fun renameProject(): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to renameMain,
                otherFile to renameOther,
            ),
        ),
    )

    private fun appliedToMain(plan: RenamePlan): String {
        var result = renameMain
        for (edit in plan.files.single { it.fileName == mainFile }.edits.asReversed()) {
            result = result.substring(0, edit.start) + edit.newText + result.substring(edit.end)
        }
        return result
    }

    /**
     * THE RESULTING TEXT, which is the only assertion that can tell a correct edit from
     * one that eats a delimiter: `{ [renamed]: v }` and `{ renamed: v }` both compile
     * and both mean something else. The plan is additionally applied and recompiled by
     * `renameAt` itself, so an applicable plan is one a build has already accepted.
     */
    @Test
    fun `renaming the member rewrites every literal key and keeps its delimiters`() {
        val project = renameProject()
        val plan = project.renameAt(
            otherFile, renameOther.indexOf("p?: string"), "renamed",
        )
        assert(plan.isApplicable)
        assert(plan.conflicts.isEmpty())
        val after = appliedToMain(plan)
        assert(after.contains("""{ ["renamed"]: "computed""""))
        assert(after.contains("{ [`renamed`]: \"template\""))
        assert(after.contains("""{ "renamed": "quoted""""))
        assert(after.contains("computed.renamed"))
        // …and the unrelated top-level binding of the same spelling is untouched.
        assert(after.contains("""export const p = "an unrelated"""))
    }

    /**
     * …and a computed key that is a NAME renames as the BINDING it is, writing
     * `[renamed]` and leaving the member alone. tsc writes exactly this (measured).
     */
    @Test
    fun `renaming a name used as a computed key rewrites the binding`() {
        val source = """
            export const K = "p";
            export interface Shape { p?: string }
            export const viaConst: Shape = { [K]: "v" };
        """.trimIndent() + "\n"
        val project = Project.open(
            "/proj",
            InMemoryVfs(
                mapOf("/proj/tsconfig.json" to config, mainFile to source),
            ),
        )
        val plan = project.renameAt(mainFile, source.indexOf("[K]") + 1, "renamed")
        assert(plan.isApplicable)
        var after = source
        for (edit in plan.files.single().edits.asReversed()) {
            after = after.substring(0, edit.start) + edit.newText + after.substring(edit.end)
        }
        assert(after.contains("{ [renamed]: \"v\" }"))
        assert(after.contains("""export const renamed = "p""""))
        assert(after.contains("interface Shape { p?: string }"))
    }

    /** The other direction: the caret IN a computed key renames the whole group. */
    @Test
    fun `renaming from inside a computed key rewrites the whole group`() {
        val project = renameProject()
        val caret = renameMain.indexOf("""["p"]""") + 2
        val plan = project.renameAt(mainFile, caret, "renamed")
        assert(plan.isApplicable)
        val after = appliedToMain(plan)
        assert(after.contains("""{ ["renamed"]: "computed""""))
        assert(after.contains("computed.renamed"))
        assert(plan.files.any { it.fileName == otherFile })
    }
}
