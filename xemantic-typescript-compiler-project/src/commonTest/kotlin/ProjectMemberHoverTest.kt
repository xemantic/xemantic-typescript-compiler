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
 * (BUG.4) [Project.quickInfoAt] on a MEMBER NAME — the hover a user gets on the
 * `p` of `o.p`, which is most hovers in real code.
 *
 * ## What this pins, and why the fixture looks the way it does
 *
 * Before this round a member hover asked the compiler for the type of the member's
 * SPELLING as a free name. A member name is bound by no scope, so the answer was
 * `any` where nothing in the file shared the spelling and — far worse — THE
 * COLLIDER'S TYPE where something did. So every member fixture here is deliberately
 * spelled like a file-level `const` of ANOTHER type: `k` is a `string` property and
 * a `boolean` `const`, `value` a `number` property and a `string` `const`, `p` a
 * `string` field and a `number` `const`. A fix that merely stopped answering `any`
 * — by falling back to the free-name path, say — passes a naive "not any" assertion
 * and fails every pin here.
 *
 * The expected values are not this project's opinion: each was read out of **tsc
 * 7.0.2's own language server** (`tools/tsgo-7.0.2/lib/tsc --lsp`) over the same
 * fixture, and the round's session note carries the before/after/tsc table. Where
 * this compiler deliberately answers something else, the pin says so and names the
 * reason rather than asserting the divergence away.
 *
 * ## The two collisions that make the type-position pins honest
 *
 * The free bindings must live in THIS file: a top-level binding in another MODULE
 * is not in this file's scope at all, so it could never have been the wrong answer
 * and would discriminate nothing.
 */
class ProjectMemberHoverTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "strict": true, "module": "esnext" },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/main.ts"

    private val main = """
        import { Imported } from "./m";

        interface Base { inherited: boolean }
        interface Shape extends Base { k: string; m(): number; loose: any }
        interface BoxLike<T> { value: T; wrap(): T[] }
        interface Indexed { num: number }

        enum Color { Red, Green }

        namespace NS {
            export const nsMember: number = 1;
            export interface Plain { z: string }
        }

        const k: boolean = true;
        const value: string = "free";
        const inherited: number = 0;
        const p: number = 0;
        const s: string = "free";
        const field: boolean = false;
        const nsMember: string = "free";
        const num: string = "free";
        const q: boolean = true;
        const loose: string = "free";

        declare const o: Shape;
        declare const box: BoxLike<number>;
        declare const u: { p: string } | { p: number };
        declare const imp: Imported;
        declare const ix: Indexed;
        declare const n: { q: string | number };
        declare const np: NS.Plain;

        class C {
            p: string | number = "";
            static s: number = 1;
            method(): void {
                const inMethod = this.p;
                const inArrowHolder = () => { const inArrow = this.p; };
            }
            static stat(): void {
                const inStatic = this.s;
            }
        }

        class D extends C {
            p: string = "";
            other(): void {
                const viaSuper = super.p;
                const viaThis = this.p;
            }
        }

        const localObj = { k: 1 };
        const literal = "notAMember";
        const idxName = "num";

        function generic<T extends Shape>(t: T): void {
            t.k;
        }

        function use(): void {
            o.k;
            o.m;
            o.inherited;
            o.loose;
            box.value;
            box.wrap;
            Color.Red;
            NS.nsMember;
            C.s;
            u.p;
            imp.field;
            localObj.k;
            ix["num"];
            ix[idxName];
            const shorthandSource = 5;
            const sh = { shorthandSource };
            if (typeof n.q === "string") {
                n.q;
            }
        }
    """.trimIndent() + "\n"

    private val other = "export interface Imported { field: string }\n"

    private fun project(): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to main,
                "/proj/src/m.ts" to other,
            ),
        ),
    )

    /**
     * The offset of [sub] at or after the first occurrence of [anchor].
     *
     * Two-step because every member spelling here occurs several times by design —
     * once as the colliding `const`, once in its declaration, once at the use — and
     * a bare `indexOf` would pin whichever came first rather than the position the
     * test is about.
     */
    private fun caret(anchor: String, sub: String): Int {
        val start = main.indexOf(anchor)
        check(start >= 0) { "anchor absent: $anchor" }
        val at = main.indexOf(sub, start)
        check(at >= 0) { "sub absent: $sub after $anchor" }
        return at
    }

    private fun hover(offset: Int): String? =
        project().quickInfoAt(mainFile, offset)?.displayString

    // --- the discriminator ---------------------------------------------------------

    @Test
    fun `a member name reports the MEMBER type and not a colliding free binding`() {
        // `k` is `string` on Shape and `boolean` as a file-level const. The old
        // behaviour read `boolean` here — a confident, plausible, wrong hover.
        assert(hover(caret("    o.k;", "k;")) == "string")
    }

    @Test
    fun `negative control - the colliding free binding still reports its own type`() {
        assert(hover(caret("const k: boolean", "k:")) == "boolean")
    }

    @Test
    fun `negative control - the RECEIVER of a member access is unaffected`() {
        assert(hover(caret("    o.k;", "o.k")) == "Shape")
    }

    // --- the shapes the receiver's type has to be resolved through -----------------

    @Test
    fun `a generic member reports the INSTANTIATED type - not the type parameter`() {
        // The one pin a member-table read cannot pass: an interface member's symbol
        // is shared by every instantiation and its cached type is the bare `T`, so
        // reading it answers `any`. Through the carrier it is `number`.
        assert(hover(caret("box.value;", "value;")) == "number")
    }

    @Test
    fun `a generic method reports the instantiated signature`() {
        assert(hover(caret("box.wrap;", "wrap;")) == "() => number[]")
    }

    @Test
    fun `an inherited member reports the BASE declared type`() {
        assert(hover(caret("o.inherited;", "inherited;")) == "boolean")
    }

    @Test
    fun `a union receiver reports the union of the member types`() {
        assert(hover(caret("    u.p;", "p;")) == "string | number")
    }

    @Test
    fun `a type parameter receiver reports the member through its constraint`() {
        assert(hover(caret("    t.k;", "k;")) == "string")
    }

    @Test
    fun `a member that really is any reports any`() {
        // The control for the pin above it: `loose` collides with a `string` const,
        // so `any` here is the MEMBER's type and not the absence of an answer.
        assert(hover(caret("o.loose;", "loose;")) == "any")
    }

    @Test
    fun `a method name reports its signature`() {
        assert(hover(caret("    o.m;", "m;")) == "() => number")
    }

    @Test
    fun `a local object literal member reports its inferred type`() {
        assert(hover(caret("localObj.k;", "k;")) == "number")
    }

    @Test
    fun `a class instance member reports the declared type`() {
        assert(hover(caret("const viaThis = this.p;", "p;")) == "string")
    }

    @Test
    fun `a static member reports its type`() {
        assert(hover(caret("    C.s;", "s;")) == "number")
    }

    @Test
    fun `an enum member reports the member type`() {
        assert(hover(caret("Color.Red;", "Red;")) == "Color.Red")
    }

    @Test
    fun `a namespace member reports its type`() {
        assert(hover(caret("    NS.nsMember;", "nsMember;")) == "number")
    }

    @Test
    fun `a narrowed member reports the NARROWED type`() {
        // Inside `if (typeof n.q === "string")`. The declared type is
        // `string | number`, so this separates the access path — which the flow
        // graph narrows — from a bare member-table read, which cannot.
        assert(hover(caret("        n.q;", "q;")) == "string")
    }

    // --- the imported receiver, with the control that the import resolved ----------

    @Test
    fun `a member of an imported interface reports its type`() {
        assert(hover(caret("imp.field;", "field;")) == "string")
    }

    @Test
    fun `negative control - the imported receiver really resolved`() {
        // (API.*) fixture trap: an in-memory fixture pinning module resolution needs
        // an ES `module` kind and two real files, and it fails SILENTLY otherwise —
        // an unresolved `Imported` would leave the receiver `any`, and then the pin
        // above would be asserting a coincidence rather than a resolution.
        assert(hover(caret("imp.field;", "imp.field")) == "Imported")
    }

    // --- this and super ------------------------------------------------------------

    @Test
    fun `this member in a method reports the field type`() {
        assert(hover(caret("const inMethod = this.p;", "p;")) == "string | number")
    }

    @Test
    fun `this member in a nested arrow reports the field type`() {
        assert(hover(caret("const inArrow = this.p;", "p;")) == "string | number")
    }

    @Test
    fun `super member reports the BASE member and not the override`() {
        // `D` overrides `p: string` over `C`'s `p: string | number`, which is what
        // separates the base leg from the this leg: an implementation that answered
        // `super.p` from the this-type reads `string` here.
        assert(hover(caret("const viaSuper = super.p;", "p;")) == "string | number")
    }

    // --- element access ------------------------------------------------------------

    @Test
    fun `an element access by string literal reports the MEMBER type`() {
        // `num` is a `number` member and the literal's own type is `string`, so the
        // old answer was right by coincidence for a string member and wrong here.
        assert(hover(caret("ix[\"num\"];", "\"num\"")) == "number")
    }

    @Test
    fun `negative control - a string literal outside an element access is a string`() {
        assert(hover(caret("const literal = \"notAMember\"", "\"notAMember\"")) == "string")
    }

    @Test
    fun `negative control - a computed element access index reports its own type`() {
        // `ix[idxName]` names no member syntactically, so the caret is on an ordinary
        // expression and its own type is the right answer.
        val at = caret("ix[idxName];", "idxName]")
        assert(hover(at) == "\"num\"")
    }

    // --- a qualified TYPE name -----------------------------------------------------

    @Test
    fun `a qualified type name reports the declared type`() {
        assert(hover(caret("declare const np: NS.Plain;", "Plain;")) == "Plain")
    }

    // --- what is deliberately still refused ----------------------------------------

    @Test
    fun `this in a STATIC member answers no member type`() {
        // `currentClassForThis` is deliberately null inside a static member — a
        // static `this` is `typeof C`, which a `ClassDeclaration?` cannot model
        // (round 916) — so the access answers `any`. It used to answer `string`,
        // the colliding const's type: a non-answer replacing a wrong name.
        assert(hover(caret("const inStatic = this.s;", "s;")) == "any")
    }

    @Test
    fun `a shorthand property still reports the LOCAL it references`() {
        // Round 922's refusal, unchanged: the useful answer for an object literal's
        // own key is the CONTEXTUAL type's property, which is walk-scoped state this
        // capture does not read. A shorthand is a reference to the local, so what is
        // reported is true about a different subject.
        assert(hover(caret("{ shorthandSource }", "shorthandSource }")) == "5")
    }
}
