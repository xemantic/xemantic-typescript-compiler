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
 * `Object.setPrototypeOf` is a standard ES2015 `ObjectConstructor` method that was missing from
 * the embedded lib (only `getPrototypeOf` was present), so tsc's own debug.ts calls to it
 * FP'd TS2551 "Property 'setPrototypeOf' does not exist on type 'ObjectConstructor'. Did you
 * mean 'getPrototypeOf'?" (5 self-compile FPs). Added to the embedded ObjectConstructor;
 * zero corpus baseline shifts.
 */
class ObjectSetPrototypeOfTest {

    @Test
    fun `Object_setPrototypeOf resolves - no TS2551 or TS2339`() {
        diagnose(
            """
            const o = {};
            Object.setPrototypeOf(o, null);
            """,
            directives = "",
        ) should {
            have(none { it.code == 2551 || it.code == 2339 })
        }
    }

    @Test
    fun `a genuinely missing ObjectConstructor member still errors - negative control`() {
        diagnose("Object.definitelyNotAMethod({});", directives = "") should {
            have(any { it.code == 2339 || it.code == 2551 })
        }
    }
}
