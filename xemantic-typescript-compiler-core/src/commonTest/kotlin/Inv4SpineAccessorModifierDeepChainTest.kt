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
 * The one [Inv4SpineAccessorModifierTest] pin that cannot live in `commonTest`:
 * it drives a 10,000-term binary chain to prove the TS18045 spine walker is
 * ITERATIVE, and asserts the sharp signal — no TS2589, i.e. the init boundary
 * guard did not swallow an overflow.
 *
 * Round 822 had to move it to `src/jvmTest`: `runWithDeepStack` was a
 * pass-through on Kotlin/Native, so this depth did not FAIL the native suite —
 * it KILLED the test process, taking every alphabetically-later class with it.
 * (NATIVE.1), round 827, gave native the 256 MB pthread the JVM always had, so
 * it is back in `commonTest` and the 10k-term chain runs on both platforms.
 *
 * Read the two assertions differently per platform. `any { 18045 }` is the real
 * signal everywhere. `none { 2589 }` is sharp on the JVM (it asserts the init
 * boundary guard did not swallow an overflow) and VACUOUS on native, where
 * `StackOverflowError` is a never-thrown stub and TS2589 cannot be produced at
 * all — natively what this class pins is that the walk COMPLETES. It stays a
 * separate class from [Inv4SpineAccessorModifierTest] for that reason: the
 * caveat belongs to this depth and to nothing else in that class.
 */
class Inv4SpineAccessorModifierDeepChainTest {

    private val es5 = "// @target: es5"

    @Test
    fun `iterative walk survives a 10k-term binary chain without masking the diagnostic`() {
        val chain = (1..10_000).joinToString(" + ") { "1" }
        val source = "class C {\n    accessor x = 1;\n}\nconst big = $chain;\n"
        diagnose(source, directives = es5) should {
            have(any { it.code == 18045 })
            have(none { it.code == 2589 })
        }
    }
}
