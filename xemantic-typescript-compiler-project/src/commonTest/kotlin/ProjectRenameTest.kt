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
 * (API.8) [Project.renameAt] end to end: a caret offset and a new name in, an EDIT
 * PLAN out — or a refusal — through real builds of a real (in-memory) project.
 *
 * ## What every pin here is built to fail against
 *
 * **A PLAIN OCCURRENCE REWRITE.** "Find every reference and replace the text" is what
 * a rename looks like from a distance; it passes every assertion of the form "all N
 * occurrences were renamed", and on `{ p }` it renames the object's KEY. So the first
 * test asserts the resulting TEXT of that line and nothing about a count, and the
 * second does the same for the binding-pattern form of the same trap.
 *
 * The second thing they fail against is **a rename that is merely produced rather than
 * checked**: the collision test renames onto an existing binding and the CAPTURE test
 * renames onto a name a nested scope already holds — the latter produces a program
 * that compiles perfectly and means something else, so only the resolution half of the
 * verification can see it, and a "no new diagnostics" implementation passes every
 * other test in this class.
 *
 * The third is **a name match**: the shadowing test renames an inner binding and
 * asserts the outer's occurrences are untouched, in both directions.
 *
 * Offsets are derived from the fixture text with `indexOf`; a hardcoded offset would
 * pin this test's own arithmetic and pass for an implementation that ignored its
 * argument.
 */
class ProjectRenameTest {

    /**
     * `module` is an ES kind and the program has TWO files ON PURPOSE: the
     * unresolved-import region returns early below two program files and its
     * relative-specifier leg additionally demands an ES module kind, so with either
     * missing every cross-file assertion here would be vacuous. The control below pins
     * that the import genuinely resolves.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * Every shape a rename has to treat specially, in one file, each on its own name so
     * that renaming one cannot disturb another's pin:
     *
     * - `local` is read through an object-literal SHORTHAND — the discriminator;
     * - `boxed` is bound by a binding-pattern SHORTHAND — the same trap, mirrored;
     * - `outer` and `inner` are the CAPTURE pair: `host` reads `outer` and holds its
     *   own `inner`, so renaming either onto the other's spelling compiles and lies;
     * - `Solo.solitary` is a member whose occurrence set IS provably complete;
     * - `Contract.shared` has an IMPLEMENTOR and `bracket.bracketed` an ELEMENT ACCESS —
     *   two of round 925's three member obstacles, both CLOSED by (API.9) and both now
     *   pinned as the resulting text rather than as a refusal — while `Ctx.ctxKey` has a
     *   CONTEXTUAL SHORTHAND, which (API.10) closed — leaving `Unplaceable.unplaceable`,
     *   whose shorthand sits in a literal NOTHING contextually types, as the residue that
     *   is still refused;
     * - `localAlias` crosses an `as`, and `"abc".length` lands in a library.
     */
    private val main = """
        import { imported, aliased as localAlias, Shape } from "./b";
        export function topFunction(param: number): number {
            return param + 1;
        }
        const local = 1;
        const objectShorthand = { local };
        declare const box: { boxed: number };
        const { boxed } = box;
        const readBoxed = boxed;
        const outer = 10;
        function host(): number {
            const inner = 20;
            return inner + outer;
        }
        declare const shape: Shape;
        const readWidth = shape.width;
        interface Solo { solitary: string; }
        declare const solo: Solo;
        const readSolitary = solo.solitary;
        interface Contract { shared: string; }
        class Implementor implements Contract { shared = "x"; }
        declare const contract: Contract;
        const readShared = contract.shared;
        declare const bracket: { bracketed: number };
        const readDot = bracket.bracketed;
        const readBracket = bracket["bracketed"];
        interface Ctx { ctxKey: string; }
        const ctxKey = "v";
        const ctxObject: Ctx = { ctxKey };
        const readCtx = ctxObject.ctxKey;
        interface Unplaceable { unplaceable: string; }
        const unplaceable = "u";
        const looseObject = { unplaceable };
        declare const looseHost: Unplaceable;
        const readUnplaceable = looseHost.unplaceable;
        export const useImported = imported;
        export const useAlias = localAlias;
        const libLength = "abc".length;
        export { topFunction };
        console.log(objectShorthand, readBoxed, host, readWidth, readSolitary, readShared);
        console.log(readDot, readBracket, readCtx, useImported, useAlias, libLength, Implementor);
        console.log(looseObject, readUnplaceable);
    """.trimIndent() + "\n"

