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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.21) round 945 — **the `target < ES2015` DOWNLEVEL gates are decided from
 * [CompilerOptions.defaultedTarget] too, so a project that names NO target is no longer
 * told its code is being downleveled to ES3.**
 *
 * [CompilerOptions.target] defaults to [ScriptTarget.ES3] and that zero value is
 * indistinguishable from "the user named no target" — round 944 closed the LIB half of
 * that ambiguity, this is the other half. tsc reads ONE notion for every checker question
 * (`var languageVersion = getEmitScriptTarget(compilerOptions)`, whose `computeValue` is
 * `target === ES3 ? undefined : target` then `?? LatestStandard`), so at an unset target
 * its downlevel gates are all SHUT. Ours were all OPEN, and a tsconfig with no `target`
 * therefore collected SIX false positives from the one small file this class pins:
 * TS1250, TS1501, TS1503, TS2659, TS2737 and TS18045.
 *
 * The oracle for that: across the whole pristine baseline corpus, **every** TS2737 (4
 * baselines), TS18045 (5), TS1250 (7) and TS2802 (10) comes from a fixture with an
 * EXPLICIT `@target` — pristine never emits a downlevel-gated diagnostic at its default.
 *
 * The explicit-target tests are what make the change safe rather than a deletion, and
 * they are the arm that refuses [CompilerOptions.effectiveTarget]: that notion maps an
 * explicit `es5` UP to ES2015, which would OPEN every gate for the one program the gates
 * exist for. Every diagnostic below is byte-identical before and after the round at an
 * explicit target; only the unset-target column moved.
 */
class DownlevelGateDefaultTargetTest {

    /** Every shape that reaches a flipped gate, in one file — so the unset-target
     *  tests are one compile and the "silent" claim covers all of them at once. */
    private val downlevelShapes = """
        {
            function blockScopedFn() { }
        }
        const bigintLiteral = 1n;
        class WithAccessor { accessor p: number = 1; }
        var objectLiteral = { m() { super.toString(); } };
        const namedGroup = /(?<nm>a)/;
        const stickyFlag = /a/y;
    """

    // ---- the fix: an UNSET target is NOT downlevel -----------------------------------

    @Test
    fun `a project that names no target gets no downlevel diagnostic at all`() {
        val d = diagnose(downlevelShapes, directives = "")
        d should {
            have(none { it.code == 1250 })
            have(none { it.code == 1501 })
            have(none { it.code == 1503 })
            have(none { it.code == 2659 })
            have(none { it.code == 2737 })
            have(none { it.code == 18045 })
        }
    }

    @Test
    fun `a bigint literal is legal when the tsconfig names no target`() {
        diagnose("const b = 1n;", directives = "") should {
            have(none { it.code == 2737 })
        }
    }

    @Test
    fun `an accessor property is legal when the tsconfig names no target`() {
        diagnose("class C { accessor p: number = 1; }", directives = "") should {
            have(none { it.code == 18045 })
        }
    }

    // ---- the safety: an EXPLICIT downlevel target still fires -------------------------

    @Test
    fun `an explicit es2017 target still refuses a bigint literal`() {
        diagnose("const b = 1n;", directives = "// @target: es2017") should {
            have(any { it.code == 2737 })
        }
    }

    @Test
    fun `an explicit es5 target still refuses an accessor property`() {
        diagnose(
            "class C { accessor p: number = 1; }",
            directives = "// @target: es5\n// @ignoreDeprecations: 6.0",
        ) should {
            have(any { it.code == 18045 })
        }
    }

    @Test
    fun `an explicit es5 target still refuses a block-scoped function in strict mode`() {
        diagnose(
            """
            {
                function blockScopedFn() { }
            }
            """,
            directives = "// @target: es5\n// @strict: true\n// @ignoreDeprecations: 6.0",
        ) should {
            have(any { it.code == 1250 })
        }
    }

    @Test
    fun `an explicit es5 target still refuses super in an object-literal method`() {
        diagnose(
            "var o = { m() { super.toString(); } };",
            directives = "// @target: es5\n// @ignoreDeprecations: 6.0",
        ) should {
            have(any { it.code == 2659 })
        }
    }

    @Test
    fun `an explicit es5 target still refuses a sticky regular-expression flag`() {
        diagnose(
            "const r = /a/y;",
            directives = "// @target: es5\n// @ignoreDeprecations: 6.0",
        ) should {
            have(any { it.code == 1501 })
        }
    }

    @Test
    fun `an explicit es2017 target still refuses a named capturing group`() {
        diagnose(
            "const r = /(?<nm>a)/;",
            directives = "// @target: es2017",
        ) should {
            have(any { it.code == 1503 })
        }
    }

    // ---- the control: these checks are target-driven at all ---------------------------

    @Test
    fun `an explicit es2020 target accepts a bigint literal`() {
        diagnose("const b = 1n;", directives = "// @target: es2020") should {
            have(none { it.code == 2737 })
        }
    }
}
