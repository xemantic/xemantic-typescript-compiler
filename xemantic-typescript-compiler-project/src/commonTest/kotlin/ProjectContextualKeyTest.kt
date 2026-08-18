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
 * (API.10) ONE SPAN, TWO SYMBOLS — a contextually-typed object-literal key and the two
 * SHORTHANDS, which are the last of round 922's five refusals.
 *
 * ## Where every expectation here comes from
 *
 * All of it was READ OUT of tsc 7.0.2's own language server
 * (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, driven by `scripts/lsp_rename.py` and
 * `scripts/lsp_member_refs.py`) over a fixture of this shape — thirty-two carets,
 * references and rename and definition and hover at each. Three answers were surprises
 * and each has a pin below:
 *
 * - a generic call whose type argument is INFERRED does NOT put its key in the group,
 *   because the parameter is a naked type parameter and names no member — the same
 *   answer this API gives for the same reason, rather than by agreement;
 * - a caret ON a shorthand answers the LOCAL's group and nothing else, while the
 *   MEMBER's group CONTAINS that token. The relation is asymmetric, and that is what
 *   `CapturedDefinition.shorthand` exists to say;
 * - a caret on a CONTEXTUAL key answers the union of TWO groups — the contextual
 *   member's and the literal's own property's — so `sat.p`, which reads the literal's
 *   own `p`, is in the answer while the member's own group does not contain it.
 *
 * ## The discriminators
 *
 * `p` is also spelled by an unrelated exported top-level binding, so any answer derived
 * from the scope chain names the wrong thing. `Other.p` is a second member of the same
 * spelling with no relation to `Shape.p`, so any answer derived from the spelling merges
 * them. And the RENAME assertions are on the RESULTING TEXT: a shorthand expands as
 * `p: renamed` from one side and `renamed: p` from the other, both compile, and no
 * assertion about the number of edits can tell them apart.
 */
class ProjectContextualKeyTest {

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

    private val other = """
        export interface Shape { p: string; q: number; }
        export interface Other { p: number; r: boolean; }
    """.trimIndent() + "\n"

    /**
     * One object literal per contextual position, each key tagged with the position's
     * name so a failure diagram says which one moved. `export const p` and `Other.p` are
     * the two colliders.
     */
    private val main = """
        import { Shape, Other } from "./b";
        export const p = "an unrelated top-level binding";
        declare const q: number;
        declare function takes(s: Shape): void;
        takes({ p: "in-arg", q: 1 });
        const annotated: Shape = { p: "in-annot", q: 2 };
        function returns(): Shape { return { p: "in-return", q: 3 }; }
        interface Outer { inner: Shape; }
        const nested: Outer = { inner: { p: "in-nested", q: 4 } };
        const arr: Shape[] = [{ p: "in-array", q: 5 }];
        const sat = { p: "in-satisfies", q: 6 } satisfies Shape;
        const asserted = { p: "in-as", q: 7 } as Shape;
        declare const cond: boolean;
        const ternary: Shape = cond ? { p: "in-ternary", q: 8 } : annotated;
        function withDefault(s: Shape = { p: "in-default", q: 9 }) { return s; }
        class Holder { s: Shape = { p: "in-classprop", q: 10 }; }
        declare function takesGeneric<T>(t: T): T;
        const explicit = takesGeneric<Shape>({ p: "in-generic", q: 11 });
        const inferred: Shape = takesGeneric({ p: "in-inferred", q: 12 });
        const free = { p: "in-free", q: 13 };
        const computed: Shape = { ["p"]: "in-computed", q: 14 };
        declare function takesUnion(u: Shape | Other): void;
        takesUnion({ p: "in-union", q: 15 });
        const shorthandLit: Shape = { p, q };
        function destructures() {
            const { p } = annotated;
            return p;
        }
        export const reads = [annotated.p, sat.p, free.p, ternary.p];
        console.log(asserted, withDefault, Holder, explicit, inferred, computed, shorthandLit, destructures);
    """.trimIndent() + "\n"

