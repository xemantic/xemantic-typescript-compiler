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
 * (API.4a) [Project.completionsAt] — a caret offset in, a candidate list out,
 * through a real build of a real (in-memory) project.
 *
 * The discriminating device is a receiver whose members COLLIDE IN SPELLING with
 * unrelated top-level bindings of the same file. An implementation that answered
 * from the lexical scope — which is what the free-name half will one day do, and
 * what a member path that quietly reused it would do here — offers those bindings
 * too; only a resolution through the receiver's TYPE offers exactly the receiver's
 * members. The assertion is therefore an EXACT list, not a containment: a superset
 * is the failure this test exists to catch.
 *
 * Offsets are derived from the fixture text by `indexOf`; a hardcoded offset pins
 * this file's own arithmetic and would pass for an implementation that ignored its
 * argument.
 */
class ProjectCompletionTest {

    /**
     * `module` is an ES kind ON PURPOSE and the program has TWO files: the
     * unresolved-module region returns early below two program files, and its
     * relative-specifier leg additionally demands an ES module kind — with either
     * missing, every import-related assertion here would be vacuous.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /**
     * `alpha` and `beta` are BOTH a top-level binding and a member of `holder`, which
     * is what makes the exact-list assertion below discriminate a type-derived answer
     * from a scope-derived one.
     */
    private val main = """
        import { Imported, ns } from "./b";
        export const alpha: string = "an unrelated top-level binding";
        export const beta: string = "another unrelated top-level binding";
        export const holder = { alpha: 1, beta: 2 };
        export const readHolder = holder.alpha;
        interface Base { inherited: string; overridden: string; }
        interface Derived extends Base { own: number; overridden: string; }
        declare const derived: Derived;
        export const readDerived = derived.own;
        interface Flags {
            required: string;
            optionalOne?: number;
            readonly frozen: boolean;
            method(x: number): void;
        }
        declare const flags: Flags;
        export const readFlags = flags.required;
        declare const united: { shared: string; onlyLeft: number }
            | { shared: number; onlyRight: boolean };
        export const readUnited = united.shared;
        declare const intersected: { fromLeft: string } & { fromRight: number };
        export const readIntersected = intersected.fromLeft;
        declare const maybe: { present: string } | undefined;
        export const readMaybe = maybe?.present;
        class Access {
            pub: string = "";
            private secret: number = 1;
            protected guarded: boolean = false;
            get computed(): string { return ""; }
            greet(): void {}
        }
        declare const access: Access;
        export const readAccess = access.pub;
        class WithThis {
            field: string = "";
            other: number = 0;
            use(): string { return this.field; }
        }
        enum Colour { Red, Green }
        export const readColour = Colour.Red;
        export interface Merged { first: string; }
        export interface Merged { second: number; }
        declare const merged: Merged;
        export const readMerged = merged.first;
        declare const anything: any;
        export const readAnything = anything.whatever;
        export const readNs = ns.inside;
        declare const imported: Imported;
        export const readImported = imported.width;
        export const readLib = "abc".length;
        interface Untouched { onlyMember: string; }
        declare const untouched: Untouched;
    """.trimIndent() + "\n"

