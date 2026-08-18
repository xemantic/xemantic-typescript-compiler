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
 * (API.9) THE MEMBER OCCURRENCE SET — the three kinds round 925 measured this API to
 * be short by, and the neighbours each of them must NOT swallow.
 *
 * ## What this is pinned against, and where the expectations come from
 *
 * Every positive expectation here was READ OUT of tsc 7.0.2's own language server
 * (`tools/tsgo-7.0.2/lib/tsc --lsp -stdio`, `scripts/lsp_member_refs.py`) over a
 * fixture of the same shape, not reasoned about. Two of the answers were surprises
 * worth naming, and both are pinned below:
 *
 * - a class whose members merely MATCH an interface's, with no `implements`, is a
 *   DIFFERENT symbol — structural compatibility does not relate, only a declared
 *   heritage edge does;
 * - go-to-definition on an implementor's own member answers THAT MEMBER while
 *   find-references on it answers the interface's whole group, which is why the
 *   relation is a separate field on the capture and not more `locations`.
 *
 * ## The discriminator per kind — a spelling scan must fail each one
 *
 * `o["p"]`: the fixture carries the string `"p"` twice more, in positions that name
 * no member. `const { p: local }`: `local` is spelled like an unrelated file-level
 * binding. The implementor: `Structural` carries an unrelated member of the same
 * spelling, and it is the class the relation must leave alone.
 *
 * Offsets come from `indexOf` on the fixture text; a hardcoded one would pin this
 * test's own arithmetic.
 */
class ProjectMemberOccurrenceTest {

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
        export interface Other { p: boolean; }
    """.trimIndent() + "\n"

    /**
     * `local` and `"p"`-the-plain-string are the colliders: a spelling scan for the
     * member `p` finds the two unrelated string literals, and a scan for the local
     * `local` finds the file-level one.
     */
    private val main = """
        import { Shape, Other } from "./b";
        declare const o: Shape;
        export const direct = o.p;
        export const viaElement = o["p"];
        export const unrelatedLiteral = "p";
        export const alsoUnrelated: string = "p";
        const { p: local } = o;
        export const useLocal = local;
        const { p: taken, ...rest } = o;
        export const useTaken = taken;
        declare const nested: { inner: Shape };
        const { inner: { p: deep } } = nested;
        export const useDeep = deep;
        export function takes({ p: fromParam }: Shape): string { return fromParam; }
        export class Impl implements Shape {
            p = "a";
            q = 1;
            read(): string { return this.p; }
        }
        export class Sub extends Impl {
            other(): string { return this.p; }
        }
        export class Structural {
            p = "b";
            q = 2;
            read(): string { return this.p; }
        }
        declare const u: Shape | Other;
        export const readUnion = u.p;
        declare const onlyOther: Other;
        export const readOther = onlyOther.p;
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

    /** [references] as `file@start` strings, so a failure diagram names the places. */
    private fun places(references: List<ReferenceLocation>): List<String> =
        references.map { "${it.fileName.substringAfterLast('/')}@${it.start}" }

    /** The text each reference covers — what an editor would highlight. */
    private fun texts(references: List<ReferenceLocation>): List<String> =
        references.map {
            val source = if (it.fileName == otherFile) other else main
            source.substring(it.start, it.end)
        }

    /** The whole occurrence set of `Shape.p`, taken from the interface's declaration. */
    private fun shapeGroup(project: Project): List<ReferenceLocation> =
        project.referencesAt(otherFile, offsetOf("p: string", 0, other))

    // --- KIND 2: an element access named by a string literal ----------------------

