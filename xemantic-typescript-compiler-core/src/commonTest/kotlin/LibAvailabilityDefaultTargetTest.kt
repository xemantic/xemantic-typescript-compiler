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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.17) round 944 — **lib availability is decided from [CompilerOptions.libTarget], and
 * an UNSET `target` is the LATEST standard there, not `ES3`.**
 *
 * [CompilerOptions.target] defaults to [ScriptTarget.ES3], which is indistinguishable from
 * "the user named no target"; tsc's `getEmitScriptTarget` maps an unset target to
 * `LatestStandard` and `getDefaultLibFileName` picks the default lib from THAT. So a
 * project with no `target` in its tsconfig used to be given the es5 lib and told
 * `Cannot find name 'AsyncIterableIterator'. Do you need to change your target library?`,
 * and every later-lib interface member was filtered out from under it.
 *
 * Two halves, pinned separately because they can break separately: the lib **SET**
 * ([RealLibResolver.resolve] through [CompilerOptions.libTarget]) and the availability
 * **GATE** (`libFeatureAvailable` / `libProvidesGlobalAt`). The explicit-`es5` pins are
 * the safety of the change: `effectiveTarget` maps es5 UP to ES2015 and would have
 * deleted every genuine TS2550/TS2583 (round 941 refused it at TS18028 for the same
 * reason).
 */
class LibAvailabilityDefaultTargetTest {

    // ---- the accessor itself ------------------------------------------------------

    @Test
    fun `an unset target answers the latest standard for lib availability`() {
        assert(CompilerOptions().libTarget == ScriptTarget.ES2024)
    }

    @Test
    fun `an explicit es5 target answers es5 for lib availability`() {
        val o = CompilerOptions(target = ScriptTarget.ES5, targetExplicitlySet = true)
        assert(o.libTarget == ScriptTarget.ES5)
        // the BOUND: effectiveTarget would answer ES2015 here and lose the es5 lib
        assert(o.effectiveTarget == ScriptTarget.ES2015)
    }

    @Test
    fun `an explicit es2017 target answers es2017 for lib availability`() {
        val o = CompilerOptions(target = ScriptTarget.ES2017, targetExplicitlySet = true)
        assert(o.libTarget == ScriptTarget.ES2017)
    }

    // ---- the lib SET --------------------------------------------------------------

    // The DISCRIMINATING key is an es2016+ layer. `es2018.asynciterable` is NOT one:
    // measured this round, the es5 default closure ALREADY carries it (and the whole
    // es2015 layer) through `lib.d.ts` -> dom, so at an explicit `es5` the TS2583/TS2550
    // family is decided by the availability GATE and not by the file set at all.
    @Test
    fun `the default lib set for an unset target is the latest full one`() {
        val keys = RealLibResolver.resolve(null, CompilerOptions().libTarget).orderedKeys
        assert("es2024.full" in keys)
        assert("es2017.string" in keys)
    }

    @Test
    fun `the default lib set for an explicit es5 target is the es5 one`() {
        val o = CompilerOptions(target = ScriptTarget.ES5, targetExplicitlySet = true)
        val keys = RealLibResolver.resolve(null, o.libTarget).orderedKeys
        assert("es5.full" in keys)
        assert("es2024.full" !in keys)
        assert("es2017.string" !in keys)
    }

    // ---- the availability GATE, end to end through the real libs ------------------

    // NOTE: every directive is passed through `diagnose(directives = …)` rather than
    // written into the source block — `diagnose` trimIndents the source, and a directive
    // line prepended to an indented raw string would keep its indentation.
    private val realLibs = "// @useRealLibs: true"

    private val asyncIterable = "declare const it: AsyncIterableIterator<number>;"

    @Test
    fun `an unset target resolves a later-lib global`() {
        diagnose(asyncIterable, directives = realLibs) should { have(isEmpty()) }
    }

    @Test
    fun `an explicit es5 target still reports a later-lib global`() {
        diagnose(asyncIterable, directives = "$realLibs\n// @target: es5") should {
            have(any { it.code == 2583 })
        }
    }

    @Test
    fun `an explicit es2017 target still reports an es2018 global`() {
        diagnose(asyncIterable, directives = "$realLibs\n// @target: es2017") should {
            have(any { it.code == 2583 })
        }
    }

    @Test
    fun `an explicit es2018 target resolves an es2018 global`() {
        diagnose(asyncIterable, directives = "$realLibs\n// @target: es2018") should {
            have(isEmpty())
        }
    }

    @Test
    fun `an explicit lib option still decides on its own`() {
        // regression guard: an explicit `lib` never consults the target at all
        diagnose(asyncIterable, directives = "$realLibs\n// @lib: es5") should {
            have(any { it.code == 2583 })
        }
    }

    // An es2015-era global: the ONE shape that separates `libTarget` from
    // `effectiveTarget`, because effectiveTarget maps an explicit es5 UP to ES2015 and
    // would make `Reflect` resolve for a program that asked for es5.
    private val reflect = "const p: object | null = Reflect.getPrototypeOf({});"

    @Test
    fun `an explicit es5 target still reports an es2015 global`() {
        diagnose(reflect, directives = "$realLibs\n// @target: es5") should {
            have(any { it.code == 2583 })
        }
    }

    @Test
    fun `an unset target resolves an es2015 global`() {
        diagnose(reflect, directives = realLibs) should { have(isEmpty()) }
    }

    // ---- the availability GATE on a later-lib MEMBER (TS2550) ---------------------

    private val padStart = """const s: string = "a".padStart(3);"""

    @Test
    fun `an unset target resolves a later-lib member`() {
        diagnose(padStart, directives = realLibs) should { have(isEmpty()) }
    }

    @Test
    fun `an explicit es5 target still reports a later-lib member`() {
        diagnose(padStart, directives = "$realLibs\n// @target: es5") should {
            have(any { it.code == 2550 })
        }
    }
}
