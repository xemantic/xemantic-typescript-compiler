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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (LIB.1)(b): the DOM / webworker / scripthost lib sets are SHIPPED, so a browser or
 * worker project is actually type-CHECKED instead of silently running unchecked.
 *
 * THE TRAP THIS FILE EXISTS TO AVOID — and it cost a round before it was written down:
 * "does `HTMLElement` resolve?" proves NOTHING. An unresolved type name degrades to
 * `any`, and `any` is silent, so a resolution probe passes just as happily when the
 * whole DOM is missing. **Only a MEMBER probe discriminates**: an unknown member must
 * be REPORTED, a real member's TYPE must be honoured, and a call's ARITY must be
 * enforced. Every target below is of that kind, and each carries the control that
 * fails loudly if the probe has gone blind.
 *
 * Verified against unmodified HEAD before the fix landed: every target was silent
 * (DOM unshipped -> `Resolution.unavailable` -> nothing consumes it -> no diagnostic),
 * every control already passed.
 */
class RealLibsDomTest {

    private val dom = "// @strict: true\n// @useRealLibs: true\n// @lib: dom,es2015"

    // ---- an unknown member of a DOM interface is an error (TS2339) ----
    //
    // `Screen` deliberately, not `HTMLElement`: the TS2339 walker can only report a
    // missing member once it can enumerate the receiver's COMPLETE member set, and it
    // gives up on a lib interface with a heritage clause (the documented B153 limit —
    // `HTMLElement extends Element, ElementCSSInlineStyle, ElementContentEditable,
    // GlobalEventHandlers, HTMLOrSVGElement`). That is a pre-existing checker gap, not
    // a lib-shipping one, and it is FN-not-FP; the HTMLElement case below therefore
    // probes the axis that does discriminate for a heritage-carrying interface — the
    // TYPE of its own members.

    @Test
    fun `an unknown member of a DOM interface parameter is reported`() {
        val diagnostics = diagnose(
            """
            function f(s: Screen): void {
                s.definitelyNotAMember
            }
            """,
            directives = dom,
        )
        assert(diagnostics.any { it.code == 2339 })
    }

    @Test
    fun `control - a real member of the same DOM interface is not reported`() {
        val diagnostics = diagnose(
            """
            function f(s: Screen): void {
                s.availHeight
            }
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `an HTMLElement member carries its real type`() {
        val diagnostics = diagnose(
            """
            function f(e: HTMLElement): void {
                const n: number = e.accessKey
            }
            """,
            directives = dom,
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `control - the right type for the same HTMLElement member is accepted`() {
        val diagnostics = diagnose(
            """
            function f(e: HTMLElement): void {
                const s: string = e.accessKey
            }
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    // ---- a DOM member carries its real TYPE (TS2322) ----

    @Test
    fun `the type of a DOM member is honoured so a wrong annotation is reported`() {
        val diagnostics = diagnose(
            """
            declare const d: Document
            const n: number = d.title
            """,
            directives = dom,
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `control - the right annotation for the same DOM member is accepted`() {
        val diagnostics = diagnose(
            """
            declare const d: Document
            const s: string = d.title
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    // ---- a DOM method's ARITY is enforced (TS2554) ----

    @Test
    fun `a DOM method called with too few arguments is reported`() {
        val diagnostics = diagnose(
            """
            declare const d: Document
            d.getElementById()
            """,
            directives = dom,
        )
        assert(diagnostics.any { it.code == 2554 })
    }

    @Test
    fun `control - the same DOM method called correctly is accepted`() {
        val diagnostics = diagnose(
            """
            declare const d: Document
            d.getElementById("x")
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2554 })
    }

    // ---- the DOM global VALUE declarations are typed too, not just the interfaces ----

    @Test
    fun `the global document value carries the Document type`() {
        val diagnostics = diagnose(
            """
            const n: number = document.title
            """,
            directives = dom,
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `control - the global document value accepts its real member type`() {
        val diagnostics = diagnose(
            """
            const s: string = document.title
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    // ---- webworker is a SEPARATE set, requested on its own ----

    @Test
    fun `an unknown member of a webworker interface is reported`() {
        val diagnostics = diagnose(
            """
            function f(r: FileReaderSync): void {
                r.definitelyNotAMember
            }
            """,
            directives = "// @strict: true\n// @useRealLibs: true\n// @lib: webworker,es2015",
        )
        assert(diagnostics.any { it.code == 2339 })
    }

    @Test
    fun `control - a real webworker member is not reported`() {
        val diagnostics = diagnose(
            """
            function f(r: FileReaderSync): void {
                r.readAsText
            }
            """,
            directives = "// @strict: true\n// @useRealLibs: true\n// @lib: webworker,es2015",
        )
        assert(diagnostics.none { it.code == 2339 })
    }

    // ---- scripthost too ----

    @Test
    fun `a scripthost member type is honoured`() {
        val diagnostics = diagnose(
            """
            declare const t: TextStreamReader
            const n: number = t.ReadAll()
            """,
            directives = "// @strict: true\n// @useRealLibs: true\n// @lib: scripthost,es5",
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `control - the right scripthost member type is accepted`() {
        val diagnostics = diagnose(
            """
            declare const t: TextStreamReader
            const s: string = t.ReadAll()
            """,
            directives = "// @strict: true\n// @useRealLibs: true\n// @lib: scripthost,es5",
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    // ---- the DOM set is NOT pulled in when it was not asked for ----

    @Test
    fun `negative control - an es2015-only lib set still does not know the DOM`() {
        // The complement of every target above: with `lib` naming no host library,
        // `HTMLElement` is genuinely absent, so the member probe must go silent again.
        // This is what proves the targets measure the SHIPPED DOM and not some
        // unrelated widening of member checking.
        val diagnostics = diagnose(
            """
            function f(s: Screen): void {
                s.definitelyNotAMember
            }
            """,
            directives = "// @strict: true\n// @useRealLibs: true\n// @lib: es2015",
        )
        assert(diagnostics.none { it.code == 2339 })
    }
}