    /**
     * THE DISCRIMINATOR for `o["p"]`. The fixture spells `"p"` three times and exactly
     * ONE of them names a member; a spelling scan finds all three and a correct answer
     * finds one, so this fails for any implementation that reached the population by
     * text.
     */
    @Test
    fun `an element access is an occurrence and an unrelated string of the same text is not`() {
        val project = projectWith()
        val group = shapeGroup(project)
        val inAccess = offsetOf("""o["p"]""") + 3
        assert("a.ts@$inAccess" in places(group))
        assert("a.ts@${offsetOf("""unrelatedLiteral = "p"""") + 19}" !in places(group))
        assert("a.ts@${offsetOf("""alsoUnrelated: string = "p"""") + 25}" !in places(group))
    }

    /**
     * The SPAN is the literal's text and not its quotes. A rename writes into exactly
     * this span, so an off-by-one here produces `o[newName]` or `o[""newName""]` — which
     * is why the assertion is on the TEXT rather than on the offsets.
     */
    @Test
    fun `an element access occurrence covers the text between the quotes`() {
        val project = projectWith()
        val group = shapeGroup(project)
        assert(texts(group).all { it == "p" })
    }

    /** The caret may be ON the literal, which is the one non-identifier this resolves. */
    @Test
    fun `a caret inside the string literal answers the member's whole group`() {
        val project = projectWith()
        val fromLiteral = project.referencesAt(mainFile, offsetOf("""o["p"]""") + 3)
        assert(places(fromLiteral) == places(shapeGroup(project)))
    }

    // --- KIND 1: a binding element's property name -------------------------------

    /**
     * THE DISCRIMINATOR for `const { p: local } = o`: the `p` is the MEMBER and the
     * `local` is a binding of its own, spelled like the unrelated file-level `local`
     * this fixture also declares. So the member's group must contain the `p` and NOT
     * the `local`, and the local's group must be the mirror.
     */
    @Test
    fun `a binding element's property name is the member and its binding is not`() {
        val project = projectWith()
        val group = places(shapeGroup(project))
        val property = offsetOf("{ p: local }") + 2
        val binding = offsetOf("{ p: local }") + 5
        assert("a.ts@$property" in group)
        assert("a.ts@$binding" !in group)
        val localGroup = places(project.referencesAt(mainFile, binding))
        assert("a.ts@$property" !in localGroup)
        assert("a.ts@${offsetOf("useLocal = local") + 11}" in localGroup)
    }

    /**
     * The three neighbours that must behave the same way, because each takes a
     * different route to the destructured type: a REST pattern (the source is still the
     * declaration's initializer), a NESTED one (the source is the level above's own
     * member) and a PARAMETER (the source is its annotation, and there is no
     * initializer to fall back on).
     */
    @Test
    fun `a rest a nested and a parameter binding element all name the member`() {
        val project = projectWith()
        val group = places(shapeGroup(project))
        assert("a.ts@${offsetOf("{ p: taken, ...rest }") + 2}" in group)
        assert("a.ts@${offsetOf("{ inner: { p: deep } }") + 11}" in group)
        assert("a.ts@${offsetOf("{ p: fromParam }") + 2}" in group)
    }

    // --- KIND 3: an implementor ---------------------------------------------------

    /**
     * THE DISCRIMINATOR for the implementor: `Structural` has the same members and no
     * `implements`, so it is a DIFFERENT symbol — measured, tsc answers two references
     * for its `p` (its own declaration and its own `this.p`) and thirteen for the
     * interface's. A relation built on shape rather than on heritage merges them.
     */
    @Test
    fun `an implementor's member joins the group and a structural match does not`() {
        val project = projectWith()
        val group = places(shapeGroup(project))
        assert("a.ts@${offsetOf("""p = "a"""")}" in group)
        assert("a.ts@${offsetOf("""p = "b"""")}" !in group)
        assert("a.ts@${offsetOf("return this.p", 2) + 12}" !in group)
    }

    /**
     * The edge is TRANSITIVE, because tsc's is: `Sub extends Impl implements Shape`
     * reaches the interface two edges away, so a `this.p` inside `Sub` is in the group.
     * A one-level relation passes every other pin in this class and fails this one.
     */
    @Test
    fun `a member reached two heritage edges away is in the group`() {
        val project = projectWith()
        val group = places(shapeGroup(project))
        assert("a.ts@${offsetOf("return this.p", 1) + 12}" in group)
    }

    /**
     * The caret may sit on the IMPLEMENTOR, and it answers the whole group rather than
     * only the classes below it — which is what seeding with the implementor's own
     * declaration alone would do.
     */
    @Test
    fun `a caret on the implementor's own member answers the interface's group`() {
        val project = projectWith()
        val fromImpl = project.referencesAt(mainFile, offsetOf("""p = "a""""))
        assert(places(fromImpl) == places(shapeGroup(project)))
    }

    /**
     * Go-to-definition is deliberately NOT widened by any of this. Measured against
     * tsc 7.0.2: `textDocument/definition` on an implementor's own member answers that
     * member, where `textDocument/references` on it answers the base's whole group.
     * That disagreement is the whole reason the relation is a separate field.
     */
    @Test
    fun `go to definition on an implementor's member still answers that member`() {
        val project = projectWith()
        val at = offsetOf("""p = "a"""")
        val definitions = project.definitionsAt(mainFile, at)
        assert(definitions.map { "${it.fileName.substringAfterLast('/')}@${it.start}" } ==
            listOf("a.ts@$at"))
    }

    /**
     * …and neither does a member USE inside an implementor. `this.p` in `Impl` carries
     * the heritage edge — that is what puts it in the interface's group — and its
     * DEFINITION is still `Impl`'s own member alone. The two pins are separate because
     * the edge is attached at two different places: a declaration name gets it from
     * its own class, a use gets it from the symbol it resolved to.
     */
    @Test
    fun `go to definition on a use inside an implementor answers that class's member`() {
        val project = projectWith()
        val definitions = project.definitionsAt(mainFile, offsetOf("return this.p", 0) + 12)
        assert(
            definitions.map { "${it.fileName.substringAfterLast('/')}@${it.start}" } ==
                listOf("a.ts@${offsetOf("""p = "a"""")}"),
        )
    }

    /**
     * …while an element access and a binding element's property name DO gain one, and
     * it is the interface's declaration — which is also what tsc answers for both.
     */
    @Test
    fun `go to definition answers the declaration for an element access and a binding property`() {
        val project = projectWith()
        val declaration = offsetOf("p: string", 0, other)
        val fromLiteral = project.definitionsAt(mainFile, offsetOf("""o["p"]""") + 3)
        assert(fromLiteral.map { it.fileName to it.start } == listOf(otherFile to declaration))
        val fromBinding = project.definitionsAt(mainFile, offsetOf("{ p: local }") + 2)
        assert(fromBinding.map { it.fileName to it.start } == listOf(otherFile to declaration))
    }

    // --- the whole set, and the neighbours it must not swallow --------------------

    /**
     * The tsc-parity assertion: every span of the member's group at once, as an exact
     * set. Written as a set rather than as a size because an answer of the right size
     * can still be the wrong one — which is exactly what the collider fixture makes
     * possible.
     */
    @Test
    fun `the whole member group is every position tsc names and nothing else`() {
        val project = projectWith()
        assert(
            places(shapeGroup(project)) == listOf(
                "a.ts@${offsetOf("o.p") + 2}",
                "a.ts@${offsetOf("""o["p"]""") + 3}",
                "a.ts@${offsetOf("{ p: local }") + 2}",
                "a.ts@${offsetOf("{ p: taken, ...rest }") + 2}",
                "a.ts@${offsetOf("{ inner: { p: deep } }") + 11}",
                "a.ts@${offsetOf("{ p: fromParam }") + 2}",
                "a.ts@${offsetOf("""p = "a"""")}",
                "a.ts@${offsetOf("return this.p", 0) + 12}",
                "a.ts@${offsetOf("return this.p", 1) + 12}",
                "a.ts@${offsetOf("u.p") + 2}",
                "b.ts@${offsetOf("p: string", 0, other)}",
            ),
        )
    }

    /**
     * The UNRELATED interface stays unrelated. `Other.p` is a different member with the
     * same spelling in the same program, and its group is its own — the neighbour a
     * relation that reached too far would swallow first.
     */
    @Test
    fun `an unrelated interface's member of the same spelling is a different group`() {
        val project = projectWith()
        val group = places(project.referencesAt(otherFile, offsetOf("p: boolean", 0, other)))
        assert("a.ts@${offsetOf("onlyOther.p") + 10}" in group)
        assert("a.ts@${offsetOf("o.p") + 2}" !in group)
        assert("b.ts@${offsetOf("p: string", 0, other)}" !in group)
    }

    /**
     * A caret on a UNION receiver's member names one declaration per constituent, so it
     * legitimately answers the union of both groups — the intersection rule § 10b
     * documents, unchanged by this round and asserted because the new grouping term
     * (`related`) runs through the same predicate.
     */
    @Test
    fun `a union receiver's member still answers both constituents' groups`() {
        val project = projectWith()
        val group = places(project.referencesAt(mainFile, offsetOf("u.p") + 2))
        assert("b.ts@${offsetOf("p: string", 0, other)}" in group)
        assert("b.ts@${offsetOf("p: boolean", 0, other)}" in group)
        assert("a.ts@${offsetOf("onlyOther.p") + 10}" in group)
    }

    /**
     * THE PIN THE ONE-LEVEL EDGE FAILS. A subclass that OVERRIDES the member declares
     * its own, so the base it names is the IMPLEMENTOR's member and not the interface's
     * — the interface is one edge further, behind a declaration that hides it. tsc puts
     * the override in the interface's group (measured), so the walk has to follow the
     * base's own bases. Every other heritage pin in this class is satisfied by a
     * single-level edge, which is why this one has its own fixture.
     */
    @Test
    fun `an overriding member two heritage edges from the interface is in the group`() {
        val text = """
            interface Contract { p: string; }
            class Middle implements Contract { p = "m"; }
            class Bottom extends Middle { override p = "b"; }
            declare const c: Contract;
            export const readC = c.p;
            export const keep = [new Middle(), new Bottom()];
        """.trimIndent() + "\n"
        val project = projectWith(mainText = text)
        val group = places(
            project.referencesAt(mainFile, text.indexOf("interface Contract { p") + 21),
        )
        assert("a.ts@${text.indexOf("""p = "m"""")}" in group)
        assert("a.ts@${text.indexOf("""p = "b"""")}" in group)
        assert("a.ts@${text.indexOf("c.p") + 2}" in group)
    }

    /**
     * THE NEIGHBOUR A TRANSITIVE CLOSURE GETS WRONG, and it is why this relation is
     * per-occurrence rather than a fixpoint over the group. Measured against tsc 7.0.2:
     * with `interface A { p }`, `interface B { p }` and `class C implements A, B { p }`,
     * a caret on `A`'s `p` answers SEVEN references — including `C`'s `p` and every use
     * of `C` — and does NOT include `b.p` or `B`'s own `p`. A closure that added `C`'s
     * whole edge set back into the search would merge the two interfaces; matching each
     * occurrence's own (symbol + roots) against the caret's does not.
     *
     * The mirror is asserted in the same run: a caret on `C`'s `p` legitimately answers
     * both groups, because that member really is both.
     */
    @Test
    fun `two interfaces sharing an implementor do not merge into one group`() {
        val text = """
            interface A { p: string; }
            interface B { p: string; }
            class C implements A, B { p = "x"; }
            declare const av: A;
            declare const bv: B;
            declare const cv: C;
            export const readA = av.p;
            export const readB = bv.p;
            export const readC = cv.p;
            class D implements A { p = "y"; own(): string { return this.p; } }
            class E extends D { deeper(): string { return this.p; } }
        """.trimIndent() + "\n"
        val project = projectWith(mainText = text)
        fun at(needle: String, occurrence: Int = 0, plus: Int = 0) =
            offsetOf(needle, occurrence, text) + plus
        val fromA = places(project.referencesAt(mainFile, at("interface A { p", plus = 14)))
        // `C`'s own member, and every use of `C`, are A's — but B's are not.
        assert("a.ts@${at("""C implements A, B { p""", plus = 20)}" in fromA)
        assert("a.ts@${at("cv.p", plus = 3)}" in fromA)
        assert("a.ts@${at("interface B { p", plus = 14)}" !in fromA)
        assert("a.ts@${at("bv.p", plus = 3)}" !in fromA)
        // …and a `this.p` inside an implementor, and one an `extends` further down,
        // reach the interface's group, which is what makes the edge per-occurrence.
        assert("a.ts@${at("return this.p", 0, 12)}" in fromA)
        assert("a.ts@${at("return this.p", 1, 12)}" in fromA)
        // The mirror: the shared implementor's own member IS both.
        val fromC = places(project.referencesAt(mainFile, at("""C implements A, B { p""", plus = 20)))
        assert("a.ts@${at("interface A { p", plus = 14)}" in fromC)
        assert("a.ts@${at("interface B { p", plus = 14)}" in fromC)
        assert("a.ts@${at("bv.p", plus = 3)}" in fromC)
    }

    /**
     * Document highlights ride the same occurrence set, restricted to one file. Pinned
     * because the three kinds are wired ONCE and a change that reached only
     * `referencesAt` would leave this query short — the state round 925 measured.
     */
    @Test
    fun `document highlights see the same new occurrence kinds in this file`() {
        val project = projectWith()
        val here = places(project.documentHighlightsAt(mainFile, offsetOf("o.p") + 2))
        assert("a.ts@${offsetOf("""o["p"]""") + 3}" in here)
        assert("a.ts@${offsetOf("{ p: local }") + 2}" in here)
        assert("a.ts@${offsetOf("""p = "a"""")}" in here)
        assert(here.none { it.startsWith("b.ts@") })
    }

    /**
     * The read/write classifier follows the member through the bracket exactly as it
     * follows it through a dot: `o["p"] = 1` writes `p`. Its own pin, because the
     * literal reaches `ascendUse` by an edge no identifier ever takes.
     */
    @Test
    fun `an element access on the left of an assignment is a write`() {
        val text = main + """
            declare const w: Shape;
            export function put(): void { w["p"] = "x"; }
        """.trimIndent() + "\n"
        val project = projectWith(mainText = text)
        val group = project.referencesAt(mainFile, offsetOf("o.p", 0, text) + 2)
        val write = group.single { it.start == text.indexOf("""w["p"]""") + 3 }
        assert(write.use == ReferenceUse.WRITE)
    }
}