    private val other = """
        export interface Imported { width: number; height: number; }
        export namespace ns { export const inside = 7; export const beside = 8; }
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

    /** The caret immediately after the `.` of the first `<receiver>.` in [access]. */
    private fun afterDotOf(access: String, text: String = main): Int =
        offsetOf(access, 0, text) + access.indexOf('.') + 1

    private fun namesAt(project: Project, offset: Int, fileName: String = mainFile): List<String> =
        project.completionsAt(fileName, offset).items.map { it.name }

    // --- THE DISCRIMINATOR --------------------------------------------------------

    /**
     * A receiver whose members are spelled exactly like two unrelated top-level
     * bindings. The wrong answer is not empty and not a crash — it is a LONGER list
     * that still contains the right names, so only an exact comparison separates
     * them.
     */
    @Test
    fun `a member caret offers the RECEIVER's members and NOTHING the scope binds`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, afterDotOf("holder.alpha"))
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.refusal == null)
        assert(completions.items.map { it.name } == listOf("alpha", "beta"))
        // ... and not, for instance, the top-level bindings of the same spelling,
        // nor any other name the file's scope holds.
        assert(completions.items.none { it.name == "holder" })
        assert(completions.items.none { it.name == "readHolder" })
        assert(completions.items.none { it.name == "Derived" })
    }

    // --- what the receiver's type says --------------------------------------------

    @Test
    fun `an INHERITED member is offered, and an override is offered ONCE`() {
        val project = projectWith()
        val names = namesAt(project, afterDotOf("derived.own"))
        // `inherited` comes from the base — an implementation reading only the
        // derived type's own table would lose it.
        assert(names == listOf("inherited", "overridden", "own"))
        // `overridden` is declared in BOTH interfaces and appears exactly once: a
        // member table is keyed by name, and a completion list showing a name twice
        // is a defect a user sees.
        assert(names.count { it == "overridden" } == 1)
    }

    @Test
    fun `a UNION receiver offers only the members present on EVERY constituent`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, afterDotOf("united.shared"))
        // `onlyLeft` and `onlyRight` exist on one arm each and may NOT be written
        // through the union, so they are not candidates. This is deliberately NOT
        // the rule `definitionsAt` uses on the same receiver, which collects every
        // declaration of a name the user has already written.
        assert(completions.items.map { it.name } == listOf("shared"))
        // The member's type through the union is the distinct constituent types.
        assert(completions.items[0].typeText == "string | number")
    }

    @Test
    fun `an INTERSECTION receiver offers the members of every constituent`() {
        val project = projectWith()
        assert(
            namesAt(project, afterDotOf("intersected.fromLeft")) ==
                listOf("fromLeft", "fromRight"),
        )
    }

    @Test
    fun `a union with undefined offers the members of the non-nullish constituent`() {
        val project = projectWith()
        // `undefined` contributes no members, so intersecting with it would empty
        // every optional chain and every strictNullChecks union. It is skipped.
        assert(namesAt(project, afterDotOf("maybe?.present")) == listOf("present"))
    }

    @Test
    fun `a MERGED interface receiver offers the members of every declaration`() {
        val project = projectWith()
        assert(namesAt(project, afterDotOf("merged.first")) == listOf("first", "second"))
    }

    @Test
    fun `a receiver typed by an IMPORTED interface offers that interface's members`() {
        val project = projectWith()
        // The module-resolution trap: an unresolved import would make this measure
        // nothing, so pin that it resolves.
        assert(project.diagnostics(mainFile).none { it.code == 2307 })
        // Sorted by name, which is why `height` leads: the order is imposed here and
        // is deliberately not the declaration order a member table happens to hold.
        assert(namesAt(project, afterDotOf("imported.width")) == listOf("height", "width"))
    }

    @Test
    fun `negative control - the import stops resolving when the other file is deleted`() {
        val project = projectWith()
        project.deleteFile(otherFile)
        assert(project.diagnostics(mainFile).any { it.code == 2307 })
    }

    @Test
    fun `a NAMESPACE receiver is answered from its export table`() {
        val project = projectWith()
        // A namespace's members are on no TYPE — its identifier types as `any` — so
        // a type-only implementation reads empty here.
        assert(namesAt(project, afterDotOf("ns.inside")) == listOf("beside", "inside"))
    }

    @Test
    fun `an ENUM receiver is answered from its export table`() {
        val project = projectWith()
        assert(namesAt(project, afterDotOf("Colour.Red")) == listOf("Green", "Red"))
    }

    @Test
    fun `a this receiver offers the enclosing class's members`() {
        val project = projectWith()
        // `this` is `Identifier("this")` in this parser and types as `any`; the
        // answer comes from the ambient class the capture hook restores.
        assert(namesAt(project, afterDotOf("this.field")) == listOf("field", "other", "use"))
    }

    /**
     * `Untouched` is declared and never USED anywhere else in the file.
     *
     * WRITTEN as the discriminator for the round-833 lazy-member-table rule and
     * MEASURED not to be one: with `resolveStructuredTypeMembers` ablated out of the
     * member walk this pin stays GREEN, because the `declare const untouched:
     * Untouched` declaration alone already resolves that type's table. The rule IS
     * load-bearing — the one receiver whose table nothing else has resolved is
     * `this`, whose type comes from `resolveUncalledThisType` rather than from a
     * declaration the checker has visited, and that pin is what reddens. Renamed to
     * say what it actually tests rather than left claiming a discrimination it does
     * not have (CLAUDE.md, round 807).
     */
    @Test
    fun `a receiver used NOWHERE else in the file still offers its members`() {
        val project = projectWith(main + "untouched.\n")
        val at = main.length + "untouched.".length
        assert(namesAt(project, at) == listOf("onlyMember"))
    }

    @Test
    fun `a LIB receiver offers the lib's members`() {
        val project = projectWith()
        val names = namesAt(project, afterDotOf("\"abc\".length"))
        // A primitive reaches its members through its apparent type — the wrapper
        // interface — so this is non-empty only if that leg is live.
        assert("length" in names)
        assert("charAt" in names)
    }

    // --- what each item carries ---------------------------------------------------

    @Test
    fun `an item carries its kind, type, optionality, readonly-ness and accessibility`() {
        val project = projectWith()
        val items = project.completionsAt(mainFile, afterDotOf("flags.required")).items
            .associateBy { it.name }
        assert(items.keys == setOf("required", "optionalOne", "frozen", "method"))

        val required = items["required"]
        assert(required != null)
        assert(required.typeText == "string")
        assert(!required.optional)
        assert(!required.readonly)
        assert(required.accessibility == "public")
        // Interface members are class elements in this parser, so a property
        // signature reports as a property declaration.
        assert(required.kind == "PropertyDeclaration")

        val optional = items["optionalOne"]
        assert(optional != null)
        assert(optional.optional)

        val frozen = items["frozen"]
        assert(frozen != null)
        assert(frozen.readonly)

        // A METHOD is told from a property by its kind and by nothing else.
        val method = items["method"]
        assert(method != null)
        assert(method.kind == "MethodDeclaration")
        assert(!method.optional)
    }

    /**
     * UPDATED BY (API.7), WHICH IS A BEHAVIOUR CHANGE TO AN EXISTING ANSWER. Round 917
     * asserted here that `secret` and `guarded` ARE offered, and said why: filtering
     * them correctly needs to know where the caret sits relative to the declaring
     * class, which was a mechanism it did not build. `SyntaxRoles`' sibling ascent in
     * the checker is that mechanism, so this caret — at FILE level, outside `Access` —
     * now sees neither. `ProjectMemberAccessibilityTest` carries the discriminating
     * cases (inside the class, inside a subclass); this one stays here because it also
     * pins that ACCESSIBILITY IS STILL REPORTED on what survives.
     */
    @Test
    fun `private and protected members are HIDDEN from a caret outside the class`() {
        val project = projectWith()
        val items = project.completionsAt(mainFile, afterDotOf("access.pub")).items
            .associateBy { it.name }
        assert(items.keys == setOf("pub", "computed", "greet"))
        assert(items["pub"]!!.accessibility == "public")
        assert(items["computed"]!!.kind == "GetAccessor")
        assert(items["computed"]!!.typeText == "string")
    }

    @Test
    fun `items are sorted by name and deduplicated`() {
        val project = projectWith()
        val names = namesAt(project, afterDotOf("access.pub"))
        assert(names == names.sorted())
        assert(names == names.distinct())
    }

    // --- what answers EMPTY, and why ----------------------------------------------

    @Test
    fun `an any receiver offers nothing`() {
        val project = projectWith()
        val completions = project.completionsAt(mainFile, afterDotOf("anything.whatever"))
        // `any` has no apparent type but itself, so there is nothing to enumerate —
        // the same answer tsc gives, and a stated one rather than a gap.
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.items.isEmpty())
        // Not a refusal: the receiver WAS reached and genuinely has no members.
        assert(completions.refusal == null)
    }

    @Test
    fun `an UNRESOLVABLE receiver answers empty rather than crashing`() {
        val project = projectWith(main + "nope.\n")
        val completions = project.completionsAt(mainFile, main.length + "nope.".length)
        assert(completions.kind == CompletionKind.MEMBER)
        assert(completions.items.isEmpty())
    }

    // --- the incomplete receiver: the case a parse must recover from ---------------

    @Test
    fun `an INCOMPLETE member access still offers the receiver's members`() {
        // `holder.` with nothing after it — the shape a user is actually in when
        // asking for completions, and the one no other query in this module has to
        // survive. The parser synthesizes an empty name and reports TS1003; the
        // receiver is still a real node.
        val edited = main + "holder.\n"
        val project = projectWith(edited)
        val at = main.length + "holder.".length
        assert(namesAt(project, at) == listOf("alpha", "beta"))
    }

    /**
     * The buffer ends IMMEDIATELY after the dot, with no newline and no next token.
     * That is the one shape where the raw span stops identifying a node: the
     * synthesized name is zero-width at end of file, so `holder` and
     * `holder.<nothing>` carry the SAME `(pos, end)` pair and a preorder first-wins
     * capture answers with the members of `any`. Measured before the descendant rule
     * existed, this read EMPTY while every other shape here was green.
     */
    @Test
    fun `an incomplete member access at the very END of the buffer still offers members`() {
        val edited = main + "holder."
        val project = projectWith(edited)
        assert(namesAt(project, edited.length) == listOf("alpha", "beta"))
    }

    @Test
    fun `an incomplete member access followed by a newline and a statement offers members`() {
        val edited = main + "holder.\nexport const tail = 1;\n"
        val project = projectWith(edited)
        val at = main.length + "holder.".length
        assert(namesAt(project, at) == listOf("alpha", "beta"))
    }

    // --- the anchor, reported through the public value ----------------------------

    @Test
    fun `a partially typed member name is reported as a prefix and a replacement span`() {
        val project = projectWith()
        val at = afterDotOf("holder.alpha") + 2
        val completions = project.completionsAt(mainFile, at)
        assert(completions.prefix == "al")
        assert(completions.replacementStart == offsetOf("holder.alpha") + "holder.".length)
        assert(completions.replacementEnd == completions.replacementStart + "alpha".length)
        // NOT filtered by the prefix: ranking is host policy, and a list already cut
        // cannot be re-ranked.
        assert(completions.items.map { it.name } == listOf("alpha", "beta"))
    }

    // --- the refusals -------------------------------------------------------------

    /**
     * (API.4b) THIS PIN'S MEANING CHANGED, which is logged rather than quiet: it
     * asserted `refusal == FREE_NAMES_NOT_IMPLEMENTED` while (API.4a) shipped only
     * the member half, and that refusal no longer exists. What it guarded — that the
     * ANCHOR of a free-name caret is correct even where the candidate list is not
     * this test's subject — it still guards, and it now additionally asserts that a
     * list comes back at all. The candidates themselves are
     * `ProjectFreeNameCompletionTest`'s subject.
     */
    @Test
    fun `a free-name caret is ANSWERED, and its anchor is still correct`() {
        val project = projectWith()
        val at = offsetOf("readHolder = holder") + "readHolder = ho".length
        val completions = project.completionsAt(mainFile, at)
        assert(completions.kind == CompletionKind.FREE_NAME)
        assert(completions.refusal == null)
        assert(completions.items.any { it.name == "holder" })
        // The anchor half, unchanged.
        assert(completions.prefix == "ho")
        assert(completions.replacementStart == offsetOf("readHolder = holder") + "readHolder = ".length)
        assert(completions.replacementEnd == completions.replacementStart + "holder".length)
    }

    @Test
    fun `a caret in a string is refused with NO_COMPLETION_CONTEXT`() {
        val project = projectWith()
        val completions =
            project.completionsAt(mainFile, offsetOf("an unrelated top-level binding") + 3)
        assert(completions.kind == CompletionKind.NONE)
        assert(completions.refusal == CompletionRefusal.NO_COMPLETION_CONTEXT)
        assert(completions.items.isEmpty())
    }

    @Test
    fun `a file that is not in the overlay is refused`() {
        val project = projectWith()
        val completions = project.completionsAt("/proj/src/nope.ts", 0)
        assert(completions.kind == CompletionKind.NONE)
        assert(completions.refusal == CompletionRefusal.NO_COMPLETION_CONTEXT)
    }

    // --- the API's own contract ---------------------------------------------------

    @Test
    fun `an edit is seen by the next query`() {
        val project = projectWith()
        // The list before the edit does not contain `gamma`...
        assert(namesAt(project, afterDotOf("holder.alpha")) == listOf("alpha", "beta"))
        val edited = main.replace(
            "export const holder = { alpha: 1, beta: 2 };",
            "export const holder = { alpha: 1, beta: 2, gamma: 3 };",
        )
        project.updateFile(mainFile, edited)
        val at = afterDotOf("holder.alpha", edited)
        // ... and does after it, against the EDITED text: a stale parse would answer
        // the old member set at a shifted offset.
        assert(namesAt(project, at) == listOf("alpha", "beta", "gamma"))
    }

    @Test
    fun `a closed project refuses to answer`() {
        val project = projectWith()
        project.close()
        var threw = false
        try {
            project.completionsAt(mainFile, afterDotOf("holder.alpha"))
        } catch (e: IllegalStateException) {
            threw = true
        }
        assert(threw)
    }
}