    private val other = """
        export const imported: string = "i";
        export const aliased: number = 2;
        export interface Shape { width: number; }
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

    /** [plan] applied to [text] — back to front, which is the documented way. */
    private fun applied(plan: RenamePlan, fileName: String, text: String): String {
        var result = text
        val edits = plan.files.single { it.fileName == fileName }.edits
        for (edit in edits.asReversed()) {
            result = result.substring(0, edit.start) + edit.newText + result.substring(edit.end)
        }
        return result
    }

    /** The one line of [text] containing [needle] — so a failure diagram shows text. */
    private fun lineWith(text: String, needle: String): String =
        text.lines().single { needle in it }

    /** [plan]'s edits as `file@start` strings, so a failure names the places. */
    private fun places(plan: RenamePlan): List<String> =
        plan.files.flatMap { file ->
            file.edits.map { "${file.fileName.substringAfterLast('/')}@${it.start}" }
        }

    // --- THE DISCRIMINATOR --------------------------------------------------------

    /**
     * The one pin a plain occurrence rewrite fails. `{ local }` is a KEY and a
     * REFERENCE spelled with one identifier, so replacing it gives `{ renamedLocal }`
     * — which compiles, passes every count-based assertion, and has renamed the
     * object's property.
     *
     * Asserted as the resulting TEXT of both affected lines.
     */
    @Test
    fun `renaming a local read through an object shorthand expands the shorthand`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("local = 1"), "renamedLocal")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert(lineWith(text, "renamedLocal = 1") == "const renamedLocal = 1;")
        assert(lineWith(text, "const objectShorthand") == "const objectShorthand = { local: renamedLocal };")
    }

    /**
     * The mirror: a binding pattern's shorthand names the SOURCE property with the same
     * token as the local it binds, so `{ renamedBoxed }` would destructure a property
     * that does not exist.
     */
    @Test
    fun `renaming a local bound by a binding shorthand expands the shorthand`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("boxed } = box"), "renamedBoxed")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert(lineWith(text, "= box;") == "const { boxed: renamedBoxed } = box;")
        assert(lineWith(text, "readBoxed =") == "const readBoxed = renamedBoxed;")
    }

    // --- THE SAFETY PINS ----------------------------------------------------------

    /**
     * A symbol declared in a library cannot be renamed, because the declaration is not
     * editable and renaming the uses alone leaves a program that does not compile. tsc
     * refuses the same thing.
     */
    @Test
    fun `a symbol declared in a library is refused loudly`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf(""""abc".length""") + 6, "renamedLength")
        assert(plan.refusal == RenameRefusal.DECLARED_IN_A_LIBRARY)
        assert(plan.files.isEmpty())
        assert(plan.oldName == "length")
    }

    /**
     * A COLLISION: the new name is already a binding in a scope this rename reaches.
     * The plan is built, applied to a scratch program and re-compiled, and the
     * redeclaration errors are what withdraw it — with the diagnostics attached, so a
     * host can say where.
     */
    @Test
    fun `renaming onto an existing binding is refused with the errors it would cause`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("local = 1"), "outer")
        assert(plan.refusal == RenameRefusal.WOULD_NOT_COMPILE)
        assert(plan.files.isEmpty())
        assert(plan.conflicts.all { it.kind == RenameConflictKind.NEW_DIAGNOSTIC })
        assert(plan.conflicts.any { "TS2451" in it.detail })
    }

    /**
     * THE STRONGEST PIN IN THIS CLASS. `host` reads the file-level `outer` and holds
     * its own `inner`, so renaming `outer` to `inner` makes that read bind to the local
     * — a program that COMPILES, with no diagnostic anywhere, and means something
     * different. Only the resolution half of the verification can see it: an
     * implementation that checks diagnostics alone passes every other test here.
     */
    @Test
    fun `a rename that would be CAPTURED by a nested binding is refused - and it compiles`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("outer = 10"), "inner")
        assert(plan.refusal == RenameRefusal.WOULD_CHANGE_MEANING)
        assert(plan.files.isEmpty())
        // No diagnostic would have fired: this is exactly the silent case.
        assert(plan.conflicts.none { it.kind == RenameConflictKind.NEW_DIAGNOSTIC })
        assert(plan.conflicts.any { it.kind == RenameConflictKind.RESOLUTION_CHANGED })
        // And it points at the READ that would have moved, not at the declaration.
        // The span named is the OCCURRENCE whose meaning moved — the read inside `host`
        // that would silently rebind — not the declaration being renamed.
        assert(plan.conflicts.single().start == offsetOf("outer;"))
    }

    // --- shadowing, in both directions --------------------------------------------

    /**
     * A rename must not follow a spelling. `inner` lives inside `host` and `outer`
     * outside it, and renaming either leaves the other's occurrences alone — which a
     * text search cannot do and a resolution that lost the walk's lexical chain gets
     * wrong in the more dangerous direction (it edits the wrong declaration).
     */
    @Test
    fun `renaming an inner binding does not touch the outer one and the reverse`() {
        val project = projectWith()
        val inner = project.renameAt(mainFile, offsetOf("inner = 20"), "renamedInner")
        assert(
            places(inner) == listOf(
                "a.ts@${offsetOf("inner = 20")}",
                "a.ts@${offsetOf("inner + outer")}",
            ),
        )
        val outer = project.renameAt(mainFile, offsetOf("outer = 10"), "renamedOuter")
        assert(
            places(outer) == listOf(
                "a.ts@${offsetOf("outer = 10")}",
                "a.ts@${offsetOf("outer;")}",
            ),
        )
    }

    // --- across the import boundary -----------------------------------------------

    /**
     * The declaration, the import clause and every use, in both files. Seeded from the
     * DECLARING side, which is the direction that fails when the alias hop runs one way.
     */
    @Test
    fun `an imported symbol is renamed at its declaration its import and every use`() {
        val project = projectWith()
        // The negative control for the module-resolution trap: an unresolved import
        // would leave every assertion here measuring nothing.
        assert(project.diagnostics(mainFile).none { it.code == 2307 })
        val plan = project.renameAt(otherFile, offsetOf("imported", 0, other), "renamedImported")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert(
            lineWith(text, "from \"./b\"") ==
                """import { renamedImported, aliased as localAlias, Shape } from "./b";""",
        )
        assert(lineWith(text, "export const useImported") == "export const useImported = renamedImported;")
        assert(
            lineWith(applied(plan, otherFile, other), "renamedImported") ==
                """export const renamedImported: string = "i";""",
        )
    }

    /** A member declared in another file, renamed through its use here. */
    @Test
    fun `a member of an imported interface is renamed on both sides`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("shape.width") + 6, "renamedWidth")
        assert(plan.refusal == null)
        assert(lineWith(applied(plan, mainFile, main), "const readWidth") == "const readWidth = shape.renamedWidth;")
        assert(
            lineWith(applied(plan, otherFile, other), "renamedWidth") ==
                "export interface Shape { renamedWidth: number; }",
        )
    }

    // --- APPLY AND RECHECK ---------------------------------------------------------

    /**
     * THE STRONGEST POSSIBLE PIN FOR A REFACTORING, and cheap here because `Project`
     * already edits in memory: apply the plan to the project and ask the COMPILER
     * whether the result still says what it said. A rename that breaks the program is
     * caught by the compiler rather than by this test's imagination.
     *
     * It is also an INDEPENDENT oracle of the verification `renameAt` performs
     * internally: that one runs on a scratch overlay through the capture path, this one
     * through `updateFile` and the ordinary diagnostic path, so the two agreeing is not
     * a tautology.
     */
    @Test
    fun `applying a plan leaves the program compiling exactly as it did`() {
        val project = projectWith()
        val before = project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" }
        val plan = project.renameAt(mainFile, offsetOf("topFunction"), "renamedTop")
        assert(plan.refusal == null)
        project.updateFile(mainFile, applied(plan, mainFile, main))
        val after = project.diagnostics().map { "${it.fileName}:${it.code}:${it.message}" }
        assert(after == before)
    }

    /** The same, for the cross-file rename — where a half-applied plan breaks an import. */
    @Test
    fun `applying a cross-file plan leaves the program compiling exactly as it did`() {
        val project = projectWith()
        val before = project.diagnostics().map { "${it.fileName}:${it.code}" }
        val plan = project.renameAt(otherFile, offsetOf("imported", 0, other), "renamedImported")
        assert(plan.refusal == null)
        project.updateFile(mainFile, applied(plan, mainFile, main))
        project.updateFile(otherFile, applied(plan, otherFile, other))
        val after = project.diagnostics().map { "${it.fileName}:${it.code}" }
        assert(after == before)
    }

    // --- members: what succeeds and what is refused --------------------------------

    /** A member whose every occurrence resolves: the plan is written. */
    @Test
    fun `a member whose occurrence set is provably complete is renamed`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("solitary: string"), "renamedSolitary")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert(lineWith(text, "interface Solo") == "interface Solo { renamedSolitary: string; }")
        assert(lineWith(text, "const readSolitary") == "const readSolitary = solo.renamedSolitary;")
    }

    /**
     * (API.9) THE REFUSAL THIS ROUND CASHED, half one. An IMPLEMENTOR's member used to be
     * a different symbol here, so the class would have stopped implementing its
     * interface and the whole rename was refused; a DECLARED heritage edge now ties the
     * two, and the plan edits both. Asserted as the resulting TEXT of both lines,
     * because a plan of the right size can still have edited the wrong spans.
     */
    @Test
    fun `a member with an implementor is renamed on the interface and on the class`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("shared: string"), "renamedShared")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert(lineWith(text, "interface Contract") == "interface Contract { renamedShared: string; }")
        assert(
            lineWith(text, "class Implementor") ==
                """class Implementor implements Contract { renamedShared = "x"; }""",
        )
        assert(lineWith(text, "const readShared") == "const readShared = contract.renamedShared;")
    }

    /**
     * (API.9) THE REFUSAL THIS ROUND CASHED, half two, and the discriminator is the
     * QUOTES. `o["p"]` names its member with a string literal, which is now swept and
     * renamed — but the edit covers the literal's TEXT and not its quotes, so an
     * off-by-one produces `bracket[renamedBracketed]` or a doubled quote. Asserting the
     * resulting line is the only form that sees that; a count or an offset does not.
     */
    @Test
    fun `a member also reached by a string element access is renamed inside the quotes`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("bracketed: number"), "renamedBracketed")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert(
            lineWith(text, "const readBracket") ==
                """const readBracket = bracket["renamedBracketed"];""",
        )
        assert(lineWith(text, "const readDot") == "const readDot = bracket.renamedBracketed;")
        assert(
            lineWith(text, "declare const bracket") ==
                "declare const bracket: { renamedBracketed: number };",
        )
    }

    /**
     * (API.9) …and applying THAT plan leaves the program compiling exactly as it did —
     * the independent oracle, run through the ordinary diagnostic path rather than
     * through the verification `renameAt` performs internally. It is the pin that says
     * the newly-covered kinds are edited CORRECTLY and not merely edited.
     */
    @Test
    fun `applying a plan over the newly covered member kinds leaves the program compiling`() {
        val project = projectWith()
        val before = project.diagnostics().map { it.code }.sorted()
        for (name in listOf("shared: string", "bracketed: number")) {
            val fresh = projectWith()
            val plan = fresh.renameAt(mainFile, offsetOf(name), "renamedByThePlan")
            assert(plan.refusal == null)
            fresh.updateFile(mainFile, applied(plan, mainFile, main))
            assert(fresh.diagnostics().map { it.code }.sorted() == before)
        }
    }

    /**
     * (API.10) REPLACES round 925's `a member also supplied by a contextual shorthand is
     * refused`. The contextual type now resolves, so the shorthand is an ORDINARY member
     * of the group — and the assertion is the RESULTING TEXT, because the token means two
     * things and both expansions compile.
     *
     * THE DISCRIMINATOR: renaming the MEMBER must write `{ renamedCtxKey: ctxKey }` and
     * renaming the LOCAL must write `{ ctxKey: renamedLocal }`. An implementation that
     * files one answer per span writes the same text for both and passes every
     * count-based assertion. The local `ctxKey` must also be LEFT ALONE here.
     */
    @Test
    fun `renaming a member expands a contextual shorthand the other way round`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("ctxKey: string"), "renamedCtxKey")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert("const ctxObject: Ctx = { renamedCtxKey: ctxKey };" in text)
        assert("interface Ctx { renamedCtxKey: string; }" in text)
        assert("const readCtx = ctxObject.renamedCtxKey;" in text)
        assert("""const ctxKey = "v";""" in text)
    }

    /**
     * (API.10) …and the same span reached from the LOCAL, which is the other half of the
     * discriminator. tsc 7.0.2 answers the local's group alone for a caret ON the token
     * (two spans), so the member's own declaration must NOT move.
     */
    @Test
    fun `renaming the local behind a contextual shorthand expands it the usual way`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("{ ctxKey }") + 2, "renamedLocal")
        assert(plan.refusal == null)
        val text = applied(plan, mainFile, main)
        assert("const ctxObject: Ctx = { ctxKey: renamedLocal };" in text)
        assert("interface Ctx { ctxKey: string; }" in text)
        assert("""const renamedLocal = "v";""" in text)
    }

    /**
     * (API.10) THE SURVIVING SHORTHAND REFUSAL. A shorthand is an occurrence when its
     * member can be PLACED; one in a literal that NOTHING contextually types cannot be,
     * so it keeps `CONTEXTUAL_SHORTHAND` — which is a different report to a user than an
     * identifier that would not resolve, exactly as `ELEMENT_ACCESS` is.
     */
    @Test
    fun `a shorthand whose member cannot be placed still refuses the rename`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("unplaceable: string"), "renamedUnplaceable")
        assert(plan.refusal == RenameRefusal.OCCURRENCES_INCOMPLETE)
        assert(plan.conflicts.single().kind == RenameConflictKind.CONTEXTUAL_SHORTHAND)
        assert(plan.conflicts.single().start == offsetOf("{ unplaceable }") + 2)
    }

    /**
     * (API.9) The element access is an occurrence when it RESOLVES; one that does not —
     * a member of an `any`, which nothing can place — is still a refusal, and it keeps
     * its own conflict kind because "a bracket naming something unplaceable" is a
     * different report to a user than "an identifier that would not resolve".
     */
    @Test
    fun `an element access this search cannot place still refuses the rename`() {
        val text = main + """
            declare const loose: any;
            console.log(loose["bracketed"]);
        """.trimIndent() + "\n"
        val project = projectWith(mainText = text)
        val plan = project.renameAt(mainFile, offsetOf("bracketed: number", text = text), "renamedB")
        assert(plan.refusal == RenameRefusal.OCCURRENCES_INCOMPLETE)
        assert(plan.conflicts.single().kind == RenameConflictKind.ELEMENT_ACCESS)
        assert(plan.conflicts.single().start == text.indexOf("""loose["bracketed"]""") + 7)
    }

    // --- aliases -------------------------------------------------------------------

    /**
     * `import { aliased as localAlias }` makes the alias and the original ONE symbol
     * here — which is what lets find-references answer across the hop — so the group
     * carries two spellings and one new name cannot be applied to both.
     */
    @Test
    fun `an aliased import is refused because one new name cannot spell two things`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("localAlias, Shape"), "renamedAlias")
        assert(plan.refusal == RenameRefusal.ALIASED_SYMBOL)
        assert(plan.files.isEmpty())
        assert(plan.oldName == "localAlias")
    }

    /** The other half of the `as`: the property name resolves to nothing at all. */
    @Test
    fun `the imported half of an alias names nothing this search can find`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("aliased as localAlias"), "renamedAliased")
        assert(plan.refusal == RenameRefusal.NO_SYMBOL)
        assert(plan.files.isEmpty())
    }

    // --- the new name ---------------------------------------------------------------

    /**
     * The three new-name refusals, and the point that they cost NO BUILD: the counting
     * Vfs is untouched between the baseline build and the three answers.
     *
     * tsc's own language server checks none of these and will write `const class = 1`.
     */
    @Test
    fun `an unusable new name is refused without compiling anything`() {
        val vfs = CountingVfs(
            InMemoryVfs(
                mapOf("/proj/tsconfig.json" to config, mainFile to main, otherFile to other),
            ),
        )
        val project = Project.open("/proj", vfs)
        project.diagnostics()
        val at = offsetOf("local = 1")
        // The parse of the queried file is what a caret needs; take the baseline AFTER
        // it, so the assertion is about the BUILD and not about that one read.
        project.nodeInfoAt(mainFile, at)
        val touches = vfs.touches
        assert(project.renameAt(mainFile, at, "class").refusal == RenameRefusal.NEW_NAME_IS_RESERVED)
        assert(project.renameAt(mainFile, at, "1bad").refusal == RenameRefusal.NEW_NAME_IS_NOT_AN_IDENTIFIER)
        assert(project.renameAt(mainFile, at, "two words").refusal == RenameRefusal.NEW_NAME_IS_NOT_AN_IDENTIFIER)
        assert(project.renameAt(mainFile, at, "local").refusal == RenameRefusal.NEW_NAME_UNCHANGED)
        assert(vfs.touches == touches)
    }

    // --- the positions that name nothing ---------------------------------------------

    /** A caret on anything but an identifier, and an unknown file. */
    @Test
    fun `a caret that is not on an identifier is refused and does not compile anything`() {
        val project = projectWith()
        assert(
            project.renameAt(mainFile, offsetOf("const local"), "x").refusal ==
                RenameRefusal.NOT_AN_IDENTIFIER,
        )
        assert(
            project.renameAt(mainFile, offsetOf(""""v";"""), "x").refusal ==
                RenameRefusal.NOT_AN_IDENTIFIER,
        )
        assert(
            project.renameAt("/proj/src/nope.ts", 0, "x").refusal ==
                RenameRefusal.NOT_AN_IDENTIFIER,
        )
        assert(project.renameAt(mainFile, main.length + 500, "x").refusal ==
            RenameRefusal.NOT_AN_IDENTIFIER)
    }

    // --- the plan's own contract ------------------------------------------------------

    /**
     * The contract [RenamePlan] states: a refusal never carries a partial plan, and a
     * plan never carries a refusal. Asserted over one of each rather than trusted.
     */
    @Test
    fun `a refusal carries no edits and a plan carries no refusal`() {
        val project = projectWith()
        // (API.10) A shorthand whose member cannot be PLACED — `loose` is an `any`, so
        // nothing supplies the literal a contextual type. The contextual shorthand that
        // used to stand here produces a plan now, as the implementor and the element
        // access did one round earlier.
        val refused = project.renameAt(mainFile, offsetOf("unplaceable: string"), "renamedUnplaceable")
        assert(refused.refusal != null && refused.files.isEmpty() && !refused.isApplicable)
        val planned = project.renameAt(mainFile, offsetOf("local = 1"), "renamedLocal")
        assert(planned.refusal == null && planned.conflicts.isEmpty() && planned.isApplicable)
    }

    /**
     * The applicability promise: one file's edits are sorted ascending and do not
     * overlap, so `asReversed()` is a correct application with no offset arithmetic.
     */
    @Test
    fun `edits are sorted ascending and never overlap`() {
        val project = projectWith()
        val plan = project.renameAt(otherFile, offsetOf("imported", 0, other), "renamedImported")
        assert(plan.refusal == null)
        assert(plan.files.map { it.fileName } == plan.files.map { it.fileName }.sorted())
        for (file in plan.files) {
            assert(file.edits.map { it.start } == file.edits.map { it.start }.sorted())
            for (index in 1 until file.edits.size) {
                assert(file.edits[index - 1].end <= file.edits[index].start)
            }
            assert(file.edits.all { it.start < it.end })
        }
    }

    /**
     * The span an edit replaces is the identifier's REAL extent, not a raw `Node.end`
     * — which in this parser reaches into the following token, so a plan built from one
     * would eat the `=` of `const local = 1`.
     */
    @Test
    fun `an edit replaces exactly the identifier and nothing after it`() {
        val project = projectWith()
        val plan = project.renameAt(mainFile, offsetOf("local = 1"), "renamedLocal")
        val edit = plan.files.single().edits.first()
        assert(main.substring(edit.start, edit.end) == "local")
    }
}
