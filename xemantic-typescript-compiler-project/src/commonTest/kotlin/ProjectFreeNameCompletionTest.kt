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
 * (API.4b) [Project.completionsAt] at a FREE position — a caret offset in, the
 * names the lexical scope chain binds there out, through a real build of a real
 * (in-memory) two-file project.
 *
 * ## The discriminating device is (API.4a)'s, inverted
 *
 * The member half was pinned by a receiver whose members collide in spelling with
 * top-level bindings, so that a scope-derived answer would be a SUPERSET. Here the
 * question runs the other way: a function-body local SHADOWS a name imported from
 * ANOTHER FILE. The wrong answer is not empty and not a crash — it is the same
 * spelling meaning something else, or the same spelling twice. Both are separated
 * by the item's `kind`, which is the declaration behind it: `VariableDeclaration`
 * for the local, `ImportSpecifier` for the import it hides.
 *
 * A whole class of wrong implementations is caught by the SIBLING negative below
 * instead: an enumeration that walked the file's nodes rather than ascending the
 * scope chain passes every positive assertion here and offers another function's
 * locals.
 *
 * ## The caret is a comment marker, deliberately
 *
 * The markers named A and B in the fixture are real block COMMENTS, so the fixture
 * is valid TypeScript and no marker identifier can pollute the very scope under
 * test. The caret goes IMMEDIATELY AFTER a marker's closing delimiter, which is
 * outside the comment (spans are half-open) and is therefore a free position
 * between two statements — exactly where a user presses a key on a blank line.
 * (A literal marker cannot be written in this comment: a block-comment opener
 * inside a KDoc opens a NESTED comment and swallows the rest of the file.)
 *
 * Offsets are derived from the fixture text by `indexOf`; a hardcoded offset would
 * pin this file's own arithmetic and would pass for an implementation that ignored
 * its argument.
 */
class ProjectFreeNameCompletionTest {

    /**
     * `module` is an ES kind ON PURPOSE and the program has TWO files: the
     * unresolved-module region returns early below two program files, and its
     * relative-specifier leg additionally demands an ES module kind — with either
     * missing, every import-related assertion here would be vacuous. The negative
     * control below fires the diagnostic to prove the leg is live.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * `shadowed` is exported here AND declared as a body local in [main], which is
     * the discriminator; `neverImported` is exported and never imported, which is
     * the visibility negative.
     */
    private val other = """
        export function shadowed(): void {}
        export interface Imported { width: number; }
        export const neverImported = 1;
    """.trimIndent() + "\n"

