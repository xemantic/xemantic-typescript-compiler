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
 * TS18028 — `Private identifiers are only available when targeting ECMAScript 2015 and
 * higher.`
 *
 * The gate is the target the USER ASKED FOR. [CompilerOptions.target] defaults to
 * [ScriptTarget.ES3] while tsc's `getEmitScriptTarget` defaults an unset `target` to the
 * latest standard, so a raw `target <= ES5` read made every `#field` in a project with no
 * `target` an error. Round 941 measured 26 such rows over four PRISTINE fixtures; the
 * corpus is structurally blind to it, because `usesUnsupportedOption` skips every
 * explicit es3/es5 config, so no ACTIVE baseline exercises either side of this gate.
 */
class PrivateIdentifierTargetGateTest {

    private val source = """
        class C {
            #a = 1;
            #b: string;
            #m() { return this.#a; }
        }
    """

    @Test
    fun `an unset target does not make a private identifier an error`() {
        diagnose(source, directives = "") should { have(none { it.code == 18028 }) }
    }

    @Test
    fun `an explicit ES5 target still reports a private identifier`() {
        diagnose(source, directives = "// @target: es5") should {
            have(any { it.code == 18028 })
        }
    }

    @Test
    fun `an explicit ES3 target still reports a private identifier`() {
        diagnose(source, directives = "// @target: es3") should {
            have(any { it.code == 18028 })
        }
    }

    @Test
    fun `an explicit ES2015 target does not report a private identifier`() {
        diagnose(source, directives = "// @target: es2015") should {
            have(none { it.code == 18028 })
        }
    }

    @Test
    fun `an explicit ESNext target does not report a private identifier`() {
        diagnose(source, directives = "// @target: esnext") should {
            have(none { it.code == 18028 })
        }
    }
}
