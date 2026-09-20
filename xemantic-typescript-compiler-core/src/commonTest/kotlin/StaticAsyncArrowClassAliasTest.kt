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
 * (LEGACY.0b) The class-alias capture for a static field initializer is decided by
 * `this` and by nothing else — an async arrow is not special.
 *
 * ## What changed and why
 *
 * Below ES2022 a static field initializer that reads `this` needs the class captured
 * into a temp first (`var _a; … _a = Cls;`), because the initializers run before the
 * binding is usable. TypeScript 6 ALSO pre-emitted that capture for every
 * async-arrow initializer, defensively, on the grounds that the downleveled
 * `__awaiter` template is conceptually `this`-binding. The result is a `var _a;` and
 * an `_a = Cls;` that the emitted program never reads.
 *
 * tsgo 7.0.2 does not, and the three shapes below are its own output at
 * `target: es2015`, read off `tools/tsgo-7.0.2/lib/tsc` before the change:
 *
 * ```
 * static m = async (x) => {}           no capture
 * static m = async () => WithThis.n    no capture — a NAME is not `this`
 * static m = async () => this          `_a = Cls`, and `_a` IS read
 * ```
 *
 * So the async case that genuinely needs an alias is exactly the one
 * `containsThisInExpr` already answers.
 *
 * ## A pre-existing divergence this does NOT close, recorded so it is not mistaken
 *
 * In the third shape tsgo rewrites the arrow's `this` to `_a`; we emit the capture
 * and leave `this` in the body. That is unchanged by this round — it was the same
 * before — and it is why the third pin asserts the CAPTURE and not the body.
 */
class StaticAsyncArrowClassAliasTest {

    /**
     * `target: es2015` is NOT optional here and is the corpus variant's own: the
     * capture only exists below ES2022, so at the default target every pin below
     * would pass vacuously (CLAUDE.md — a pin about a downlevel gate must name its
     * target).
     */
    private fun js(source: String): String =
        TypeScriptCompiler()
            .compile(
                "// @target: es2015\n// @noEmitHelpers: true\n" + source.trimIndent(),
                "input.ts",
            )
            .jsOutputs.joinToString("\n") { it.second }

    /**
     * The corpus row (`asyncArrowInClassES5(target=es2015)`): no `this` anywhere, so
     * no capture. Both halves are asserted — the hoisted declaration AND the
     * assignment — because the two are emitted from different places and a fix that
     * dropped only one would leave a `var _a;` nothing assigns.
     */
    @Test
    fun `an async-arrow static field with no this emits no class alias`() {
        val out = js(
            """
            class Test {
                static member = async (x: string) => { };
            }
            """,
        )
        val assigns = out.contains("Test.member =")
        assert(assigns)
        val noHoist = !out.contains("var _a")
        assert(noHoist)
        val noCapture = !out.contains("_a = Test")
        assert(noCapture)
    }

    /**
     * A NAME is not `this`: referring to the class by its own binding needs no
     * alias, which is the cell that separates "reads the class" from "reads `this`".
     */
    @Test
    fun `an async-arrow static field reading the class by name emits no alias`() {
        val out = js(
            """
            class WithThis {
                static n = 1;
                static m = async () => { return WithThis.n; };
            }
            """,
        )
        val noHoist = !out.contains("var _a")
        assert(noHoist)
    }

    /**
     * The positive control, and what keeps the change from being "delete the
     * capture": an async arrow that DOES read `this` still captures. Without this
     * the two pins above are satisfied by removing the mechanism entirely.
     */
    @Test
    fun `an async-arrow static field reading this still captures the class`() {
        val out = js(
            """
            class ThisInArrow {
                static n = 1;
                static m = async () => { return this; };
            }
            """,
        )
        val hoisted = out.contains("var _a")
        assert(hoisted)
        val captured = out.contains("_a = ThisInArrow")
        assert(captured)
    }

    /**
     * …and a NON-async static field reading `this` captures too, so the rule is
     * about `this` rather than about the arrow being async.
     */
    @Test
    fun `a plain static field reading this captures the class`() {
        val out = js(
            """
            class Plain {
                static m = () => this;
            }
            """,
        )
        val captured = out.contains("_a = Plain")
        assert(captured)
    }

    /** An ordinary static field needs neither — the baseline shape. */
    @Test
    fun `an ordinary static field emits a direct assignment`() {
        val out = js(
            """
            class Simple {
                static n = 1;
            }
            """,
        )
        val direct = out.contains("Simple.n = 1")
        assert(direct)
        val noHoist = !out.contains("var _a")
        assert(noHoist)
    }
}
