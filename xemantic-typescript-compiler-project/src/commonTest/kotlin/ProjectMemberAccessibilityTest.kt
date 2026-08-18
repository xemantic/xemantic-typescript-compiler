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
 * (API.7) The MEMBER-COMPLETION ACCESSIBILITY FILTER — round 917's first refusal,
 * cashed, end to end through a real build.
 *
 * ## The discriminator, and why it is written first
 *
 * A caret inside a SUBCLASS method. Every shortcut that "filters accessibility"
 * without the heritage walk — hide `private` and `protected` unless the caret is
 * inside SOME class, hide them unless the caret is inside THE declaring class, hide
 * neither — passes the outside case and the inside case and fails exactly here:
 * `protected` must be visible from a subclass and `private` must not. That is
 * `a subclass sees protected and not private`, and nothing else in this file can
 * distinguish those three implementations.
 *
 * ## The bias, stated because it decides every uncertain case
 *
 * PROVE TO HIDE. A member whose declaring class cannot be found, a base class this
 * cannot resolve, a heritage chain past its depth cap — every unknown leaves the item
 * OFFERED. Round 917 refused this feature on the grounds that a list which has
 * silently lost a real candidate is indistinguishable from a complete one; the bias
 * is what keeps that true in the direction that matters.
 */
class ProjectMemberAccessibilityTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    /** The base lives in ANOTHER FILE, so the heritage walk must follow an import. */
    private val other = """
        export class Imported {
            open: string = "";
            private hidden: number = 1;
            protected shared: boolean = false;
            protected static sharedStatic: number = 2;
        }
    """.trimIndent() + "\n"

    /**
     * EVERY RECEIVER IS SPELLED DIFFERENTLY, on purpose: the pins locate a caret by
     * the whole `receiver.member` text, so counting occurrences of one spelling — the
     * shape that silently selects a different site the moment the fixture grows — is
     * impossible here.
     *
     * `this` is deliberately NOT the receiver anywhere. When this file was written a
     * caret on `this.` inside a NESTED ARROW answered no members at all — a
     * pre-existing (API.4a) gap, unrelated to accessibility — so a pin using it would
     * have measured that gap instead of this filter. **(BUG.3), round 923, FIXED the
     * gap** (`ProjectThisReceiverTest`, and one of its pins asserts that this filter
     * still offers a `private` member through a nested arrow), and the receivers here
     * stay named anyway: a named receiver exercises the round-922 ascent, which is
     * what this file is about.
     */
    private val main = """
        import { Imported } from "./b";
        class Base {
            open: string = "";
            private hidden: number = 1;
            protected shared: boolean = false;
            inside(): void {
                insideBase.open;
            }
            nested(): void {
                const f = (): void => { insideArrow.open; };
            }
            static fromStatic(): void {
                Base.open2;
            }
            static open2: string = "";
            private static hiddenStatic: number = 1;
        }
        class Derived extends Base {
            child(): void {
                insideDerived.open;
            }
        }
        class Unrelated {
            elsewhere(): void {
                insideUnrelated.open;
            }
        }
        class Deep extends Derived {
            grandchild(): void {
                insideDeep.open;
            }
        }
        class FromOther extends Imported {
            here(): void {
                insideOther.open;
            }
        }
        declare const insideBase: Base;
        declare const insideArrow: Base;
        declare const insideDerived: Base;
        declare const insideUnrelated: Base;
        declare const insideDeep: Base;
        declare const insideOther: Imported;
        declare const outsideBase: Base;
        export const outside = outsideBase.open;
        declare const outsideImported: Imported;
        export const outsideOther = outsideImported.open;
        const BaseAlias = Base;
        export const outsideStatic = BaseAlias.open2;
    """.trimIndent() + "\n"

    private fun project(): Project = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                mainFile to main,
                otherFile to other,
            ),
        ),
    )

    /**
     * The names offered at the caret sitting immediately after the dot of the UNIQUE
     * `receiver.member` text [access] — the completion a user would get by deleting
     * the member name written there.
     *
     * [access] must occur exactly once, which is asserted: a needle that matched twice
     * would silently measure the first site and read as though it measured the other.
     */
    private fun namesAfterDot(access: String): List<String> {
        val at = main.indexOf(access)
        assert(at >= 0)
        assert(main.indexOf(access, at + 1) < 0)
        val caret = at + access.indexOf('.') + 1
        return project().completionsAt(mainFile, caret).items.map { it.name }
    }

    // --- THE DISCRIMINATOR ---------------------------------------------------------

    @Test
    fun `a subclass sees protected and not private - the discriminator`() {
        val names = namesAfterDot("insideDerived.open")
        assert("open" in names)
        assert("shared" in names)
        assert("hidden" !in names)
    }

    /** The same, one level further down: the heritage walk is transitive. */
    @Test
    fun `a grandchild class sees protected and not private`() {
        val names = namesAfterDot("insideDeep.open")
        assert("shared" in names)
        assert("hidden" !in names)
    }

    // --- the two easy cases the discriminator's rivals also pass ---------------------

    @Test
    fun `the declaring class sees everything it declares`() {
        val names = namesAfterDot("insideBase.open")
        assert("open" in names)
        assert("hidden" in names)
        assert("shared" in names)
    }

    @Test
    fun `a caret outside every class sees only public members`() {
        val names = namesAfterDot("outsideBase.open")
        assert("open" in names)
        assert("hidden" !in names)
        assert("shared" !in names)
    }

    // --- the shapes a rule keyed on "the immediately enclosing declaration" misses -----

    /**
     * The ascent goes out of the arrow, out of its `const`, through the method, to the
     * class. A rule that stopped at the first declaration-shaped ancestor answers
     * "not in a class" here and hides both members.
     */
    @Test
    fun `a caret in a nested arrow inside a method still sees the class's own members`() {
        val names = namesAfterDot("insideArrow.open")
        assert("hidden" in names)
        assert("shared" in names)
    }

    @Test
    fun `an UNRELATED class sees neither - being inside SOME class is not the rule`() {
        val names = namesAfterDot("insideUnrelated.open")
        assert("open" in names)
        assert("hidden" !in names)
        assert("shared" !in names)
    }

    // --- statics, and a base in another file -----------------------------------------

    @Test
    fun `a private STATIC obeys the same rule as an instance member`() {
        val inside = namesAfterDot("Base.open2")
        assert("open2" in inside)
        assert("hiddenStatic" in inside)
        val outside = namesAfterDot("BaseAlias.open2")
        assert("open2" in outside)
        assert("hiddenStatic" !in outside)
    }

    /**
     * The base is IMPORTED, so the heritage walk has to go through the import alias
     * hop — the leg a same-file fixture cannot exercise at all.
     */
    @Test
    fun `a subclass of an IMPORTED base sees its protected members`() {
        val names = namesAfterDot("insideOther.open")
        assert("open" in names)
        assert("shared" in names)
        assert("hidden" !in names)
    }

    @Test
    fun `an imported class's protected members are hidden from outside it`() {
        val names = namesAfterDot("outsideImported.open")
        assert("open" in names)
        assert("shared" !in names)
        assert("hidden" !in names)
    }

    /**
     * The NEGATIVE CONTROL for the whole fixture: with `module` unset or one program
     * file the unresolved-import region returns early and every cross-file assertion
     * above would pass vacuously. This proves the import genuinely resolves — if it
     * did not, `Imported` would carry no members at all.
     */
    @Test
    fun `negative control - the imported base really resolves`() {
        assert(project().diagnostics().none { it.code == 2307 })
        assert("open" in namesAfterDot("outsideImported.open"))
    }
}