    /**
     * The RENAME fixture is a SECOND file pair, and the reason is a refusal this round
     * did not close: a member DECLARATION name resolves to nothing here (it is bound by
     * no scope and has no receiver), so `Other`'s own `p` above is an identifier the
     * completeness net cannot place and every member rename in the query fixture is
     * refused because of it. That is (API.8)'s net doing its job — the missed occurrence
     * it is guarding against is a merged declaration — and closing it needs a member
     * declaration name to resolve to its SYMBOL, which is the successor this round names.
     * So the rename pins run on a program with one interface, where the shorthand
     * expansion is the only thing under test.
     */
    private val renameOther = """
        export interface Shape { p: string; q: number; }
    """.trimIndent() + "\n"

    private val renameMain = """
        import { Shape } from "./b";
        export const p = "an unrelated top-level binding";
        declare const q: number;
        declare function takes(s: Shape): void;
        takes({ p: "in-arg", q: 1 });
        const annotated: Shape = { p: "in-annot", q: 2 };
        const free = { p: "in-free", q: 3 };
        const shorthandLit: Shape = { p, q };
        function destructures() {
            const { p } = annotated;
            return p;
        }
        export const reads = [annotated.p, free.p, shorthandLit, destructures];
    """.trimIndent() + "\n"

    private fun projectWith(mainText: String = main): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to mainText,
                otherFile to other,
            ),
        ),
    )

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

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [text]. */
    private fun offsetOf(needle: String, occurrence: Int = 0, text: String = main): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /** The offset of the key `p` of the literal tagged [tag]. */
    private fun keyOf(tag: String): Int = offsetOf("""{ p: "$tag"""") + 2

    /** [references] as `file@start` strings, so a failure diagram names the places. */
    private fun places(references: List<ReferenceLocation>): List<String> =
        references.map { "${it.fileName.substringAfterLast('/')}@${it.start}" }

    /** The whole occurrence set of `Shape.p`, taken from the interface's declaration. */
    private fun shapeGroup(project: Project): List<ReferenceLocation> =
        project.referencesAt(otherFile, offsetOf("p: string", 0, other))

    private fun appliedTo(plan: RenamePlan, fileName: String, text: String): String {
        var result = text
        for (edit in plan.files.single { it.fileName == fileName }.edits.asReversed()) {
            result = result.substring(0, edit.start) + edit.newText + result.substring(edit.end)
        }
        return result
    }

    /**
     * THE FIXTURE MEASURES WHAT IT CLAIMS: it compiles clean, so no assertion below is
     * about a program the checker already rejects.
     */
    @Test
    fun `the fixture compiles with no diagnostics`() {
        assert(projectWith().diagnostics().isEmpty())
    }

    // --- the contextual positions -------------------------------------------------

    /**
     * EVERY position that supplies a contextual type, as ONE exact set. An exact set
     * rather than a size, so a position that silently stops resolving is named by the
     * diagram; and the three keys that must NOT be here are asserted separately below.
     */
    @Test
    fun `a key is an occurrence of the member its CONTEXTUAL type supplies`() {
        val project = projectWith()
        val group = places(shapeGroup(project))
        val expected = listOf(
            "b.ts@${offsetOf("p: string", 0, other)}",
            "a.ts@${keyOf("in-arg")}",
            "a.ts@${keyOf("in-annot")}",
            "a.ts@${keyOf("in-return")}",
            "a.ts@${keyOf("in-nested")}",
            "a.ts@${keyOf("in-array")}",
            "a.ts@${keyOf("in-satisfies")}",
            "a.ts@${keyOf("in-as")}",
            "a.ts@${keyOf("in-ternary")}",
            "a.ts@${keyOf("in-default")}",
            "a.ts@${keyOf("in-classprop")}",
            "a.ts@${keyOf("in-generic")}",
            "a.ts@${keyOf("in-union")}",
            // (API.17) …and the COMPUTED key, which round 927 stated as a refusal and
            // round 932 closed — one span, in the same group, reached by the same leg.
            "a.ts@${offsetOf("""["p"]""") + 2}",
            "a.ts@${offsetOf("{ p, q }") + 2}",
            "a.ts@${offsetOf("const { p } = annotated") + 8}",
            "a.ts@${offsetOf("annotated.p") + 10}",
            "a.ts@${offsetOf("ternary.p") + 8}",
        )
        assert(group.sorted() == expected.sorted())
    }

    /**
     * THE SCOPE-CHAIN DISCRIMINATOR. `export const p` is spelled exactly like every key
     * above and is a different thing entirely; a resolution that reached the key through
     * the lexical chain — the failure mode `Project.definitionsAt` has warned about since
     * (API.3d) — would put every key in this binding's group.
     */
    @Test
    fun `a key spelled like an unrelated top-level binding is not that binding`() {
        val project = projectWith()
        val binding = places(project.referencesAt(mainFile, offsetOf("export const p =") + 13))
        // The binding's own group is itself and the SHORTHAND that reads it — nothing else.
        assert(
            binding.sorted() == listOf(
                "a.ts@${offsetOf("export const p =") + 13}",
                "a.ts@${offsetOf("{ p, q }") + 2}",
            ).sorted(),
        )
        assert("a.ts@${keyOf("in-arg")}" !in binding)
    }

    /**
     * A literal NOTHING contextually types declares its own property, so its key is the
     * declaration and its group is that literal's own reads. It must not leak into the
     * member's group — measured on tsc 7.0.2, which answers two spans here.
     */
    @Test
    fun `a key with no contextual type is its own declaration`() {
        val project = projectWith()
        assert("a.ts@${keyOf("in-free")}" !in places(shapeGroup(project)))
        val own = places(project.referencesAt(mainFile, keyOf("in-free")))
        assert(
            own.sorted() == listOf(
                "a.ts@${keyOf("in-free")}",
                "a.ts@${offsetOf("free.p") + 5}",
            ).sorted(),
        )
    }

    /**
     * A CONTEXTUAL key is TWO symbols in the SYMMETRIC direction — it DECLARES the
     * literal's own property and REFERS to the contextual member — and tsc 7.0.2 answers
     * a caret there with the UNION of both groups (twenty-one spans on a fixture whose
     * contextual group is twenty). The literal's own read is the discriminator: it is in
     * the key's answer and NOT in the member's, because membership meets set by set and
     * never closes transitively.
     */
    @Test
    fun `a contextual key also declares the literal's OWN property`() {
        val project = projectWith()
        val satKey = keyOf("in-satisfies")
        val satRead = offsetOf("sat.p") + 4
        assert(
            places(project.referencesAt(mainFile, satRead)).sorted() ==
                listOf("a.ts@$satKey", "a.ts@$satRead").sorted(),
        )
        val fromKey = places(project.referencesAt(mainFile, satKey))
        assert("a.ts@$satRead" in fromKey)
        assert("a.ts@${keyOf("in-arg")}" in fromKey)
        assert("a.ts@$satRead" !in places(shapeGroup(project)))
    }
    /**
     * A GENERIC call whose type argument is INFERRED supplies a naked type parameter,
     * which names no member — so the key is the literal's own and NOT the annotation's,
     * even though the call's result is assigned to `Shape`. tsc 7.0.2 answers the same
     * and for the same reason; the EXPLICIT instantiation beside it is the control that
     * says the call leg works at all.
     */
    @Test
    fun `an inferred generic argument supplies no member and an explicit one does`() {
        val project = projectWith()
        val group = places(shapeGroup(project))
        assert("a.ts@${keyOf("in-generic")}" in group)
        assert("a.ts@${keyOf("in-inferred")}" !in group)
    }

    /**
     * (API.17), round 932 — WAS a pin on a REFUSAL. Round 927 left a COMPUTED key out of
     * the swept population, on the ground that admitting it without resolving it would
     * turn every such key into a rename obstacle; the resolution is the same contextual
     * leg one node deeper, so the key is an ordinary occurrence now. Both directions,
     * because the caret and the sweep are two different questions: the member's group
     * CONTAINS the key, and a caret IN the key answers the member's whole group.
     */
    @Test
    fun `a computed key is an occurrence and answers the group from inside it`() {
        val project = projectWith()
        val inComputed = offsetOf("""["p"]""") + 2
        assert("a.ts@$inComputed" in places(shapeGroup(project)))
        assert(places(project.referencesAt(mainFile, inComputed)) == places(shapeGroup(project)))
    }

    /**
     * The SPAN excludes the quotes, which is what a rename writes into and what tsc
     * edits (measured: `[232,233)` for a literal occupying `[231,234)`). Asserted as the
     * TEXT rather than as offsets, because the failure that matters is `{ [newName]: v }`
     * — which compiles and means something else.
     */
    @Test
    fun `a computed key occurrence covers the text between the quotes`() {
        val project = projectWith()
        val inComputed = offsetOf("""["p"]""") + 2
        val key = shapeGroup(project).single { it.fileName == mainFile && it.start == inComputed }
        assert(main.substring(key.start, key.end) == "p")
    }

    /**
     * A UNION contextual type supplies the member from EVERY constituent that declares
     * it, so the one key belongs to both groups — and neither group swallows the other,
     * which is the same non-transitivity (API.9) measured for the heritage edge.
     */
    @Test
    fun `a key under a UNION contextual type is in both members' groups`() {
        val project = projectWith()
        val inUnion = "a.ts@${keyOf("in-union")}"
        assert(inUnion in places(shapeGroup(project)))
        val others = places(project.referencesAt(otherFile, offsetOf("p: number", 0, other)))
        assert(others.sorted() == listOf("b.ts@${offsetOf("p: number", 0, other)}", inUnion).sorted())
        // …and `Other`'s group did NOT acquire `Shape`'s keys through the shared span.
        assert("a.ts@${keyOf("in-arg")}" !in others)
    }

    /**
     * GO-TO-DEFINITION on a key answers the CONTEXTUAL member where there is one and the
     * key itself where there is not — both read out of tsc 7.0.2.
     */
    @Test
    fun `definition on a key answers the contextual member or the key itself`() {
        val project = projectWith()
        val contextual = project.definitionsAt(mainFile, keyOf("in-arg"))
        assert(
            contextual.map { it.fileName to it.start } ==
                listOf(otherFile to offsetOf("p: string", 0, other)),
        )
        val ownKey = project.definitionsAt(mainFile, keyOf("in-free"))
        assert(ownKey.map { it.fileName to it.start } == listOf(mainFile to keyOf("in-free")))
    }

    // --- one span, two symbols: the SHORTHANDS ------------------------------------

    /**
     * THE ASYMMETRY, on an object literal's `{ p }`. The MEMBER's group contains the
     * token — asserted in the exact set above — while a caret ON the token answers the
     * LOCAL's group and nothing else. A capture that filed the member as an ordinary
     * `locations` entry would answer the member's whole group here; one that filed it as
     * a `related` entry would answer BOTH groups merged.
     */
    @Test
    fun `a caret on an object-literal shorthand answers the LOCAL and not the member`() {
        val project = projectWith()
        val token = offsetOf("{ p, q }") + 2
        assert("a.ts@$token" in places(shapeGroup(project)))
        assert(
            places(project.referencesAt(mainFile, token)).sorted() == listOf(
                "a.ts@${offsetOf("export const p =") + 13}",
                "a.ts@$token",
            ).sorted(),
        )
    }

    /** …and the same, mirrored, on a binding pattern's `const { p } = o`. */
    @Test
    fun `a caret on a binding shorthand answers the LOCAL and not the member`() {
        val project = projectWith()
        val token = offsetOf("const { p } = annotated") + 8
        assert("a.ts@$token" in places(shapeGroup(project)))
        assert(
            places(project.referencesAt(mainFile, token)).sorted() == listOf(
                "a.ts@$token",
                "a.ts@${offsetOf("return p;") + 7}",
            ).sorted(),
        )
    }

    /**
     * GO-TO-DEFINITION on a shorthand answers BOTH — the local and the member — which is
     * what tsc 7.0.2 answers for the object literal's form, and is the one place the two
     * meanings are handed to a host side by side rather than one being chosen for it.
     */
    @Test
    fun `definition on an object-literal shorthand answers the local AND the member`() {
        val project = projectWith()
        val definitions = project.definitionsAt(mainFile, offsetOf("{ p, q }") + 2)
        assert(
            definitions.map { it.fileName to it.start } == listOf(
                mainFile to offsetOf("export const p =") + 13,
                otherFile to offsetOf("p: string", 0, other),
            ),
        )
    }

    /**
     * DOCUMENT HIGHLIGHTS share the occurrence set, so the same span is highlighted from
     * the member's side too — the pin that says the set is wired once rather than per
     * query.
     */
    @Test
    fun `document highlights of a member reach the keys and the shorthands`() {
        val project = projectWith()
        val highlights = places(project.documentHighlightsAt(mainFile, keyOf("in-annot")))
        assert("a.ts@${keyOf("in-arg")}" in highlights)
        assert("a.ts@${offsetOf("{ p, q }") + 2}" in highlights)
        assert("a.ts@${offsetOf("const { p } = annotated") + 8}" in highlights)
        // A highlight request never crosses a file, so the declaration in `b.ts` is out.
        assert(highlights.none { it.startsWith("b.ts") })
    }

    // --- THE RENAME DISCRIMINATOR -------------------------------------------------

    /**
     * THE DISCRIMINATOR, and the reason this round needed a mechanism rather than a
     * wider population: renaming the MEMBER writes `renamed: p` into both shorthands and
     * renaming the LOCAL writes `p: renamed`. Both compile. Both are one edit at one
     * span. Only the TEXT tells them apart, which is why every assertion here is text.
     */
    @Test
    fun `renaming the member expands both shorthands the member's way`() {
        val project = renameProject()
        val plan = project.renameAt(otherFile, offsetOf("p: string", 0, renameOther), "renamed")
        assert(plan.refusal == null)
        val text = appliedTo(plan, mainFile, renameMain)
        assert("const shorthandLit: Shape = { renamed: p, q };" in text)
        assert("const { renamed: p } = annotated;" in text)
        // …the value each shorthand still reads keeps its own name…
        assert("""export const p = "an unrelated top-level binding";""" in text)
        assert("    return p;" in text)
        // …the contextual key is a plain replacement, and the FREE one does not move.
        assert("""takes({ renamed: "in-arg", q: 1 });""" in text)
        assert("""const free = { p: "in-free", q: 3 };""" in text)
    }

    /** …and the object-literal shorthand reached from the LOCAL expands the other way. */
    @Test
    fun `renaming the local expands the object-literal shorthand the local's way`() {
        val project = renameProject()
        val at = offsetOf("export const p =", 0, renameMain) + 13
        val plan = project.renameAt(mainFile, at, "renamed")
        assert(plan.refusal == null)
        val text = appliedTo(plan, mainFile, renameMain)
        assert("const shorthandLit: Shape = { p: renamed, q };" in text)
        assert("""export const renamed = "an unrelated top-level binding";""" in text)
        // The MEMBER did not move.
        assert("""takes({ p: "in-arg", q: 1 });""" in text)
    }

    /** …and so does the binding shorthand reached from the local it binds. */
    @Test
    fun `renaming the binding local expands the binding shorthand the local's way`() {
        val project = renameProject()
        val at = offsetOf("const { p } = annotated", 0, renameMain) + 8
        val plan = project.renameAt(mainFile, at, "renamed")
        assert(plan.refusal == null)
        val text = appliedTo(plan, mainFile, renameMain)
        assert("const { p: renamed } = annotated;" in text)
        assert("    return renamed;" in text)
        assert("""takes({ p: "in-arg", q: 1 });""" in text)
    }

    /**
     * APPLY AND RE-CHECK, for each of the three renames above: the plan goes through
     * `updateFile` and the program is compiled again, and its diagnostics must be what
     * they were. `renameAt` verifies internally on a scratch overlay; this is the
     * INDEPENDENT oracle, because it runs through the public surface a host uses.
     */
    @Test
    fun `every planned rename leaves the program compiling exactly as before`() {
        val before = renameProject().diagnostics().map { it.code }.sorted()
        assert(before.isEmpty())
        for (caret in listOf(0, 1, 2)) {
            val project = renameProject()
            val plan = when (caret) {
                0 -> project.renameAt(otherFile, offsetOf("p: string", 0, renameOther), "renamed")
                1 -> project.renameAt(mainFile, offsetOf("export const p =", 0, renameMain) + 13, "renamed")
                else -> project.renameAt(
                    mainFile, offsetOf("const { p } = annotated", 0, renameMain) + 8, "renamed",
                )
            }
            assert(plan.refusal == null)
            for (file in plan.files) {
                val source = if (file.fileName == otherFile) renameOther else renameMain
                var text = source
                for (edit in file.edits.asReversed()) {
                    text = text.substring(0, edit.start) + edit.newText + text.substring(edit.end)
                }
                project.updateFile(file.fileName, text)
            }
            assert(project.diagnostics().map { it.code }.sorted() == before)
        }
    }
}