    private val main = """
        import { shadowed, Imported } from "./b";
        const fileLevel = 1;
        interface FileIface { a: string; }
        class Klass { member: string = ""; }
        function sibling(): void { const siblingOnly = 1; }
        namespace Space {
            export const inSpace = 2;
            export function fromSpace(): void { const insideSpace = 3; /*B*/ }
        }
        function host<TParam>(param: number, second: string): void {
            const shadowed = 99;
            { const inClosedBlock = 1; }
            /*A*/
            const useLocal = shadowed;
            let laterLet = 5;
        }
        const useImported: Imported = { width: 1 };
        const useShadowed = shadowed;
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

    /** The offset of the `n`-th occurrence (0-based) of [needle] in [text]. */
    private fun offsetOf(needle: String, occurrence: Int = 0, text: String = main): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /** The caret immediately after the comment marker named [marker]. */
    private fun caretAt(marker: String, text: String = main): Int {
        val open = "/*$marker*/"
        return offsetOf(open, 0, text) + open.length
    }

    private fun completionsAt(project: Project, marker: String): CompletionList =
        project.completionsAt(mainFile, caretAt(marker))

    private fun namesAt(project: Project, marker: String): List<String> =
        completionsAt(project, marker).items.map { it.name }

    // --- THE DISCRIMINATOR --------------------------------------------------------

    /**
     * A body local whose spelling is also imported from another file. It must appear
     * ONCE, and it must be the LOCAL — the two facts are independent and a wrong
     * implementation fails one or the other: a chain that does not shadow offers it
     * twice, and a chain that never ascends past the file (or that answers post-hoc,
     * with the chain torn down) offers the IMPORT under the same name.
     */
    @Test
    fun `a body local SHADOWING an imported name is offered ONCE and is the LOCAL`() {
        val project = projectWith()
        val items = completionsAt(project, "A").items.filter { it.name == "shadowed" }
        assert(items.size == 1)
        assert(items[0].kind == "VariableDeclaration")
        // ... and the import it hides is what the SAME query answers one scope out,
        // which is what makes the assertion above about shadowing rather than about
        // the local merely existing.
        val atFileLevel = project.completionsAt(mainFile, main.length)
            .items.filter { it.name == "shadowed" }
        assert(atFileLevel.size == 1)
        assert(atFileLevel[0].kind == "ImportSpecifier")
    }

    /**
     * The enumeration and go-to-definition are ONE traversal, so what the list
     * offers is what navigation resolves. If they ever diverge the list would be
     * offering names a jump cannot follow.
     */
    @Test
    fun `the name the list offers is the name go-to-definition resolves`() {
        val project = projectWith()
        assert(completionsAt(project, "A").items.any { it.name == "shadowed" })
        val use = offsetOf("useLocal = shadowed") + "useLocal = ".length
        val definitions = project.definitionsAt(mainFile, use)
        assert(definitions.size == 1)
        // The BODY declaration — the second `shadowed` in the file, the first being
        // the import at the top — and not that import.
        assert(definitions[0].fileName == mainFile)
        assert(definitions[0].start == offsetOf("shadowed", 1))
        assert(definitions[0].start != offsetOf("shadowed"))
    }

    // --- THE SHARP NEGATIVES ------------------------------------------------------

    /**
     * The scope chain ASCENDS; it never descends into a scope that is not an
     * ancestor. An enumeration built over the file's nodes — or over the binder's
     * whole `lexicalScopes` map — passes every positive pin in this class and fails
     * exactly here.
     */
    @Test
    fun `a name declared in a SIBLING scope is NOT offered`() {
        val names = namesAt(projectWith(), "A")
        // Another function's body local.
        assert("siblingOnly" !in names)
        // A block that opened and CLOSED before the caret, in the same function.
        assert("inClosedBlock" !in names)
        // A body local of a function inside an unrelated namespace.
        assert("insideSpace" !in names)
        // The controls: the enclosing function's own bindings ARE there, so the
        // three absences above are not an empty list.
        assert("param" in names)
        assert("shadowed" in names)
    }

    /**
     * A free position is not a member position. The converse — that a MEMBER caret
     * is not answered from the scope — is `ProjectCompletionTest`'s discriminator,
     * and the two together are what keeps the round-913 confusion out.
     */
    @Test
    fun `a CLASS MEMBER is not offered at a free position`() {
        val names = namesAt(projectWith(), "A")
        assert("member" !in names)
        // The class itself is, which is what makes the absence above about members.
        assert("Klass" in names)
    }

    @Test
    fun `a name exported by another file and NOT imported here is NOT offered`() {
        val project = projectWith()
        // The module-resolution trap: an unresolved import would make this vacuous.
        assert(project.diagnostics(mainFile).none { it.code == 2307 })
        val names = namesAt(project, "A")
        assert("neverImported" !in names)
        // The control: what IS imported from that same file is offered.
        assert("Imported" in names)
    }

    @Test
    fun `negative control - the import stops resolving when the other file is deleted`() {
        val project = projectWith()
        project.deleteFile(otherFile)
        assert(project.diagnostics(mainFile).any { it.code == 2307 })
    }

    // --- what the chain binds -----------------------------------------------------

    @Test
    fun `parameters and type parameters of the enclosing function are offered`() {
        val items = completionsAt(projectWith(), "A").items
        assert(items.any { it.name == "param" && it.kind == "Parameter" })
        assert(items.any { it.name == "second" && it.kind == "Parameter" })
        assert(items.any { it.name == "TParam" && it.kind == "TypeParameter" })
    }

    /**
     * A `let` declared LATER in the same block is offered, deliberately: a block's
     * bindings are a set and not a sequence, the binding exists, and it is merely in
     * its temporal dead zone — which is what tsc offers too. Pinned so that the
     * behaviour is a decision rather than an accident nobody noticed.
     */
    @Test
    fun `a let declared LATER in the same block IS offered`() {
        assert("laterLet" in namesAt(projectWith(), "A"))
    }

    @Test
    fun `the file's own declarations and its imports are offered`() {
        val items = completionsAt(projectWith(), "A").items
        assert(items.any { it.name == "fileLevel" && it.kind == "VariableDeclaration" })
        assert(items.any { it.name == "FileIface" && it.kind == "InterfaceDeclaration" })
        assert(items.any { it.name == "sibling" && it.kind == "FunctionDeclaration" })
        assert(items.any { it.name == "Space" && it.kind == "ModuleDeclaration" })
        assert(items.any { it.name == "Imported" && it.kind == "ImportSpecifier" })
        // The enclosing function itself, which a chain stopping at the function's
        // own scope would lose.
        assert(items.any { it.name == "host" })
    }

    /**
     * A caret inside a NAMESPACE body is offered that namespace's own members.
     *
     * This is the round-918 divergence from `lexLevelHasName`, and it is what the
     * pin exists to make visible: that ascent SKIPS a ModuleDeclaration level as
     * untrusted, because the chain it serves has a second, export-filtered
     * population to fall back on. This chain has none, so applying the same rule
     * would answer nothing at all inside every namespace body — where each of these
     * names is legally writable. Applying it reddens exactly this test.
     */
    @Test
    fun `a caret inside a NAMESPACE body is offered the namespace's own members`() {
        val names = namesAt(projectWith(), "B")
        assert("inSpace" in names)
        assert("fromSpace" in names)
        assert("insideSpace" in names)
        // ... and not the unrelated function's parameters, one file scope away.
        assert("param" !in names)
    }

    /**
     * The lib and merged globals, filtered by what is visible in THIS file. Without
     * the globals leg a completion list is unusable; without the per-file filter it
     * would offer one module's exported names inside every other file (INV.3(c)).
     */
    @Test
    fun `the lib globals are offered`() {
        val names = namesAt(projectWith(), "A")
        assert("console" in names)
        assert("Math" in names)
        assert("Array" in names)
    }

    // --- the shape of the answer --------------------------------------------------

    /**
     * NONE OF THE THREE ASSERTIONS BELOW WAS DISCRIMINATED BY ROUND 918's ABLATION,
     * and saying so is round 807's rule rather than modesty. Removing the
     * writable-name filter left this GREEN (arm A7, 0 red): a symbol table's keys
     * are not all identifiers in general — the binder names a `declare module "x"`
     * symbol with its quotes, and index signatures and the compiler's own `__`
     * entries live in member tables — but no such spelling reaches the scope chain
     * or the globals of THIS fixture. The filter is therefore a guard against a
     * shape this test does not carry, and the sort and the dedup are pinned by no
     * arm at all. Treat all three as unablated assertions.
     */
    @Test
    fun `the list is sorted, deduplicated, and every name is writable as it stands`() {
        val names = namesAt(projectWith(), "A")
        assert(names == names.sorted())
        assert(names.size == names.distinct().size)
        // A symbol table's keys are not all identifiers — the compiler's own entries
        // are `__`-prefixed and a quoted member lands under a spelling with
        // punctuation in it. Inserting one of those produces text that does not parse.
        assert(names.none { it.startsWith("__") })
        assert(names.all { name -> name.all { it.isLetterOrDigit() || it == '_' || it == '$' } })
    }

    /**
     * KEYWORDS ARE NOT OFFERED, and that is a stated refusal rather than an
     * oversight: a useful keyword list is context-sensitive — `interface` may start
     * a statement and may not appear inside an expression — and the anchor is a
     * TOKEN-level device with no grammar position to key one on. Offering an
     * unconditional list would offer items that do not compile, which is the one
     * thing the member half already refuses to do.
     */
    @Test
    fun `keywords are NOT offered`() {
        val names = namesAt(projectWith(), "A")
        assert("interface" !in names)
        assert("function" !in names)
        assert("const" !in names)
        assert("return" !in names)
        assert("await" !in names)
    }

    /**
     * Filtering stays the host's job, exactly as it is for a member list: the prefix
     * is REPORTED and the full set comes back, because ranking is host policy and a
     * list already cut cannot be re-ranked.
     */
    @Test
    fun `a typed prefix is reported and does NOT change the candidate set`() {
        val project = projectWith()
        val at = offsetOf("useShadowed = shadowed") + "useShadowed = sha".length
        val completions = project.completionsAt(mainFile, at)
        assert(completions.kind == CompletionKind.FREE_NAME)
        assert(completions.prefix == "sha")
        assert(completions.replacementStart == at - "sha".length)
        assert(completions.replacementEnd == at + "dowed".length)
        // Unfiltered: names sharing no prefix with what was typed are still there.
        assert(completions.items.any { it.name == "console" })
        assert(completions.items.any { it.name == "shadowed" })
    }

    /**
     * A free-name item carries no type, by the decision `CompletionItem` records:
     * 37.9% of the names in scope at a caret in a real file name a TYPE, for which
     * `getTypeOfSymbol` renders `any` and decorates the item with a lie.
     */
    @Test
    fun `a free-name item carries a kind and deliberately NO type`() {
        val items = completionsAt(projectWith(), "A").items
        assert(items.all { it.typeText == "" })
        assert(items.all { !it.optional && !it.readonly && it.accessibility == "public" })
        assert(items.none { it.kind == "" })
        // ... where a MEMBER item through the SAME entry point does carry one, which
        // is what makes the emptiness above a decision about free names rather than
        // a broken renderer.
        val project = projectWith()
        val edited = main.replace("const useShadowed = shadowed;", "useImported.width;")
        project.updateFile(mainFile, edited)
        val memberCaret = edited.indexOf("useImported.width;") + "useImported.".length
        val member = project.completionsAt(mainFile, memberCaret).items.first { it.name == "width" }
        assert(member.typeText == "number")
    }

    // --- the caret's own edges ----------------------------------------------------

    @Test
    fun `a caret at the very END of the file is answered in the FILE's scope`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, main.length)
        assert(completions.kind == CompletionKind.FREE_NAME)
        assert(completions.items.any { it.name == "fileLevel" })
        assert(completions.items.any { it.name == "console" })
        // Not inside any function, so no function's bindings.
        assert(completions.items.none { it.name == "param" })
    }

    @Test
    fun `a caret inside a comment is still refused`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, offsetOf("/*A*/") + 2)
        assert(completions.kind == CompletionKind.NONE)
        assert(completions.refusal == CompletionRefusal.NO_COMPLETION_CONTEXT)
        assert(completions.items.isEmpty())
    }

    // --- the API's own contract ---------------------------------------------------

    @Test
    fun `an edit is seen by the next query`() {
        val project = projectWith()
        assert("addedLater" !in namesAt(project, "A"))
        val edited = main.replace("/*A*/", "const addedLater = 1;\n    /*A*/")
        project.updateFile(mainFile, edited)
        val at = edited.indexOf("/*A*/") + "/*A*/".length
        val names = project.completionsAt(mainFile, at).items.map { it.name }
        assert("addedLater" in names)
        // ... and the rest of the scope is still there, so the edit did not simply
        // reset the answer.
        assert("param" in names)
        assert("console" in names)
    }
}
